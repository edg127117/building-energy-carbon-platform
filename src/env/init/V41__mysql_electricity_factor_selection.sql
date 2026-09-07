-- 电力因子年度证据、区域适用层级与目录录入回执；不预置业务因子或自动激活。
ALTER TABLE biz_carbon_factor_version
    ADD COLUMN data_year INT NULL,
    ADD COLUMN accounting_year INT NULL,
    DROP CHECK chk_carbon_factor_version_values,
    DROP CHECK chk_carbon_factor_version_region,
    ADD CONSTRAINT chk_carbon_factor_version_values CHECK
        (version_no > 0 AND config_revision >= 0 AND
         applicability_level IN ('BUILDING_SPECIFIC','PROVINCE','GRID_REGION','NATIONAL','NOT_REGION_SPECIFIC') AND
         usage_nature IN ('DEVELOPMENT_REFERENCE','FORMAL') AND
         status IN ('PENDING_REVIEW','APPROVED','ACTIVE','DISABLED','REJECTED') AND
         (effective_to IS NULL OR effective_to > effective_from)),
    ADD CONSTRAINT chk_carbon_factor_version_region CHECK
        ((applicability_level IN ('PROVINCE','GRID_REGION') AND region_code IS NOT NULL) OR
         (applicability_level NOT IN ('PROVINCE','GRID_REGION') AND region_code IS NULL)),
    ADD CONSTRAINT chk_carbon_electricity_years CHECK
        ((data_year IS NULL AND accounting_year IS NULL) OR
         (data_year IS NOT NULL AND accounting_year IS NOT NULL AND
          data_year BETWEEN 1900 AND 2200 AND accounting_year BETWEEN 2000 AND 2200));

CREATE TABLE biz_carbon_electricity_catalog_import (
    entry_code VARCHAR(100) NOT NULL,
    usage_nature VARCHAR(32) NOT NULL,
    factor_version_id VARCHAR(32) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (entry_code,usage_nature),
    UNIQUE KEY uk_carbon_electricity_import_version (factor_version_id),
    CONSTRAINT fk_carbon_electricity_import_factor FOREIGN KEY (factor_version_id)
        REFERENCES biz_carbon_factor_version (factor_version_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_carbon_electricity_import_nature CHECK
        (usage_nature IN ('DEVELOPMENT_REFERENCE','FORMAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官方电力平均因子候选录入回执';

INSERT INTO biz_carbon_formula_version
    (formula_version_id,formula_code,version_no,algorithm_code,result_basis,gas_coverage,
     usage_nature,status,effective_from)
VALUES ('CFV_ELECTRICITY_CO2_V1','PURCHASED_ELECTRICITY_LOCATION_CO2',1,
        'PURCHASED_ELECTRICITY_LOCATION_CO2_V1','GAS_MASS','CO2_ONLY_ELECTRICITY',
        'FORMAL','ACTIVE','2000-01-01');
