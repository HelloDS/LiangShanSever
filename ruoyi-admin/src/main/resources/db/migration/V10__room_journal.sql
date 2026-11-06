CREATE TABLE room_record (
 room_id CHAR(36) NOT NULL PRIMARY KEY,
 room_code CHAR(6) NOT NULL,
 game_id VARCHAR(4) NOT NULL,
 owner_uid BIGINT NOT NULL,
 creator_uid BIGINT NOT NULL,
 payer_uid BIGINT NULL,
 club_id BIGINT NULL,
 options_json JSON NOT NULL,
 rule_version VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
 is_test BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL,
 ended_at DATETIME(6) NULL,
 KEY idx_room_created(created_at,room_id),
 KEY idx_room_owner(owner_uid,created_at,room_id),
 KEY idx_room_creator(creator_uid,created_at,room_id),
 KEY idx_room_status(status,created_at,room_id),
 KEY idx_room_code_history(room_code,created_at,room_id),
 KEY idx_room_club(club_id,status,created_at,room_id),
 FOREIGN KEY(owner_uid) REFERENCES player_role(uid),
 FOREIGN KEY(creator_uid) REFERENCES player_role(uid),
 FOREIGN KEY(payer_uid) REFERENCES player_role(uid),
 CONSTRAINT ck_room_status CHECK(status IN ('WAITING','PLAYING','ROUND_FINISHED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE active_room_code (
 room_code CHAR(6) NOT NULL PRIMARY KEY,
 room_id CHAR(36) NOT NULL UNIQUE,
 FOREIGN KEY(room_id) REFERENCES room_record(room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_runtime (
 room_id CHAR(36) NOT NULL PRIMARY KEY,
 owner_epoch BIGINT NOT NULL DEFAULT 0,
 owner_node CHAR(36) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 round_no INT NOT NULL DEFAULT 0,
 phase VARCHAR(32) NOT NULL DEFAULT 'WAITING',
 state_hash CHAR(64) NOT NULL,
 snapshot_seq BIGINT NOT NULL DEFAULT 0,
 snapshot_round INT NOT NULL DEFAULT 0,
 snapshot_phase VARCHAR(32) NOT NULL DEFAULT 'WAITING',
 snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 snapshot_hash CHAR(64) NOT NULL,
 schema_version INT NOT NULL,
 engine_version VARCHAR(64) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(room_id) REFERENCES room_record(room_id),
 CONSTRAINT ck_room_versions CHECK(owner_epoch>=0 AND version>=0 AND snapshot_seq>=0 AND snapshot_seq<=version),
 CONSTRAINT ck_room_round CHECK(round_no>=0 AND snapshot_round>=0 AND snapshot_round<=round_no),
 CONSTRAINT ck_room_snapshot_json CHECK(JSON_VALID(snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_ownership_event (
 room_id CHAR(36) NOT NULL,
 owner_epoch BIGINT NOT NULL,
 node_id CHAR(36) NOT NULL,
 claimed_at DATETIME(6) NOT NULL,
 PRIMARY KEY(room_id,owner_epoch),
 FOREIGN KEY(room_id) REFERENCES room_record(room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_event (
 room_id CHAR(36) NOT NULL,
 event_seq BIGINT NOT NULL,
 owner_epoch BIGINT NOT NULL,
 round_no INT NOT NULL,
 phase VARCHAR(32) NOT NULL,
 event_kind VARCHAR(32) NOT NULL,
 payload_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) NOT NULL,
 before_hash CHAR(64) NOT NULL,
 after_hash CHAR(64) NOT NULL,
 schema_version INT NOT NULL,
 engine_version VARCHAR(64) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 committed_at DATETIME(6) NOT NULL,
 PRIMARY KEY(room_id,event_seq),
 FOREIGN KEY(room_id) REFERENCES room_record(room_id),
 CONSTRAINT ck_room_event_seq CHECK(event_seq>0),
 CONSTRAINT ck_room_payload_json CHECK(JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_player (
 room_id CHAR(36) NOT NULL,
 uid BIGINT NOT NULL,
 seat INT NOT NULL,
 joined_at DATETIME(6) NOT NULL,
 PRIMARY KEY(room_id,uid),
 UNIQUE KEY uk_room_seat(room_id,seat),
 FOREIGN KEY(room_id) REFERENCES room_record(room_id),
 FOREIGN KEY(uid) REFERENCES player_role(uid),
 CONSTRAINT ck_room_seat CHECK(seat>=0 AND seat<3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE player_room_binding (
 uid BIGINT NOT NULL PRIMARY KEY,
 room_id CHAR(36) NOT NULL,
 seat INT NOT NULL,
 joined_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_active_room_seat(room_id,seat),
 FOREIGN KEY(uid) REFERENCES player_role(uid),
 FOREIGN KEY(room_id) REFERENCES room_record(room_id),
 CONSTRAINT ck_active_room_seat CHECK(seat>=0 AND seat<3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consumer_receipt (
 consumer_name VARCHAR(64) NOT NULL,
 event_id CHAR(36) NOT NULL,
 receipt_id CHAR(36) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 consumed_at DATETIME(6) NOT NULL,
 PRIMARY KEY(consumer_name,event_id),
 FOREIGN KEY(event_id) REFERENCES outbox_event(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_event_projection (
 room_id CHAR(36) NOT NULL PRIMARY KEY,
 event_count BIGINT NOT NULL,
 last_version BIGINT NOT NULL,
 last_occurred_at DATETIME(6) NOT NULL,
 processed_at DATETIME(6) NOT NULL,
 FOREIGN KEY(room_id) REFERENCES room_record(room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES (3008,'房间记录',3000,8,'rooms','game/rooms/index',1,0,'C','0','0','game:room:query','table','admin',UTC_TIMESTAMP());
