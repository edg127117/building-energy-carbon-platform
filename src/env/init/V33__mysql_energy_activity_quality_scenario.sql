-- 为多能源活动数据归集增加独立质量使用场景。
-- 迁移只发布场景目录，不为任何测点猜测 Q1/Q2 规则；没有正式策略时运行解析器默认仅允许 Q0。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_platform`;

START TRANSACTION;

INSERT INTO `biz_quality_usage_scenario`
    (`scenario_id`,`scenario_code`,`scenario_name`,`adapter_type`,`status`,
     `introduced_version`,`enabled_at`,`status_reason`)
VALUES
    ('QUS_SCENARIO_ENERGY_ACTIVITY_V1','ENERGY_ACTIVITY_AGGREGATION',
     '能源活动数据归集','ENERGY_ACTIVITY_INPUT_GATE','ENABLED',
     'ENERGY_ACTIVITY_V1',CURRENT_TIMESTAMP(3),'未发布测点策略时默认仅允许Q0');

UPDATE `biz_quality_usage_config_revision`
SET `config_revision` = `config_revision` + 1,
    `last_change_summary` = 'ADD_ENERGY_ACTIVITY_AGGREGATION_DEFAULT_Q0';

COMMIT;
