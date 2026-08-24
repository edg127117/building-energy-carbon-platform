package com.platform.iot.collection.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_collection_policy_version")
/** 不可变发布事实及其草稿；发布后只有生命周期时间和退役状态允许由状态机更新。 */
public class BizCollectionPolicyVersion {
    @TableId(type = IdType.INPUT)
    private String versionId;
    private String policyId;
    private Integer versionNo;
    private String status;
    private Integer enabledFlag;
    private Integer expectedIntervalSeconds;
    private Integer allowedDelaySeconds;
    private String timeSemantics;
    private String rawRetentionMode;
    private Integer rawRetentionDays;
    private String minuteRetentionMode;
    private Integer minuteRetentionDays;
    private String sourceCodeSnapshot;
    private String sourcePointCodeSnapshot;
    private String pointIdSnapshot;
    private String pointCodeSnapshot;
    private String dataTypeSnapshot;
    private String unitSnapshot;
    private String changeType;
    private String changeSource;
    private String changeReason;
    private String copiedFromVersionId;
    private Integer revision;
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private LocalDateTime retiredAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
