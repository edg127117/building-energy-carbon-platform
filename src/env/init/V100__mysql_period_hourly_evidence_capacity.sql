-- 整月小时来源需保留逐条事实及版本证据，不能为适配 TEXT 的 64 KiB 限制而截断。
-- 当前投影与封账快照采用相同容量，既有内容、哈希和审核语义保持不变。
ALTER TABLE biz_energy_period_result_current
    MODIFY COLUMN evidence_json MEDIUMTEXT NOT NULL;

ALTER TABLE biz_energy_period_result_snapshot
    MODIFY COLUMN evidence_json MEDIUMTEXT NOT NULL;
