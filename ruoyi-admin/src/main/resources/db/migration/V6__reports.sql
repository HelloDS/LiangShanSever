CREATE TABLE user_report (
 id CHAR(36) NOT NULL PRIMARY KEY,
 reporter_uid BIGINT NOT NULL,
 report_type VARCHAR(32) NOT NULL,
 anonymous BOOLEAN NOT NULL,
 detail VARCHAR(2000) NOT NULL,
 phone_cipher TEXT NULL,
 wechat_cipher TEXT NULL,
 contact_mask VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 processed_by BIGINT NULL,
 process_note VARCHAR(512) NULL,
 created_at DATETIME(6) NOT NULL,
 processed_at DATETIME(6) NULL,
 KEY idx_report_status(status,created_at,id),
 KEY idx_report_actor(reporter_uid,created_at),
 FOREIGN KEY(reporter_uid) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES
(3004,'玩家举报',3000,4,'reports','game/reports/index',1,0,'C','0','0','game:report:list','message','admin',UTC_TIMESTAMP()),
(3016,'处理举报',3004,1,'#','',1,0,'F','0','0','game:report:process','#','admin',UTC_TIMESTAMP()),
(3017,'查看联系方式',3004,2,'#','',1,0,'F','0','0','game:report:contact','#','admin',UTC_TIMESTAMP());
