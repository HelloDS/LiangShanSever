package com.ruoyi;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import com.ruoyi.game.auth.*;
import com.ruoyi.game.games.landlord.*;
import com.ruoyi.game.outbox.OutboxStore;
import com.ruoyi.game.protocol.GameJsonCodec;
import com.ruoyi.game.room.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.context.support.StandardServletEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 显式启动的隔离负载工具，不进入默认测试、不打入运行 JAR、不连接运行数据库。 */
public final class RoomStorageLoad {
    private static final String ENGINE="landlord-play-load-v1",RULE="mechanics-load-v1";
    private static final String MYSQL_IMAGE="mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902";
    private static final String REDIS_IMAGE="redis@sha256:f245f4c83b84a9e9a0588da1c9456e579455310f0945d5db52cd2ba27f2000cc";
    private record Command(GuestIdentity actor,LandlordAction action,LandlordDeck.Deal deal){ }
    private record Event(String kind,String json){ }
    private static final class Actor {
        final RoomMailboxes.Handle handle;final Map<Long,GuestIdentity> identities;
        final AtomicBoolean pending=new AtomicBoolean();volatile RoomSnapshot<LandlordPlayState> state;
        Actor(RoomMailboxes.Handle handle,Map<Long,GuestIdentity> identities,RoomSnapshot<LandlordPlayState> state){this.handle=handle;this.identities=Map.copyOf(identities);this.state=state;}
    }
    private static final class Stats {
        final AtomicInteger completed=new AtomicInteger();final AtomicLong sent=new AtomicLong(),failed=new AtomicLong(),busy=new AtomicLong(),missed=new AtomicLong(),plays=new AtomicLong(),passes=new AtomicLong(),redeals=new AtomicLong();
        final ConcurrentMap<String,AtomicLong> failures=new ConcurrentHashMap<>();final long[] latency;
        final AtomicInteger slowCount=new AtomicInteger();final Queue<Map<String,Object>> slow=new ConcurrentLinkedQueue<>();
        long elapsedNanos;
        Stats(int capacity){latency=new long[capacity];}
        void failure(Throwable error){failed.incrementAndGet();Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();failures.computeIfAbsent(cause.getClass().getSimpleName(),ignored->new AtomicLong()).incrementAndGet();}
        Map<String,Object> report(){
            int done=completed.get();long[] sorted=Arrays.copyOf(latency,Math.min(done,latency.length));Arrays.sort(sorted);
            Map<String,Object> result=new LinkedHashMap<>();result.put("sent",sent.get());result.put("completed",done);result.put("failed",failed.get());result.put("busy",busy.get());result.put("missed",missed.get());
            result.put("played",plays.get());result.put("passed",passes.get());result.put("testRedeals",redeals.get());result.put("throughput",elapsedNanos==0?0:done*1e9/elapsedNanos);
            result.put("latencyMs",Map.of("p50",percentile(sorted,.5),"p95",percentile(sorted,.95),"p99",percentile(sorted,.99),"max",percentile(sorted,1)));
            Map<String,Long> errors=new TreeMap<>();failures.forEach((key,value)->errors.put(key,value.get()));result.put("errors",errors);return result;
        }
    }
    private static double percentile(long[] sorted,double percentile){return sorted.length==0?0:sorted[Math.min(sorted.length-1,(int)Math.ceil(sorted.length*percentile)-1)]/1e6;}
    private static int parameter(String name,int fallback,int min,int max){int value=Integer.parseInt(System.getenv().getOrDefault(name,Integer.toString(fallback)));if(value<min||value>max)throw new IllegalArgumentException("负载参数无效："+name);return value;}
    public static void main(String[] args) throws Exception {
        if(!"1".equals(System.getenv("RUN_ROOM_STORAGE_LOAD")))throw new IllegalStateException("需要显式启用隔离房间负载");
        int seconds=parameter("ROOM_LOAD_SECONDS",900,10,3600),rate=parameter("ROOM_LOAD_RATE",200,1,200),rooms=parameter("ROOM_LOAD_ROOMS",200,3,400);
        int adminReaders=parameter("ROOM_LOAD_ADMIN_READERS",0,0,10);if(adminReaders!=0&&adminReaders!=10)throw new IllegalArgumentException("后台混合读取仅支持 0 或 10 会话");
        String loadAdminPassword=adminReaders==0?"":SecureTokens.create();
        String reportName=System.getenv().getOrDefault("ROOM_LOAD_REPORT","room-storage-load.json");
        if(!reportName.matches("room-storage-load(?:-[a-z0-9-]+)?\\.json"))throw new IllegalArgumentException("报告名称无效");
        Path output=Path.of("docs/evidence",reportName);if(Files.exists(output))throw new IllegalStateException("保留既有结果，请使用新的报告名称");
        System.setProperty("spring.devtools.restart.enabled","false");
        Map<String,Object> report=new LinkedHashMap<>();report.put("startedAt",Instant.now().toString());
        report.put("scope","隔离 MySQL/Redis 的内部 6003 单张/过牌及测试换局持久提交；没有 WS、叫分、收费、积分结算或生产容量结论");
        report.put("requested",Map.of("seconds",seconds,"commitsPerSecond",rate,"rooms",rooms,"players",rooms*3,"outboxLagBudgetSeconds",10,"adminSessions",adminReaders));
        report.put("sourceCommit",System.getenv().getOrDefault("ROOM_LOAD_GIT","unrecorded"));report.put("snapshotSha256",System.getenv().getOrDefault("ROOM_LOAD_SNAPSHOT_SHA256","unrecorded"));
        report.put("flightRecordingEnabled","1".equals(System.getenv("ROOM_LOAD_JFR")));
        report.put("hostCompetition",System.getenv().getOrDefault("HOST_LOAD_COMPETITION","none"));
        long hostMemory=ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os?os.getTotalMemorySize():-1;
        report.put("environment",Map.of("java",System.getProperty("java.version"),"vendor",System.getProperty("java.vendor"),"os",System.getProperty("os.name"),"arch",System.getProperty("os.arch"),"availableProcessors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),"mysqlImage",MYSQL_IMAGE,"redisImage",REDIS_IMAGE,"dockerCpuMemoryVersion",System.getenv().getOrDefault("ROOM_LOAD_DOCKER_ENV","unrecorded"),"hostMemoryBytes",hostMemory));
        boolean pass=false;
        try(var mysql=new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE)).withDatabaseName("room_load").withUsername("room_load").withPassword(SecureTokens.create()).withUrlParam("allowPublicKeyRetrieval","true").withUrlParam("serverTimezone","UTC");
            var redis=new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379)){
            String redisPassword=SecureTokens.create();redis.withCommand("redis-server","--requirepass",redisPassword);mysql.start();redis.start();
            Map<String,Object> properties=new HashMap<>();
            properties.put("spring.datasource.druid.master.url",mysql.getJdbcUrl());properties.put("spring.datasource.druid.master.username",mysql.getUsername());properties.put("spring.datasource.druid.master.password",mysql.getPassword());
            properties.put("spring.data.redis.host",redis.getHost());properties.put("spring.data.redis.port",redis.getMappedPort(6379));properties.put("spring.data.redis.password",redisPassword);
            properties.put("token.secret",SecureTokens.create());properties.put("game.pii-key",SecureTokens.create());properties.put("game.ws.port",0);properties.put("server.port",0);properties.put("ADMIN_INITIAL_PASSWORD",loadAdminPassword);properties.put("game.outbox.worker-enabled",true);
            properties.put("game.reconciliation.worker-enabled",false);
            properties.put("game.online-history.enabled",false);
            var environment=new StandardServletEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("isolated-room-load",properties));
            var application=new SpringApplication(RuoYiApplication.class);application.setEnvironment(environment);
            try(var context=application.run()){
                pass=execute(context,mysql,seconds,rate,rooms,report,adminReaders,loadAdminPassword);
            }
        }catch(Exception failure){report.put("failureClass",failure.getClass().getSimpleName());System.err.println("隔离负载失败："+failure.getClass().getSimpleName());}
        report.put("endedAt",Instant.now().toString());report.put("result",pass?"PASS":"FAIL");
        Files.createDirectories(output.getParent());Files.writeString(output,new GameJsonCodec().write(report)+"\n");System.out.println("房间持久负载 "+(pass?"PASS":"FAIL")+"，报告："+output);
        if(!pass)System.exit(1);
    }
    private static boolean execute(ConfigurableApplicationContext context,MySQLContainer mysql,int seconds,int rate,int rooms,Map<String,Object> report,int adminReaders,String loadAdminPassword) throws Exception {
        var jdbc=context.getBean(JdbcTemplate.class);var auth=context.getBean(GuestAuthService.class);var journal=context.getBean(RoomJournal.class);var outbox=context.getBean(OutboxStore.class);var metrics=context.getBean(MeterRegistry.class);
        var codec=new LandlordPlayCodec(context.getBean(GameJsonCodec.class));var engine=new LandlordPlayEngine();
        if(!"room_load".equals(jdbc.queryForObject("SELECT DATABASE()",String.class)))throw new IllegalStateException("拒绝连接非隔离数据库");
        report.put("durability",jdbc.queryForMap("SELECT @@innodb_flush_log_at_trx_commit AS flushAtCommit,@@sync_binlog AS syncBinlog,@@log_bin AS logBin"));
        report.put("mysqlSettings",jdbc.queryForMap("SELECT @@innodb_buffer_pool_size AS bufferPoolBytes,@@max_connections AS maxConnections,@@innodb_page_size AS pageBytes,@@transaction_isolation AS defaultIsolation"));
        report.put("mysqlContainer",mysql.getContainerId());report.put("mysqlDirectoryBeforeBytes",directoryBytes(mysql));
        RoomMailboxes.Engine<LandlordPlayState,Command,Event> rules=(before,command)->{
            if(command.deal()!=null){
                if(!before.state().finished()||command.action()!=null||command.actor().uid()!=before.state().players().getFirst())throw new RoomRejected("INVALID_TEST_REDEAL");
                var next=LandlordPlayEngine.start(before.state().players(),command.deal(),before.state().next(before.state().landlord()));
                return new RoomTransition<>(before.round()+1,"PLAYING",next,new Event("TEST_DEAL_RECORDED",codec.json(next)));
            }
            var next=engine.apply(before,new LandlordPlayCommand(command.actor().uid(),command.action()));
            return new RoomTransition<>(next.round(),next.phase(),next.state(),new Event("TEST_"+codec.kind(next.event()),codec.eventJson(next.event())));
        };
        var persistence=new JournalRoomPersistence<LandlordPlayState,Command,Event>(journal,write->{
            var expected=rules.apply(write.previous(),write.command());
            if(!expected.state().equals(write.candidate().state())||!expected.event().equals(write.event())||expected.round()!=write.candidate().round()||!expected.phase().equals(write.candidate().phase()))throw new IllegalArgumentException("负载候选不一致");
            return new RoomJournalCommand(encoded(write.previous(),codec),encoded(write.candidate(),codec),write.event().kind(),write.event().json(),RULE,1,ENGINE,write.command().actor(),List.of(),null,write.command().deal()!=null||write.candidate().state().finished(),write.occurredAt());
        });
        int shards=Math.min(8,Runtime.getRuntime().availableProcessors());
        try(var boxes=new RoomMailboxes<LandlordPlayState,Command,Event>(new RoomMailboxes.Config(shards,4096,1024,16,8,256,10),persistence)){
            List<Actor> actors=new ArrayList<>();
            for(int room=0;room<rooms;room++){
                List<GuestIdentity> members=new ArrayList<>();Map<Long,GuestIdentity> identities=new HashMap<>();
                for(int seat=0;seat<3;seat++){
                    var token=auth.createGuest();var identity=auth.verify(token);jdbc.update("UPDATE user_account SET is_test=TRUE WHERE id=?",identity.accountId());identity=auth.verify(token);members.add(identity);identities.put(identity.uid(),identity);
                }
                var players=members.stream().map(GuestIdentity::uid).toList();String id=UUID.randomUUID().toString(),code=Integer.toString(700000+room),empty="{}";
                jdbc.update("INSERT INTO room_record(room_id,room_code,game_id,owner_uid,creator_uid,options_json,rule_version,is_test,created_at) VALUES(?,?,'6003',?,?,'{}',?,TRUE,UTC_TIMESTAMP(6))",id,code,players.getFirst(),players.getFirst(),RULE);
                jdbc.update("INSERT INTO active_room_code(room_code,room_id) VALUES(?,?)",code,id);
                jdbc.update("INSERT INTO room_runtime(room_id,state_hash,snapshot_json,snapshot_hash,schema_version,engine_version,updated_at) VALUES(?,?,?,?,1,?,UTC_TIMESTAMP(6))",id,journal.stateHash(empty),empty,journal.stateHash(empty),ENGINE);
                for(int seat=0;seat<3;seat++){jdbc.update("INSERT INTO room_player(room_id,uid,seat,joined_at) VALUES(?,?,?,UTC_TIMESTAMP(6))",id,players.get(seat),seat);jdbc.update("INSERT INTO player_room_binding(uid,room_id,seat,joined_at) VALUES(?,?,?,UTC_TIMESTAMP(6))",players.get(seat),id,seat);}
                var owner=journal.claim(id,UUID.randomUUID().toString(),1,ENGINE);var state=LandlordPlayEngine.start(players,LandlordDeck.deal(),players.getFirst());
                var previous=new RoomSnapshot<>(id,owner.ownerEpoch(),0,0,"WAITING",empty);var candidate=new RoomSnapshot<>(id,owner.ownerEpoch(),1,1,"PLAYING",codec.json(state));
                if(journal.commit(new RoomJournalCommand(previous,candidate,"TEST_DEAL_RECORDED",codec.json(state),RULE,1,ENGINE,members.getFirst(),List.of(),null,true,Instant.now()))!=RoomPersistence.Decision.COMMITTED)throw new IllegalStateException("负载初始事件提交失败");
                var snapshot=new RoomSnapshot<>(id,owner.ownerEpoch(),1,1,"PLAYING",state);actors.add(new Actor(boxes.register(snapshot,rules),identities,snapshot));
            }
            System.out.println("隔离房间已准备："+rooms+"，开始 15 秒预热");
            var warmup=new Stats(15*rate);window(boxes,actors,15,rate,warmup);await(actors,30);report.put("warmup",warmup.report());
            report.put("eventsBeforeMeasurement",count(jdbc,"SELECT COUNT(*) FROM room_event"));
            var stats=new Stats(seconds*rate);List<Map<String,Object>> samples=Collections.synchronizedList(new ArrayList<>());AtomicLong sampleFailures=new AtomicLong();
            String day=Instant.now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
            RoomLoadAdminReaders adminReads=adminReaders==0?null:new RoomLoadAdminReaders(adminReaders,Integer.parseInt(context.getEnvironment().getProperty("local.server.port")),loadAdminPassword,day,actors.getFirst().state.state().players().getFirst(),rooms,()->"room_load".equals(jdbc.queryForObject("SELECT DATABASE()",String.class)));
            var sampler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("room-load-sample").daemon().factory());
            sampler.scheduleWithFixedDelay(()->{try{
                var observed=sample(boxes,outbox,metrics,stats);samples.add(observed);
                System.out.println("消费采样：READY="+observed.get("outboxReady")+"，CLAIMED="+observed.get("outboxClaimed")+"，最老="+observed.get("oldestSeconds")+" 秒，确认="+observed.get("completed"));
            }catch(RuntimeException failure){sampleFailures.incrementAndGet();System.out.println("消费采样未完成："+failure.getClass().getSimpleName());}},0,5,TimeUnit.SECONDS);
            report.put("measurementStartedAt",Instant.now().toString());
            try{if(adminReads!=null)adminReads.start();window(boxes,actors,seconds,rate,stats);await(actors,30);}
            finally{sampler.shutdown();sampler.awaitTermination(5,TimeUnit.SECONDS);if(adminReads!=null)adminReads.stop();}
            boolean adminPass=adminReads==null||adminReads.passed(seconds);
            if(adminReads!=null){report.put("adminReads",adminReads.report());adminReads.close();}
            report.put("measurementEndedAt",Instant.now().toString());report.put("measurementMs",stats.elapsedNanos/1e6);report.put("actual",stats.report());report.put("slowCommitCount",stats.slowCount.get());report.put("slowCommitSamples",List.copyOf(stats.slow));report.put("samples",List.copyOf(samples));report.put("sampleFailures",sampleFailures.get());
            double maxLag=samples.stream().mapToDouble(sample->((Number)sample.get("oldestSeconds")).doubleValue()).max().orElse(Double.POSITIVE_INFINITY);
            report.put("maxObservedOutboxLagSeconds",Double.isFinite(maxLag)?maxLag:null);
            long drainStart=System.nanoTime();OutboxStore.Backlog backlog;
            do{backlog=outbox.backlog();if(backlog.ready()==0&&backlog.claimed()==0)break;Thread.sleep(250);}while(System.nanoTime()-drainStart<30_000_000_000L);
            report.put("outboxAfterDrain",Map.of("ready",backlog.ready(),"claimed",backlog.claimed(),"dead",backlog.dead(),"oldestSeconds",backlog.oldestSeconds()));
            Map<String,Double> outboxCounters=new LinkedHashMap<>();
            for(String name:List.of("completed","failed","lease.lost","poll.failed"))outboxCounters.put(name,metrics.get("game.outbox."+name).counter().count());
            report.put("outboxCounters",outboxCounters);
            long events=count(jdbc,"SELECT COUNT(*) FROM room_event"),done=count(jdbc,"SELECT COUNT(*) FROM outbox_event WHERE status='DONE'"),receipts=count(jdbc,"SELECT COUNT(*) FROM consumer_receipt"),projected=count(jdbc,"SELECT COALESCE(SUM(event_count),0) FROM room_event_projection");
            report.put("persistentTotals",Map.of("events",events,"outboxDone",done,"consumerReceipts",receipts,"projectedEvents",projected,"walletTransactions",count(jdbc,"SELECT COUNT(*) FROM wallet_transaction")));
            report.put("eventPayloadSizeHistogram",jdbc.queryForList("SELECT OCTET_LENGTH(payload_json) AS bytes,COUNT(*) AS events FROM room_event GROUP BY OCTET_LENGTH(payload_json) ORDER BY bytes"));
            report.put("schemaTableEstimates",jdbc.queryForList("SELECT table_name AS tableName,table_rows AS estimatedRows,data_length AS estimatedDataBytes,index_length AS estimatedIndexBytes FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name"));
            report.put("mysqlDirectoryAfterBytes",directoryBytes(mysql));
            report.put("pending",Map.of("commands",boxes.pendingCommands(),"completions",boxes.pendingCompletions(),"io",boxes.pendingIo(),"reconciliations",boxes.pendingReconciliations()));
            boolean drained=boxes.pendingCommands()==0&&boxes.pendingCompletions()==0&&boxes.pendingIo()==0&&boxes.pendingReconciliations()==0;
            boxes.close();List<Map<String,Object>> recoveryTimes=new ArrayList<>();
            RoomReplay.Codec<LandlordPlayState,Event> recoveryCodec=new RoomReplay.Codec<>(){
                public boolean supports(String game,int schema,String engineVersion){return "6003".equals(game)&&schema==1&&ENGINE.equals(engineVersion);}
                public LandlordPlayState state(String json){return codec.state(json);}
                public Event event(String kind,String json){return new Event(kind,json);}
                public String json(LandlordPlayState state){return codec.json(state);}
            };
            for(var actor:actors.subList(0,Math.min(10,actors.size()))){
                long began=System.nanoTime();var recovered=journal.claim(actor.handle.roomId(),UUID.randomUUID().toString(),1,ENGINE);
                var replay=RoomReplay.recover(recovered,recoveryCodec,(state,event)->{
                    if("TEST_DEAL_RECORDED".equals(event.kind())){if(!state.finished())throw new IllegalArgumentException("提前换局");return codec.state(event.json());}
                    return engine.replay(state,codec.event(event.kind().substring(5),event.json()));
                },journal::stateHash);
                if(!replay.state().equals(actor.state.state()))throw new IllegalStateException("负载后恢复不一致");
                recoveryTimes.add(Map.of("tailEvents",recovered.events().size(),"milliseconds",(System.nanoTime()-began)/1e6));
            }
            report.put("recoverySamples",recoveryTimes);
            long[] measured=Arrays.copyOf(stats.latency,stats.completed.get());Arrays.sort(measured);
            return adminPass&&stats.failed.get()==0&&stats.busy.get()==0&&stats.missed.get()==0&&stats.completed.get()==stats.sent.get()&&stats.completed.get()>=seconds*rate*.99&&stats.elapsedNanos>=seconds*1_000_000_000L
                    &&drained&&maxLag<=10&&sampleFailures.get()==0&&backlog.ready()==0&&backlog.claimed()==0&&backlog.dead()==0&&outboxCounters.get("failed")==0&&outboxCounters.get("lease.lost")==0&&outboxCounters.get("poll.failed")==0&&events==done&&done==receipts&&receipts==projected&&count(jdbc,"SELECT COUNT(*) FROM wallet_transaction")==0&&warmup.failed.get()==0&&percentile(measured,.95)<=300;
        }
    }
    private static void window(RoomMailboxes<LandlordPlayState,Command,Event> boxes,List<Actor> actors,int seconds,int rate,Stats stats){
        long began=System.nanoTime(),duration=seconds*1_000_000_000L,nextReport=began+30_000_000_000L;int sequence=0;
        while(System.nanoTime()-began<duration){
            long now=System.nanoTime();int due=(int)Math.min((long)seconds*rate,(now-began)*rate/1_000_000_000L);
            if(due-sequence>rate){stats.missed.addAndGet(due-sequence-rate);sequence=due-rate;}
            while(sequence<due){
                var actor=actors.get(sequence++%actors.size());if(!actor.pending.compareAndSet(false,true)){stats.busy.incrementAndGet();continue;}
                var state=actor.state.state();Command command;
                if(state.finished()){command=new Command(actor.identities.get(state.players().getFirst()),null,LandlordDeck.deal());stats.redeals.incrementAndGet();}
                else{
                    long uid=state.currentUid();var target=state.target();var card=state.hands().get(uid).stream().filter(value->target==null||value.rank()>target.pattern().mainRank()).min(Comparator.comparingInt(LandlordCard::rank));
                    LandlordAction action=card.<LandlordAction>map(value->new LandlordAction.Play(List.of(value),LandlordPatterns.classify(List.of(value)).orElseThrow())).orElseGet(LandlordAction.Pass::new);
                    command=new Command(actor.identities.get(uid),action,null);if(card.isPresent())stats.plays.incrementAndGet();else stats.passes.incrementAndGet();
                }
                stats.sent.incrementAndGet();long sentAt=System.nanoTime(),sentWallMillis=System.currentTimeMillis();
                boxes.submit(actor.handle,command).whenComplete((snapshot,error)->{
                    try{if(error!=null)stats.failure(error);else{
                        actor.state=snapshot;long latency=System.nanoTime()-sentAt;int index=stats.completed.getAndIncrement();if(index<stats.latency.length)stats.latency[index]=latency;
                        if(latency>300_000_000L&&stats.slowCount.getAndIncrement()<100)stats.slow.add(Map.of("sentAt",Instant.ofEpochMilli(sentWallMillis).toString(),"completedAt",Instant.now().toString(),"milliseconds",latency/1e6,"kind",command.deal()!=null?"TEST_REDEAL":command.action() instanceof LandlordAction.Play?"PLAY":"PASS","round",snapshot.round(),"ioPending",boxes.pendingIo(),"commandsPending",boxes.pendingCommands()));
                    }}
                    finally{actor.pending.set(false);}
                });
            }
            if(now>=nextReport){System.out.println("持久测量 "+((now-began)/1_000_000_000L)+" 秒：提交 "+stats.sent.get()+"，完成 "+stats.completed.get()+"，错误 "+stats.failed.get()+"，忙碌 "+stats.busy.get());nextReport+=30_000_000_000L;}
            LockSupport.parkNanos(1_000_000);
        }
        stats.elapsedNanos=System.nanoTime()-began;
    }
    private static void await(List<Actor> actors,int seconds) throws InterruptedException {
        long began=System.nanoTime();while(actors.stream().anyMatch(actor->actor.pending.get())){if(System.nanoTime()-began>seconds*1_000_000_000L)throw new IllegalStateException("房间提交没有排空");Thread.sleep(10);}
    }
    private static Map<String,Object> sample(RoomMailboxes<?,?,?> boxes,OutboxStore outbox,MeterRegistry metrics,Stats stats){
        var backlog=outbox.backlog();var memory=ManagementFactory.getMemoryMXBean();
        Map<String,Object> values=new LinkedHashMap<>();values.put("at",Instant.now().toString());values.put("completed",stats.completed.get());values.put("pendingCommands",boxes.pendingCommands());values.put("pendingIo",boxes.pendingIo());
        values.put("outboxReady",backlog.ready());values.put("outboxClaimed",backlog.claimed());values.put("outboxDead",backlog.dead());values.put("oldestSeconds",backlog.oldestSeconds());
        values.put("heapUsedBytes",memory.getHeapMemoryUsage().getUsed());values.put("nonHeapUsedBytes",memory.getNonHeapMemoryUsage().getUsed());
        values.put("threads",ManagementFactory.getThreadMXBean().getThreadCount());
        if(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)values.put("processCpuFraction",os.getProcessCpuLoad());
        values.put("gcMillis",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->Math.max(0,bean.getCollectionTime())).sum());
        for(String pool:List.of("room","general"))for(String metric:List.of("active","idle","waiters"))values.put(pool+"Sql"+metric,metrics.get("game.sql."+metric).tag("pool",pool).gauge().value());
        for(String name:List.of("game.room.sql.duration","game.outbox.claim.duration","game.outbox.handle.duration")){
            var timer=metrics.get(name).timer();values.put(name,Map.of("count",timer.count(),"totalMs",timer.totalTime(TimeUnit.MILLISECONDS),"maxMs",timer.max(TimeUnit.MILLISECONDS)));
        }
        var batches=metrics.get("game.outbox.batch.size").summary();values.put("outboxBatches",Map.of("count",batches.count(),"totalEvents",batches.totalAmount(),"maxSize",batches.max()));
        values.put("outboxActive",metrics.get("game.outbox.active").gauge().value());values.put("outboxQueued",metrics.get("game.outbox.queue").gauge().value());values.put("outboxInflight",metrics.get("game.outbox.inflight").gauge().value());
        return values;
    }
    private static RoomSnapshot<String> encoded(RoomSnapshot<LandlordPlayState> state,LandlordPlayCodec codec){return new RoomSnapshot<>(state.roomId(),state.ownerEpoch(),state.version(),state.round(),state.phase(),codec.json(state.state()));}
    private static long count(JdbcTemplate jdbc,String query){return Objects.requireNonNull(jdbc.queryForObject(query,Long.class));}
    private static long directoryBytes(MySQLContainer mysql) throws Exception {var result=mysql.execInContainer("du","-sb","/var/lib/mysql");if(result.getExitCode()!=0)throw new IllegalStateException("无法读取隔离数据库目录大小");return Long.parseLong(result.getStdout().strip().split("\\s+")[0]);}
}
