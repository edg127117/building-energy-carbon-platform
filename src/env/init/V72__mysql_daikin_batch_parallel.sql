-- 策略随批次冻结；历史两台重叠批次不自动改成并发发送。
ALTER TABLE biz_daikin_control_batch
 ADD COLUMN dispatch_policy VARCHAR(32) NOT NULL DEFAULT 'SERIAL_V1';
UPDATE biz_daikin_control_batch SET dispatch_policy='ACCEPTED_PAIR_V1' WHERE overlap_allowed=TRUE;
