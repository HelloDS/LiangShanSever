package com.ruoyi.game.transport;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.ruoyi.game.outbox.*;
import com.ruoyi.game.protocol.GameJsonCodec;
import io.micrometer.core.instrument.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 最多 18 条已领取在途任务；轮询只有一个未来调度项。外部副作用按原事件幂等。 */
@Component
@ConditionalOnProperty(name="game.outbox.worker-enabled",havingValue="true",matchIfMissing=true)
public class OutboxWorker implements SmartLifecycle {
    private static final Logger log=LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxStore store;
    private final ConnectionRegistry connections;
    private final GameJsonCodec codec;
    private final com.ruoyi.game.room.RoomEventConsumer roomEvents;
    private final Semaphore slots=new Semaphore(18);
    private final Counter completed,failed,lostLease,pollFailure;
    private final AtomicLong nextWarning=new AtomicLong();
    private volatile OutboxStore.Backlog backlog;
    private long nextSample;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private ThreadPoolExecutor workers;
    public OutboxWorker(OutboxStore store,ConnectionRegistry connections,GameJsonCodec codec,MeterRegistry metrics,com.ruoyi.game.room.RoomEventConsumer roomEvents){
        this.store=store;this.connections=connections;this.codec=codec;this.roomEvents=roomEvents;
        completed=metrics.counter("game.outbox.completed");failed=metrics.counter("game.outbox.failed");
        lostLease=metrics.counter("game.outbox.lease.lost");pollFailure=metrics.counter("game.outbox.poll.failed");
        Gauge.builder("game.outbox.inflight",slots,value->18-value.availablePermits()).register(metrics);
        Gauge.builder("game.outbox.backlog.available",this,value->value.backlog==null?0:1).register(metrics);
        Gauge.builder("game.outbox.ready",this,value->value.backlog==null?Double.NaN:value.backlog.ready()).register(metrics);
        Gauge.builder("game.outbox.claimed",this,value->value.backlog==null?Double.NaN:value.backlog.claimed()).register(metrics);
        Gauge.builder("game.outbox.dead",this,value->value.backlog==null?Double.NaN:value.backlog.dead()).register(metrics);
        Gauge.builder("game.outbox.oldest.seconds",this,value->value.backlog==null?Double.NaN:value.backlog.oldestSeconds()).register(metrics);
    }
    @Override public synchronized void start(){
        if(running)return;
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().name("game-outbox-",0).daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
        scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("game-outbox-poll").daemon().factory());
        running=true;scheduler.execute(this::poll);
    }
    private void poll(){
        long delay=1000;
        try{
            if(!running)return;
            if(System.currentTimeMillis()>=nextSample){
                nextSample=System.currentTimeMillis()+10_000;
                try{backlog=store.backlog();if(backlog.dead()>0)warn();}
                catch(RuntimeException failure){backlog=null;pollFailure.increment();warn();}
            }
            int capacity=Math.min(16,slots.availablePermits());
            if(capacity==0){delay=50;return;}
            if(!slots.tryAcquire(capacity))return;
            java.util.List<OutboxEvent> events;
            try{events=store.claim(capacity);}catch(RuntimeException failure){slots.release(capacity);throw failure;}
            slots.release(capacity-events.size());
            if(!events.isEmpty())delay=25;
            for(var event:events){
                try{workers.execute(()->consume(event));}
                catch(RejectedExecutionException failure){slots.release();safeFail(event,"EXECUTOR_REJECTED");}
            }
        }catch(RuntimeException failure){pollFailure.increment();warn();}
        finally{
            if(running)try{scheduler.schedule(this::poll,delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){ }
        }
    }
    private void consume(OutboxEvent event){
        try{
            handle(event);
            if(store.complete(event))completed.increment();else lostLease.increment();
        }catch(IllegalArgumentException failure){safeFail(event,"INVALID_EVENT");}
        catch(RuntimeException failure){safeFail(event,"HANDLER_FAILED");}
        finally{slots.release();}
    }
    /** 重复投递只按提交的会话版本撤销；不生成新管理命令或资产动作。 */
    public void handle(OutboxEvent event){
        if("ROOM_EVENT_COMMITTED".equals(event.eventType())){roomEvents.consume(event);return;}
        if(!"PLAYER_REVOKED".equals(event.eventType())||event.schemaVersion()!=1||!"session-version-v1".equals(event.ruleVersion()))
            throw new IllegalArgumentException("不支持的事件版本");
        var payload=codec.read(event.payload());
        if(!payload.isObject()||payload.size()!=2||!payload.path("uid").isIntegralNumber()||!payload.path("uid").canConvertToLong()
                ||!payload.path("sessionVersion").isIntegralNumber()||!payload.path("sessionVersion").canConvertToLong())
            throw new IllegalArgumentException("撤销事件参数无效");
        long uid=payload.get("uid").asLong(),version=payload.get("sessionVersion").asLong();
        if(uid<=0||version<=0)throw new IllegalArgumentException("撤销事件身份无效");
        connections.disconnectBeforeVersion(uid,version);
    }
    private void safeFail(OutboxEvent event,String code){
        failed.increment();
        try{if(!store.failed(event,code))lostLease.increment();}catch(RuntimeException failure){pollFailure.increment();}
        warn();
    }
    private void warn(){
        long now=System.currentTimeMillis(),next=nextWarning.get();
        if(now>=next&&nextWarning.compareAndSet(next,now+60_000))log.error("持久任务处理失败，请检查任务状态与运维指标；未记录原始载荷或异常报文");
    }
    @Override public synchronized void stop(){
        running=false;
        if(scheduler!=null)scheduler.shutdownNow();
        if(workers!=null){
            workers.shutdown();
            try{if(!workers.awaitTermination(5,TimeUnit.SECONDS))releaseQueued(workers.shutdownNow());}
            catch(InterruptedException failure){releaseQueued(workers.shutdownNow());Thread.currentThread().interrupt();}
        }
        // 未完成任务保留租约，下一进程到期后领取；不把停机当作完成。
    }
    private void releaseQueued(java.util.List<Runnable> queued){slots.release(queued.size());}
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE-200;}
}
