package com.ruoyi.game.outbox;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import com.ruoyi.game.persistence.mapper.OutboxMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 事务内写入；租约和重试时刻保存在 MySQL，不使用逐条内存定时器。 */
@Service
public class OutboxStore {
    public static final int MAX_ATTEMPTS=5;
    private final OutboxMapper mapper;
    private final GameJsonCodec codec;
    private final Clock clock;
    public OutboxStore(OutboxMapper mapper,GameJsonCodec codec,Clock clock){this.mapper=mapper;this.codec=codec;this.clock=clock;}

    @Transactional(propagation=Propagation.MANDATORY)
    public String playerRevoked(long uid,long sessionVersion){
        if(uid<=0||sessionVersion<=0)throw new IllegalArgumentException("撤销事件身份无效");
        String key="player-revoked:"+uid+":"+sessionVersion,type="PLAYER_REVOKED",rule="session-version-v1";
        String payload=codec.write(new TreeMap<>(Map.of("uid",uid,"sessionVersion",sessionVersion)));
        String hash=digest(type+"\n1\n"+rule+"\n"+payload);
        mapper.append(UUID.randomUUID().toString(),key,type,rule,payload,hash,now());
        var existing=mapper.business(key,type);
        if(!hash.equals(existing.get("requestHash")))throw new IllegalStateException("持久事件业务键内容冲突");
        return (String)existing.get("id");
    }
    // 队列领取使用独立 READ COMMITTED，避免空范围/状态索引的 RR gap lock 互锁。
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=5)
    public List<OutboxEvent> claim(int limit){
        if(limit<1||limit>16)throw new IllegalArgumentException("领取数量无效");
        LocalDateTime now=now();
        for(var row:mapper.expired(now,limit))mapper.recover((String)row.get("id"),
                ((Number)row.get("attempts")).intValue()>=MAX_ATTEMPTS?"DEAD":"READY",now);
        List<OutboxEvent> events=new ArrayList<>();
        for(String id:mapper.ready(now,limit)){
            if(mapper.claim(id,UUID.randomUUID().toString(),now.plusSeconds(20))!=1)throw new IllegalStateException("持久任务领取冲突");
            events.add(mapper.event(id));
        }
        return List.copyOf(events);
    }
    @Transactional
    public boolean complete(OutboxEvent event){return mapper.complete(event.id(),event.leaseToken(),now())==1;}
    @Transactional
    public boolean failed(OutboxEvent event,String code){
        if(code==null||!code.matches("[A-Z_]{1,64}"))throw new IllegalArgumentException("错误代码无效");
        long delay=Math.min(60,1L<<Math.min(Math.max(0,event.attempts()-1),6));
        return mapper.fail(event.id(),event.leaseToken(),event.attempts()>=MAX_ATTEMPTS?"DEAD":"READY",now().plusSeconds(delay),code)==1;
    }
    public record Backlog(long ready,long claimed,long dead,double oldestSeconds,String asOf){ }
    @Transactional(readOnly=true,timeout=2)
    public Backlog backlog(){
        long ready=0,claimed=0,dead=0;LocalDateTime oldest=null;
        for(var row:mapper.backlog()){
            long count=((Number)row.get("total")).longValue();
            switch((String)row.get("status")){case "READY"->ready=count;case "CLAIMED"->claimed=count;case "DEAD"->dead=count;default->throw new IllegalStateException("任务状态无效");}
            Object value=row.get("oldest");
            LocalDateTime time=value instanceof LocalDateTime date?date:((java.sql.Timestamp)value).toLocalDateTime();
            if(oldest==null||time.isBefore(oldest))oldest=time;
        }
        return new Backlog(ready,claimed,dead,oldest==null?0:Math.max(0,Duration.between(oldest,now()).toMillis()/1000.0),clock.instant().toString());
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
