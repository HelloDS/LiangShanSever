CREATE TABLE admin_data_scope (
 principal_id BIGINT NOT NULL,
 scope_type VARCHAR(24) NOT NULL,
 scope_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(principal_id,scope_type,scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_operation (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 action VARCHAR(64) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 target VARCHAR(64) NOT NULL,
 reason VARCHAR(512) NOT NULL,
 status VARCHAR(24) NOT NULL,
 result_json JSON NULL,
 created_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 UNIQUE KEY uk_operation_actor_key(actor_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO admin_data_scope VALUES(1,'PLATFORM','*');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES
(3000,'游戏运营',0,0,'game',NULL,1,0,'M','0','0','','peoples','admin',UTC_TIMESTAMP()),
(3001,'玩家管理',3000,1,'players','game/players/index',1,0,'C','0','0','game:player:list','user','admin',UTC_TIMESTAMP()),
(3002,'在线玩家',3000,2,'online','game/online/index',1,0,'C','0','0','game:online:list','online','admin',UTC_TIMESTAMP()),
(3003,'大厅公告',3000,3,'notices','game/notices/index',1,0,'C','0','0','game:notice:list','message','admin',UTC_TIMESTAMP()),
(3010,'玩家详情',3001,1,'#','',1,0,'F','0','0','game:player:query','#','admin',UTC_TIMESTAMP()),
(3011,'封禁与解禁',3001,2,'#','',1,0,'F','0','0','game:player:ban','#','admin',UTC_TIMESTAMP()),
(3012,'强制下线',3001,3,'#','',1,0,'F','0','0','game:player:kick','#','admin',UTC_TIMESTAMP()),
(3013,'运营看板',3000,0,'#','',1,0,'F','0','0','game:dashboard:query','#','admin',UTC_TIMESTAMP()),
(3014,'公告草稿',3003,1,'#','',1,0,'F','0','0','game:notice:edit','#','admin',UTC_TIMESTAMP()),
(3015,'发布公告',3003,2,'#','',1,0,'F','0','0','game:notice:publish','#','admin',UTC_TIMESTAMP());

INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,menu_check_strictly,dept_check_strictly,status,del_flag,create_by,create_time)
VALUES
(10,'运营','game_operator',10,'1',1,1,'0','0','admin',UTC_TIMESTAMP()),
(11,'客服','game_support',11,'1',1,1,'0','0','admin',UTC_TIMESTAMP()),
(12,'财务','game_finance',12,'1',1,1,'0','0','admin',UTC_TIMESTAMP()),
(13,'技术运维','game_technical',13,'1',1,1,'0','0','admin',UTC_TIMESTAMP()),
(14,'只读审计','game_auditor',14,'1',1,1,'0','0','admin',UTC_TIMESTAMP());
