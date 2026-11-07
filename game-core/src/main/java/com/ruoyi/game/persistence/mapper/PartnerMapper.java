package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface PartnerMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM club_partner WHERE club_id=#{club} AND uid=#{uid})")
    boolean exists(@Param("club") long club,@Param("uid") long uid);

    @Select("SELECT EXISTS(SELECT 1 FROM club_partner WHERE club_id=#{club} AND uid=#{uid} AND has_review_auth=TRUE)")
    boolean mayReview(@Param("club") long club,@Param("uid") long uid);

    @Select("SELECT m.uid,r.name,r.head,r.sex,m.member_role AS memberRole,IF(p.uid IS NULL,0,1) AS is_partner,IF(p.has_review_auth,1,0) AS hasReviewAuth,pm.partner_uid AS partnerUid,pr.name AS partnerName FROM club_member m JOIN player_role r ON r.uid=m.uid LEFT JOIN club_partner p ON p.club_id=m.club_id AND p.uid=m.uid LEFT JOIN club_partner_member pm ON pm.club_id=m.club_id AND pm.member_uid=m.uid LEFT JOIN player_role pr ON pr.uid=pm.partner_uid WHERE m.club_id=#{club} ORDER BY m.uid")
    List<Map<String,Object>> members(long club);

    @Insert("INSERT INTO club_partner(club_id,uid,created_by,created_at) VALUES(#{club},#{uid},#{actor},#{now})")
    int add(@Param("club") long club,@Param("uid") long uid,@Param("actor") long actor,@Param("now") LocalDateTime now);

    @Delete("DELETE FROM club_partner WHERE club_id=#{club} AND uid=#{uid}")
    int remove(@Param("club") long club,@Param("uid") long uid);

    @Select("SELECT partner_uid FROM club_partner_member WHERE club_id=#{club} AND member_uid=#{uid}")
    Long parent(@Param("club") long club,@Param("uid") long uid);

    @Insert("INSERT INTO club_partner_member(club_id,member_uid,partner_uid,attached_at) VALUES(#{club},#{uid},#{partner},#{now}) ON DUPLICATE KEY UPDATE partner_uid=VALUES(partner_uid),attached_at=VALUES(attached_at)")
    int bind(@Param("club") long club,@Param("uid") long uid,@Param("partner") long partner,
             @Param("now") LocalDateTime now);

    @Delete("DELETE FROM club_partner_member WHERE club_id=#{club} AND member_uid=#{uid} AND partner_uid=#{partner}")
    int unbind(@Param("club") long club,@Param("uid") long uid,@Param("partner") long partner);

    @Delete("DELETE FROM club_partner_member WHERE club_id=#{club} AND (partner_uid=#{uid} OR member_uid=#{uid})")
    int removeRelations(@Param("club") long club,@Param("uid") long uid);

    @Update("UPDATE club_partner SET has_review_auth=#{allowed} WHERE club_id=#{club} AND uid=#{uid}")
    int permission(@Param("club") long club,@Param("uid") long uid,@Param("allowed") boolean allowed);

    @Select("SELECT s.uid,s.partner_uid AS partnerUid,SUM(r.round_count) AS games,SUM(s.score) AS score,MAX(r.finished_at) AS gameAt FROM club_game_record r JOIN club_game_score s ON s.record_id=r.id LEFT JOIN club_partner_stats_floor f ON f.club_id=r.club_id AND f.partner_uid=s.partner_uid AND f.member_uid=s.uid WHERE r.club_id=#{club} AND r.finished_at>=#{start} AND r.finished_at<#{end} AND (f.visible_after IS NULL OR r.finished_at>=f.visible_after) GROUP BY s.uid,s.partner_uid")
    List<Map<String,Object>> daily(@Param("club") long club,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Select("SELECT DISTINCT r.uid,r.name,r.head,r.sex FROM player_role r JOIN club_game_score s ON s.uid=r.uid JOIN club_game_record g ON g.id=s.record_id WHERE g.club_id=#{club} AND s.partner_uid=#{partner} AND g.finished_at>=#{start} AND g.finished_at<#{end} LIMIT 10000")
    List<Map<String,Object>> historicalProfiles(@Param("club") long club,@Param("partner") long partner,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Insert("INSERT INTO club_partner_stats_floor(club_id,partner_uid,member_uid,visible_after) VALUES(#{club},#{partner},#{member},#{now}) ON DUPLICATE KEY UPDATE visible_after=VALUES(visible_after)")
    int resetStats(@Param("club") long club,@Param("partner") long partner,@Param("member") long member,@Param("now") LocalDateTime now);

    @Select("SELECT action,actor_id AS actor,target_uid AS target,created_at AS time FROM club_event WHERE club_id=#{club} AND action LIKE 'PARTNER_%' AND (target_uid=#{uid} OR actor_id=#{uid} OR JSON_UNQUOTE(JSON_EXTRACT(details,'$.partner'))=CAST(#{uid} AS CHAR)) AND created_at>=#{start} AND created_at<#{end} ORDER BY created_at DESC,id DESC LIMIT 1000")
    List<Map<String,Object>> logs(@Param("club") long club,@Param("uid") long uid,
                                 @Param("start") LocalDateTime start,@Param("end") LocalDateTime end);
}
