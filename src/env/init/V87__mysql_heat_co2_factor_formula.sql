-- 只注册代码支持的热力CO2质量公式；不创建排放因子或楼宇生效配置。
INSERT INTO biz_carbon_formula_version
 (formula_version_id,formula_code,version_no,algorithm_code,result_basis,gas_coverage,usage_nature,status,effective_from)
VALUES ('CFV_HEAT_CO2_V1','PURCHASED_HEAT_CO2',1,'PURCHASED_HEAT_CO2_V1','GAS_MASS','CO2_ONLY_HEAT','FORMAL','ACTIVE','2000-01-01');
