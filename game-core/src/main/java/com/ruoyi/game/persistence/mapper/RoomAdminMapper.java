package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface RoomAdminMapper {
    String SCOPE=" EXISTS(SELECT 1 FROM admin_data_scope s WHERE s.principal_id=#{actor} AND ((s.scope_type='PLATFORM' AND s.scope_id='*') OR (s.scope_type='CLUB' AND s.scope_id=CAST(r.club_id AS CHAR)))) ";
    String COLUMNS="r.room_id AS roomId,r.room_code AS roomCode,r.game_id AS gameId,CAST(r.owner_uid AS CHAR) AS ownerUid,CAST(r.creator_uid AS CHAR) AS creatorUid,CAST(r.payer_uid AS CHAR) AS payerUid,CAST(r.club_id AS CHAR) AS clubId,r.status,r.is_test AS isTest,r.rule_version AS ruleVersion,r.created_at AS createdAt,r.ended_at AS endedAt,t.phase,t.round_no AS roundNo,CAST(t.version AS CHAR) AS version,CAST(t.snapshot_seq AS CHAR) AS snapshotSeq,t.engine_version AS engineVersion,JSON_UNQUOTE(JSON_EXTRACT(r.options_json,'$.playNumber')) AS playNumber,(SELECT COUNT(*) FROM room_player p WHERE p.room_id=r.room_id) AS participantCount";
    String FILTER="r.game_id='6003' AND r.created_at &gt;= #{start} AND r.created_at &lt; #{end} AND "+SCOPE+" <if test='code!=null'>AND r.room_code=#{code}</if><if test='owner!=null'>AND r.owner_uid=#{owner}</if><if test='creator!=null'>AND r.creator_uid=#{creator}</if><if test='status!=null'>AND r.status=#{status}</if> ";
    @Select("SELECT COUNT(*) FROM admin_data_scope WHERE principal_id=#{actor} AND ((scope_type='PLATFORM' AND scope_id='*') OR scope_type='CLUB')")
    int scope(long actor);

    @Select("<script>SELECT "+COLUMNS+" FROM room_record r JOIN room_runtime t ON t.room_id=r.room_id WHERE "+FILTER+" ORDER BY r.created_at DESC,r.room_id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    @Options(timeout=2)
    List<Map<String,Object>> list(@Param("actor") long actor,@Param("code") String code,@Param("owner") Long owner,@Param("creator") Long creator,@Param("status") String status,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("offset") int offset,@Param("limit") int limit);

    @Select("<script>SELECT COUNT(*) FROM room_record r WHERE "+FILTER+"</script>")
    @Options(timeout=2)
    long count(@Param("actor") long actor,@Param("code") String code,@Param("owner") Long owner,@Param("creator") Long creator,@Param("status") String status,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Select("SELECT "+COLUMNS+" FROM room_record r JOIN room_runtime t ON t.room_id=r.room_id WHERE r.room_id=#{id} AND r.game_id='6003' AND "+SCOPE)
    @Options(timeout=2)
    Map<String,Object> detail(@Param("actor") long actor,@Param("id") String id);

    @Select("SELECT CAST(p.uid AS CHAR) AS uid,r.name,r.head,r.sex,p.seat,p.joined_at AS joinedAt FROM room_player p JOIN player_role r ON r.uid=p.uid WHERE p.room_id=#{id} ORDER BY p.seat")
    @Options(timeout=2)
    List<Map<String,Object>> players(String id);
}
