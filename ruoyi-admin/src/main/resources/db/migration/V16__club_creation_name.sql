ALTER TABLE club
 ADD COLUMN created_name VARCHAR(8) NULL AFTER name,
 ADD UNIQUE KEY uk_club_created_name(owner_uid,created_name);
