package com.ruoyi.web.game;

import java.time.*;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.ruoyi.game.room.RoomExports;
import io.micrometer.core.instrument.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 一个线程、一个未来调度项；每次 200 行普通池查询，CSV 顺序写盘。 */
@Component
@ConditionalOnProperty(name="game.exports.enabled",havingValue="true",matchIfMissing=true)
public class RoomExportWorker implements SmartLifecycle {
    private static final Logger log=LoggerFactory.getLogger(RoomExportWorker.class);
    private final RoomExports jobs;private final RoomExportFiles files;private final Clock clock;
    private final Counter completed,failed;private final AtomicLong nextWarning=new AtomicLong();
    private volatile ScheduledThreadPoolExecutor scheduler;private volatile boolean running;private long nextCleanup;
    public RoomExportWorker(RoomExports jobs,RoomExportFiles files,Clock clock,MeterRegistry metrics){
        this.jobs=jobs;this.files=files;this.clock=clock;completed=metrics.counter("game.exports.completed");failed=metrics.counter("game.exports.failed");
        Gauge.builder("game.exports.scheduled",this,value->value.scheduler==null?0:value.scheduler.getQueue().size()).register(metrics);
    }
    @Override public synchronized void start(){
        if(running)return;running=true;scheduler=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("room-export").daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);var generation=scheduler;scheduler.execute(()->poll(generation));
    }
    private void poll(ScheduledThreadPoolExecutor generation){
        RoomExports.Lease lease=null;String error="GENERATE_FAILED";boolean terminal=false,observedFailure=false;
        try{
            if(!running||scheduler!=generation)return;
            if(clock.millis()>=nextCleanup){files.clean(clock.instant());nextCleanup=clock.millis()+3_600_000;}
            lease=jobs.claim();if(lease==null)return;
            if(!running||scheduler!=generation){error="STOPPED";return;}
            long began=System.nanoTime();var cursor=LocalDateTime.of(1000,1,1,0,0);String cursorId="";
            try(var output=files.begin(lease)){
                while(running&&scheduler==generation&&!Thread.currentThread().isInterrupted()){
                    if(System.nanoTime()-began>TimeUnit.MINUTES.toNanos(10))throw new RoomExportFiles.ExportLimit();
                    var rows=jobs.batch(lease,cursor,cursorId);
                    if(rows.isEmpty()){output.finish();jobs.complete(lease,output.rows(),output.bytes());completed.increment();lease=null;return;}
                    for(var row:rows)output.row(row);
                    var last=rows.getLast();cursor=(LocalDateTime)last.get("createdAt");cursorId=(String)last.get("roomId");
                }
                error="STOPPED";
            }
        }catch(SecurityException denied){error="AUTH_CHANGED";terminal=true;observedFailure=true;}
        catch(RoomExportFiles.ExportLimit limit){error="EXPORT_LIMIT";terminal=true;observedFailure=true;}
        catch(Exception failure){observedFailure=true;/* 原始异常可能含数据库/文件信息，只保留稳定错误分类。 */}
        finally{
            if(lease!=null){observedFailure=true;try{jobs.failed(lease,error,terminal);}catch(RuntimeException ignored){/* 保留租约，到期可接管。 */}}
            if(observedFailure){failed.increment();
                long at=clock.millis(),next=nextWarning.get();if(at>=next&&nextWarning.compareAndSet(next,at+60_000))log.warn("房间导出未完成，请查看持久任务状态；没有输出凭据或文件路径");}
            if(running&&scheduler==generation)try{generation.schedule(()->poll(generation),5000,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){ }
        }
    }
    @Override public synchronized void stop(){running=false;var current=scheduler;if(current!=null){current.shutdown();try{if(!current.awaitTermination(6,TimeUnit.SECONDS))current.shutdownNow();}catch(InterruptedException failure){current.shutdownNow();Thread.currentThread().interrupt();}}}
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE-310;}
}
