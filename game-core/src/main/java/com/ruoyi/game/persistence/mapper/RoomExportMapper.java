package com.ruoyi.game.persistence.mapper;

import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface RoomExportMapper {
    String META="id,status,attempts,row_count AS rowCount,file_bytes AS fileBytes,created_at AS createdAt,completed_at AS completedAt,expires_at AS expiresAt,last_error_code AS lastErrorCode";
    @Select("SELECT id FROM admin_export_gate WHERE id=1 FOR UPDATE") @Options(timeout=2) int gate();
    @Select("SELECT UTC_TIMESTAMP(6)") @Options(timeout=2) LocalDateTime databaseNow();
    @Select("SELECT CONCAT(scope_type,':',scope_id) FROM admin_data_scope WHERE principal_id=#{actor} AND ((scope_type='PLATFORM' AND scope_id='*') OR scope_type='CLUB') ORDER BY scope_type,scope_id LIMIT 201")
    @Options(timeout=2) List<String> scopes(long actor);
    @Select("SELECT COUNT(*) FROM room_export_job WHERE created_at>=#{since}") @Options(timeout=2) int recent(LocalDateTime since);
    @Select("SELECT COUNT(*) FROM room_export_job WHERE actor=#{actor} AND status IN ('READY','RUNNING')") @Options(timeout=2) int pending(long actor);
    @Insert("INSERT INTO room_export_job(id,actor,filter_json,scope_hash,created_at) VALUES(#{id},#{actor},#{filter},#{scope},#{now})")
    int create(@Param("id") String id,@Param("actor") long actor,@Param("filter") String filter,@Param("scope") String scope,@Param("now") LocalDateTime now);
    @Select("SELECT "+META+" FROM room_export_job WHERE actor=#{actor} AND scope_hash=#{scope} ORDER BY created_at DESC,id DESC LIMIT 20")
    @Options(timeout=2) List<Map<String,Object>> list(@Param("actor") long actor,@Param("scope") String scope);
    @Select("SELECT *,scope_hash AS scopeHash,filter_json AS filterJson,created_at AS createdAt,lease_token AS leaseToken,lease_until AS leaseUntil,expires_at AS expiresAt,file_bytes AS fileBytes FROM room_export_job WHERE id=#{id}")
    @Options(timeout=2) Map<String,Object> raw(String id);
    @Select("SELECT "+META+" FROM room_export_job WHERE id=#{id} AND actor=#{actor} AND scope_hash=#{scope}")
    @Options(timeout=2) Map<String,Object> metadata(@Param("id") String id,@Param("actor") long actor,@Param("scope") String scope);
    @Update("UPDATE room_export_job SET status='EXPIRED' WHERE status='DONE' AND expires_at<=#{now}") @Options(timeout=2) int expire(LocalDateTime now);
    @Select("SELECT COUNT(*) FROM room_export_job WHERE status='RUNNING' AND lease_until>#{now}") @Options(timeout=2) int running(LocalDateTime now);
    @Select("SELECT id,attempts FROM room_export_job WHERE status='READY' OR (status='RUNNING' AND lease_until<=#{now}) ORDER BY created_at,id LIMIT 1 FOR UPDATE")
    @Options(timeout=2) Map<String,Object> candidate(LocalDateTime now);
    @Update("UPDATE room_export_job SET status='RUNNING',attempts=attempts+1,lease_token=#{token},lease_until=#{until},last_error_code=NULL WHERE id=#{id} AND attempts<3")
    int claim(@Param("id") String id,@Param("token") String token,@Param("until") LocalDateTime until);
    @Update("UPDATE room_export_job SET lease_until=#{until} WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>#{now}")
    int renew(@Param("id") String id,@Param("token") String token,@Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
    @Update("UPDATE room_export_job SET status='DONE',row_count=#{rows},file_bytes=#{bytes},completed_at=#{now},expires_at=#{expires},lease_until=NULL,last_error_code=NULL WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>#{now}")
    int complete(@Param("id") String id,@Param("token") String token,@Param("rows") int rows,@Param("bytes") int bytes,@Param("now") LocalDateTime now,@Param("expires") LocalDateTime expires);
    @Update("UPDATE room_export_job SET status=CASE WHEN attempts>=3 OR #{terminal} THEN 'FAILED' ELSE 'READY' END,lease_until=NULL,last_error_code=#{code} WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}")
    int failed(@Param("id") String id,@Param("token") String token,@Param("terminal") boolean terminal,@Param("code") String code);
    @Update("UPDATE room_export_job SET status='FAILED',lease_until=NULL,last_error_code='LEASE_EXHAUSTED' WHERE id=#{id} AND attempts>=3") int exhausted(String id);
    String EXPORT_COLUMNS="r.room_id AS roomId,r.room_code AS roomCode,r.game_id AS gameId,CAST(r.owner_uid AS CHAR) AS ownerUid,CAST(r.creator_uid AS CHAR) AS creatorUid,CAST(r.club_id AS CHAR) AS clubId,r.status,r.is_test AS isTest,t.round_no AS roundNo,r.created_at AS createdAt,r.ended_at AS endedAt,r.rule_version AS ruleVersion";
    @Select("<script>SELECT "+EXPORT_COLUMNS+" FROM room_record r JOIN room_runtime t ON t.room_id=r.room_id WHERE "+RoomAdminMapper.FILTER+" AND r.created_at&lt;#{cutoff} AND (r.created_at>#{cursorTime} OR (r.created_at=#{cursorTime} AND r.room_id>#{cursorId})) ORDER BY r.created_at,r.room_id LIMIT 200</script>")
    @Options(timeout=2) List<Map<String,Object>> batch(@Param("actor") long actor,@Param("code") String code,@Param("owner") Long owner,@Param("creator") Long creator,@Param("status") String status,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("cutoff") LocalDateTime cutoff,@Param("cursorTime") LocalDateTime cursorTime,@Param("cursorId") String cursorId);
}
