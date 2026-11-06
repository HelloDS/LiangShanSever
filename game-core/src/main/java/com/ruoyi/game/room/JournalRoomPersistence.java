package com.ruoyi.game.room;

import java.util.Objects;
import java.util.function.Function;

/** 纯、确定性的计划转换将类型化引擎连接至真实事务日志。转换不得查询可变规则或生成新业务键。 */
public final class JournalRoomPersistence<S,C,E> implements RoomPersistence<S,C,E> {
    private final RoomJournal journal;
    private final Function<Write<S,C,E>,RoomJournalCommand> plans;
    public JournalRoomPersistence(RoomJournal journal,Function<Write<S,C,E>,RoomJournalCommand> plans){this.journal=Objects.requireNonNull(journal);this.plans=Objects.requireNonNull(plans);}
    private RoomJournalCommand plan(Write<S,C,E> write){
        var command=Objects.requireNonNull(plans.apply(write));
        if(!same(write.previous(),command.previous())||!same(write.candidate(),command.candidate())||!write.occurredAt().equals(command.occurredAt()))
            throw new IllegalArgumentException("持久化计划改变了房间标识、版本或发生时间");
        return command;
    }
    private boolean same(RoomSnapshot<?> left,RoomSnapshot<?> right){return left.roomId().equals(right.roomId())&&left.ownerEpoch()==right.ownerEpoch()
            &&left.version()==right.version()&&left.round()==right.round()&&left.phase().equals(right.phase());}
    @Override public Decision commit(Write<S,C,E> write){
        RoomJournalCommand command;
        try{command=plan(write);}catch(RuntimeException failure){return Decision.NOT_COMMITTED;}
        return journal.commit(command);
    }
    @Override public Decision resolve(Write<S,C,E> write){return journal.resolve(plan(write));}
}
