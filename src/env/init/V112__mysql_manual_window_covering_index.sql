-- Only the known three-column cursor index may be upgraded; an exact covering index is already sufficient.
-- Do not replace a missing or drifted index: its definition requires independent review.
SET @v112_cursor_index_columns = (
    SELECT CASE WHEN COUNT(*) IN (3, 4)
        AND COUNT(DISTINCT SEQ_IN_INDEX) = COUNT(*)
        AND MAX(SEQ_IN_INDEX) = COUNT(*)
        AND COALESCE(SUM(CASE
            WHEN ((SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'building_id')
               OR (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'start_inclusive')
               OR (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'fact_id')
               OR (SEQ_IN_INDEX = 4 AND COLUMN_NAME = 'end_exclusive'))
             AND NON_UNIQUE = 1
             AND INDEX_TYPE = 'BTREE'
             AND COLLATION = 'A'
             AND SUB_PART IS NULL
             AND IS_VISIBLE = 'YES'
             AND EXPRESSION IS NULL
            THEN 1 ELSE 0 END), 0) = COUNT(*)
        THEN COUNT(*) ELSE 0 END
    FROM information_schema.statistics
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'biz_energy_manual_reading'
      AND INDEX_NAME = 'idx_energy_manual_cursor'
);

SET @v112_previous_lock_wait_timeout = @@SESSION.lock_wait_timeout;
SET SESSION lock_wait_timeout = 5;
SET @v112_cursor_index_ddl = CASE @v112_cursor_index_columns
    WHEN 4 THEN 'SELECT 1'
    WHEN 3 THEN 'ALTER TABLE biz_energy_manual_reading DROP INDEX idx_energy_manual_cursor, ADD INDEX idx_energy_manual_cursor (building_id, start_inclusive, fact_id, end_exclusive), ALGORITHM=INPLACE, LOCK=NONE'
    ELSE 'SELECT v112_guard.incompatible_cursor_index FROM (SELECT 1 AS expected_definition) AS v112_guard'
END;
-- The drift branch deliberately references a nonexistent derived-table column: PREPARE fails read-only,
-- without routines, additional privileges, or accidental DDL. No COPY/locking fallback is permitted.
-- MySQL 8 performs the exact three-column replacement as one atomic online ALTER; restore timeout on success.
PREPARE v112_cursor_index_statement FROM @v112_cursor_index_ddl;
EXECUTE v112_cursor_index_statement;
DEALLOCATE PREPARE v112_cursor_index_statement;
SET SESSION lock_wait_timeout = @v112_previous_lock_wait_timeout;
SET @v112_cursor_index_columns = NULL;
SET @v112_cursor_index_ddl = NULL;
SET @v112_previous_lock_wait_timeout = NULL;
