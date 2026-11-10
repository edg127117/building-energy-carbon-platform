-- 不覆盖版本证据；仅迁移当前有效面积，不猜测已停用版本的撤销原因。
CREATE TABLE biz_carbon_area_applicability (
    applicability_id VARCHAR(32) NOT NULL PRIMARY KEY,
    denominator_version_id VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    CONSTRAINT fk_area_applicability_version FOREIGN KEY (denominator_version_id)
        REFERENCES biz_carbon_denominator_version(denominator_version_id),
    CONSTRAINT chk_area_applicability_window CHECK (effective_to IS NULL OR effective_to > effective_from),
    KEY idx_area_applicability_version (denominator_version_id,effective_from,effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='获批面积版本的实际适用期间';

INSERT INTO biz_carbon_area_applicability
SELECT REPLACE(UUID(),'-',''),v.denominator_version_id,v.effective_from,v.effective_to
FROM biz_carbon_denominator_version v JOIN biz_carbon_denominator d ON d.denominator_id=v.denominator_id
WHERE d.denominator_type='BUILDING_AREA' AND v.status='ACTIVE';
