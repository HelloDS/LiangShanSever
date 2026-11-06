package com.ruoyi.game.transport;

import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

@Component
public class BusinessExecutor {
    // 有界队列未满时 ThreadPoolExecutor 不会增长到 maximumPoolSize，直接提供固定并发。
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(16,16,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),Thread.ofPlatform().name("game-business-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public void execute(Runnable task) { executor.execute(task); }
    public int queued(){return executor.getQueue().size();}
    public int active(){return executor.getActiveCount();}
    @PreDestroy public void close() { executor.shutdownNow(); }
}
