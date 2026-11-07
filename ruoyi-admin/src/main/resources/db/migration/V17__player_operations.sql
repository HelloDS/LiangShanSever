CREATE TABLE player_operation (
 actor_uid BIGINT NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL,
 result_json JSON NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(actor_uid,operation_id),
 FOREIGN KEY(actor_uid) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
