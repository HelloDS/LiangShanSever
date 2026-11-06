package com.ruoyi.framework.config;

import com.ruoyi.common.utils.Threads;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.*;

/** 后台异步任务必须有界，饱和时不能回退到请求线程执行数据库操作。 */
@Configuration
public class ThreadPoolConfig
{
    @Bean(name = "threadPoolTaskExecutor")
    public ThreadPoolTaskExecutor threadPoolTaskExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setMaxPoolSize(8);
        executor.setCorePoolSize(4);
        executor.setQueueCapacity(256);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    /** 上游登录、操作日志不需要定时语义；使用有界队列直接异步投递。 */
    @Bean(name = "adminAuditExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor adminAuditExecutor()
    {
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            BasicThreadFactory.builder().namingPattern("admin-audit-%d").daemon(true).build(),
            new ThreadPoolExecutor.AbortPolicy())
        {
            @Override
            protected void afterExecute(Runnable task, Throwable failure)
            {
                super.afterExecute(task, failure);
                Threads.printException(task, failure);
            }
        };
    }
}
