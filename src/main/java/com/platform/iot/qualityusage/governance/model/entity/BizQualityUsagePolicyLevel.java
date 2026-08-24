package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("biz_quality_usage_policy_level")
/** 版本化显式允许集合；没有子行是受支持的“完全禁止”策略。 */
public class BizQualityUsagePolicyLevel {
    @TableId(type = IdType.INPUT)
    private String policyLevelId;
    private String versionId;
    private String qualityLevel;
}
