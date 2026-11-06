CREATE TABLE outbox_event (
 id CHAR(36) NOT NULL PRIMARY KEY,
 biz_key VARCHAR(160) NOT NULL,
 event_type VARCHAR(64) NOT NULL,
 schema_version INT NOT NULL,
 rule_version VARCHAR(64) NOT NULL,
 payload JSON NOT NULL,
 request_hash CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'READY',
 attempt_count INT NOT NULL DEFAULT 0,
 total_attempts BIGINT NOT NULL DEFAULT 0,
 manual_retries INT NOT NULL DEFAULT 0,
 retry_at DATETIME(6) NOT NULL,
 lease_token CHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 last_error_code VARCHAR(64) NULL,
 occurred_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 UNIQUE KEY uk_outbox_business(biz_key,event_type),
 KEY idx_outbox_ready(status,retry_at,id),
 KEY idx_outbox_lease(status,lease_until,id),
 KEY idx_outbox_time(occurred_at,id),
 CONSTRAINT ck_outbox_status CHECK(status IN ('READY','CLAIMED','DONE','DEAD')),
 CONSTRAINT ck_outbox_attempts CHECK(attempt_count>=0 AND total_attempts>=attempt_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES
(3007,'持久任务',3000,7,'outbox','game/outbox/index',1,0,'C','0','0','game:outbox:query','job','admin',UTC_TIMESTAMP()),
(3027,'重试失败任务',3007,1,'#','',1,0,'F','0','0','game:outbox:retry','#','admin',UTC_TIMESTAMP());
