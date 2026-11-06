-- 积压统计只需状态/发生时间，不回表扫描包含载荷的大量已完成事件。
CREATE INDEX idx_outbox_backlog ON outbox_event(status,occurred_at);
