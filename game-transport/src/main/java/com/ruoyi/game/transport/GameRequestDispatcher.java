package com.ruoyi.game.transport;

import java.util.concurrent.CompletionStage;
import com.ruoyi.game.protocol.WireRequest;
import io.netty.channel.Channel;

/** 调用发生于业务池；返回未完成阶段时必须立即释放该工作线程，禁止 join/get 等待提交。 */
@FunctionalInterface
public interface GameRequestDispatcher {
    CompletionStage<GameDispatcher.Result> dispatchAsync(WireRequest request,ConnectionRegistry.Binding binding,Channel channel);

    /** 提交结果未知时断开并恢复查询，不能给 FIFO 写入虚假的“操作失败”。 */
    final class OutcomeUnknown extends RuntimeException {
        public OutcomeUnknown(){super("OUTCOME_UNKNOWN");}
    }
}
