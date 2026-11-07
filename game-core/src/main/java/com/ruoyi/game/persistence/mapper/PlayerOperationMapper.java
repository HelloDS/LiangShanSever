package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface PlayerOperationMapper {
    @Insert("INSERT INTO player_operation(actor_uid,operation_id,request_hash,created_at) VALUES(#{uid},#{id},#{hash},#{now}) ON DUPLICATE KEY UPDATE operation_id=operation_id")
    int begin(@Param("uid") long uid,@Param("id") String id,@Param("hash") String hash,@Param("now") LocalDateTime now);

    @Select("SELECT request_hash AS requestHash,result_json AS result FROM player_operation WHERE actor_uid=#{uid} AND operation_id=#{id} FOR UPDATE")
    Map<String,Object> lock(@Param("uid") long uid,@Param("id") String id);

    @Update("UPDATE player_operation SET result_json=#{result} WHERE actor_uid=#{uid} AND operation_id=#{id} AND result_json IS NULL")
    int complete(@Param("uid") long uid,@Param("id") String id,@Param("result") String result);
}
