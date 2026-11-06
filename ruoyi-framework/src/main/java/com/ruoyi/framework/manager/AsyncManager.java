package com.ruoyi.framework.manager;

import java.util.TimerTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.utils.Threads;
import com.ruoyi.common.utils.spring.SpringUtils;

/** 上游后台访问日志的有界异步投递；游戏业务审计仍在业务事务内持久化。 */
public class AsyncManager
{
    private static final Logger log = LoggerFactory.getLogger(AsyncManager.class);
    private final ThreadPoolExecutor executor = SpringUtils.getBean("adminAuditExecutor");
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong nextWarning = new AtomicLong();
    private AsyncManager(){}
    private static final AsyncManager INSTANCE = new AsyncManager();
    public static AsyncManager me() { return INSTANCE; }

    public void execute(TimerTask task)
    {
        try { executor.execute(task); }
        catch (RejectedExecutionException failure)
        {
            long total = rejected.incrementAndGet();
            long now = System.currentTimeMillis(), next = nextWarning.get();
            if (now >= next && nextWarning.compareAndSet(next, now + 60_000))
                log.error("后台异步访问日志队列已满或关闭，累计拒绝 {} 条；业务事务审计不使用此队列", total);
        }
    }
    public long rejectedCount() { return rejected.get(); }
    public void shutdown() { Threads.shutdownAndAwaitTermination(executor); }
}
