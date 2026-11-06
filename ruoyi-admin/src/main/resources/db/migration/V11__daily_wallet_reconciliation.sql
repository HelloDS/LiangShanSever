CREATE TABLE wallet_reconciliation_run (
 id CHAR(36) NOT NULL PRIMARY KEY,
 scheduled_date DATE NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'READY',
 algorithm_version VARCHAR(64) NOT NULL DEFAULT 'opening-ledger-v1',
 personal_highwater BIGINT NOT NULL,
 club_highwater BIGINT NOT NULL,
 cursor_kind INT NOT NULL DEFAULT 0,
 cursor_owner BIGINT NOT NULL DEFAULT 0,
 cursor_currency VARCHAR(24) NOT NULL DEFAULT '',
 checked_count BIGINT NOT NULL DEFAULT 0,
 matched_count BIGINT NOT NULL DEFAULT 0,
 mismatched_count BIGINT NOT NULL DEFAULT 0,
 attempt_count INT NOT NULL DEFAULT 0,
 total_claims BIGINT NOT NULL DEFAULT 0,
 failure_count BIGINT NOT NULL DEFAULT 0,
 manual_retries INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 retry_at DATETIME(6) NOT NULL,
 last_error_code VARCHAR(64) NULL,
 origin VARCHAR(16) NOT NULL,
 created_by BIGINT NULL,
 created_at DATETIME(6) NOT NULL,
 started_at DATETIME(6) NULL,
 last_checked_at DATETIME(6) NULL,
 completed_at DATETIME(6) NULL,
 UNIQUE KEY uk_daily_wallet_reconciliation(scheduled_date),
 KEY idx_wallet_reconciliation_ready(status,retry_at,scheduled_date,id),
 CONSTRAINT ck_reconciliation_status CHECK(status IN ('READY','RUNNING','DONE','DONE_WITH_ISSUES','FAILED')),
 CONSTRAINT ck_reconciliation_cursor CHECK(cursor_kind BETWEEN 0 AND 3 AND cursor_owner>=0),
 CONSTRAINT ck_reconciliation_counts CHECK(checked_count=matched_count+mismatched_count AND matched_count>=0 AND mismatched_count>=0),
 CONSTRAINT ck_reconciliation_attempts CHECK(attempt_count BETWEEN 0 AND 5 AND total_claims>=0 AND failure_count>=0 AND manual_retries>=0),
 CONSTRAINT ck_reconciliation_origin CHECK((origin='SYSTEM' AND created_by IS NULL) OR (origin='ADMIN' AND created_by IS NOT NULL)),
 CONSTRAINT ck_reconciliation_complete CHECK(status NOT IN ('DONE','DONE_WITH_ISSUES') OR completed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wallet_reconciliation_detail (
 run_id CHAR(36) NOT NULL,
 owner_type VARCHAR(16) NOT NULL,
 owner_id BIGINT NOT NULL,
 currency VARCHAR(24) NOT NULL,
 status VARCHAR(16) NOT NULL,
 result_json JSON NOT NULL,
 checked_at DATETIME(6) NOT NULL,
 PRIMARY KEY(run_id,owner_type,owner_id,currency),
 KEY idx_wallet_reconciliation_issues(run_id,status,owner_type,owner_id,currency),
 FOREIGN KEY(run_id) REFERENCES wallet_reconciliation_run(id),
 CONSTRAINT ck_reconciliation_detail_status CHECK(status IN ('MATCH','MISMATCH')),
 CONSTRAINT ck_reconciliation_detail_type CHECK(owner_type IN ('PERSONAL','CLUB','SYSTEM')),
 CONSTRAINT ck_reconciliation_detail_currency CHECK(currency IN ('card','diamond'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES
(3009,'账本核对',3000,9,'reconciliation','game/reconciliation/index',1,0,'C','0','0','game:reconciliation:query','documentation','admin',UTC_TIMESTAMP()),
(3029,'创建本日核对',3009,1,'#','',1,0,'F','0','0','game:reconciliation:run','#','admin',UTC_TIMESTAMP()),
(3030,'重试失败核对',3009,2,'#','',1,0,'F','0','0','game:reconciliation:retry','#','admin',UTC_TIMESTAMP());
