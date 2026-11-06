package com.ruoyi.game.admin;

import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.game.persistence.mapper.GameAdminMapper;

@Service
public class GameAdminService {
    private final GameAdminMapper mapper;
    private final AdminOperations operations;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    public GameAdminService(GameAdminMapper mapper,AdminOperations operations,ApplicationEventPublisher events,Clock clock) {
        this.mapper=mapper;this.operations=operations;this.events=events;this.clock=clock;
    }
    public void requirePlatform(long actor) {
        if(mapper.platformScope(actor)==0)throw new SecurityException("缺少平台游戏数据授权");
    }
    public void requireClub(long actor,long club){if(mapper.clubScope(actor,club)==0)throw new SecurityException("缺少此亲友圈的数据授权");}
    public Map<String,Object> dashboard(long actor) {
        requirePlatform(actor);
        LocalDate today=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        LocalDateTime start=LocalDateTime.ofInstant(today.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC);
        return Map.of("todayNew",mapper.newPlayers(start,start.plusDays(1)),"todayLogin",mapper.todayLogins(today),
                "loginTrend",mapper.loginTrend(today.minusDays(6)).stream().map(AdminTimes::utc).toList(),"businessDate",today.toString(),"asOf",clock.instant().toString());
    }
    public Map<String,Object> players(long actor,Long uid,String name,String status,int page,int limit) {
        requirePlatform(actor);
        if(page<1||page>100000||limit<1||limit>200)throw new IllegalArgumentException("分页参数无效");
        if(name!=null&&name.length()>32)throw new IllegalArgumentException("昵称筛选过长");
        if(status!=null&&!Set.of("ACTIVE","BANNED").contains(status))throw new IllegalArgumentException("玩家状态无效");
        return Map.of("rows",mapper.players(uid,name,status,Math.multiplyExact(page-1,limit),limit).stream().map(AdminTimes::utc).toList(),"total",mapper.playerCount(uid,name,status),"code",200);
    }
    public Map<String,Object> player(long actor,long uid) {
        requirePlatform(actor);
        Map<String,Object> player=mapper.player(uid);
        if(player==null)throw new IllegalArgumentException("玩家不存在");
        return Map.of("player",AdminTimes.utc(player),"wallets",mapper.wallets(uid));
    }

    @Transactional
    public Map<String,Object> command(long actor,long uid,String action,String reason,String key) {
        requirePlatform(actor);
        if(!Set.of("ban","unban","kick").contains(action))throw new IllegalArgumentException("管理动作无效");
        return operations.execute(actor,action,Long.toString(uid),reason,key,Map.of(),()->{
            Map<String,Object> before=mapper.lockPlayer(uid);
            if(before==null)throw new IllegalArgumentException("玩家不存在");
            String status=switch(action){case "ban"->"BANNED";case "unban"->"ACTIVE";default->(String)before.get("status");};
            mapper.changePlayer(((Number)before.get("accountId")).longValue(),status);
            events.publishEvent(new PlayerRevoked(uid));
            return new AdminOperations.Change(before,Map.of("status",status),Map.of("uid",Long.toString(uid)));
        });
    }
}
