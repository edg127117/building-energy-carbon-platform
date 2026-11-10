-- 游标排序与区间终点均在索引中，避免日查询为排除早期区间反复读取历史原文。
ALTER TABLE biz_energy_manual_reading
    DROP INDEX idx_energy_manual_cursor,
    ADD INDEX idx_energy_manual_cursor (building_id, start_inclusive, fact_id, end_exclusive);

-- 以可空修订目标为首列，使无修订的绝大多数来源可直接跳过，不再物化整楼来源。
ALTER TABLE biz_energy_manual_reading
    DROP INDEX idx_energy_manual_supersedes,
    ADD INDEX idx_energy_manual_supersedes (supersedes_fact_id, building_id, batch_id);
