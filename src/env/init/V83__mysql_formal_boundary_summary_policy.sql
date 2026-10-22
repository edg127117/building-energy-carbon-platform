-- 正式原生封账进入碳核算时仍要求独立审核的正式汇总口径；不升级已有研发策略。
ALTER TABLE biz_energy_boundary_summary_policy_version DROP CHECK chk_energy_boundary_summary_source;
ALTER TABLE biz_energy_boundary_summary_policy_version ADD CONSTRAINT chk_energy_boundary_summary_source
 CHECK (source_type IN ('SIMULATION','FORMAL'));
