-- 原生量完整与折标完整分开；不把已有部分封账升级为完整来源。
ALTER TABLE biz_energy_period_result_snapshot DROP CHECK chk_energy_period_snapshot_status;
ALTER TABLE biz_energy_period_result_snapshot ADD CONSTRAINT chk_energy_period_snapshot_status CHECK
 (status IN ('LOCKED_COMPLETE','LOCKED_NATIVE_COMPLETE','LOCKED_WITH_EXCEPTIONS','LOCKED_PARTIAL','SUPERSEDED','INVALIDATED'));
