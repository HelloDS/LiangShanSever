-- 既有记录保留 NULL，不用时间或目标猜测它属于哪个命令。
-- 请求键是受 ASCII 校验的不透明标识，与 Java 的精确字符串语义一致。
ALTER TABLE admin_operation MODIFY request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

ALTER TABLE admin_audit
 ADD COLUMN operation_id CHAR(36) NULL,
 ADD UNIQUE KEY uk_audit_operation(operation_id),
 ADD KEY idx_audit_created(created_at,id),
 ADD CONSTRAINT fk_audit_operation FOREIGN KEY(operation_id) REFERENCES admin_operation(operation_id);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES(3031,'业务审计',3000,10,'audit','game/audit/index',1,0,'C','0','0','game:audit:query','log','admin',UTC_TIMESTAMP());
