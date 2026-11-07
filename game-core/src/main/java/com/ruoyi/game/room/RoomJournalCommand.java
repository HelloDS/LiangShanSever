package com.ruoyi.game.room;

import java.time.Instant;
import java.util.*;
import com.ruoyi.game.auth.GuestIdentity;
import com.ruoyi.game.economy.WalletPosting;

/** 仅服务端适配器生成。状态/事件 JSON 是私有恢复数据，禁止直接写入客户端报文。 */
public record RoomJournalCommand(RoomSnapshot<String> previous,RoomSnapshot<String> candidate,String eventKind,String eventJson,
        String ruleVersion,int schemaVersion,String engineVersion,GuestIdentity actor,List<WalletPosting> postings,
        SeatChange seatChange,boolean checkpoint,Instant occurredAt) {
    public enum SeatAction { JOIN,LEAVE,KICK }
    public record SeatChange(SeatAction action,long uid,int seat){
        public SeatChange{Objects.requireNonNull(action);if(uid<1||uid>999999999||seat<0||seat>=3)throw new IllegalArgumentException("座位变更无效");}
    }
    public RoomJournalCommand{
        Objects.requireNonNull(previous);Objects.requireNonNull(candidate);Objects.requireNonNull(occurredAt);
        if(!previous.roomId().equals(candidate.roomId())||previous.ownerEpoch()!=candidate.ownerEpoch()||previous.version()==Long.MAX_VALUE
                ||candidate.version()!=previous.version()+1||candidate.round()<previous.round()||(long)candidate.round()>(long)previous.round()+1)
            throw new IllegalArgumentException("房间候选版本无效");
        if(eventKind==null||!eventKind.matches("[A-Z_]{1,32}")||ruleVersion==null||!ruleVersion.matches("[A-Za-z0-9_.:-]{1,64}")
                ||schemaVersion<1||schemaVersion>1000||engineVersion==null||!engineVersion.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw new IllegalArgumentException("房间事件版本无效");
        postings=postings==null?List.of():List.copyOf(postings);
        if(postings.size()>10)throw new IllegalArgumentException("单次房间提交的账本命令过多");
        if(seatChange!=null && (actor==null || seatChange.action()!=SeatAction.KICK && actor.uid()!=seatChange.uid()
                || seatChange.action()==SeatAction.KICK && (!"KICK".equals(eventKind) || actor.uid()==seatChange.uid())))
            throw new IllegalArgumentException("座位变更缺少正确的操作者");
        if("CLOSED".equals(candidate.phase())&&!checkpoint)throw new IllegalArgumentException("关闭房间必须保存快照");
    }
}
