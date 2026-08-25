package com.platform.iot.reliability.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_telemetry_receipt_failure")
/** 仅保存异常或重投明细，不复制完整遥测载荷。 */
public class TelemetryReceiptFailure {
    @TableId
    private String failureId;
    private String canonicalMessageId;
    private String buildingId;
    private String failureStage;
    private String failureCode;
    private String safeDetail;
    private LocalDateTime occurredAt;
}
