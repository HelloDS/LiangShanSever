package com.ruoyi.game.room;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.ruoyi.game.admin.*;
import com.ruoyi.game.persistence.mapper.RoomAdminMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 元数据与公开参与者查询，不返回快照/牌局事件载荷。当前不提供强制解散或资金写入。 */
@Service
public class RoomAdminService {
    private final RoomAdminMapper mapper;
    private final Clock clock;
    public RoomAdminService(RoomAdminMapper mapper,Clock clock){this.mapper=mapper;this.clock=clock;}
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> list(long actor,String code,Long owner,Long creator,String status,LocalDate start,LocalDate end,int page,int limit){
        if(code!=null&&!code.matches("[0-9]{6}"))throw new IllegalArgumentException("房号应为 6 位数字");
        if(owner!=null&&(owner<1||owner>999999999)||creator!=null&&(creator<1||creator>999999999))throw new IllegalArgumentException("玩家编号无效");
        if(status!=null&&!Set.of("WAITING","PLAYING","ROUND_FINISHED","CLOSED").contains(status))throw new IllegalArgumentException("房间状态无效");
        if(start==null||end==null||end.isBefore(start)||ChronoUnit.DAYS.between(start,end)>30||end.getYear()>9998||start.getYear()<1000)throw new IllegalArgumentException("请选择最多 31 天的有效日期范围");
        if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        LocalDateTime from=LocalDateTime.ofInstant(start.atStartOfDay(zone).toInstant(),ZoneOffset.UTC),until=LocalDateTime.ofInstant(end.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
        try{
            requireScope(actor);
            var rows=mapper.list(actor,code,owner,creator,status,from,until,Math.multiplyExact(page-1,limit),limit).stream().map(AdminTimes::utc).toList();
            return Map.of("code",200,"rows",rows,"total",mapper.count(actor,code,owner,creator,status,from,until),"asOf",clock.instant().toString());
        }catch(DataAccessException failure){throw new GameDataUnavailable("房间查询暂不可用");}
    }
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> detail(long actor,String id){
        if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("房间实例编号无效");
        try{
            requireScope(actor);var room=mapper.detail(actor,id);
            if(room==null)throw new SecurityException("缺少此房间的数据授权或房间不存在");
            return Map.of("room",AdminTimes.utc(room),"players",mapper.players(id).stream().map(AdminTimes::utc).toList(),"asOf",clock.instant().toString());
        }catch(DataAccessException failure){throw new GameDataUnavailable("房间详情暂不可用");}
    }
    private void requireScope(long actor){if(mapper.scope(actor)==0)throw new SecurityException("缺少游戏房间数据授权");}
}
