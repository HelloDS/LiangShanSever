CREATE TABLE admin_export_gate(id INT NOT NULL PRIMARY KEY);
INSERT INTO admin_export_gate VALUES(1);
CREATE TABLE room_export_job (
 id CHAR(36) NOT NULL PRIMARY KEY,
 actor BIGINT NOT NULL,
 filter_json JSON NOT NULL,
 scope_hash CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'READY',
 attempts INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 expires_at DATETIME(6) NULL,
 row_count INT NOT NULL DEFAULT 0,
 file_bytes INT NOT NULL DEFAULT 0,
 last_error_code VARCHAR(40) NULL,
 KEY idx_export_actor(actor,created_at,id),
 KEY idx_export_ready(status,created_at,id),
 KEY idx_export_created(created_at),
 CONSTRAINT ck_export_status CHECK(status IN ('READY','RUNNING','DONE','FAILED','EXPIRED')),
 CONSTRAINT ck_export_lease CHECK(status<>'RUNNING' OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
 CONSTRAINT ck_export_bounds CHECK(attempts BETWEEN 0 AND 3 AND row_count BETWEEN 0 AND 10000 AND file_bytes BETWEEN 0 AND 8388608),
 CONSTRAINT ck_export_done CHECK(status<>'DONE' OR (completed_at IS NOT NULL AND expires_at IS NOT NULL AND lease_token IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES(3032,'导出房间记录',3008,1,'#','',1,0,'F','0','0','game:room:export','#','admin',UTC_TIMESTAMP());
