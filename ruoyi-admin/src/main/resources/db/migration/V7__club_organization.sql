CREATE TABLE club (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(8) NOT NULL,
 owner_uid BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 member_count INT NOT NULL DEFAULT 1,
 max_members INT NOT NULL,
 max_managers INT NULL,
 only_manager_open_room BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 is_test BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_club_name(name),
 KEY idx_club_owner(owner_uid,status),
 KEY idx_club_status_members(status,member_count,id),
 CONSTRAINT ck_club_count CHECK(max_members BETWEEN 1 AND 10000 AND member_count>=0 AND member_count<=max_members),
 CONSTRAINT ck_club_managers CHECK(max_managers IS NULL OR max_managers>=0),
 FOREIGN KEY(owner_uid) REFERENCES player_role(uid)
) ENGINE=InnoDB AUTO_INCREMENT=100001 DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_member (
 club_id BIGINT NOT NULL,
 uid BIGINT NOT NULL,
 member_role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
 joined_at DATETIME(6) NOT NULL,
 PRIMARY KEY(club_id,uid),
 KEY idx_club_member_uid(uid,club_id),
 FOREIGN KEY(club_id) REFERENCES club(id),
 FOREIGN KEY(uid) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_application (
 club_id BIGINT NOT NULL,
 uid BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 applied_at DATETIME(6) NOT NULL,
 reviewed_by BIGINT NULL,
 reviewed_by_type VARCHAR(16) NULL,
 reviewed_at DATETIME(6) NULL,
 PRIMARY KEY(club_id,uid,kind),
 KEY idx_club_application_queue(club_id,kind,status,applied_at,uid),
 FOREIGN KEY(club_id) REFERENCES club(id),
 FOREIGN KEY(uid) REFERENCES player_role(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_wallet_account (
 club_id BIGINT NOT NULL,
 currency VARCHAR(24) NOT NULL,
 balance BIGINT NOT NULL DEFAULT 0,
 frozen BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(club_id,currency),
 CONSTRAINT ck_club_wallet CHECK(balance>=0 AND frozen>=0),
 FOREIGN KEY(club_id) REFERENCES club(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 club_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 actor_type VARCHAR(16) NOT NULL,
 target_uid BIGINT NULL,
 action VARCHAR(48) NOT NULL,
 details JSON NOT NULL,
 created_at DATETIME(6) NOT NULL,
 KEY idx_club_event(club_id,created_at,id),
 FOREIGN KEY(club_id) REFERENCES club(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 uid BIGINT NOT NULL,
 club_id BIGINT NOT NULL,
 club_name VARCHAR(8) NOT NULL,
 kind VARCHAR(24) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 read_at DATETIME(6) NULL,
 KEY idx_club_message(uid,created_at,id),
 FOREIGN KEY(uid) REFERENCES player_role(uid),
 FOREIGN KEY(club_id) REFERENCES club(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES
(3005,'亲友圈组织',3000,5,'clubs','game/clubs/index',1,0,'C','0','0','game:club:list','peoples','admin',UTC_TIMESTAMP()),
(3020,'圈详情与成员',3005,1,'#','',1,0,'F','0','0','game:club:query','#','admin',UTC_TIMESTAMP()),
(3021,'圈申请审批',3005,2,'#','',1,0,'F','0','0','game:club:review','#','admin',UTC_TIMESTAMP()),
(3022,'圈成员移除',3005,3,'#','',1,0,'F','0','0','game:club:member','#','admin',UTC_TIMESTAMP()),
(3023,'圈管理员权限',3005,4,'#','',1,0,'F','0','0','game:club:role','#','admin',UTC_TIMESTAMP()),
(3024,'圈配置',3005,5,'#','',1,0,'F','0','0','game:club:config','#','admin',UTC_TIMESTAMP()),
(3025,'停用与恢复圈',3005,6,'#','',1,0,'F','0','0','game:club:status','#','admin',UTC_TIMESTAMP());
