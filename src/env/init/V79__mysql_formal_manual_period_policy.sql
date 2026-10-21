-- 正式人工来源须另行建立并独立审核周期口径；不升级已有研发口径或历史结果。
ALTER TABLE biz_energy_period_policy_version DROP CHECK chk_energy_period_policy_source;
ALTER TABLE biz_energy_period_policy_version ADD CONSTRAINT chk_energy_period_policy_source
 CHECK (source_type IN ('SIMULATION','FORMAL'));
