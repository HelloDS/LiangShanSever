CREATE TABLE club_default_room (
 club_id BIGINT NOT NULL PRIMARY KEY,
 game_id VARCHAR(4) NOT NULL,
 create_data JSON NOT NULL,
 auto_create BOOLEAN NOT NULL DEFAULT FALSE,
 updated_by BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(club_id) REFERENCES club(id),
 FOREIGN KEY(updated_by) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_code_sequence (
 id INT NOT NULL PRIMARY KEY,
 next_code INT NOT NULL,
 CONSTRAINT ck_room_code_sequence CHECK(next_code BETWEEN 100000 AND 999999)
) ENGINE=InnoDB;
INSERT INTO room_code_sequence(id,next_code) VALUES(1,100000);

ALTER TABLE room_record ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
