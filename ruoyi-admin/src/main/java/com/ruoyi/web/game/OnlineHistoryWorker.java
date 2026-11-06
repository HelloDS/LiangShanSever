package com.ruoyi.web.game;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import com.ruoyi.game.admin.OnlineHistoryService;
import com.ruoyi.game.transport.OnlinePresence;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 一个调度项、一个普通池写入；迟到或停机不追赶伪造历史点。 */
@Component
@ConditionalOnProperty(name="game.online-history.enabled",havingValue="true",matchIfMissing=true)
public class OnlineHistoryWorker implements SmartLifecycle {
    private final OnlinePresence online;
    private final OnlineHistoryService history;
    private final Clock clock;
    private final io.micrometer.core.instrument.Counter recorded,failed;
    private final String node=UUID.randomUUID().toString();
    private volatile boolean running;
    private ScheduledThreadPoolExecutor scheduler;
    private long nextLog;
    public OnlineHistoryWorker(OnlinePresence online,OnlineHistoryService history,Clock clock,MeterRegistry metrics){this.online=online;this.history=history;this.clock=clock;recorded=metrics.counter("game.online.history.recorded");failed=metrics.counter("game.online.history.failed");}
    @Override public synchronized void start(){if(running)return;running=true;scheduler=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("online-history").daemon().factory());scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);schedule(scheduler);}
    private synchronized void schedule(ScheduledThreadPoolExecutor generation){if(!running||scheduler!=generation)return;long delay=60000-Math.floorMod(clock.millis(),60000);generation.schedule(()->poll(generation),delay,TimeUnit.MILLISECONDS);}
    void capture(){
        var sample=online.sample();
        history.record(Instant.parse(sample.asOf()),sample.dataStatus(),sample.uids()==null?null:sample.uids().size(),node);
        recorded.increment();
    }
    private void poll(ScheduledThreadPoolExecutor generation){
        synchronized(this){if(!running||scheduler!=generation)return;}
        try{capture();}catch(RuntimeException failure){failed.increment();long now=clock.millis();if(now>=nextLog){nextLog=now+60000;System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"在线分钟采样未保存："+failure.getClass().getSimpleName());}}
        finally{schedule(generation);}
    }
    @Override public void stop(){
        ScheduledThreadPoolExecutor stopped;
        synchronized(this){running=false;stopped=scheduler;if(stopped!=null)stopped.shutdown();}
        if(stopped!=null)try{if(!stopped.awaitTermination(6,TimeUnit.SECONDS))stopped.shutdownNow();}catch(InterruptedException failure){stopped.shutdownNow();Thread.currentThread().interrupt();}
    }
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE-50;}
}
