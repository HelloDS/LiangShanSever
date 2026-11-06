package com.ruoyi.game.admin;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.ruoyi.game.persistence.mapper.OnlineHistoryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 单进程实际分钟采样；不从日登录推断过去在线，不补停机数据。 */
@Service
public class OnlineHistoryService {
    private final OnlineHistoryMapper mapper;
    private final GameAdminService permissions;
    private final Clock clock;
    private final boolean enabled;
    public OnlineHistoryService(OnlineHistoryMapper mapper,GameAdminService permissions,Clock clock,@Value("${game.online-history.enabled:true}") boolean enabled){this.mapper=mapper;this.permissions=permissions;this.clock=clock;this.enabled=enabled;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3)
    public void record(Instant observed,String status,Integer count,String node){
        Objects.requireNonNull(observed);
        if(!Set.of("AVAILABLE","REBUILDING","UNAVAILABLE").contains(status)||("AVAILABLE".equals(status)?count==null||count<0||count>10000:count!=null))throw new IllegalArgumentException("在线采样状态无效");
        if(node==null||!node.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("采样节点无效");
        mapper.record(LocalDateTime.ofInstant(observed.truncatedTo(ChronoUnit.MINUTES),ZoneOffset.UTC),LocalDateTime.ofInstant(observed.truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC),status,count,node);
    }
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> day(long actor,LocalDate date){
        if(date==null||date.getYear()<1000||date.isAfter(clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))throw new IllegalArgumentException("请选择有效的北京日期");
        ZoneId zone=ZoneId.of("Asia/Shanghai");var start=LocalDateTime.ofInstant(date.atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
        try{
            permissions.requirePlatform(actor);
            var end=LocalDateTime.ofInstant(date.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
            var rows=mapper.day(start,end).stream().map(AdminTimes::utc).peek(row->row.putIfAbsent("onlineCount",null)).toList();
            return Map.of("rows",rows,"date",date.toString(),"zone","Asia/Shanghai","samplingEnabled",enabled,"asOf",clock.instant().toString());
        }catch(DataAccessException failure){throw new GameDataUnavailable("在线历史查询暂不可用");}
    }
}
