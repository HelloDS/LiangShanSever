package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface OnlineHistoryMapper {
    @Insert("INSERT INTO online_minute_history(sample_minute,observed_at,data_status,online_count,node_instance) VALUES(#{minute},#{observed},#{status},#{count,jdbcType=INTEGER},#{node}) ON DUPLICATE KEY UPDATE sample_minute=sample_minute")
    int record(@Param("minute") LocalDateTime minute,@Param("observed") LocalDateTime observed,@Param("status") String status,@Param("count") Integer count,@Param("node") String node);
    @Select("SELECT sample_minute AS sampleMinute,observed_at AS observedAt,data_status AS dataStatus,online_count AS onlineCount FROM online_minute_history WHERE sample_minute>=#{start} AND sample_minute<#{end} ORDER BY sample_minute LIMIT 1440")
    @Options(timeout=2)
    List<Map<String,Object>> day(@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);
}
