package com.ruoyi.game.persistence.mapper;

import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.*;
import com.ruoyi.game.auth.GuestIdentity;

public interface GameAdminMapper {
    @Select("SELECT COUNT(*) FROM admin_data_scope WHERE principal_id=#{actor} AND scope_type='PLATFORM' AND scope_id='*'")
    int platformScope(long actor);

    @Select("SELECT COUNT(*) FROM admin_data_scope WHERE principal_id=#{actor} AND ((scope_type='PLATFORM' AND scope_id='*') OR (scope_type='CLUB' AND scope_id=CAST(#{club} AS CHAR)))")
    int clubScope(@Param("actor") long actor,@Param("club") long club);

    @Select("<script>SELECT CAST(r.uid AS CHAR) AS uid,r.name,r.head,r.sex,a.status,a.is_test AS isTest,r.created_at AS createdAt,r.last_login_at AS lastLoginAt FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE 1=1 <if test='uid != null'>AND r.uid=#{uid}</if><if test='name != null'>AND r.name LIKE CONCAT('%',#{name},'%')</if><if test='status != null'>AND a.status=#{status}</if> ORDER BY r.uid DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> players(@Param("uid") Long uid,@Param("name") String name,@Param("status") String status,@Param("offset") int offset,@Param("limit") int limit);

    @Select("<script>SELECT COUNT(*) FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE 1=1 <if test='uid != null'>AND r.uid=#{uid}</if><if test='name != null'>AND r.name LIKE CONCAT('%',#{name},'%')</if><if test='status != null'>AND a.status=#{status}</if></script>")
    long playerCount(@Param("uid") Long uid,@Param("name") String name,@Param("status") String status);

    @Select("SELECT COUNT(*) FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE a.is_test=FALSE AND r.created_at>=#{start} AND r.created_at<#{end}")
    long newPlayers(@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM player_login_daily d JOIN player_role r ON r.uid=d.uid JOIN user_account a ON a.id=r.account_id WHERE d.business_date=#{date} AND d.is_test=FALSE AND a.is_test=FALSE")
    long todayLogins(LocalDate date);

    @Select("SELECT d.business_date AS date,COUNT(*) AS loginCount FROM player_login_daily d JOIN player_role r ON r.uid=d.uid JOIN user_account a ON a.id=r.account_id WHERE d.business_date>=#{start} AND d.is_test=FALSE AND a.is_test=FALSE GROUP BY d.business_date ORDER BY d.business_date")
    List<Map<String,Object>> loginTrend(LocalDate start);

    @Select("<script>SELECT r.uid FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE a.is_test=FALSE AND a.status='ACTIVE' AND r.uid IN <foreach collection='uids' item='uid' open='(' separator=',' close=')'>#{uid}</foreach></script>")
    List<Long> realPlayers(@Param("uids") Collection<Long> uids);

    @Select("<script>SELECT r.uid FROM player_role r JOIN user_account a ON a.id=r.account_id JOIN guest_credential g ON g.account_id=a.id WHERE a.status='ACTIVE' AND g.status='ACTIVE' AND g.expires_at &gt; #{now} <if test='!includeTest'>AND a.is_test=FALSE</if> AND <foreach collection='identities' item='identity' open='(' separator=' OR ' close=')'>(r.uid=#{identity.uid} AND a.id=#{identity.accountId} AND a.session_version=#{identity.sessionVersion} AND g.id=#{identity.credentialId})</foreach></script>")
    List<Long> activeIdentities(@Param("identities") Collection<GuestIdentity> identities,@Param("includeTest") boolean includeTest,@Param("now") LocalDateTime now);

    @Select("SELECT CAST(r.uid AS CHAR) AS uid,r.name,r.head,r.sex,a.status,a.is_test AS isTest,r.created_at AS createdAt,r.last_login_at AS lastLoginAt FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE r.uid=#{uid}")
    Map<String,Object> player(long uid);

    @Select("SELECT currency,balance,frozen,version FROM wallet_account WHERE uid=#{uid} ORDER BY currency")
    List<Map<String,Object>> wallets(long uid);

    @Select("SELECT a.id AS accountId,a.status,a.session_version AS sessionVersion FROM user_account a JOIN player_role r ON r.account_id=a.id WHERE r.uid=#{uid} FOR UPDATE")
    Map<String,Object> lockPlayer(long uid);

    @Update("UPDATE user_account SET status=#{status},session_version=session_version+1 WHERE id=#{id}")
    int changePlayer(@Param("id") long accountId,@Param("status") String status);

    @Insert("INSERT INTO admin_operation(operation_id,actor_id,request_key,action,request_hash,target,reason,status,created_at) VALUES(#{operationId},#{actor},#{key},#{action},#{hash},#{target},#{reason},'RUNNING',#{now}) ON DUPLICATE KEY UPDATE operation_id=operation_id")
    int beginOperation(Map<String,Object> operation);

    @Select("SELECT operation_id AS operationId,request_hash AS requestHash,status,result_json AS result FROM admin_operation WHERE actor_id=#{actor} AND request_key=#{key} FOR UPDATE")
    Map<String,Object> operation(@Param("actor") long actor,@Param("key") String key);

    @Update("UPDATE admin_operation SET status='COMPLETED',result_json=#{result},completed_at=#{now} WHERE operation_id=#{id}")
    int complete(@Param("id") String id,@Param("result") String result,@Param("now") LocalDateTime now);

    @Insert("INSERT INTO admin_audit(operator_id,action,target,reason,before_json,after_json,created_at) VALUES(#{actor},#{action},#{target},#{reason},#{before},#{after},#{now})")
    int audit(@Param("actor") long actor,@Param("action") String action,@Param("target") String target,@Param("reason") String reason,@Param("before") String before,@Param("after") String after,@Param("now") LocalDateTime now);

    @Insert("INSERT INTO admin_audit(operation_id,operator_id,action,target,reason,before_json,after_json,created_at) VALUES(#{operationId},#{actor},#{action},#{target},#{reason},#{before},#{after},#{now})")
    int auditCommand(@Param("operationId") String operationId,@Param("actor") long actor,@Param("action") String action,@Param("target") String target,@Param("reason") String reason,@Param("before") String before,@Param("after") String after,@Param("now") LocalDateTime now);
}
