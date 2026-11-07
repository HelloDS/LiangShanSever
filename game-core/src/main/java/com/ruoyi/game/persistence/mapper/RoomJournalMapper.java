package com.ruoyi.game.persistence.mapper;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface RoomJournalMapper {
    @Select("SELECT t.owner_epoch AS ownerEpoch,t.owner_node AS ownerNode,t.version,t.round_no AS roundNo,t.phase,t.state_hash AS stateHash,t.snapshot_seq AS snapshotSeq,t.snapshot_round AS snapshotRound,t.snapshot_phase AS snapshotPhase,CAST(t.snapshot_json AS CHAR) AS snapshotJson,t.snapshot_hash AS snapshotHash,t.schema_version AS schemaVersion,t.engine_version AS engineVersion,r.rule_version AS ruleVersion,r.game_id AS gameId,r.status,r.is_test AS isTest,r.club_id AS clubId,r.owner_uid AS ownerUid,r.payer_uid AS payerUid FROM room_runtime t JOIN room_record r ON r.room_id=t.room_id WHERE t.room_id=#{id} FOR UPDATE")
    @Options(timeout=5)
    Map<String,Object> lock(String id);

    @Update("UPDATE room_runtime SET owner_epoch=owner_epoch+1,owner_node=#{node},updated_at=#{now} WHERE room_id=#{id} AND owner_epoch=#{epoch}")
    int owner(@Param("id") String id,@Param("epoch") long epoch,@Param("node") String node,@Param("now") LocalDateTime now);

    @Insert("INSERT INTO room_ownership_event(room_id,owner_epoch,node_id,claimed_at) VALUES(#{id},#{epoch},#{node},#{now})")
    int ownership(@Param("id") String id,@Param("epoch") long epoch,@Param("node") String node,@Param("now") LocalDateTime now);

    @Select("SELECT event_seq AS eventSeq,owner_epoch AS ownerEpoch,round_no AS roundNo,phase,event_kind AS eventKind,CAST(payload_json AS CHAR) AS payloadJson,request_hash AS requestHash,before_hash AS beforeHash,after_hash AS afterHash,schema_version AS schemaVersion,engine_version AS engineVersion,occurred_at AS occurredAt,(SELECT rule_version FROM room_record r WHERE r.room_id=room_event.room_id) AS ruleVersion FROM room_event WHERE room_id=#{id} AND event_seq=#{version} FOR SHARE")
    Map<String,Object> selectEvent(@Param("id") String id,@Param("version") long version);

    @Select("SELECT event_seq AS eventSeq,round_no AS roundNo,phase,event_kind AS eventKind,CAST(payload_json AS CHAR) AS payloadJson,before_hash AS beforeHash,after_hash AS afterHash,schema_version AS schemaVersion,engine_version AS engineVersion,occurred_at AS occurredAt FROM room_event WHERE room_id=#{id} AND event_seq>#{after} ORDER BY event_seq LIMIT 2049")
    List<Map<String,Object>> tail(@Param("id") String id,@Param("after") long after);

    @Select("SELECT COUNT(*) AS total,COALESCE(SUM(OCTET_LENGTH(CAST(payload_json AS CHAR))),0) AS bytes FROM room_event WHERE room_id=#{id} AND event_seq>#{after}")
    @Options(timeout=5)
    Map<String,Object> recoveryBudget(@Param("id") String id,@Param("after") long after);

    @Insert("INSERT INTO room_event(room_id,event_seq,owner_epoch,round_no,phase,event_kind,payload_json,request_hash,before_hash,after_hash,schema_version,engine_version,occurred_at,committed_at) VALUES(#{room},#{version},#{epoch},#{round},#{phase},#{kind},#{payload},#{requestHash},#{beforeHash},#{afterHash},#{schema},#{engine},#{occurred},#{now})")
    int insertEvent(Map<String,Object> values);

    @Update("UPDATE room_runtime SET version=#{version},round_no=#{round},phase=#{phase},state_hash=#{afterHash},updated_at=#{now} WHERE room_id=#{room} AND owner_epoch=#{epoch} AND version=#{previousVersion} AND state_hash=#{beforeHash}")
    int advance(Map<String,Object> values);

    @Update("UPDATE room_runtime SET snapshot_seq=#{version},snapshot_round=#{round},snapshot_phase=#{phase},snapshot_json=#{snapshot},snapshot_hash=#{afterHash} WHERE room_id=#{room} AND owner_epoch=#{epoch} AND version=#{version}")
    int checkpoint(Map<String,Object> values);

    @Update("UPDATE room_record SET status=#{status},ended_at=CASE WHEN #{status}='CLOSED' THEN #{now} ELSE ended_at END WHERE room_id=#{id}")
    int status(@Param("id") String id,@Param("status") String status,@Param("now") LocalDateTime now);

    @Select("SELECT account_id AS accountId FROM guest_credential WHERE id=#{id} FOR UPDATE")
    Map<String,Object> lockCredential(String id);

    @Select("SELECT uid FROM player_role WHERE uid=#{uid} AND account_id=#{account} FOR SHARE")
    Long lockRole(@Param("uid") long uid,@Param("account") long account);

    @Select("SELECT id FROM user_account WHERE id=#{id} FOR UPDATE")
    Long lockAccount(long id);

    @Select("SELECT room_id AS roomId,seat FROM player_room_binding WHERE uid=#{uid}")
    Map<String,Object> binding(long uid);

    @Insert("INSERT INTO player_room_binding(uid,room_id,seat,joined_at) VALUES(#{uid},#{room},#{seat},#{now})")
    int bind(@Param("uid") long uid,@Param("room") String room,@Param("seat") int seat,@Param("now") LocalDateTime now);

    @Insert("INSERT INTO room_player(room_id,uid,seat,joined_at) VALUES(#{room},#{uid},#{seat},#{now})")
    int seat(@Param("uid") long uid,@Param("room") String room,@Param("seat") int seat,@Param("now") LocalDateTime now);

    @Delete("DELETE FROM player_room_binding WHERE uid=#{uid} AND room_id=#{room}")
    int unbind(@Param("uid") long uid,@Param("room") String room);

    @Delete("DELETE FROM room_player WHERE uid=#{uid} AND room_id=#{room}")
    int unseat(@Param("uid") long uid,@Param("room") String room);

    @Select("SELECT COUNT(*) FROM room_player WHERE room_id=#{room} AND uid=#{uid}")
    int participant(@Param("uid") long uid,@Param("room") String room);

    @Select("SELECT uid FROM room_player WHERE room_id=#{id}") List<Long> participants(String id);

    @Delete("DELETE FROM player_room_binding WHERE room_id=#{id}") int releasePlayers(String id);
    @Delete("DELETE FROM active_room_code WHERE room_id=#{id}") int releaseCode(String id);

    @Insert("INSERT INTO consumer_receipt(consumer_name,event_id,receipt_id,request_hash,consumed_at) VALUES('ROOM_ACTIVITY',#{event},#{receipt},#{hash},#{now}) ON DUPLICATE KEY UPDATE receipt_id=receipt_id")
    int beginReceipt(@Param("event") String event,@Param("receipt") String receipt,@Param("hash") String hash,@Param("now") LocalDateTime now);

    @Select("SELECT receipt_id AS receiptId,request_hash AS requestHash FROM consumer_receipt WHERE consumer_name='ROOM_ACTIVITY' AND event_id=#{id} FOR UPDATE")
    Map<String,Object> receipt(String id);

    @Insert("INSERT INTO room_event_projection(room_id,event_count,last_version,last_occurred_at,processed_at) VALUES(#{room},1,#{version},#{occurred},#{now}) ON DUPLICATE KEY UPDATE event_count=event_count+1,last_version=GREATEST(last_version,#{version}),last_occurred_at=GREATEST(last_occurred_at,#{occurred}),processed_at=#{now}")
    int project(@Param("room") String room,@Param("version") long version,@Param("occurred") LocalDateTime occurred,@Param("now") LocalDateTime now);
}
