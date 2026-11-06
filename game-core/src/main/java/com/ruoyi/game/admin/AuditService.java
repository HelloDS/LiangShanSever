package com.ruoyi.game.admin;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.ruoyi.game.persistence.mapper.AuditMapper;
import com.ruoyi.game.protocol.GameJsonCodec;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台业务审计：只读白名单摘要，不返回命令凭据、任意私有 JSON 或猜测历史关联。 */
@Service
public class AuditService {
    private final AuditMapper mapper;
    private final GameAdminService permissions;
    private final GameJsonCodec codec;
    private final Clock clock;
    public AuditService(AuditMapper mapper,GameAdminService permissions,GameJsonCodec codec,Clock clock){this.mapper=mapper;this.permissions=permissions;this.codec=codec;this.clock=clock;}
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> list(long actor,Long operator,String action,String target,String operationId,LocalDate start,LocalDate end,int page,int limit){
        if(operator!=null&&operator<1)throw new IllegalArgumentException("操作者编号无效");
        if(action!=null&&!action.matches("[a-z][a-z0-9_:]{0,63}"))throw new IllegalArgumentException("动作筛选无效");
        if(target!=null&&(target.isBlank()||target.length()>64||target.chars().anyMatch(Character::isISOControl)))throw new IllegalArgumentException("目标筛选无效");
        if(operationId!=null&&!operationId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("命令编号无效");
        if(start==null||end==null||end.isBefore(start)||ChronoUnit.DAYS.between(start,end)>30||start.getYear()<1000||end.getYear()>9998)throw new IllegalArgumentException("请选择最多 31 天的有效日期范围");
        if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        var from=LocalDateTime.ofInstant(start.atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
        var until=LocalDateTime.ofInstant(end.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
        try{
            permissions.requirePlatform(actor);
            return Map.of("code",200,"rows",mapper.list(operator,action,target,operationId,from,until,Math.multiplyExact(page-1,limit),limit).stream().map(AdminTimes::utc).toList(),
                    "total",mapper.count(operator,action,target,operationId,from,until),"asOf",clock.instant().toString());
        }catch(DataAccessException failure){throw new GameDataUnavailable("业务审计查询暂不可用");}
    }
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> detail(long actor,long id){
        if(id<1)throw new IllegalArgumentException("审计编号无效");
        try{
            permissions.requirePlatform(actor);
            var saved=mapper.detail(id);if(saved==null)throw new IllegalArgumentException("审计记录不存在");
            var row=AdminTimes.utc(saved);String action=(String)row.get("action");
            var keys=summaryKeys(action);Object before=row.remove("beforeJson"),after=row.remove("afterJson");
            row.put("before",summary(before,keys));row.put("after",summary(after,keys));row.put("summaryAvailable",!keys.isEmpty());
            return Map.of("record",row,"asOf",clock.instant().toString());
        }catch(DataAccessException failure){throw new GameDataUnavailable("业务审计详情暂不可用");}
    }
    private static Set<String> summaryKeys(String action){
        return switch(action){
            case "ban","unban","kick" -> Set.of("status","sessionVersion");
            case "notice:draft","notice:publish","notice:rollback","notice:unpublish" -> Set.of("id","version","published","status");
            case "club:review","club:kick","club:manager","club:unmanager","club:config","club:status" -> Set.of("id","name","owner","status","current_mem","max_mem","maxManagers","onlyManagers","version");
            case "report:process" -> Set.of("status");
            case "report:contact" -> Set.of("contactViewed");
            case "wallet:reconcile" -> Set.of("reportId","status");
            case "wallet:daily:run","wallet:daily:retry" -> Set.of("runId","status");
            case "outbox:retry" -> Set.of("status","attempts","manualRetries","lastErrorCode");
            default -> Set.of();
        };
    }
    private Map<String,Object> summary(Object raw,Set<String> keys){
        if(raw==null||keys.isEmpty())return Map.of();
        try{
            var json=codec.read(raw.toString());if(!json.isObject())throw new IllegalStateException();
            Map<String,Object> result=new TreeMap<>();
            for(String key:keys){var value=json.get(key);if(value==null||value.isNull())continue;
                // 摘要只输出标量，所有数值为精确字符串；嵌套任意对象永不穿透。
                if(value.isBoolean())result.put(key,value.asBoolean());
                else if(value.isString())result.put(key,value.asString());
                else if(value.isNumber())result.put(key,value.toString());
            }
            return result;
        }catch(RuntimeException failure){throw new GameDataUnavailable("业务审计摘要暂不可用");}
    }
}
