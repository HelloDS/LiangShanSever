package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface ClubMapper {
    String COLUMNS="c.id,c.name,c.owner_uid AS owner,c.status,c.member_count AS current_mem,c.max_members AS max_mem,c.max_managers AS maxManagers,c.only_manager_open_room AS onlyManagerOpenRoom,c.version,c.created_at AS createdAt,c.updated_at AS updatedAt,COALESCE(w.balance,0) AS card,COALESCE(d.balance,0) AS diamond";
    String JOINS=" FROM club c LEFT JOIN club_wallet_account w ON w.club_id=c.id AND w.currency='card' LEFT JOIN club_wallet_account d ON d.club_id=c.id AND d.currency='diamond' ";
    @Select("SELECT "+COLUMNS+JOINS+"WHERE c.id=#{id}") Map<String,Object> club(long id);
    @Select("SELECT a.is_test AS isTest FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE r.uid=#{uid} AND a.status='ACTIVE' FOR UPDATE")
    Map<String,Object> lockActivePlayer(long uid);
    @Select("SELECT id FROM club WHERE owner_uid=#{uid} AND created_name=#{name}")
    Long createdClub(@Param("uid") long uid,@Param("name") String name);
    @Insert("INSERT INTO club(name,created_name,owner_uid,max_members,max_managers,is_test,created_at,updated_at) VALUES(#{name},#{name},#{uid},#{maxMembers},#{maxManagers},#{test},#{now},#{now})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int create(Map<String,Object> values);
    @Insert("INSERT INTO club_member(club_id,uid,member_role,joined_at) VALUES(#{id},#{uid},'OWNER',#{now})")
    int createOwner(@Param("id") long id,@Param("uid") long uid,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO club_wallet_account(club_id,currency) VALUES(#{id},'card'),(#{id},'diamond')")
    int createWallets(long id);
    @Update("UPDATE club SET name=#{name},version=version+1,updated_at=#{now} WHERE id=#{id}")
    int rename(@Param("id") long id,@Param("name") String name,@Param("now") LocalDateTime now);
    @Update("UPDATE club SET max_members=#{capacity},version=version+1,updated_at=#{now} WHERE id=#{id}")
    int capacity(@Param("id") long id,@Param("capacity") int capacity,@Param("now") LocalDateTime now);
    @Select("SELECT currency,balance FROM club_wallet_account WHERE club_id=#{id} ORDER BY currency FOR UPDATE")
    List<Map<String,Object>> lockAssets(long id);
    @Select("SELECT currency,balance FROM wallet_account WHERE uid=#{uid} ORDER BY currency FOR UPDATE")
    List<Map<String,Object>> lockPersonalAssets(long uid);
    @Select("SELECT EXISTS(SELECT 1 FROM room_record WHERE club_id=#{id} AND status IN ('WAITING','PLAYING','ROUND_FINISHED'))")
    boolean hasLiveRooms(long id);
    @Update("UPDATE club SET status='DISBANDED',version=version+1,updated_at=#{now} WHERE id=#{id} AND status='ACTIVE'")
    int disband(@Param("id") long id,@Param("now") LocalDateTime now);
    @Update("UPDATE club_application SET status='REJECTED',reviewed_by=#{actor},reviewed_by_type='PLAYER',reviewed_at=#{now} WHERE club_id=#{id} AND status='PENDING'")
    int closeApplications(@Param("id") long id,@Param("actor") long actor,@Param("now") LocalDateTime now);
    @Select("SELECT id,name,owner_uid AS owner,status,member_count AS current_mem,max_members AS max_mem,max_managers AS maxManagers,version FROM club WHERE id=#{id} FOR UPDATE") Map<String,Object> lock(long id);
    @Select("SELECT "+COLUMNS+JOINS+"JOIN club_member m ON m.club_id=c.id WHERE m.uid=#{uid} AND c.status='ACTIVE' ORDER BY c.id") List<Map<String,Object>> mine(long uid);
    @Select("SELECT member_role FROM club_member WHERE club_id=#{id} AND uid=#{uid}") String memberRole(@Param("id") long id,@Param("uid") long uid);
    @Select("SELECT uid FROM club_member WHERE club_id=#{id} ORDER BY CASE member_role WHEN 'OWNER' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END,joined_at,uid") List<Long> members(long id);
    @Select("SELECT COUNT(*) FROM club_member WHERE club_id=#{id} AND member_role='MANAGER'") int managers(long id);
    @Insert("INSERT INTO club_member(club_id,uid,member_role,joined_at) VALUES(#{id},#{uid},'MEMBER',#{now})") int join(@Param("id") long id,@Param("uid") long uid,@Param("now") LocalDateTime now);
    @Delete("DELETE FROM club_member WHERE club_id=#{id} AND uid=#{uid}") int leave(@Param("id") long id,@Param("uid") long uid);
    @Update("UPDATE club_member SET member_role=#{role} WHERE club_id=#{id} AND uid=#{uid}") int role(@Param("id") long id,@Param("uid") long uid,@Param("role") String role);
    @Update("UPDATE club SET member_count=member_count+#{delta},version=version+1,updated_at=#{now} WHERE id=#{id}") int count(@Param("id") long id,@Param("delta") int delta,@Param("now") LocalDateTime now);
    @Update("UPDATE club SET only_manager_open_room=#{only},version=version+1,updated_at=#{now} WHERE id=#{id}") int config(@Param("id") long id,@Param("only") boolean only,@Param("now") LocalDateTime now);
    @Select("SELECT status FROM club_application WHERE club_id=#{id} AND uid=#{uid} AND kind=#{kind}") String application(@Param("id") long id,@Param("uid") long uid,@Param("kind") String kind);
    @Insert("INSERT INTO club_application(club_id,uid,kind,status,applied_at) VALUES(#{id},#{uid},#{kind},'PENDING',#{now}) ON DUPLICATE KEY UPDATE status='PENDING',applied_at=VALUES(applied_at),reviewed_by=NULL,reviewed_by_type=NULL,reviewed_at=NULL") int apply(@Param("id") long id,@Param("uid") long uid,@Param("kind") String kind,@Param("now") LocalDateTime now);
    @Update("UPDATE club_application SET status=#{status},reviewed_by=#{actor},reviewed_by_type=#{actorType},reviewed_at=#{now} WHERE club_id=#{id} AND uid=#{uid} AND kind=#{kind} AND status='PENDING'") int review(@Param("id") long id,@Param("uid") long uid,@Param("kind") String kind,@Param("status") String status,@Param("actor") long actor,@Param("actorType") String actorType,@Param("now") LocalDateTime now);
    @Select("SELECT uid,applied_at AS appliedAt FROM club_application WHERE club_id=#{id} AND kind=#{kind} AND status='PENDING' ORDER BY applied_at,uid LIMIT #{limit} OFFSET #{offset}") List<Map<String,Object>> applications(@Param("id") long id,@Param("kind") String kind,@Param("offset") int offset,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM club_application WHERE club_id=#{id} AND kind=#{kind} AND status='PENDING'") long applicationCount(@Param("id") long id,@Param("kind") String kind);
    @Insert("INSERT INTO club_event(club_id,actor_id,actor_type,target_uid,action,details,created_at) VALUES(#{id},#{actor},#{actorType},#{target},#{action},#{details},#{now})") int event(@Param("id") long id,@Param("actor") long actor,@Param("actorType") String actorType,@Param("target") Long target,@Param("action") String action,@Param("details") String details,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO club_message(uid,club_id,club_name,kind,created_at) VALUES(#{uid},#{id},#{name},#{kind},#{now})") int message(@Param("uid") long uid,@Param("id") long id,@Param("name") String name,@Param("kind") String kind,@Param("now") LocalDateTime now);
    @Select("SELECT id,club_name AS name,kind,created_at AS createdAt FROM club_message WHERE uid=#{uid} ORDER BY created_at DESC,id DESC LIMIT 100 FOR UPDATE") List<Map<String,Object>> messages(long uid);
    @Select("SELECT COUNT(*) FROM club_message WHERE uid=#{uid} AND read_at IS NULL") long unread(long uid);
    @Update("UPDATE club_message SET read_at=#{now} WHERE uid=#{uid} AND id<=#{through} AND read_at IS NULL") int readMessages(@Param("uid") long uid,@Param("through") long through,@Param("now") LocalDateTime now);
    @Select("SELECT COUNT(*) FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE r.uid=#{uid} AND a.status='ACTIVE'") int activePlayer(long uid);
}
