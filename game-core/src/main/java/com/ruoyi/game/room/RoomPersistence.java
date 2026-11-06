package com.ruoyi.game.room;

/** 所有方法仅在有界 I/O 线程中调用。适配器负责同连接事务、版本及所有者围栏。 */
public interface RoomPersistence<S,C,E> {
    record Write<S,C,E>(RoomSnapshot<S> previous,RoomSnapshot<S> candidate,C command,E event,java.time.Instant occurredAt){ }
    enum Decision { COMMITTED, NOT_COMMITTED, UNKNOWN, OWNER_CHANGED }
    Decision commit(Write<S,C,E> write);
    /** 必须通过数据库锁/唯一事件确认原事务结果；不以无锁查不到事件推断回滚。 */
    Decision resolve(Write<S,C,E> write);
}
