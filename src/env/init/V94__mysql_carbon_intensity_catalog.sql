-- 只追加集中目录；不建立逐栋阈值、面积或审批数据。日期未知不等同于无限有效。
ALTER TABLE building ADD COLUMN carbon_evaluation_category VARCHAR(30) NULL,
    ADD COLUMN carbon_evaluation_subcategory VARCHAR(60) NULL;

CREATE TABLE biz_carbon_intensity_standard_version (
    version_id VARCHAR(32) NOT NULL PRIMARY KEY,
    catalog_id VARCHAR(32) NOT NULL,
    revision_no INT NOT NULL,
    standard_name VARCHAR(200) NOT NULL,
    document_version VARCHAR(100) NOT NULL,
    source_reference VARCHAR(1000) NOT NULL,
    published_on DATE NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    evidence_hash CHAR(64) NOT NULL,
    UNIQUE KEY uk_intensity_catalog_revision (catalog_id, revision_no),
    CONSTRAINT chk_intensity_revision CHECK (revision_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE biz_carbon_intensity_standard_entry (
    version_id VARCHAR(32) NOT NULL,
    entry_no INT NOT NULL,
    region_prefix VARCHAR(6) NOT NULL,
    building_category VARCHAR(30) NOT NULL,
    time_status VARCHAR(16) NOT NULL,
    applicable_from DATE NULL,
    applicable_to DATE NULL,
    rule_json JSON NOT NULL,
    PRIMARY KEY (version_id,entry_no),
    CONSTRAINT fk_intensity_entry_version FOREIGN KEY (version_id) REFERENCES biz_carbon_intensity_standard_version(version_id),
    CONSTRAINT chk_intensity_time CHECK (
        (time_status='KNOWN' AND applicable_from IS NOT NULL AND (applicable_to IS NULL OR applicable_to>applicable_from))
        OR (time_status IN ('NOT_STATED','PENDING') AND applicable_from IS NULL AND applicable_to IS NULL)
    ),
    INDEX idx_intensity_region_category (region_prefix,building_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 官方表4.1.2及3.0.2核对到的六市I区办公参考。核算适用年未确认，不推定为2026或追溯历史。
INSERT INTO biz_carbon_intensity_standard_version
    (version_id,catalog_id,revision_no,standard_name,document_version,source_reference,published_on,created_by,evidence_hash)
VALUES ('JS_PUBLIC_202604_V1','JS_PUBLIC_BUILDING',1,'江苏省公共建筑用能和碳排放限额指南（试行）','2026年4月',
    'https://jsszfhcxjst.jiangsu.gov.cn/module/download/downfile.jsp?classid=0&filename=8c7877cb352b4a78904adeff0bd016dd.pdf#page=10',
    '2026-04-27',0,'898bebe61dacecea3c1ede1994131cdeaea1fff82d1e05c6a8eb2eda821de5e6');

INSERT INTO biz_carbon_intensity_standard_entry
    (version_id,entry_no,region_prefix,building_category,time_status,applicable_from,applicable_to,rule_json)
SELECT 'JS_PUBLIC_202604_V1',cities.entry_no,cities.region_prefix,'办公','PENDING',NULL,NULL,
    JSON_OBJECT('regionPrefix',cities.region_prefix,'buildingCategory','办公','subcategory',NULL,'climateLabel','夏热冬冷Ⅰ区',
        'constraintValue','56.71','baselineValue','35.56','guideValue','20.72','gasBasis','CO2_ONLY',
        'boundaryCode','STANDARD_SPECIFIC','areaBasis','BUILDING_AREA',
        'boundaryNote','指南3.0.1：运行阶段外部输入能源，包含规定的交通、外送能源及外景照明等扣除边界；不能仅凭全建筑账单默认相同',
        'areaNote','指南2.0.8、3.0.6规定的建筑面积',
        'timeStatus','PENDING','applicableFrom',NULL,'applicableTo',NULL)
FROM (
    SELECT 0 entry_no,'3201' region_prefix UNION ALL SELECT 1,'3202' UNION ALL SELECT 2,'3204'
    UNION ALL SELECT 3,'3205' UNION ALL SELECT 4,'3206' UNION ALL SELECT 5,'3211'
) cities;
