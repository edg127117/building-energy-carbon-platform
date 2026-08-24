package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_quality_usage_scenario")
/** 系统受控的质量事实消费场景目录；普通治理 API 只能读取其状态。 */
public class BizQualityUsageScenario {
    @TableId(type = IdType.INPUT)
    private String scenarioId;
    private String scenarioCode;
    private String scenarioName;
    private String adapterType;
    private String status;
    private String introducedVersion;
    private LocalDateTime createTime;
    private LocalDateTime enabledAt;
    private LocalDateTime disabledAt;
    private String statusReason;
}
