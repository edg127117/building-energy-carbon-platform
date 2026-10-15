-- 保留既有占用；只有新建且明确允许重叠核验的批量启停可占用同控制器的第二个槽。
ALTER TABLE biz_daikin_control_batch
 ADD COLUMN overlap_allowed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE biz_daikin_control_controller_slot
 DROP PRIMARY KEY,
 ADD PRIMARY KEY (controller_key, operation_id);

ALTER TABLE biz_daikin_control_batch_lane
 DROP PRIMARY KEY,
 ADD PRIMARY KEY (controller_key, item_id);
