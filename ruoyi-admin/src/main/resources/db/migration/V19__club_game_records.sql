CREATE TABLE club_game_record (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 room_id CHAR(36) NOT NULL,
 club_id BIGINT NOT NULL,
 game_id VARCHAR(4) NOT NULL,
 room_code CHAR(6) NOT NULL,
 round_count INT NOT NULL,
 record_json JSON NOT NULL,
 finished_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_club_room_record(room_id),
 KEY idx_club_game_record(club_id,finished_at,id),
 FOREIGN KEY(room_id) REFERENCES room_record(room_id),
 FOREIGN KEY(club_id) REFERENCES club(id),
 CONSTRAINT ck_club_record_rounds CHECK(round_count>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_game_score (
 record_id BIGINT NOT NULL,
 uid BIGINT NOT NULL,
 partner_uid BIGINT NOT NULL,
 score BIGINT NOT NULL,
 PRIMARY KEY(record_id,uid),
 KEY idx_club_score_member(uid,record_id),
 KEY idx_club_score_partner(partner_uid,record_id,uid),
 FOREIGN KEY(record_id) REFERENCES club_game_record(id),
 FOREIGN KEY(uid) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
