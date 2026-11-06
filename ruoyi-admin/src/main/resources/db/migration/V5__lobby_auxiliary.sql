CREATE TABLE lobby_visit_daily (
 business_date DATE NOT NULL,
 uid BIGINT NOT NULL,
 activity_id VARCHAR(48) NOT NULL,
 visit_count BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(business_date,uid,activity_id),
 FOREIGN KEY(uid) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE player_koi_state (
 uid BIGINT NOT NULL PRIMARY KEY,
 floor INT NOT NULL DEFAULT 0,
 win_state INT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(uid) REFERENCES player_role(uid),
 CONSTRAINT ck_koi_floor CHECK(floor>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO player_koi_state(uid,updated_at) SELECT uid,UTC_TIMESTAMP(6) FROM player_role;
