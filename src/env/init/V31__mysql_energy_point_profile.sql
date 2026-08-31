CREATE TABLE `biz_energy_point_profile` (
    `profile_id` VARCHAR(32) NOT NULL COMMENT '能源测点属性ID',
    `point_id` VARCHAR(32) NOT NULL COMMENT '标准测点ID',
    `building_id` VARCHAR(32) NOT NULL COMMENT '建筑权限范围',
    `energy_type` VARCHAR(32) NOT NULL COMMENT '能源类型',
    `energy_subtype` VARCHAR(32) DEFAULT NULL COMMENT '电力来源类型',
    `value_semantics` VARCHAR(32) NOT NULL COMMENT '瞬时、累计或期间合计',
    `reporting_period` VARCHAR(20) NOT NULL COMMENT '业务统计周期',
    `annual_summary` TINYINT(1) NOT NULL COMMENT '是否要求年度汇总',
    `confirmation_status` VARCHAR(20) NOT NULL COMMENT '专业确认状态',
    `evidence_reference` VARCHAR(500) NOT NULL COMMENT '配置依据引用',
    `config_revision` INT NOT NULL DEFAULT 0 COMMENT '乐观锁修订号',
    `create_by` BIGINT NOT NULL,
    `update_by` BIGINT NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`profile_id`),
    UNIQUE KEY `uk_energy_point_profile_point` (`point_id`),
    KEY `idx_energy_point_profile_building_status`
        (`building_id`, `confirmation_status`, `profile_id`),
    KEY `idx_energy_point_profile_type` (`energy_type`, `energy_subtype`),
    CONSTRAINT `chk_energy_point_profile_type`
        CHECK (`energy_type` IN ('ELECTRICITY','NATURAL_GAS','HEAT','COLD','FUEL')),
    CONSTRAINT `chk_energy_point_profile_subtype`
        CHECK ((`energy_type` = 'ELECTRICITY' AND (`energy_subtype` IS NULL OR
            `energy_subtype` IN ('GRID_PURCHASED','TRADED_PURCHASED',
                'DIRECT_RENEWABLE','SELF_GENERATED')))
            OR (`energy_type` <> 'ELECTRICITY' AND `energy_subtype` IS NULL)),
    CONSTRAINT `chk_energy_point_profile_semantics`
        CHECK (`value_semantics` IN ('INSTANTANEOUS','CUMULATIVE','PERIOD_TOTAL')),
    CONSTRAINT `chk_energy_point_profile_period` CHECK (`reporting_period` = 'MONTH'),
    CONSTRAINT `chk_energy_point_profile_annual` CHECK (`annual_summary` IN (0,1)),
    CONSTRAINT `chk_energy_point_profile_confirmation`
        CHECK (`confirmation_status` IN ('PENDING_EXPERT','CONFIRMED')),
    CONSTRAINT `chk_energy_point_profile_confirmed_electricity`
        CHECK (`confirmation_status` <> 'CONFIRMED' OR `energy_type` <> 'ELECTRICITY'
            OR `energy_subtype` IS NOT NULL),
    CONSTRAINT `chk_energy_point_profile_evidence`
        CHECK (CHAR_LENGTH(TRIM(`evidence_reference`)) > 0),
    CONSTRAINT `chk_energy_point_profile_revision` CHECK (`config_revision` >= 0),
    CONSTRAINT `fk_energy_point_profile_point_building`
        FOREIGN KEY (`point_id`, `building_id`)
        REFERENCES `biz_data_point` (`point_id`, `building_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_energy_point_profile_building`
        FOREIGN KEY (`building_id`) REFERENCES `building` (`building_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能源标准测点专业采集属性';
