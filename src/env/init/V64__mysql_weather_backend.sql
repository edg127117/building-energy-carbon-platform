-- 天气基础数据与轻量日量对照；无研究项目、证据归档或原始清理拦截。
CREATE TABLE biz_weather_location (
 building_id VARCHAR(32) PRIMARY KEY, current_version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_weather_binding_version (
 id VARCHAR(32) PRIMARY KEY, building_id VARCHAR(32) NOT NULL, version_no BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, latitude DECIMAL(10,7) NOT NULL, longitude DECIMAL(10,7) NOT NULL,
 city_code VARCHAR(9), coverage VARCHAR(16) NOT NULL, effective_from BIGINT NOT NULL,
 effective_to BIGINT, enabled BOOLEAN NOT NULL, actor_id BIGINT NOT NULL,
 idempotency_key VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_weather_binding_version(building_id,version_no),
 UNIQUE KEY uk_weather_binding_request(building_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_weather_job (
 id VARCHAR(32) PRIMARY KEY, kind VARCHAR(16) NOT NULL, building_id VARCHAR(32) NOT NULL,
 binding_id VARCHAR(32), source VARCHAR(24), product VARCHAR(32), start_day DATE NOT NULL, end_day DATE NOT NULL,
 system_id VARCHAR(32), point_id VARCHAR(32), actor_id BIGINT NOT NULL,
 idempotency_key VARCHAR(160) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL, attempts INT NOT NULL DEFAULT 0, fence BIGINT NOT NULL DEFAULT 0,
 lease_until BIGINT NOT NULL DEFAULT 0, next_attempt BIGINT NOT NULL, dataset_id VARCHAR(32),
 reason VARCHAR(100), created_at BIGINT NOT NULL,
 UNIQUE KEY uk_weather_job_request(actor_id,idempotency_key),
 KEY ix_weather_job_due(state,next_attempt), KEY ix_weather_job_building(building_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_weather_batch (
 id VARCHAR(32) PRIMARY KEY, job_id VARCHAR(32) NOT NULL, attempt_no INT NOT NULL,
 fetched_at BIGINT NOT NULL, state VARCHAR(24) NOT NULL, reason VARCHAR(100), dataset_id VARCHAR(32),
 UNIQUE KEY uk_weather_batch_attempt(job_id,attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_weather_dataset (
 id VARCHAR(32) PRIMARY KEY, binding_id VARCHAR(32) NOT NULL, source VARCHAR(24) NOT NULL,
 product VARCHAR(32) NOT NULL, fetched_at BIGINT NOT NULL, content_hash VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL, table_name VARCHAR(64) NOT NULL, payload MEDIUMBLOB NOT NULL,
 row_count INT NOT NULL, created_at BIGINT NOT NULL,
 KEY ix_weather_dataset_latest(binding_id,source,product,fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_weather_dataset_head (
 binding_id VARCHAR(32) NOT NULL, source VARCHAR(24) NOT NULL, product VARCHAR(32) NOT NULL,
 business_day DATE NOT NULL, dataset_id VARCHAR(32) NOT NULL, fetched_at BIGINT NOT NULL,
 PRIMARY KEY(binding_id,source,product,business_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE biz_energy_daily_comparison_result (
 building_id VARCHAR(32) NOT NULL, system_id VARCHAR(32) NOT NULL, point_id VARCHAR(32) NOT NULL,
 business_day DATE NOT NULL, result_json TEXT NOT NULL, calculated_at BIGINT NOT NULL,
 PRIMARY KEY(building_id,system_id,point_id,business_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 三条共享执行通道限制集群并发，并持久化源站退避时间。
CREATE TABLE biz_weather_lane (
 lane VARCHAR(24) PRIMARY KEY, job_id VARCHAR(32), lease_until BIGINT NOT NULL DEFAULT 0,
 next_allowed BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO biz_weather_lane(lane) VALUES ('OPEN_METEO'),('CHINA_WEATHER'),('ENERGY');
-- 补采命令的逻辑幂等边界，拆月后的多个 job 仍属于同一个请求。
CREATE TABLE biz_weather_command (
 actor_id BIGINT NOT NULL, idempotency_key VARCHAR(120) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, result_json MEDIUMTEXT,
 PRIMARY KEY(actor_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
