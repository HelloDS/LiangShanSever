package com.ruoyi.game.admin;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import com.ruoyi.game.persistence.mapper.GameAdminMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新后台命令的事务幂等边界；不声称旧游戏经济协议拥有请求编号。 */
@Service
public class AdminOperations {
    private final GameAdminMapper mapper;
    private final GameJsonCodec codec;
    private final Clock clock;
    public AdminOperations(GameAdminMapper mapper,GameJsonCodec codec,Clock clock){this.mapper=mapper;this.codec=codec;this.clock=clock;}
    public record Change(Map<String,Object> before,Map<String,Object> after,Map<String,Object> result) { }

    @Transactional
    public Map<String,Object> execute(long actor,String action,String target,String reason,String key,Map<String,Object> payload,Supplier<Change> effect) {
        if(reason==null||reason.trim().length()<3||reason.length()>512)throw new IllegalArgumentException("请填写 3 至 512 字的原因");
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,64}"))throw new IllegalArgumentException("缺少有效的 Idempotency-Key");
        String hash=digest(codec.write(canonical(Map.of("target",target,"action",action,"reason",reason,"payload",payload))));
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        String proposedId=UUID.randomUUID().toString();
        mapper.beginOperation(Map.of("operationId",proposedId,"actor",actor,"key",key,"action",action,"hash",hash,"target",target,"reason",reason,"now",now));
        Map<String,Object> operation=mapper.operation(actor,key);
        if(!hash.equals(operation.get("requestHash")))throw new IllegalArgumentException("幂等键已用于不同的请求内容");
        if(!proposedId.equals(operation.get("operationId"))) {
            var saved=codec.read((String)operation.get("result"));
            return Map.of("operationId",saved.get("operationId").asString(),"status",saved.get("status").asString(),"result",saved.get("result"));
        }
        Change change=effect.get();
        Map<String,Object> result=Map.of("operationId",proposedId,"status","COMPLETED","result",change.result());
        mapper.audit(actor,action,target,reason,codec.write(change.before()),codec.write(change.after()),now);
        mapper.complete(proposedId,codec.write(result),now);
        return result;
    }
    private Object canonical(Object value) {
        if(value instanceof Map<?,?> map){Map<String,Object> sorted=new TreeMap<>();map.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(this::canonical).toList();
        return value;
    }
    private String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
