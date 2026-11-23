CREATE TABLE biz_comfort_emission_factor_history (
    building_id VARCHAR(32) NOT NULL,
    space_id VARCHAR(32) NOT NULL,
    effective_at BIGINT NOT NULL,
    factor DECIMAL(6,3),
    PRIMARY KEY (building_id, space_id, effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 已保存的值从迁移时刻开始有依据，不能回填为过去用过的因子。
INSERT INTO biz_comfort_emission_factor_history (building_id,space_id,effective_at,factor)
SELECT building_id,space_id,CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED),parameter_value
FROM biz_comfort_manual_parameter WHERE parameter_key='dynamicEmissionFactor';
