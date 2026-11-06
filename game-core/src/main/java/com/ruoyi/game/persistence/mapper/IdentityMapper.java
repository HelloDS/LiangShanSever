package com.ruoyi.game.persistence.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface IdentityMapper {
    @Insert("INSERT INTO user_account(provider,external_id,created_at) VALUES('guest',#{externalId},#{now})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAccount(Map<String, Object> values);

    @Insert("INSERT INTO guest_credential(id,account_id,token_hash,expires_at,last_used_at) VALUES(#{id},#{accountId},#{hash},#{expires},#{now})")
    int insertCredential(@Param("id") String id, @Param("accountId") long accountId, @Param("hash") String hash,
                         @Param("expires") LocalDateTime expires, @Param("now") LocalDateTime now);

    @Select("SELECT g.id AS credentialId,g.account_id AS accountId,g.expires_at AS expiresAt,g.status AS credentialStatus,a.status,a.is_test AS isTest,a.session_version AS sessionVersion FROM guest_credential g JOIN user_account a ON a.id=g.account_id WHERE g.token_hash=#{hash} FOR UPDATE")
    Map<String, Object> credential(String hash);

    @Update("UPDATE guest_credential SET last_used_at=#{now} WHERE id=#{id}")
    int touchCredential(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE guest_credential SET status='REVOKED',revoked_at=#{now} WHERE id=#{id}")
    int revokeCredential(@Param("id") String id, @Param("now") LocalDateTime now);

    @Select("SELECT g.status AS credentialStatus,g.expires_at AS expiresAt,a.status,a.session_version AS sessionVersion FROM guest_credential g JOIN user_account a ON a.id=g.account_id WHERE g.id=#{credentialId} AND a.id=#{accountId}")
    Map<String, Object> sessionIdentity(@Param("credentialId") String credentialId, @Param("accountId") long accountId);

    @Insert("INSERT INTO player_role(account_id,name,created_at) VALUES(#{accountId},#{name},#{now})")
    @Options(useGeneratedKeys = true, keyProperty = "uid")
    int insertRole(Map<String, Object> values);

    @Insert("INSERT INTO wallet_account(uid,currency,balance) VALUES(#{uid},'card',0),(#{uid},'diamond',0)")
    int insertWallets(long uid);

    @Select("SELECT uid,account_id AS accountId,name,head,sex,created_at AS createdAt,last_login_at AS lastLoginAt FROM player_role WHERE account_id=#{accountId}")
    Map<String, Object> roleForAccount(long accountId);

    @Select("SELECT r.uid,r.name,r.head,r.sex,COALESCE(c.balance,0) AS card,COALESCE(d.balance,0) AS diamond FROM player_role r LEFT JOIN wallet_account c ON c.uid=r.uid AND c.currency='card' LEFT JOIN wallet_account d ON d.uid=r.uid AND d.currency='diamond' WHERE r.uid=#{uid}")
    Map<String, Object> ownRole(long uid);

    @Select("SELECT uid,name,head,sex FROM player_role WHERE uid=#{uid}")
    Map<String, Object> publicRole(long uid);

    @Insert("INSERT INTO player_login_event(event_id,uid,occurred_at,business_date,login_kind,is_test) VALUES(#{id},#{uid},#{now},#{date},#{kind},#{test})")
    int loginEvent(@Param("id") String id, @Param("uid") long uid, @Param("now") LocalDateTime now,
                   @Param("date") LocalDate date, @Param("kind") String kind, @Param("test") boolean test);

    @Insert("INSERT INTO player_login_daily(business_date,uid,first_login_at,last_login_at,login_count,is_test) VALUES(#{date},#{uid},#{now},#{now},1,#{test}) ON DUPLICATE KEY UPDATE last_login_at=VALUES(last_login_at),login_count=login_count+1")
    int loginDaily(@Param("uid") long uid, @Param("now") LocalDateTime now, @Param("date") LocalDate date, @Param("test") boolean test);

    @Update("UPDATE player_role SET last_login_at=#{now} WHERE uid=#{uid}")
    int touchRole(@Param("uid") long uid, @Param("now") LocalDateTime now);

    @Update("UPDATE player_role SET latitude=#{latitude},longitude=#{longitude},version=version+1 WHERE uid=#{uid}")
    int location(@Param("uid") long uid, @Param("latitude") double latitude, @Param("longitude") double longitude);

    @Select("SELECT id,title,content FROM game_notice WHERE published=TRUE ORDER BY id DESC LIMIT 50")
    List<Map<String,Object>> notices();
}
