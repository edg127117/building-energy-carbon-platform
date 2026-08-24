package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_quality_usage_policy_version")
/** 草稿与不可变正式版本共用一张表；发布后只允许生命周期字段由状态机改变。 */
public class BizQualityUsagePolicyVersion {
    @TableId(type = IdType.INPUT)
    private String versionId;
    private String policyId;
    private String changeSetId;
    private Integer versionNo;
    private String status;
    private String baseActiveVersionId;
    private String copiedFromVersionId;
    private Long effectiveFromMs;
    private Long effectiveToMs;
    private Integer initialBaseline;
    private Long publishedConfigRevision;
    private String changeSource;
    private String changeReason;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime retiredAt;
    private LocalDateTime updateTime;
}
