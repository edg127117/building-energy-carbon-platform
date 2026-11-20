-- 固定触发时的空间和对象类型；旧事件保留原始归属证据，查询时沿用兼容路径。
ALTER TABLE biz_alarm_event ADD COLUMN space_id VARCHAR(32) NULL;
ALTER TABLE biz_alarm_event ADD COLUMN object_type VARCHAR(16) NULL;
CREATE INDEX idx_alarm_event_space ON biz_alarm_event(building_id,space_id,status);
