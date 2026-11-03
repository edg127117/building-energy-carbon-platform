-- 月度分页、测点封账和修订排除各自按实际查询键建索引，避免完整月份反复扫描全部人工来源。
CREATE INDEX idx_energy_manual_cursor ON biz_energy_manual_reading (building_id, start_inclusive, fact_id);
CREATE INDEX idx_energy_manual_point_period ON biz_energy_manual_reading (building_id, point_id, start_inclusive, end_exclusive);
CREATE INDEX idx_energy_manual_supersedes ON biz_energy_manual_reading (building_id, supersedes_fact_id, batch_id);
