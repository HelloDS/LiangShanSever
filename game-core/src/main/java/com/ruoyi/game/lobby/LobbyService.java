package com.ruoyi.game.lobby;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import com.ruoyi.game.auth.GameFailure;
import com.ruoyi.game.persistence.mapper.LobbyMapper;

@Service
public class LobbyService {
    private static final Set<String> VISITS=Set.of("addDiamond","addRoomCard","backpack","bindingPhone","chat","club","createRoom","dailyTask","freephone","guanli","invite","joinRoom","koi","latestActivity","loginTool","mainHead","match","notice","qiandao","realName","record","report","sapling","set","store");
    private final LobbyMapper mapper;
    private final Clock clock;
    public LobbyService(LobbyMapper mapper,Clock clock){this.mapper=mapper;this.clock=clock;}
    public void visit(long uid,String activity){
        if(!VISITS.contains(activity))throw new GameFailure(-4,"入口标识无效");
        mapper.visit(clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),uid,activity,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
    }
    public Map<String,Object> koi(long uid){
        Map<String,Object> state=mapper.koi(uid);
        if(state==null)throw new IllegalStateException("玩家锦鲤查询状态缺失");
        Map<String,Object> result=new LinkedHashMap<>(state);result.put("state",0);return result;
    }
    public Map<String,Object> gameConfiguration(String gameId){
        if(!Set.of("6008","6014","6038","6003","6009","6061","6055","6056","6057","6073","6060").contains(gameId))throw new GameFailure(-16,"游戏不存在");
        if(Set.of("6073","6060").contains(gameId))throw new GameFailure(-18,"该游戏的动态收费配置尚待确认");
        return Map.of("state",0,"game",Map.of("game_id",gameId));
    }
}
