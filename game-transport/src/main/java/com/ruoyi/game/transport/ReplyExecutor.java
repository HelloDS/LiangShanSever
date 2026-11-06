package com.ruoyi.game.transport;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/** 全局在途票据覆盖等待异步结果、编码、EventLoop 排入 ACK；断连不提前退还未知事务票据。 */
@Component
public final class ReplyExecutor {
    private final int capacity;
    private final Semaphore slots;
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean accepting=new AtomicBoolean(true);
    public ReplyExecutor(@Value("${game.ws.awaiting-replies:2048}") int capacity){
        if(capacity<1||capacity>8192)throw new IllegalArgumentException("异步响应容量无效");
        this.capacity=capacity;slots=new Semaphore(capacity);
        // 队列容量等于全部已领取票据，正常运行时完成通知不与普通业务队列争抢容量。
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name("game-reply-",0).daemon().factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    public Ticket reserve(){
        if(!accepting.get()||!slots.tryAcquire())return null;
        Ticket ticket=new Ticket();if(!accepting.get()){ticket.close();return null;}return ticket;
    }
    public final class Ticket implements AutoCloseable {
        private final AtomicBoolean started=new AtomicBoolean(),released=new AtomicBoolean();
        private Ticket(){ }
        /** 回调仅投递有界编码工作，不在房间邮箱、提交 I/O 或网络线程执行编码。 */
        public void reply(Runnable task){
            if(released.get()||!started.compareAndSet(false,true))throw new IllegalStateException("响应票据已使用");
            workers.execute(task);
        }
        @Override public void close(){if(released.compareAndSet(false,true))slots.release();}
    }
    public int pending(){return capacity-slots.availablePermits();}
    public int active(){return workers.getActiveCount();}
    public int queued(){return workers.getQueue().size();}
    @PreDestroy public void close(){accepting.set(false);workers.shutdown();}
}
