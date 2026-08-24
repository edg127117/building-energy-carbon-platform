package com.platform.iot.collection.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_collection_config_audit_log")
/** 脱敏成功审计；不持有业务外键，因此草稿删除后审计事实仍被保留。 */
public class BizCollectionConfigAuditLog {
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
    private LocalDateTime operationTime;
}
