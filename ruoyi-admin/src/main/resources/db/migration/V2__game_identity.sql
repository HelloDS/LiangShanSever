CREATE TABLE user_account (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 provider VARCHAR(24) NOT NULL,
 external_id VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 is_test BOOLEAN NOT NULL DEFAULT FALSE,
 session_version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_account_provider (provider, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE guest_credential (
 id CHAR(36) NOT NULL PRIMARY KEY,
 account_id BIGINT NOT NULL,
 token_hash CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 expires_at DATETIME(6) NOT NULL,
 last_used_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 UNIQUE KEY uk_guest_hash (token_hash),
 KEY idx_guest_account (account_id,status),
 FOREIGN KEY (account_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE player_role (
 uid BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 account_id BIGINT NOT NULL,
 name VARCHAR(32) NOT NULL,
 head VARCHAR(512) NOT NULL DEFAULT '',
 sex INT NOT NULL DEFAULT 0,
 latitude DECIMAL(10,7) NULL,
 longitude DECIMAL(10,7) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL,
 last_login_at DATETIME(6) NULL,
 UNIQUE KEY uk_role_account (account_id),
 KEY idx_role_created (created_at,uid),
 FOREIGN KEY (account_id) REFERENCES user_account(id)
) ENGINE=InnoDB AUTO_INCREMENT=100001 DEFAULT CHARSET=utf8mb4;

CREATE TABLE wallet_account (
 uid BIGINT NOT NULL,
 currency VARCHAR(24) NOT NULL,
 balance BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(uid,currency),
 CONSTRAINT ck_wallet_balance CHECK(balance>=0),
 FOREIGN KEY(uid) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE player_login_event (
 event_id CHAR(36) NOT NULL PRIMARY KEY,
 uid BIGINT NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 business_date DATE NOT NULL,
 login_kind VARCHAR(16) NOT NULL,
 is_test BOOLEAN NOT NULL,
 KEY idx_login_date (business_date,uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE player_login_daily (
 business_date DATE NOT NULL,
 uid BIGINT NOT NULL,
 first_login_at DATETIME(6) NOT NULL,
 last_login_at DATETIME(6) NOT NULL,
 login_count INT NOT NULL,
 is_test BOOLEAN NOT NULL,
 PRIMARY KEY(business_date,uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operator_id BIGINT NOT NULL,
 action VARCHAR(64) NOT NULL,
 target VARCHAR(64) NOT NULL,
 reason VARCHAR(512) NOT NULL,
 before_json JSON NULL,
 after_json JSON NULL,
 created_at DATETIME(6) NOT NULL,
 KEY idx_audit_operator (operator_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE game_notice (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(100) NOT NULL,
 content TEXT NOT NULL,
 published BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
