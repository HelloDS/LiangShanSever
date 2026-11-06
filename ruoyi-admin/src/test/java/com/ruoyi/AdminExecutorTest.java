package com.ruoyi;

import com.ruoyi.framework.config.ThreadPoolConfig;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminExecutorTest {
    @Test void saturationRejectsWithoutRunningOnRequestThread() throws Exception {
        ThreadPoolExecutor executor=new ThreadPoolConfig().adminAuditExecutor();
        CountDownLatch release=new CountDownLatch(1),running=new CountDownLatch(4);
        Runnable blocked=()->{running.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        try{
            for(int i=0;i<260;i++)executor.execute(blocked);
            assertTrue(running.await(3,TimeUnit.SECONDS));
            assertEquals(256,executor.getQueue().size());assertEquals(4,executor.getPoolSize());
            assertThrows(RejectedExecutionException.class,()->executor.execute(()->fail("不能在请求线程执行")));
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
}
