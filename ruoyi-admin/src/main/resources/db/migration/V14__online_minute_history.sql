CREATE TABLE online_minute_history (
 sample_minute DATETIME NOT NULL PRIMARY KEY,
 observed_at DATETIME(6) NOT NULL,
 data_status VARCHAR(16) NOT NULL,
 online_count INT NULL,
 node_instance CHAR(36) NOT NULL,
 CONSTRAINT ck_online_history_state CHECK(data_status IN ('AVAILABLE','REBUILDING','UNAVAILABLE')),
 CONSTRAINT ck_online_history_count CHECK((data_status='AVAILABLE' AND online_count IS NOT NULL AND online_count BETWEEN 0 AND 10000) OR (data_status<>'AVAILABLE' AND online_count IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
