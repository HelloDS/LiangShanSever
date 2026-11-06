package com.ruoyi.web.game;

import java.time.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.ruoyi.game.economy.WalletReconciliationJobs;
import com.ruoyi.game.persistence.mapper.WalletReconciliationMapper;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 普通池中的单个专用工作线程、一个未来调度项，不占房间邮箱或房间事务池。 */
@Component
@ConditionalOnProperty(name="game.reconciliation.worker-enabled",havingValue="true",matchIfMissing=true)
public class WalletReconciliationWorker implements SmartLifecycle {
    private static final Logger log=LoggerFactory.getLogger(WalletReconciliationWorker.class);
    private final WalletReconciliationJobs jobs;
    private final WalletReconciliationMapper mapper;
    private final Clock clock;
    private final LocalTime dailyAt;
    private final Counter checked,failed;
    private final Timer duration;
    private final AtomicLong nextWarning=new AtomicLong();
    private volatile Map<String,Long> backlog;
    private volatile boolean running;
    private volatile ScheduledThreadPoolExecutor scheduler;
    private LocalDate createdDate;
    private long nextSample;
    public WalletReconciliationWorker(WalletReconciliationJobs jobs,WalletReconciliationMapper mapper,Clock clock,MeterRegistry metrics,
            @Value("${game.reconciliation.daily-hour:4}") int hour,@Value("${game.reconciliation.daily-minute:15}") int minute){
        this.jobs=jobs;this.mapper=mapper;this.clock=clock;dailyAt=LocalTime.of(hour,minute);
        checked=metrics.counter("game.reconciliation.checked");failed=metrics.counter("game.reconciliation.failed");duration=metrics.timer("game.reconciliation.check.duration");
        Gauge.builder("game.reconciliation.backlog.available",this,value->value.backlog==null?0:1).register(metrics);
        for(String status:List.of("READY","RUNNING","FAILED"))Gauge.builder("game.reconciliation.runs",this,value->value.backlog==null?Double.NaN:value.backlog.getOrDefault(status,0L)).tag("status",status).register(metrics);
        Gauge.builder("game.reconciliation.scheduled",this,value->value.scheduler==null?0:value.scheduler.getQueue().size()).register(metrics);
    }
    @Override public synchronized void start(){
        if(running)return;running=true;
        scheduler=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("wallet-reconciliation").daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);scheduler.execute(this::poll);
    }
    private void poll(){
        long delay=5000;WalletReconciliationJobs.Lease lease=null;
        try{
            if(!running)return;
            var local=ZonedDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));
            if(!local.toLocalTime().isBefore(dailyAt)&&!local.toLocalDate().equals(createdDate)){
                jobs.ensureToday("SYSTEM",null);createdDate=local.toLocalDate();
            }
            if(System.currentTimeMillis()>=nextSample){
                nextSample=System.currentTimeMillis()+10_000;Map<String,Long> sample=new HashMap<>();
                for(var row:mapper.backlog())sample.put((String)row.get("status"),((Number)row.get("total")).longValue());
                backlog=Map.copyOf(sample);if(sample.getOrDefault("FAILED",0L)>0)warn();
            }
            lease=jobs.claim();if(lease==null)return;
            long began=System.nanoTime();
            for(int account=0;running&&account<32&&System.nanoTime()-began<1_000_000_000L;account++){
                long checkBegan=System.nanoTime();WalletReconciliationJobs.Step step;
                try{step=jobs.process(lease);}finally{duration.record(System.nanoTime()-checkBegan,TimeUnit.NANOSECONDS);}
                if(step==WalletReconciliationJobs.Step.PROGRESSED)checked.increment();else break;
            }
            jobs.release(lease);lease=null;delay=250;
        }catch(RuntimeException failure){
            backlog=null;failed.increment();warn();
            if(lease!=null)try{jobs.failed(lease,"CHECK_RESULT_UNKNOWN");}catch(RuntimeException ignored){/* 保留租约，后续到期接管。 */}
        }finally{
            if(running)try{scheduler.schedule(this::poll,delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){ }
        }
    }
    private void warn(){
        long now=System.currentTimeMillis(),next=nextWarning.get();
        if(now>=next&&nextWarning.compareAndSet(next,now+60_000))log.error("账本核对任务未完成，请检查任务状态；没有修改账户资产或记录原始异常报文");
    }
    @Override public synchronized void stop(){
        running=false;if(scheduler!=null){scheduler.shutdown();try{if(!scheduler.awaitTermination(6,TimeUnit.SECONDS))scheduler.shutdownNow();}catch(InterruptedException failure){scheduler.shutdownNow();Thread.currentThread().interrupt();}}
    }
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE-300;}
}
