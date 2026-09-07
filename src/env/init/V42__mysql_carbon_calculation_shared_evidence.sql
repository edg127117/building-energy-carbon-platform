USE `iot_platform`;

-- 与明细在同一事务中固定重复规则，历史批次保持 NULL，不用当前规则回填。
ALTER TABLE `biz_carbon_calculation_batch`
    ADD COLUMN `shared_evidence_json` JSON NULL COMMENT '本批次按内容摘要保存的固定共享证据';
