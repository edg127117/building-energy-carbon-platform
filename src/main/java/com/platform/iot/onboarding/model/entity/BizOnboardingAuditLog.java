package com.platform.iot.onboarding.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_onboarding_audit_log")
/**
 * 设备接入管理操作的脱敏审计事实。
 *
 * <p>摘要仅保存状态、归属和对象标识等业务变化，不保存设备完整样例、密码或 Token。</p>
 */
public class BizOnboardingAuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private String auditId;
    private String buildingId;
    private String actorType;
    private Long operatorId;
    private String actionType;
    private String objectType;
    private String objectId;
    private String versionId;
    private String reviewRequestId;
    private String beforeSummary;
    private String afterSummary;
    private String result;
    private String reasonCode;
    private String traceId;
    private String environmentMode;
    private Boolean selfApprovalDevMode;
    private LocalDateTime operationTime;
}
