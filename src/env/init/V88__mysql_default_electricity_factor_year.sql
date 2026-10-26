-- 旧全国0.5703默认因子按已确认的2021/2022核算年治理，统计数据年不补造。
-- CHECK不能跨表核验身份和组件值；服务层另校验固定编码、CO2口径及570.3 kgCO2/MWh。
-- 仅修改约束，不回填年份、不改因子状态、不发布楼宇配置。
ALTER TABLE biz_carbon_factor_version
    DROP CHECK chk_carbon_electricity_years,
    ADD CONSTRAINT chk_carbon_electricity_years CHECK (
        (data_year IS NULL AND accounting_year IS NULL)
        OR (data_year IS NOT NULL AND accounting_year IS NOT NULL
            AND data_year BETWEEN 1900 AND 2200 AND accounting_year BETWEEN 2000 AND 2200)
        OR (data_year IS NULL AND accounting_year IN (2021,2022)
            AND applicability_level='NATIONAL' AND usage_nature='FORMAL' AND input_unit_code='MWH'
            AND YEAR(effective_from)=accounting_year AND effective_to IS NOT NULL
            AND effective_to <= CAST(CONCAT(accounting_year+1,'-01-01 00:00:00') AS DATETIME))
    );
