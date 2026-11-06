package com.ruoyi.game.room;

import java.time.*;
import java.util.*;
import com.ruoyi.game.outbox.OutboxEvent;
import com.ruoyi.game.persistence.mapper.RoomJournalMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 实际持久事件计数投影；不是对局数、结算数或奖励资格。 */
@Service
public class RoomEventConsumer {
    private final RoomJournalMapper mapper;
    private final GameJsonCodec codec;
    private final Clock clock;
    public RoomEventConsumer(RoomJournalMapper mapper,GameJsonCodec codec,Clock clock){this.mapper=mapper;this.codec=codec;this.clock=clock;}
    @Transactional(timeout=5)
    public void consume(OutboxEvent event){
        if(!"ROOM_EVENT_COMMITTED".equals(event.eventType())||event.schemaVersion()!=1)throw new IllegalArgumentException("房间投影事件版本无效");
        var payload=codec.read(event.payload());
        if(payload.size()!=2||!payload.path("roomId").isString()||!payload.path("eventSeq").isIntegralNumber()||!payload.path("eventSeq").canConvertToLong())throw new IllegalArgumentException("房间投影参数无效");
        String room=payload.get("roomId").asString();long sequence=payload.get("eventSeq").asLong();
        if(!room.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||sequence<1)throw new IllegalArgumentException("房间投影标识无效");
        String hash=RoomJournal.hash(event.eventType()+"\n"+event.schemaVersion()+"\n"+event.ruleVersion()+"\n"+codec.canonicalObject(event.payload()));
        String proposed=UUID.randomUUID().toString();LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        mapper.beginReceipt(event.id(),proposed,hash,now);
        var receipt=mapper.receipt(event.id());
        if(!hash.equals(receipt.get("requestHash")))throw new IllegalArgumentException("消费者收据内容冲突");
        if(!proposed.equals(receipt.get("receiptId")))return;
        var source=mapper.selectEvent(room,sequence);
        if(source==null)throw new IllegalStateException("房间持久事件尚不可见");
        if(!event.ruleVersion().equals(source.get("ruleVersion")))throw new IllegalArgumentException("房间统计规则版本不一致");
        Object value=source.get("occurredAt");LocalDateTime occurred=value instanceof LocalDateTime time?time:((java.sql.Timestamp)value).toLocalDateTime();
        mapper.project(room,sequence,occurred,now);
    }
}
