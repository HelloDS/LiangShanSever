package com.ruoyi.game.room;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import com.ruoyi.game.admin.*;
import com.ruoyi.game.persistence.mapper.RoomExportMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 只导出房间元数据；普通事务池、有界分批，绝不读取手牌、快照和回放。 */
@Service
public class RoomExports {
    public static final int MAX_ROWS=10000,MAX_BYTES=8*1024*1024;
    private final RoomExportMapper mapper;private final AdminOperations operations;private final GameJsonCodec codec;private final Clock clock;private final RoomExportAuthorizer authorizer;
    public RoomExports(RoomExportMapper mapper,AdminOperations operations,GameJsonCodec codec,Clock clock,RoomExportAuthorizer authorizer){this.mapper=mapper;this.operations=operations;this.codec=codec;this.clock=clock;this.authorizer=authorizer;}
    public record Query(String roomCode,Long ownerUid,Long creatorUid,String status,LocalDate start,LocalDate end){
        public RoomAdminService.Filter validate(){return RoomAdminService.filter(roomCode,ownerUid,creatorUid,status,start,end);}
        Map<String,Object> payload(){Map<String,Object> value=new TreeMap<>();value.put("roomCode",roomCode);value.put("ownerUid",ownerUid);value.put("creatorUid",creatorUid);value.put("status",status);value.put("start",start.toString());value.put("end",end.toString());return value;}
    }
    public record Lease(String id,String token,long actor,String scopeHash,Query query,LocalDateTime cutoff){ }
    public static final class LeaseLost extends IllegalStateException { public LeaseLost(){super("导出任务所有权已改变");} }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    public static void id(String id){if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("导出任务编号无效");}
    public String access(long actor){
        if(!authorizer.allowed(actor))throw new SecurityException("导出权限已撤销或账号不可用");
        var scopes=mapper.scopes(actor);if(scopes.isEmpty()||scopes.size()>200)throw new SecurityException("缺少有效的房间数据范围");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(codec.write(scopes).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    // 授权读取发生在容量门锁之前；RC 确保取得门锁后的计数看见前一个创建事务。
    @Transactional(timeout=5,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(long actor,Query query,String reason,String key){
        if(query==null)throw new IllegalArgumentException("缺少导出筛选");query.validate();String scope=access(actor);
        return operations.execute(actor,"room:export","rooms",reason,key,query.payload(),()->{
            mapper.gate();var at=mapper.databaseNow();
            if(mapper.recent(at.minusHours(24))>=20||mapper.pending(actor)>=2)throw new IllegalArgumentException("导出任务已达容量上限，请等待现有任务完成或稍后再试");
            String id=UUID.randomUUID().toString();if(mapper.create(id,actor,codec.write(query.payload()),scope,at)!=1)throw new IllegalStateException("导出任务未确认写入");
            var result=Map.<String,Object>of("jobId",id,"status","READY");return new AdminOperations.Change(Map.of(),result,result);
        });
    }
    private Query query(String json){
        var value=codec.read(json);
        return new Query(text(value,"roomCode"),number(value,"ownerUid"),number(value,"creatorUid"),text(value,"status"),LocalDate.parse(value.path("start").asString()),LocalDate.parse(value.path("end").asString()));
    }
    private static String text(tools.jackson.databind.JsonNode value,String key){var node=value.get(key);return node==null||node.isNull()?null:node.asString();}
    private static Long number(tools.jackson.databind.JsonNode value,String key){var node=value.get(key);return node==null||node.isNull()?null:node.asLong();}
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> list(long actor){String scope=access(actor);return Map.of("rows",mapper.list(actor,scope).stream().map(this::metadata).toList(),"asOf",clock.instant().toString());}
    private Map<String,Object> metadata(Map<String,Object> row){
        var value=new LinkedHashMap<>(row);if("DONE".equals(value.get("status"))&&!((LocalDateTime)value.get("expiresAt")).isAfter(now()))value.put("status","EXPIRED");
        return AdminTimes.utc(value);
    }
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> detail(long actor,String id){id(id);var saved=mapper.metadata(id,actor,access(actor));if(saved==null)throw new SecurityException("任务不存在或数据范围已改变");return metadata(saved);}
    @Transactional(timeout=3)
    public Lease claim(){
        mapper.gate();var at=now();mapper.expire(at);if(mapper.running(at)>0)return null;
        var candidate=mapper.candidate(at);if(candidate==null)return null;String id=(String)candidate.get("id");
        if(((Number)candidate.get("attempts")).intValue()>=3){mapper.exhausted(id);return null;}
        String token=UUID.randomUUID().toString();if(mapper.claim(id,token,at.plusSeconds(60))!=1)throw new LeaseLost();
        var row=mapper.raw(id);Query saved;
        try{saved=query(row.get("filterJson").toString());saved.validate();}
        catch(RuntimeException invalid){mapper.failed(id,token,true,"FILTER_INVALID");return null;}
        return new Lease(id,token,((Number)row.get("actor")).longValue(),(String)row.get("scopeHash"),saved,(LocalDateTime)row.get("createdAt"));
    }
    private void requireLease(Lease lease){
        if(!lease.scopeHash().equals(access(lease.actor())))throw new SecurityException("任务创建后的数据范围已改变");
        var at=now();if(mapper.renew(lease.id(),lease.token(),at,at.plusSeconds(60))!=1)throw new LeaseLost();
    }
    @Transactional(timeout=3)
    public List<Map<String,Object>> batch(Lease lease,LocalDateTime cursorTime,String cursorId){
        requireLease(lease);var q=lease.query();var filter=q.validate();
        return mapper.batch(lease.actor(),q.roomCode(),q.ownerUid(),q.creatorUid(),q.status(),filter.from(),filter.until(),lease.cutoff(),cursorTime,cursorId);
    }
    @Transactional(timeout=3)
    public void complete(Lease lease,int rows,int bytes){
        if(rows<0||rows>MAX_ROWS||bytes<1||bytes>MAX_BYTES)throw new IllegalArgumentException("导出文件超出容量");
        requireLease(lease);var at=now();if(mapper.complete(lease.id(),lease.token(),rows,bytes,at,at.plusHours(24))!=1)throw new LeaseLost();
    }
    @Transactional(timeout=3)
    public void failed(Lease lease,String code,boolean terminal){
        if(!Set.of("AUTH_CHANGED","EXPORT_LIMIT","GENERATE_FAILED","STOPPED").contains(code))throw new IllegalArgumentException("任务错误码无效");
        mapper.failed(lease.id(),lease.token(),terminal,code);
    }
    public record Download(String id,String token,int bytes){ }
    @Transactional(readOnly=true,timeout=3)
    public Download download(long actor,String id){
        id(id);String scope=access(actor);var row=mapper.raw(id);
        if(row==null||((Number)row.get("actor")).longValue()!=actor||!scope.equals(row.get("scopeHash")))throw new SecurityException("任务不存在或数据范围已改变");
        if(!"DONE".equals(row.get("status"))||!((LocalDateTime)row.get("expiresAt")).isAfter(now()))throw new IllegalArgumentException("文件尚未生成或已过期");
        return new Download(id,(String)row.get("leaseToken"),((Number)row.get("fileBytes")).intValue());
    }
}
