package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface ClubRecordMapper {
    @Select("<script>SELECT g.id,g.room_id AS roomId,g.game_id AS gameId,g.room_code AS roomCode,g.round_count AS rounds,g.record_json AS record,g.finished_at AS finishedAt FROM club_game_record g WHERE g.club_id=#{club} AND g.finished_at&gt;=#{start} AND g.finished_at&lt;#{end}<if test='uid != null'> AND EXISTS(SELECT 1 FROM club_game_score s WHERE s.record_id=g.id AND s.uid=#{uid})</if> ORDER BY g.finished_at DESC,g.id DESC LIMIT 100</script>")
    List<Map<String,Object>> clubRecords(@Param("club") long club,@Param("uid") Long uid,
            @Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Select("SELECT g.id,g.game_id AS gameId,g.room_code AS roomCode,g.round_count AS rounds,g.record_json AS record,g.finished_at AS finishedAt,s.score FROM club_game_record g JOIN club_game_score s ON s.record_id=g.id LEFT JOIN club_partner_stats_floor f ON f.club_id=g.club_id AND f.partner_uid=s.partner_uid AND f.member_uid=s.uid WHERE g.club_id=#{club} AND s.partner_uid=#{partner} AND s.uid=#{uid} AND g.finished_at>=#{start} AND g.finished_at<#{end} AND (f.visible_after IS NULL OR g.finished_at>=f.visible_after) ORDER BY g.finished_at DESC,g.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> partnerRecords(@Param("club") long club,@Param("partner") long partner,@Param("uid") long uid,
            @Param("start") LocalDateTime start,@Param("end") LocalDateTime end,@Param("offset") int offset,@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM club_game_record g JOIN club_game_score s ON s.record_id=g.id LEFT JOIN club_partner_stats_floor f ON f.club_id=g.club_id AND f.partner_uid=s.partner_uid AND f.member_uid=s.uid WHERE g.club_id=#{club} AND s.partner_uid=#{partner} AND s.uid=#{uid} AND g.finished_at>=#{start} AND g.finished_at<#{end} AND (f.visible_after IS NULL OR g.finished_at>=f.visible_after)")
    long partnerCount(@Param("club") long club,@Param("partner") long partner,@Param("uid") long uid,
            @Param("start") LocalDateTime start,@Param("end") LocalDateTime end);

    @Select("SELECT club_id AS clubId,record_json AS record FROM club_game_record WHERE id=#{id}")
    Map<String,Object> detail(long id);

    @Select("SELECT EXISTS(SELECT 1 FROM club_game_score WHERE record_id=#{record} AND partner_uid=#{partner})")
    boolean containsPartner(@Param("record") long record,@Param("partner") long partner);

    @Select("SELECT COALESCE(SUM(r.round_count),0) AS games,MAX(r.finished_at) AS gameAt FROM club_game_score s JOIN club_game_record r ON r.id=s.record_id WHERE r.club_id=#{club} AND s.uid=#{uid}")
    Map<String,Object> memberStats(@Param("club") long club,@Param("uid") long uid);
}
