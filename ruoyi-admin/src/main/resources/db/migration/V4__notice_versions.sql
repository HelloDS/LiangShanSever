CREATE TABLE config_version (
 config_key VARCHAR(100) NOT NULL,
 version BIGINT NOT NULL,
 payload JSON NOT NULL,
 status VARCHAR(16) NOT NULL,
 created_by BIGINT NOT NULL,
 published_by BIGINT NULL,
 created_at DATETIME(6) NOT NULL,
 published_at DATETIME(6) NULL,
 PRIMARY KEY(config_key,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE config_namespace (
 id VARCHAR(100) NOT NULL PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO config_namespace VALUES('notices',0);

ALTER TABLE game_notice ADD COLUMN image_link VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE game_notice ADD COLUMN click_link VARCHAR(512) NOT NULL DEFAULT '';
