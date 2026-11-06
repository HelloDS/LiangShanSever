package com.ruoyi.game.outbox;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.ruoyi.game.admin.*;
import com.ruoyi.game.persistence.mapper.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxAdminService {
    private final GameAdminService access;
    private final AdminOperations operations;
    private final OutboxMapper mapper;
    private final Clock clock;
    public OutboxAdminService(GameAdminService access,AdminOperations operations,OutboxMapper mapper,Clock clock){
        this.access=access;this.operations=operations;this.mapper=mapper;this.clock=clock;
    }
    public Map<String,Object> list(long actor,String status,LocalDate start,LocalDate end,int page,int limit){
        access.requirePlatform(actor);
        if(status!=null&&!Set.of("READY","CLAIMED","DONE","DEAD").contains(status))throw new IllegalArgumentException("任务状态无效");
        if(start==null||end==null||end.isBefore(start)||ChronoUnit.DAYS.between(start,end)>30)throw new IllegalArgumentException("时间范围最多 31 天");
        if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        LocalDateTime from=LocalDateTime.ofInstant(start.atStartOfDay(zone).toInstant(),ZoneOffset.UTC),
                until=LocalDateTime.ofInstant(end.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
        return Map.of("code",200,"rows",mapper.list(status,from,until,limit,Math.multiplyExact(page-1,limit)).stream().map(AdminTimes::utc).toList(),
                "total",mapper.count(status,from,until),"asOf",clock.instant().toString());
    }
    public Map<String,Object> backlog(long actor){
        access.requirePlatform(actor);
        return Map.of("asOf",clock.instant().toString(),"rows",mapper.backlog().stream().map(AdminTimes::utc).toList());
    }
    @Transactional
    public Map<String,Object> retry(long actor,String id,String reason,String key){
        access.requirePlatform(actor);
        if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("事件编号无效");
        return operations.execute(actor,"outbox:retry",id,reason,key,Map.of(),()->{
            var before=mapper.lock(id);
            if(before==null)throw new IllegalArgumentException("任务不存在");
            if(!"DEAD".equals(before.get("status")))throw new IllegalArgumentException("仅能重试待人工处理任务");
            if(mapper.retry(id,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))!=1)throw new IllegalStateException("任务状态已变化");
            return new AdminOperations.Change(before,Map.of("status","READY"),Map.of("eventId",id,"taskStatus","READY"));
        });
    }
}
