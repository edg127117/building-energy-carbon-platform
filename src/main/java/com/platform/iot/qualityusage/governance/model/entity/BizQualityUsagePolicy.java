package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_quality_usage_policy")
/** 测点与消费场景组成的稳定策略身份，不把建筑级继承伪装成当前能力。 */
public class BizQualityUsagePolicy {
    @TableId(type = IdType.INPUT)
    private String policyId;
    private String buildingId;
    private String pointId;
    private String scenarioId;
    private String currentActiveVersionId;
    private String pendingReviewRequestId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
