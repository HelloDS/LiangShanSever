package com.ruoyi.game.persistence.mapper;

import java.time.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface LobbyMapper {
    @Insert("INSERT INTO lobby_visit_daily(business_date,uid,activity_id,visit_count,updated_at) VALUES(#{date},#{uid},#{activity},1,#{now}) ON DUPLICATE KEY UPDATE visit_count=visit_count+1,updated_at=VALUES(updated_at)")
    int visit(@Param("date") LocalDate date,@Param("uid") long uid,@Param("activity") String activity,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO player_koi_state(uid,updated_at) VALUES(#{uid},#{now})")
    int createKoi(@Param("uid") long uid,@Param("now") LocalDateTime now);
    @Select("SELECT floor,win_state AS winState FROM player_koi_state WHERE uid=#{uid}")
    Map<String,Object> koi(long uid);
}
