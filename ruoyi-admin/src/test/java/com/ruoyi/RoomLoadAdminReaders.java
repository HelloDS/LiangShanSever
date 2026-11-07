package com.ruoyi;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.ruoyi.game.protocol.GameJsonCodec;

/** 仅隔离 RoomStorageLoad 使用：真实 HTTP 登录/JWT 查询，不调用管理服务绕过权限链。 */
final class RoomLoadAdminReaders implements AutoCloseable {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final GameJsonCodec codec=new GameJsonCodec();
    private final URI base;
    private final List<String> tokens=new ArrayList<>();
    private final List<AtomicBoolean> inFlight=new ArrayList<>();
    private final List<String> paths;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicLong sent=new AtomicLong(),completed=new AtomicLong(),failed=new AtomicLong(),busy=new AtomicLong();
    private final AtomicInteger concurrent=new AtomicInteger(),peakConcurrent=new AtomicInteger();
    private final Queue<Long> latencyNanos=new ConcurrentLinkedQueue<>();
    private final int count;
    private final int rooms;
    private final long uid;
    private final java.util.function.BooleanSupplier databaseIsolated;
    private volatile boolean running;
    RoomLoadAdminReaders(int count,int port,String password,String day,long uid,int rooms,java.util.function.BooleanSupplier databaseIsolated) throws Exception {
        if(count!=10||port<1||port>65535||!databaseIsolated.getAsBoolean())throw new IllegalArgumentException("后台混合读取只能用于隔离负载");
        this.count=count;this.uid=uid;this.rooms=rooms;this.databaseIsolated=databaseIsolated;base=URI.create("http://127.0.0.1:"+port);
        paths=List.of("/game-admin/rooms?start="+day+"&end="+day+"&pageSize=50","/game-admin/players?uid="+uid,"/game-admin/dashboard");
        // 登录先完成，不把认证预备时延混入读取测量；凭据仅存在于当前测试进程内存。
        try{for(int i=0;i<count;i++){
            var request=HttpRequest.newBuilder(base.resolve("/login")).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(codec.write(Map.of("username","admin","password",password)))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("后台负载登录失败");
            var packet=codec.read(response.body());if(packet.path("code").asInt()!=200||!packet.path("token").isString())throw new IllegalStateException("后台负载会话未建立");
            tokens.add(packet.path("token").asString());inFlight.add(new AtomicBoolean());
        }}catch(Exception failure){tokens.clear();http.close();throw failure;}
        workers=new ThreadPoolExecutor(10,10,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(10),Thread.ofPlatform().name("room-load-admin-http-",0).daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
        workers.prestartAllCoreThreads();
        scheduler=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("room-load-admin-tick").daemon().factory());
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    synchronized void start(){if(running)return;if(!databaseIsolated.getAsBoolean())throw new IllegalStateException("隔离数据库已改变");running=true;scheduler.scheduleWithFixedDelay(this::tick,0,10,TimeUnit.SECONDS);}
    private void tick(){
        for(int index=0;index<count&&running;index++){
            int slot=index;if(!inFlight.get(slot).compareAndSet(false,true)){busy.incrementAndGet();continue;}
            long began=System.nanoTime();sent.incrementAndGet();
            try{workers.execute(()->query(slot,began));}catch(RejectedExecutionException rejection){inFlight.get(slot).set(false);failed.incrementAndGet();}
        }
    }
    private void query(int slot,long began){
        try{
            var request=HttpRequest.newBuilder(base.resolve(paths.get(slot%paths.size()))).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+tokens.get(slot)).GET().build();
            HttpResponse<String> response;int active=concurrent.incrementAndGet();peakConcurrent.accumulateAndGet(active,Math::max);
            try{response=http.send(request,HttpResponse.BodyHandlers.ofString());}finally{concurrent.decrementAndGet();}
            var packet=codec.read(response.body());
            if(response.statusCode()!=200||packet.path("code").asInt()!=200)throw new IllegalStateException("后台读取未完成");
            if(slot%paths.size()==0){
                if(!packet.path("rows").isArray()||packet.path("rows").size()!=Math.min(50,rooms)||packet.path("total").asLong()!=rooms)throw new IllegalStateException("房间读取与实际夹具不符");
            }else if(slot%paths.size()==1){
                if(!packet.path("rows").isArray()||packet.path("rows").size()!=1||!Long.toString(uid).equals(packet.path("rows").get(0).path("uid").asString()))throw new IllegalStateException("玩家读取与实际夹具不符");
            }else{
                if(!packet.path("data").isObject()||packet.path("data").path("todayNew").asInt()!=0||packet.path("data").path("todayLogin").asInt()!=0)throw new IllegalStateException("测试玩家混入运营统计");
            }
            latencyNanos.add(System.nanoTime()-began);completed.incrementAndGet();
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();failed.incrementAndGet();}
        finally{inFlight.get(slot).set(false);}
    }
    synchronized void stop(){
        running=false;scheduler.shutdown();workers.shutdown();
        try{if(!scheduler.awaitTermination(1,TimeUnit.SECONDS))scheduler.shutdownNow();if(!workers.awaitTermination(6,TimeUnit.SECONDS))workers.shutdownNow();}
        catch(InterruptedException failure){scheduler.shutdownNow();workers.shutdownNow();Thread.currentThread().interrupt();}
    }
    Map<String,Object> report(){
        var sorted=latencyNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        Map<String,Object> result=new LinkedHashMap<>();result.put("sessions",count);result.put("httpWorkers",10);result.put("peakConcurrentQueries",peakConcurrent.get());result.put("latencyIncludesClientQueue",true);result.put("intervalSeconds",10);result.put("sent",sent.get());result.put("completed",completed.get());result.put("failed",failed.get());result.put("busy",busy.get());result.put("pending",inFlight.stream().filter(AtomicBoolean::get).count());
        result.put("latencyMs",Map.of("p50",percentile(sorted,.5),"p95",percentile(sorted,.95),"p99",percentile(sorted,.99),"max",percentile(sorted,1)));return result;
    }
    boolean passed(int seconds){return peakConcurrent.get()==10&&sent.get()==completed.get()&&completed.get()>=count*Math.max(1,seconds/10-1)&&failed.get()==0&&busy.get()==0&&inFlight.stream().noneMatch(AtomicBoolean::get)&&percentile(latencyNanos.stream().mapToLong(Long::longValue).sorted().toArray(),.95)<=300;}
    private static double percentile(long[] sorted,double fraction){return sorted.length==0?0:sorted[Math.min(sorted.length-1,(int)Math.ceil(sorted.length*fraction)-1)]/1e6;}
    @Override public void close(){stop();tokens.clear();http.close();}
}
