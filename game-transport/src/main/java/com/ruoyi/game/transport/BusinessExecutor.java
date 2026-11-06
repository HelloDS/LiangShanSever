package com.ruoyi.game.transport;

import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

@Component
public class BusinessExecutor {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4,16,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),Thread.ofPlatform().name("game-business-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public void execute(Runnable task) { executor.execute(task); }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
