-- 动态因子与监测结果独立于静态小时批次；电量仍引用原计量链路。
CREATE TABLE biz_dynamic_electricity_factor (
 factor_id CHAR(64) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 point_id VARCHAR(80) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 revision INT NOT NULL,
 import_id CHAR(64) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 payload_json MEDIUMTEXT NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 UNIQUE KEY uk_dynamic_factor_revision(building_id,point_id,start_ms,end_ms,revision),
 KEY idx_dynamic_factor_window(building_id,start_ms,end_ms),
 KEY idx_dynamic_factor_import(building_id,import_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每建筑一条LOCK行仅串行化发布与修订，其余行是不可变结果版本。
CREATE TABLE biz_dynamic_electricity_result (
 result_id CHAR(64) NOT NULL PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 grain VARCHAR(8) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 source_version CHAR(64) NOT NULL,
 payload_json MEDIUMTEXT NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE KEY uk_dynamic_result_source(building_id,grain,start_ms,end_ms,source_version),
 KEY idx_dynamic_result_window(building_id,start_ms,end_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
