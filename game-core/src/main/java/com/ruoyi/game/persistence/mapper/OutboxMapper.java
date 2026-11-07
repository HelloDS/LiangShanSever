package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;
import com.ruoyi.game.outbox.OutboxEvent;

public interface OutboxMapper {
    @Insert("INSERT INTO outbox_event(id,biz_key,event_type,schema_version,rule_version,payload,request_hash,retry_at,occurred_at) VALUES(#{id},#{key},#{type},1,#{rule},#{payload},#{hash},#{now},#{occurred}) ON DUPLICATE KEY UPDATE id=id")
    int append(@Param("id") String id,@Param("key") String key,@Param("type") String type,@Param("rule") String rule,@Param("payload") String payload,@Param("hash") String hash,@Param("now") LocalDateTime now,@Param("occurred") LocalDateTime occurred);

    @Select("SELECT id,request_hash AS requestHash FROM outbox_event WHERE biz_key=#{key} AND event_type=#{type} FOR UPDATE")
    Map<String,Object> business(@Param("key") String key,@Param("type") String type);

    @Select("SELECT id,attempt_count AS attempts FROM outbox_event WHERE status='CLAIMED' AND lease_until<=#{now} ORDER BY lease_until,id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<Map<String,Object>> expired(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Update("UPDATE outbox_event SET status=#{status},retry_at=#{now},lease_token=NULL,lease_until=NULL,last_error_code='LEASE_EXPIRED' WHERE id=#{id} AND status='CLAIMED' AND lease_until<=#{now}")
    int recover(@Param("id") String id,@Param("status") String status,@Param("now") LocalDateTime now);

    @Select("SELECT id FROM outbox_event WHERE status='READY' AND retry_at<=#{now} ORDER BY retry_at,id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<String> ready(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Update("<script>UPDATE outbox_event SET status='CLAIMED',attempt_count=attempt_count+1,total_attempts=total_attempts+1,lease_token=#{token},lease_until=#{until} WHERE status='READY' AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int claimBatch(@Param("ids") List<String> ids,@Param("token") String token,@Param("until") LocalDateTime until);

    String EVENT_COLUMNS="id,biz_key AS businessKey,event_type AS eventType,schema_version AS schemaVersion,rule_version AS ruleVersion,CAST(payload AS CHAR) AS payload,attempt_count AS attempts,lease_token AS leaseToken,occurred_at AS occurredAt";
    @Select("SELECT "+EVENT_COLUMNS+" FROM outbox_event WHERE id=#{id}")
    OutboxEvent event(String id);

    @Select("<script>SELECT "+EVENT_COLUMNS+" FROM outbox_event WHERE status='CLAIMED' AND lease_token=#{token} AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY retry_at,id</script>")
    List<OutboxEvent> claimedBatch(@Param("ids") List<String> ids,@Param("token") String token);

    @Update("UPDATE outbox_event SET status='DONE',completed_at=#{now},lease_token=NULL,lease_until=NULL,last_error_code=NULL WHERE id=#{id} AND status='CLAIMED' AND lease_token=#{token}")
    int complete(@Param("id") String id,@Param("token") String token,@Param("now") LocalDateTime now);
    @Select("SELECT id FROM outbox_event WHERE id=#{id} AND status='CLAIMED' AND lease_token=#{token} FOR UPDATE")
    @Options(timeout=2)
    String lockOwned(@Param("id") String id,@Param("token") String token);

    @Update("UPDATE outbox_event SET status=#{status},retry_at=#{retry},lease_token=NULL,lease_until=NULL,last_error_code=#{code} WHERE id=#{id} AND status='CLAIMED' AND lease_token=#{token}")
    int fail(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("retry") LocalDateTime retry,@Param("code") String code);

    @Select("SELECT id,status,attempt_count AS attempts,total_attempts AS totalAttempts,last_error_code AS lastErrorCode FROM outbox_event WHERE id=#{id} FOR UPDATE")
    Map<String,Object> lock(String id);

    @Update("UPDATE outbox_event SET status='READY',attempt_count=0,manual_retries=manual_retries+1,retry_at=#{now},last_error_code=NULL WHERE id=#{id} AND status='DEAD'")
    int retry(@Param("id") String id,@Param("now") LocalDateTime now);

    @Select("<script>SELECT CAST(id AS CHAR) AS id,biz_key AS businessKey,event_type AS eventType,status,attempt_count AS attempts,total_attempts AS totalAttempts,manual_retries AS manualRetries,retry_at AS retryAt,lease_until AS leaseUntil,last_error_code AS lastErrorCode,occurred_at AS occurredAt,completed_at AS completedAt FROM outbox_event WHERE occurred_at>=#{start} AND occurred_at&lt;#{end} <if test='status!=null'>AND status=#{status}</if> ORDER BY occurred_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> list(@Param("status") String status,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("limit") int limit,@Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM outbox_event WHERE occurred_at>=#{start} AND occurred_at&lt;#{end} <if test='status!=null'>AND status=#{status}</if></script>")
    long count(@Param("status") String status,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Select("SELECT status,COUNT(*) AS total,MIN(occurred_at) AS oldest FROM outbox_event FORCE INDEX(idx_outbox_backlog) WHERE status='READY' GROUP BY status UNION ALL SELECT status,COUNT(*) AS total,MIN(occurred_at) AS oldest FROM outbox_event FORCE INDEX(idx_outbox_backlog) WHERE status='CLAIMED' GROUP BY status UNION ALL SELECT status,COUNT(*) AS total,MIN(occurred_at) AS oldest FROM outbox_event FORCE INDEX(idx_outbox_backlog) WHERE status='DEAD' GROUP BY status")
    @Options(timeout=2)
    List<Map<String,Object>> backlog();
}
