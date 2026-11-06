package com.ruoyi.game.room;

import java.util.function.Supplier;

/** 基础设施实现先选择房间池，再在当前 I/O 线程开启唯一事务管理器的事务。 */
public interface RoomTransactions {
    <T> T execute(Supplier<T> work);
}
