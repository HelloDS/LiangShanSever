ALTER TABLE wallet_account ADD COLUMN frozen BIGINT NOT NULL DEFAULT 0 AFTER balance,
 ADD CONSTRAINT ck_wallet_frozen CHECK(frozen>=0);

CREATE TABLE wallet_system_account (
 currency VARCHAR(24) NOT NULL PRIMARY KEY,
 balance BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO wallet_system_account(currency) VALUES('card'),('diamond');

-- 迁移覆盖起点是明确的余额快照，不伪造此前的历史流水。
CREATE TABLE wallet_opening_balance (
 owner_type VARCHAR(16) NOT NULL,
 owner_id BIGINT NOT NULL,
 currency VARCHAR(24) NOT NULL,
 balance BIGINT NOT NULL,
 frozen BIGINT NOT NULL,
 recorded_at DATETIME(6) NOT NULL,
 PRIMARY KEY(owner_type,owner_id,currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO wallet_opening_balance SELECT 'PERSONAL',uid,currency,balance,frozen,UTC_TIMESTAMP(6) FROM wallet_account;
INSERT INTO wallet_opening_balance SELECT 'CLUB',club_id,currency,balance,frozen,UTC_TIMESTAMP(6) FROM club_wallet_account;
INSERT INTO wallet_opening_balance SELECT 'SYSTEM',0,currency,balance,0,UTC_TIMESTAMP(6) FROM wallet_system_account;

CREATE TABLE wallet_transaction (
 id CHAR(36) NOT NULL PRIMARY KEY,
 business_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(32) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 rule_version VARCHAR(64) NOT NULL,
 reference_id VARCHAR(160) NOT NULL,
 status VARCHAR(16) NOT NULL,
 receipt_json JSON NULL,
 created_at DATETIME(6) NOT NULL,
 posted_at DATETIME(6) NULL,
 UNIQUE KEY uk_wallet_business_key(business_key),
 KEY idx_wallet_transaction_time(created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wallet_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 transaction_id CHAR(36) NOT NULL,
 owner_type VARCHAR(16) NOT NULL,
 owner_id BIGINT NOT NULL,
 currency VARCHAR(24) NOT NULL,
 available_delta BIGINT NOT NULL,
 frozen_delta BIGINT NOT NULL,
 balance_after BIGINT NOT NULL,
 frozen_after BIGINT NOT NULL,
 account_version BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_wallet_tx_account(transaction_id,owner_type,owner_id,currency),
 UNIQUE KEY uk_wallet_account_version(owner_type,owner_id,currency,account_version),
 KEY idx_wallet_ledger_account(owner_type,owner_id,currency,created_at,id),
 FOREIGN KEY(transaction_id) REFERENCES wallet_transaction(id),
 CONSTRAINT ck_wallet_ledger_effect CHECK(available_delta<>0 OR frozen_delta<>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wallet_reconciliation (
 id CHAR(36) NOT NULL PRIMARY KEY,
 owner_type VARCHAR(16) NOT NULL,
 owner_id BIGINT NOT NULL,
 currency VARCHAR(24) NOT NULL,
 status VARCHAR(16) NOT NULL,
 result_json JSON NOT NULL,
 checked_by BIGINT NOT NULL,
 checked_at DATETIME(6) NOT NULL,
 KEY idx_reconciliation_account(owner_type,owner_id,currency,checked_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES
(3006,'资产账本',3000,6,'wallets','game/wallets/index',1,0,'C','0','0','game:wallet:query','money','admin',UTC_TIMESTAMP()),
(3026,'余额核对',3006,1,'#','',1,0,'F','0','0','game:wallet:reconcile','#','admin',UTC_TIMESTAMP());
