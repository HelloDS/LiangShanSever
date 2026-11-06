package com.ruoyi.game.transport;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessExecutorTest {
    @Test void fullConcurrencyIsAvailableBeforeQueueSaturation() throws Exception {
        var executor=new BusinessExecutor();
        var running=new CountDownLatch(16);var release=new CountDownLatch(1);
        Runnable blocked=()->{running.countDown();try{release.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();}};
        try{
            for(int i=0;i<272;i++)executor.execute(blocked);
            assertTrue(running.await(3,TimeUnit.SECONDS));assertEquals(16,executor.active());assertEquals(256,executor.queued());
            assertThrows(RejectedExecutionException.class,()->executor.execute(()->fail("拒绝不能回退调用线程")));
        }finally{release.countDown();executor.close();}
    }
}
