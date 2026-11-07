package com.ruoyi.game.persistence.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.*;

public interface LiveRoomMapper {
    @Select(
            "SELECT a.is_test FROM user_account a JOIN player_role r ON r.account_id=a.id WHERE"
                    + " r.uid=#{uid}")
    boolean isTest(long uid);

    String COLUMNS =
            "r.room_id AS roomId,r.room_code AS code,r.club_id AS club,r.owner_uid AS"
                + " owner,r.creator_uid AS creator,r.status,r.is_test AS isTest,r.is_default AS"
                + " isDefault,CAST(r.options_json AS CHAR) AS options,t.owner_epoch AS"
                + " epoch,t.version,t.round_no AS roundNo,t.phase,CAST(t.snapshot_json AS CHAR) AS"
                + " snapshot,t.engine_version AS engine";

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM room_record r JOIN room_runtime t ON t.room_id=r.room_id JOIN"
                    + " active_room_code a ON a.room_id=r.room_id WHERE a.room_code=#{code}")
    Map<String, Object> byCode(String code);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM room_record r JOIN room_runtime t ON t.room_id=r.room_id WHERE"
                    + " r.room_id=#{id}")
    Map<String, Object> byId(String id);

    @Select("SELECT room_id FROM player_room_binding WHERE uid=#{uid}")
    String binding(long uid);

    @Select("SELECT room_code FROM active_room_code WHERE room_code=#{code}")
    String occupied(String code);

    @Select("SELECT next_code FROM room_code_sequence WHERE id=1 FOR UPDATE")
    int nextCode();

    @Update("UPDATE room_code_sequence SET next_code=#{next} WHERE id=1")
    int advanceCode(int next);

    @Insert(
            "INSERT INTO"
                + " room_record(room_id,room_code,game_id,owner_uid,creator_uid,payer_uid,club_id,options_json,rule_version,is_test,is_default,created_at)"
                + " VALUES(#{id},#{code},'6003',#{owner},#{creator},#{payer},#{club},#{options},#{rule},#{test},#{defaultRoom},#{now})")
    int create(Map<String, Object> row);

    @Insert("INSERT INTO active_room_code(room_code,room_id) VALUES(#{code},#{id})")
    int activate(@Param("code") String code, @Param("id") String id);

    @Insert(
            "INSERT INTO"
                + " room_runtime(room_id,owner_epoch,owner_node,state_hash,snapshot_json,snapshot_hash,schema_version,engine_version,updated_at)"
                + " VALUES(#{id},1,#{node},#{hash},#{snapshot},#{hash},2,#{engine},#{now})")
    int runtime(Map<String, Object> row);

    @Select(
            "SELECT r.room_code AS code,r.status FROM room_record r WHERE r.club_id=#{club} AND"
                + " r.status<>'CLOSED' ORDER BY r.created_at,r.room_id")
    List<Map<String, Object>> circleRooms(long club);

    String DEFAULT_WAITING="r.is_default=TRUE AND r.status='WAITING' AND r.options_json=d.create_data "
            +"AND (SELECT COUNT(*) FROM player_room_binding b WHERE b.room_id=r.room_id)<3";

    @Select("SELECT r.room_id AS roomId,r.room_code AS code FROM club_default_room d JOIN room_record r ON r.club_id=d.club_id "
            +"WHERE d.club_id=#{club} AND d.auto_create=TRUE AND "+DEFAULT_WAITING+" ORDER BY r.created_at,r.room_id LIMIT 1")
    Map<String,Object> availableDefault(long club);

    @Select(
            "SELECT club_id AS club,game_id AS gameId,CAST(create_data AS CHAR) AS"
                + " options,auto_create AS autoCreate FROM club_default_room WHERE club_id=#{club}")
    Map<String, Object> config(long club);

    @Insert(
            "INSERT INTO"
                + " club_default_room(club_id,game_id,create_data,auto_create,updated_by,updated_at)"
                + " VALUES(#{club},'6003',#{options},TRUE,#{actor},#{now}) ON DUPLICATE KEY UPDATE"
                + " game_id=VALUES(game_id),create_data=VALUES(create_data),auto_create=TRUE,updated_by=VALUES(updated_by),updated_at=VALUES(updated_at)")
    int configure(
            @Param("club") long club,
            @Param("options") String options,
            @Param("actor") long actor,
            @Param("now") LocalDateTime now);

    @Update(
            "UPDATE club_default_room SET"
                + " auto_create=#{enabled},updated_by=#{actor},updated_at=#{now} WHERE"
                + " club_id=#{club}")
    int automatic(
            @Param("club") long club,
            @Param("enabled") boolean enabled,
            @Param("actor") long actor,
            @Param("now") LocalDateTime now);

    @Select(
            "SELECT d.club_id FROM club_default_room d JOIN club c ON c.id=d.club_id WHERE"
                    + " d.auto_create=TRUE AND c.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM room_record r WHERE r.club_id=d.club_id AND "
                    + DEFAULT_WAITING +") ORDER BY d.club_id LIMIT 1000")
    List<Long> automaticClubs();

    @Select(
            "SELECT r.room_id FROM room_record r JOIN room_runtime t ON t.room_id=r.room_id WHERE"
                + " r.status<>'CLOSED' AND t.engine_version=#{engine} ORDER BY r.created_at LIMIT"
                + " 4000")
    List<String> active(String engine);

    @Select(
            "SELECT CAST(payload_json AS CHAR) AS payload FROM room_event WHERE room_id=#{room} AND"
                    + " round_no<=#{rounds} AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.tag')) IN"
                    + " ('startGame','score','chupai','buchu') ORDER BY event_seq LIMIT 2049")
    @Options(timeout = 5)
    List<Map<String, Object>> movieEvents(@Param("room") String room, @Param("rounds") int rounds);
}
