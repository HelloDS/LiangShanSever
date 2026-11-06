package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface ClubAdminMapper {
    String SCOPE="EXISTS(SELECT 1 FROM admin_data_scope s WHERE s.principal_id=#{actor} AND ((s.scope_type='PLATFORM' AND s.scope_id='*') OR (s.scope_type='CLUB' AND s.scope_id=CAST(c.id AS CHAR))))";
    String FILTER="WHERE "+SCOPE+" <if test='id != null'>AND c.id=#{id}</if><if test='name != null'>AND c.name LIKE CONCAT('%',#{name},'%')</if><if test='owner != null'>AND c.owner_uid=#{owner}</if><if test='status != null'>AND c.status=#{status}</if>";
    String ONLINE=" LEFT JOIN (SELECT club_id,COUNT(*) AS online_count FROM club_member WHERE <choose><when test='online != null and !online.isEmpty()'>uid IN <foreach collection='online' item='uid' open='(' separator=',' close=')'>#{uid}</foreach></when><otherwise>1=0</otherwise></choose> GROUP BY club_id) o ON o.club_id=c.id ";
    @Select("<script>SELECT CAST(c.id AS CHAR) AS clubId,c.name,CAST(c.owner_uid AS CHAR) AS ownerUid,r.name AS ownerName,c.status,c.member_count AS memberCount,c.max_members AS maxMembers,c.is_test AS isTest,c.created_at AS createdAt,c.updated_at AS updatedAt,COALESCE(o.online_count,0) AS onlineCount,0 AS todayRoundCount FROM club c JOIN player_role r ON r.uid=c.owner_uid "+ONLINE+FILTER+" ORDER BY <choose><when test='sort == \"members\"'>c.member_count DESC,</when><when test='sort == \"online\"'>COALESCE(o.online_count,0) DESC,</when><when test='sort == \"created\"'>c.created_at DESC,</when></choose>c.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> list(@Param("actor") long actor,@Param("id") Long id,@Param("name") String name,@Param("owner") Long owner,@Param("status") String status,@Param("sort") String sort,@Param("online") Set<Long> online,@Param("offset") int offset,@Param("limit") int limit);
    @Select("<script>SELECT COUNT(*) FROM club c "+FILTER+"</script>")
    long count(@Param("actor") long actor,@Param("id") Long id,@Param("name") String name,@Param("owner") Long owner,@Param("status") String status);
    @Select("SELECT CAST(m.uid AS CHAR) AS uid,r.name,m.member_role AS role,m.joined_at AS joinedAt FROM club_member m JOIN player_role r ON r.uid=m.uid WHERE m.club_id=#{id} ORDER BY CASE m.member_role WHEN 'OWNER' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END,m.joined_at,m.uid LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> members(@Param("id") long id,@Param("offset") int offset,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM club_member WHERE club_id=#{id}") long memberCount(long id);
    @Select("SELECT currency,balance,frozen,version FROM club_wallet_account WHERE club_id=#{id} ORDER BY currency") List<Map<String,Object>> wallets(long id);
    @Select("SELECT a.uid,r.name,a.kind,a.status,a.applied_at AS appliedAt FROM club_application a JOIN player_role r ON r.uid=a.uid WHERE a.club_id=#{id} AND a.status='PENDING' ORDER BY a.applied_at,a.uid LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> applications(@Param("id") long id,@Param("offset") int offset,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM club_application WHERE club_id=#{id} AND status='PENDING'") long applicationCount(long id);
    @Update("UPDATE club SET status=#{status},version=version+1,updated_at=#{now} WHERE id=#{id}") int status(@Param("id") long id,@Param("status") String status,@Param("now") LocalDateTime now);
}
