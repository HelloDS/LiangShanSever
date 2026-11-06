package com.ruoyi.game.persistence.mapper;

import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface WalletReconciliationMapper {
    String META="id,CAST(scheduled_date AS CHAR) AS scheduledDate,status,algorithm_version AS algorithmVersion,checked_count AS checkedCount,matched_count AS matchedCount,mismatched_count AS mismatchedCount,attempt_count AS attempts,total_claims AS totalClaims,failure_count AS failureCount,manual_retries AS manualRetries,retry_at AS retryAt,last_error_code AS lastErrorCode,origin,CAST(created_by AS CHAR) AS createdBy,created_at AS createdAt,started_at AS startedAt,last_checked_at AS lastCheckedAt,completed_at AS completedAt";
    @Select("SELECT COALESCE(MAX(r.uid),0) FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE a.is_test=FALSE")
    @Options(timeout=2) long personalHighwater();
    @Select("SELECT COALESCE(MAX(id),0) FROM club WHERE is_test=FALSE")
    @Options(timeout=2) long clubHighwater();
    @Insert("INSERT INTO wallet_reconciliation_run(id,scheduled_date,personal_highwater,club_highwater,retry_at,origin,created_by,created_at) VALUES(#{id},#{date},#{personal},#{club},#{now},#{origin},#{actor,jdbcType=BIGINT},#{now}) ON DUPLICATE KEY UPDATE id=id")
    int begin(@Param("id") String id,@Param("date") LocalDate date,@Param("personal") long personal,@Param("club") long club,@Param("origin") String origin,@Param("actor") Long actor,@Param("now") LocalDateTime now);
    @Select("SELECT "+META+" FROM wallet_reconciliation_run WHERE scheduled_date=#{date}") Map<String,Object> byDate(LocalDate date);
    @Select("SELECT "+META+" FROM wallet_reconciliation_run WHERE scheduled_date=#{date} FOR UPDATE") Map<String,Object> lockDate(LocalDate date);
    @Select("SELECT "+META+" FROM wallet_reconciliation_run WHERE id=#{id}") Map<String,Object> metadata(String id);
    @Select("SELECT id,attempt_count AS attempts FROM wallet_reconciliation_run WHERE status IN ('READY','RUNNING') AND retry_at<=#{now} AND (lease_until IS NULL OR lease_until<=#{now}) ORDER BY scheduled_date,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    @Options(timeout=2) Map<String,Object> claimable(LocalDateTime now);
    @Update("UPDATE wallet_reconciliation_run SET status='RUNNING',attempt_count=attempt_count+1,total_claims=total_claims+1,lease_token=#{token},lease_until=#{until},started_at=COALESCE(started_at,#{now}) WHERE id=#{id} AND status IN ('READY','RUNNING')")
    int claim(@Param("id") String id,@Param("token") String token,@Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
    @Update("UPDATE wallet_reconciliation_run SET status='FAILED',lease_token=NULL,lease_until=NULL,last_error_code='LEASE_EXPIRED',failure_count=failure_count+1 WHERE id=#{id} AND attempt_count>=5 AND status IN ('READY','RUNNING')")
    int expire(String id);
    @Select("SELECT *,algorithm_version AS algorithmVersion,personal_highwater AS personalHighwater,club_highwater AS clubHighwater,cursor_kind AS cursorKind,cursor_owner AS cursorOwner,cursor_currency AS cursorCurrency,mismatched_count AS mismatchedCount,attempt_count AS attempts,lease_token AS leaseToken,lease_until AS leaseUntil FROM wallet_reconciliation_run WHERE id=#{id} FOR UPDATE")
    @Options(timeout=2) Map<String,Object> lock(String id);
    @Select("SELECT w.uid AS owner,w.currency FROM wallet_account w JOIN player_role r ON r.uid=w.uid JOIN user_account a ON a.id=r.account_id WHERE a.is_test=FALSE AND w.uid<=#{highwater} AND w.currency IN ('card','diamond') AND (w.uid>#{owner} OR (w.uid=#{owner} AND w.currency>#{currency})) ORDER BY w.uid,w.currency LIMIT 1")
    @Options(timeout=2) Map<String,Object> nextPersonal(@Param("owner") long owner,@Param("currency") String currency,@Param("highwater") long highwater);
    @Select("SELECT w.club_id AS owner,w.currency FROM club_wallet_account w JOIN club c ON c.id=w.club_id WHERE c.is_test=FALSE AND w.club_id<=#{highwater} AND w.currency IN ('card','diamond') AND (w.club_id>#{owner} OR (w.club_id=#{owner} AND w.currency>#{currency})) ORDER BY w.club_id,w.currency LIMIT 1")
    @Options(timeout=2) Map<String,Object> nextClub(@Param("owner") long owner,@Param("currency") String currency,@Param("highwater") long highwater);
    @Select("SELECT 0 AS owner,currency FROM wallet_system_account WHERE currency>#{currency} AND currency IN ('card','diamond') ORDER BY currency LIMIT 1")
    @Options(timeout=2) Map<String,Object> nextSystem(String currency);
    @Insert("INSERT INTO wallet_reconciliation_detail(run_id,owner_type,owner_id,currency,status,result_json,checked_at) VALUES(#{run},#{type},#{owner},#{currency},#{status},#{result},#{now})")
    int detail(@Param("run") String run,@Param("type") String type,@Param("owner") long owner,@Param("currency") String currency,@Param("status") String status,@Param("result") String result,@Param("now") LocalDateTime now);
    @Update("UPDATE wallet_reconciliation_run SET cursor_kind=#{kind},cursor_owner=#{owner},cursor_currency=#{currency},checked_count=checked_count+1,matched_count=matched_count+#{matched},mismatched_count=mismatched_count+#{mismatched},attempt_count=0,last_error_code=NULL,last_checked_at=#{now},lease_until=#{until} WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}")
    int advance(@Param("id") String id,@Param("token") String token,@Param("kind") int kind,@Param("owner") long owner,@Param("currency") String currency,@Param("matched") int matched,@Param("mismatched") int mismatched,@Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
    @Update("UPDATE wallet_reconciliation_run SET status=CASE WHEN mismatched_count=0 THEN 'DONE' ELSE 'DONE_WITH_ISSUES' END,cursor_kind=3,attempt_count=0,lease_token=NULL,lease_until=NULL,completed_at=#{now},last_error_code=NULL WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}")
    int finish(@Param("id") String id,@Param("token") String token,@Param("now") LocalDateTime now);
    @Update("UPDATE wallet_reconciliation_run SET status=#{status},attempt_count=#{attempts},failure_count=failure_count+1,retry_at=#{retry},last_error_code=#{code},lease_token=NULL,lease_until=NULL WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}")
    int failed(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("attempts") int attempts,@Param("retry") LocalDateTime retry,@Param("code") String code);
    @Update("UPDATE wallet_reconciliation_run SET status='READY',lease_token=NULL,lease_until=NULL,retry_at=#{now} WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}")
    int release(@Param("id") String id,@Param("token") String token,@Param("now") LocalDateTime now);
    @Update("UPDATE wallet_reconciliation_run SET status='READY',attempt_count=0,manual_retries=manual_retries+1,retry_at=#{now},last_error_code=NULL WHERE id=#{id} AND status='FAILED'")
    int retry(@Param("id") String id,@Param("now") LocalDateTime now);
    @Select("<script>SELECT "+META+" FROM wallet_reconciliation_run WHERE scheduled_date>=#{start} AND scheduled_date&lt;=#{end}<if test='status!=null'> AND status=#{status}</if> ORDER BY scheduled_date DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    @Options(timeout=2) List<Map<String,Object>> list(@Param("start") LocalDate start,@Param("end") LocalDate end,@Param("status") String status,@Param("offset") int offset,@Param("limit") int limit);
    @Select("<script>SELECT COUNT(*) FROM wallet_reconciliation_run WHERE scheduled_date>=#{start} AND scheduled_date&lt;=#{end}<if test='status!=null'> AND status=#{status}</if></script>")
    @Options(timeout=2) long count(@Param("start") LocalDate start,@Param("end") LocalDate end,@Param("status") String status);
    String DETAIL_COLUMNS="owner_type AS ownerType,CAST(owner_id AS CHAR) AS ownerId,currency,status,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.expectedAvailable')) AS expectedAvailable,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.actualAvailable')) AS actualAvailable,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.expectedFrozen')) AS expectedFrozen,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.actualFrozen')) AS actualFrozen,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.ledgerEntries')) AS ledgerEntries,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.throughLedgerId')) AS throughLedgerId,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.accountVersion')) AS accountVersion,checked_at AS checkedAt";
    @Select("<script>SELECT "+DETAIL_COLUMNS+" FROM wallet_reconciliation_detail WHERE run_id=#{id}<if test='status!=null'> AND status=#{status}</if> ORDER BY owner_type,owner_id,currency LIMIT #{limit} OFFSET #{offset}</script>")
    @Options(timeout=2) List<Map<String,Object>> details(@Param("id") String id,@Param("status") String status,@Param("offset") int offset,@Param("limit") int limit);
    @Select("<script>SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id=#{id}<if test='status!=null'> AND status=#{status}</if></script>")
    @Options(timeout=2) long detailsCount(@Param("id") String id,@Param("status") String status);
    @Select("SELECT status,COUNT(*) AS total FROM wallet_reconciliation_run WHERE status IN ('READY','RUNNING','FAILED','DONE_WITH_ISSUES') GROUP BY status")
    @Options(timeout=2) List<Map<String,Object>> backlog();
}
