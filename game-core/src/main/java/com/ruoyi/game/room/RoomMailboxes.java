package com.ruoyi.game.room;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import com.ruoyi.game.room.RoomPersistence.*;

/**
 * 固定分片串行执行纯引擎；数据库在独立线程运行。
 * 每房间至多一个提交，完成消息预留容量；所有准入预算覆盖排队、提交及结果通知。
 * 尚未注册到公开路由，玩法/收费适配完成后才启用。
 */
public final class RoomMailboxes<S,C,E> implements AutoCloseable {
    public record Config(int shards,int commandsPerShard,int roomsPerShard,int commandsPerRoom,int ioThreads,int ioQueue,long retryMillis){
        public Config{
            if(shards<1||shards>32||commandsPerShard<1||commandsPerShard>4096||roomsPerShard<1||roomsPerShard>1024
                    ||commandsPerRoom<1||commandsPerRoom>64||ioThreads<1||ioThreads>16||ioQueue<1||ioQueue>256||retryMillis<1||retryMillis>1000)
                throw new IllegalArgumentException("房间执行器容量无效");
        }
    }
    @FunctionalInterface public interface Engine<S,C,E>{RoomTransition<S,E> apply(RoomSnapshot<S> committed,C command);}
    public record Handle(String roomId,long ownerEpoch){ }
    public record Fence(long ownerEpoch,long version,int round,String phase){
        public static Fence of(RoomSnapshot<?> snapshot){return new Fence(snapshot.ownerEpoch(),snapshot.version(),snapshot.round(),snapshot.phase());}
    }
    private final Config config;
    private final RoomPersistence<S,C,E> persistence;
    private final java.time.Clock clock;
    private final List<Shard> shards=new ArrayList<>();
    private final ThreadPoolExecutor io,notifications;
    private final ScheduledThreadPoolExecutor retries;
    private final AtomicBoolean accepting=new AtomicBoolean(true);

    public RoomMailboxes(Config config,RoomPersistence<S,C,E> persistence){
        this(config,persistence,java.time.Clock.systemUTC());
    }
    public RoomMailboxes(Config config,RoomPersistence<S,C,E> persistence,java.time.Clock clock){
        this.config=Objects.requireNonNull(config);this.persistence=Objects.requireNonNull(persistence);
        this.clock=Objects.requireNonNull(clock);
        io=pool(config.ioThreads(),config.ioQueue(),"room-io-");
        notifications=pool(2,Math.multiplyExact(config.shards(),config.commandsPerShard()),"room-result-");
        retries=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("room-reconcile").daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
        retries.setRemoveOnCancelPolicy(true);retries.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        for(int i=0;i<config.shards();i++)shards.add(new Shard(i));
    }
    private static ThreadPoolExecutor pool(int threads,int capacity,String name){
        return new ThreadPoolExecutor(threads,threads,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name(name,0).daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    private Shard shard(String roomId){return shards.get(Math.floorMod(roomId.hashCode(),shards.size()));}

    /** 调用方已经在数据库取得 ownerEpoch；这里只安装已恢复的不可变状态。 */
    public Handle register(RoomSnapshot<S> snapshot,Engine<S,C,E> engine){
        if(!accepting.get())throw new RoomRejected("STOPPED");
        Objects.requireNonNull(engine);
        Shard shard=shard(snapshot.roomId());
        if(!shard.running)throw new RoomRejected("SHARD_FAILED");
        if(!shard.roomSlots.tryAcquire())throw new RoomRejected("ROOM_CAPACITY");
        Actor actor=new Actor(shard,snapshot,Objects.requireNonNull(engine));
        if(shard.actors.putIfAbsent(snapshot.roomId(),actor)!=null){shard.roomSlots.release();throw new RoomRejected("ALREADY_OWNED");}
        if(!accepting.get()){if(shard.actors.remove(snapshot.roomId(),actor)){actor.closed=true;shard.roomSlots.release();}throw new RoomRejected("STOPPED");}
        return new Handle(snapshot.roomId(),snapshot.ownerEpoch());
    }
    public CompletionStage<RoomSnapshot<S>> submit(Handle handle,C command){return submit(handle,null,command);}
    /** 定时器携带其创建时的 epoch、版本、局号、阶段，重新进入同一邮箱。 */
    public CompletionStage<RoomSnapshot<S>> submitTimer(Handle handle,Fence fence,C command){return submit(handle,Objects.requireNonNull(fence),command);}
    private CompletionStage<RoomSnapshot<S>> submit(Handle handle,Fence fence,C command){
        Objects.requireNonNull(command);
        Shard shard=shard(handle.roomId());Actor actor=shard.actors.get(handle.roomId());
        if(!accepting.get()||actor==null||actor.closed||actor.snapshot.ownerEpoch()!=handle.ownerEpoch())return rejected("NOT_OWNED");
        if(!shard.commands.tryAcquire())return rejected("SHARD_BUSY");
        if(!actor.commands.tryAcquire()){shard.commands.release();return rejected("ROOM_BUSY");}
        Request request=new Request(actor,fence,command);shard.requests.add(request);
        if(!accepting.get()||!shard.normal.offer(()->receive(actor,request)))finish(request,null,"SHARD_BUSY");
        else LockSupport.unpark(shard.thread);
        return request.result.minimalCompletionStage();
    }
    private CompletionStage<RoomSnapshot<S>> rejected(String code){return CompletableFuture.<RoomSnapshot<S>>failedFuture(new RoomRejected(code)).minimalCompletionStage();}
    public RoomSnapshot<S> snapshot(Handle handle){
        Actor actor=shard(handle.roomId()).actors.get(handle.roomId());
        if(actor==null||actor.closed||actor.snapshot.ownerEpoch()!=handle.ownerEpoch())throw new RoomRejected("NOT_OWNED");
        return actor.snapshot;
    }
    /** 由业务适配器释放已关闭的房间，在所属分片上拒绝剩余排队命令。 */
    public void retireClosed(Handle handle) {
        Shard shard=shard(handle.roomId());Actor actor=shard.actors.get(handle.roomId());
        if(actor==null || actor.snapshot.ownerEpoch()!=handle.ownerEpoch() || !"CLOSED".equals(actor.snapshot.phase()) || !actor.retiring.compareAndSet(false,true))return;
        boolean queued=shard.normal.offer(()->{
            if(actor.commit!=null){actor.retiring.set(false);return;}
            actor.closed=true;
            if(shard.actors.remove(handle.roomId(),actor))shard.roomSlots.release();
            while(!actor.waiting.isEmpty())finish(actor.waiting.remove(),null,"ROOM_CLOSED");
        });
        if(!queued)actor.retiring.set(false);
        else LockSupport.unpark(shard.thread);
    }
    public int pendingCommands(){return shards.stream().mapToInt(shard->config.commandsPerShard()-shard.commands.availablePermits()).sum();}
    public int pendingCompletions(){return shards.stream().mapToInt(shard->shard.control.size()).sum();}
    public int pendingIo(){return io.getQueue().size();}
    public int pendingReconciliations(){return retries.getQueue().size();}
    private boolean current(Actor actor){return !actor.closed&&actor.shard.actors.get(actor.snapshot.roomId())==actor;}
    private void receive(Actor actor,Request request){
        if(request.finished.get())return;
        if(!current(actor)){finish(request,null,"NOT_OWNED");return;}
        actor.waiting.add(request);advance(actor);
    }
    private void advance(Actor actor){
        while(current(actor)&&actor.commit==null&&!actor.waiting.isEmpty()){
            Request request=actor.waiting.remove();
            if(request.finished.get())continue;
            var before=actor.snapshot;
            if(request.fence!=null&&!request.fence.equals(Fence.of(before))){finish(request,null,"STALE_TIMER");continue;}
            RoomTransition<S,E> transition;
            RoomSnapshot<S> candidate;
            try{
                transition=Objects.requireNonNull(actor.engine.apply(before,request.command));
                if(transition.round()<before.round()||(long)transition.round()>((long)before.round()+1))throw new RoomRejected("INVALID_ROUND");
                candidate=new RoomSnapshot<>(before.roomId(),before.ownerEpoch(),Math.incrementExact(before.version()),transition.round(),transition.phase(),transition.state());
            }catch(RoomRejected failure){finish(request,null,failure.code());continue;}
            catch(RuntimeException failure){finish(request,null,"ENGINE_ERROR");continue;}
            if(!actor.shard.completionSlots.tryAcquire()){finish(request,null,"COMPLETION_CAPACITY");continue;}
            Commit commit=new Commit(request,new Write<>(before,candidate,request.command,transition.event(),clock.instant()));
            actor.commit=commit;attempt(actor,commit,false);
        }
    }
    private void attempt(Actor actor,Commit commit,boolean resolving){
        if(!current(actor)||actor.commit!=commit){commit.release();return;}
        try{
            io.execute(()->{
                Decision decision;
                try{decision=Objects.requireNonNull(resolving?persistence.resolve(commit.write):persistence.commit(commit.write));}
                catch(RuntimeException failure){decision=Decision.UNKNOWN;}
                Decision outcome=decision;
                control(actor,commit,()->completed(actor,commit,outcome));
            });
        }catch(RejectedExecutionException failure){
            if(resolving)scheduleResolve(actor,commit);
            else{actor.commit=null;commit.release();finish(commit.request,null,"IO_BUSY");}
        }
    }
    private void control(Actor actor,Commit commit,Runnable task){
        if(!actor.shard.running){commit.release();return;}
        // 每个已保留的房间提交最多一个完成或核对消息，与普通动作队列独立。
        if(!actor.shard.control.offer(task)){
            actor.shard.running=false;commit.release();
            System.getLogger(RoomMailboxes.class.getName()).log(System.Logger.Level.ERROR,"房间完成消息容量不变量失效，已关闭该分片");
        }
        if(!actor.shard.running&&actor.shard.control.remove(task))commit.release();
        LockSupport.unpark(actor.shard.thread);
    }
    private void completed(Actor actor,Commit commit,Decision decision){
        if(!current(actor)||actor.commit!=commit){commit.release();return;}
        if(decision==Decision.UNKNOWN){scheduleResolve(actor,commit);return;}
        actor.commit=null;commit.release();
        if(decision==Decision.COMMITTED){actor.snapshot=commit.write.candidate();finish(commit.request,actor.snapshot,null);advance(actor);}
        else if(decision==Decision.NOT_COMMITTED){finish(commit.request,null,"NOT_COMMITTED");advance(actor);}
        else{
            actor.closed=true;if(actor.shard.actors.remove(actor.snapshot.roomId(),actor))actor.shard.roomSlots.release();
            finish(commit.request,null,"OWNER_CHANGED");
            while(!actor.waiting.isEmpty())finish(actor.waiting.remove(),null,"OWNER_CHANGED");
        }
    }
    private void scheduleResolve(Actor actor,Commit commit){
        if(!accepting.get()){commit.release();return;}
        long delay=Math.min(1000,config.retryMillis()*(1L<<Math.min(commit.retries++,6)));
        try{commit.timer=retries.schedule(()->control(actor,commit,()->attempt(actor,commit,true)),delay,TimeUnit.MILLISECONDS);}
        catch(RejectedExecutionException failure){commit.release();}
    }
    private void finish(Request request,RoomSnapshot<S> result,String failure){
        if(!request.finished.compareAndSet(false,true))return;
        Runnable notify=()->{
            try{if(failure==null)request.result.complete(result);else request.result.completeExceptionally(new RoomRejected(failure));}
            finally{request.actor.shard.requests.remove(request);request.actor.commands.release();request.actor.shard.commands.release();}
        };
        // 同房间通知串行，避免两个结果线程把已提交版本 v+1 的完成回调抢在 v 前执行。
        // 每条通知仍持有原命令票据，队列受 commandsPerRoom / commandsPerShard 共同约束。
        request.actor.notices.add(notify);scheduleNotices(request.actor);
    }
    private void scheduleNotices(Actor actor){
        if(!actor.notifying.compareAndSet(false,true))return;
        Runnable drain=()->{
            // 有界批次后重新排队，热点房间不能一直占住结果线程而饿死其他房间。
            try{for(int i=0;i<8;i++){Runnable notice=actor.notices.poll();if(notice==null)break;notice.run();}}
            finally{actor.notifying.set(false);if(!actor.notices.isEmpty())scheduleNotices(actor);}
        };
        // 正常运行时已准入预算保证调度容量；停机时也只能有一个线程排空本房间。
        try{notifications.execute(drain);}catch(RejectedExecutionException rejected){drain.run();}
    }
    @Override public void close(){
        if(!accepting.compareAndSet(true,false))return;
        for(Shard shard:shards){
            shard.requests.forEach(request->finish(request,null,"STOPPED_OUTCOME_UNKNOWN"));
            shard.running=false;LockSupport.unpark(shard.thread);shard.thread.interrupt();
        }
        retries.shutdownNow();io.shutdownNow();
        for(Shard shard:shards){
            try{shard.thread.join(1000);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
        }
        notifications.shutdown();
    }
    private final class Actor{
        final Shard shard;final Engine<S,C,E> engine;final Semaphore commands=new Semaphore(config.commandsPerRoom());
        final Queue<Request> waiting=new ArrayDeque<>();
        final Queue<Runnable> notices=new ConcurrentLinkedQueue<>();final AtomicBoolean notifying=new AtomicBoolean();
        volatile RoomSnapshot<S> snapshot;volatile boolean closed;Commit commit;
        final AtomicBoolean retiring=new AtomicBoolean();
        Actor(Shard shard,RoomSnapshot<S> snapshot,Engine<S,C,E> engine){this.shard=shard;this.snapshot=snapshot;this.engine=engine;}
    }
    private final class Request{
        final Actor actor;final Fence fence;final C command;
        final CompletableFuture<RoomSnapshot<S>> result=new CompletableFuture<>();final AtomicBoolean finished=new AtomicBoolean();
        Request(Actor actor,Fence fence,C command){this.actor=actor;this.fence=fence;this.command=command;}
    }
    private final class Commit{
        final Request request;final Write<S,C,E> write;final AtomicBoolean released=new AtomicBoolean();
        ScheduledFuture<?> timer;int retries;
        Commit(Request request,Write<S,C,E> write){this.request=request;this.write=write;}
        void release(){if(released.compareAndSet(false,true)){if(timer!=null)timer.cancel(false);request.actor.shard.completionSlots.release();}}
    }
    private final class Shard{
        final ArrayBlockingQueue<Runnable> normal=new ArrayBlockingQueue<>(config.commandsPerShard());
        final ArrayBlockingQueue<Runnable> control=new ArrayBlockingQueue<>(config.roomsPerShard());
        final Semaphore commands=new Semaphore(config.commandsPerShard()),roomSlots=new Semaphore(config.roomsPerShard()),completionSlots=new Semaphore(config.roomsPerShard());
        final ConcurrentMap<String,Actor> actors=new ConcurrentHashMap<>();final Set<Request> requests=ConcurrentHashMap.newKeySet();
        final Thread thread;volatile boolean running=true;
        Shard(int number){thread=Thread.ofPlatform().name("room-shard-"+number).daemon().uncaughtExceptionHandler((ignored,failure)->System.getLogger(RoomMailboxes.class.getName()).log(System.Logger.Level.ERROR,"房间分片停止，请按持久状态恢复所有权")).start(this::run);}
        void run(){
            try{
                int completedSinceNormal=0;
                while(running){
                    Runnable task=completedSinceNormal>=8?normal.poll():null;
                    if(task!=null)completedSinceNormal=0;
                    else{
                        task=control.poll();
                        if(task!=null)completedSinceNormal++;
                        else{task=normal.poll();completedSinceNormal=0;}
                    }
                    if(task==null){LockSupport.park();Thread.interrupted();continue;}
                    task.run();
                }
            }finally{
                running=false;
                requests.forEach(request->finish(request,null,"SHARD_FAILED"));
                actors.values().forEach(actor->{actor.closed=true;if(actor.commit!=null)actor.commit.release();});
                actors.clear();normal.clear();control.clear();
            }
        }
    }
}
