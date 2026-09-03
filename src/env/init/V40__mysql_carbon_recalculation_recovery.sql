-- 重算影响分析租约、归并等待和失败隔离关系；不修改历史结果或初始化业务参数。
ALTER TABLE biz_carbon_dependency_change
    ADD COLUMN lease_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME(3) NULL,
    ADD COLUMN requested_result_nature VARCHAR(32) NULL,
    ADD KEY idx_carbon_change_recovery (status,lease_until,next_attempt_at);

ALTER TABLE biz_carbon_recalculation_batch
    ADD COLUMN merge_key VARCHAR(64) NULL,
    ADD COLUMN merge_ready_at DATETIME(3) NULL,
    ADD COLUMN parent_recalculation_batch_id VARCHAR(32) NULL,
    ADD KEY idx_carbon_batch_merge (merge_key,status,scope_frozen),
    ADD CONSTRAINT fk_carbon_recalc_parent FOREIGN KEY (parent_recalculation_batch_id)
        REFERENCES biz_carbon_recalculation_batch (recalculation_batch_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;
