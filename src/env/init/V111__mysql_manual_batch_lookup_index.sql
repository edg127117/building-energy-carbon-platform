-- 批次审核按建筑和批次定位，并按事实 ID 排序；索引不包含来源正文，也不改变审核状态。
-- 已有同名索引只有定义完全一致才跳过；不一致仍执行 ADD，由重复索引名错误中止，禁止替换。
SET @v111_batch_index_matches = (
    SELECT COUNT(*) = 3
       AND COALESCE(SUM(CASE
           WHEN ((SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'building_id')
              OR (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'batch_id')
              OR (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'fact_id'))
            AND NON_UNIQUE = 1
            AND INDEX_TYPE = 'BTREE'
            AND COLLATION = 'A'
            AND SUB_PART IS NULL
            AND IS_VISIBLE = 'YES'
            AND EXPRESSION IS NULL
           THEN 1 ELSE 0 END), 0) = 3
    FROM information_schema.statistics
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'biz_energy_manual_reading'
      AND INDEX_NAME = 'idx_energy_manual_batch_fact'
);

SET @v111_previous_lock_wait_timeout = @@SESSION.lock_wait_timeout;
SET SESSION lock_wait_timeout = 5;
SET @v111_batch_index_ddl = IF(
    @v111_batch_index_matches,
    'SELECT 1',
    'ALTER TABLE biz_energy_manual_reading ADD INDEX idx_energy_manual_batch_fact (building_id, batch_id, fact_id), ALGORITHM=INPLACE, LOCK=NONE'
);
-- 不支持在线添加时必须失败，不能退回表复制或扩大锁定范围；成功后恢复迁移连接原有超时。
PREPARE v111_batch_index_statement FROM @v111_batch_index_ddl;
EXECUTE v111_batch_index_statement;
DEALLOCATE PREPARE v111_batch_index_statement;
SET SESSION lock_wait_timeout = @v111_previous_lock_wait_timeout;
SET @v111_batch_index_matches = NULL;
SET @v111_batch_index_ddl = NULL;
SET @v111_previous_lock_wait_timeout = NULL;
