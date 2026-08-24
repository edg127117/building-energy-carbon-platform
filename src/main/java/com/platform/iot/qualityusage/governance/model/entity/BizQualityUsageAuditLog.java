package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_quality_usage_audit_log")
/** 治理操作的脱敏审计和状态动作幂等证据，绝不记录实时门禁逐条决定。 */
public class BizQualityUsageAuditLog {
    @TableId(type = IdType.INPUT)
    private String auditId;
    private String buildingId;
    private String actorType;
    private Long operatorId;
    private String actionType;
    private String objectType;
    private String objectId;
    private String versionId;
    private String beforeSummary;
    private String afterSummary;
    private String result;
    private String reasonCode;
    private Long configRevision;
    private String idempotencyKey;
    private String requestSha256;
    private LocalDateTime operationTime;
}
