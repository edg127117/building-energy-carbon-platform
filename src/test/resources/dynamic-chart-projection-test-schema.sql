CREATE TABLE biz_dynamic_electricity_chart_projection (
 result_id VARCHAR(64) PRIMARY KEY,
 building_id VARCHAR(32) NOT NULL,
 grain VARCHAR(8) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 source_version VARCHAR(64) NOT NULL,
 source_kind VARCHAR(32) NOT NULL,
 evidence_hash VARCHAR(64) NOT NULL,
 chart_json CLOB NOT NULL,
 chart_hash VARCHAR(64) NOT NULL,
 last_verified_at BIGINT NOT NULL DEFAULT 0,
 integrity_error VARCHAR(64),
 created_at BIGINT NOT NULL
);
CREATE INDEX idx_dynamic_chart_window ON biz_dynamic_electricity_chart_projection(building_id,start_ms,end_ms);
CREATE INDEX idx_dynamic_chart_backfill ON biz_dynamic_electricity_chart_projection(created_at,result_id);
CREATE INDEX idx_dynamic_chart_audit ON biz_dynamic_electricity_chart_projection(integrity_error,last_verified_at,result_id);
