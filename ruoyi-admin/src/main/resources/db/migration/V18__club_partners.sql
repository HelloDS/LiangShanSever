CREATE TABLE club_partner (
 club_id BIGINT NOT NULL,
 uid BIGINT NOT NULL,
 has_review_auth BOOLEAN NOT NULL DEFAULT FALSE,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(club_id,uid),
 FOREIGN KEY(club_id,uid) REFERENCES club_member(club_id,uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_partner_member (
 club_id BIGINT NOT NULL,
 member_uid BIGINT NOT NULL,
 partner_uid BIGINT NOT NULL,
 attached_at DATETIME(6) NOT NULL,
 PRIMARY KEY(club_id,member_uid),
 KEY idx_partner_team(club_id,partner_uid,member_uid),
 FOREIGN KEY(club_id,member_uid) REFERENCES club_member(club_id,uid),
 FOREIGN KEY(club_id,partner_uid) REFERENCES club_partner(club_id,uid),
 CONSTRAINT ck_partner_not_self CHECK(member_uid<>partner_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE club_partner_stats_floor (
 club_id BIGINT NOT NULL,
 partner_uid BIGINT NOT NULL,
 member_uid BIGINT NOT NULL,
 visible_after DATETIME(6) NOT NULL,
 PRIMARY KEY(club_id,partner_uid,member_uid),
 FOREIGN KEY(club_id) REFERENCES club(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
