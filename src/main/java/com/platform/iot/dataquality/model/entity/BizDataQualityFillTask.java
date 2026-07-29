package com.platform.iot.dataquality.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MySQL 中一次数据补全的写入、重试与来源追溯批次。
 *
 * <p>任务通过 {@code taskId} 与 TDengine 分钟行关联。它记录跨库最终一致所需的
 * 证据和技术状态，但不承担管理员逐条批准、拒绝补全结果的职责。</p>
 */
@Data
@TableName("biz_data_quality_fill_task")
public class BizDataQualityFillTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String taskId;
    private String idempotencyKey;
    private String buildingId;
    private String pointId;
    private LocalDateTime startMinute;
    private LocalDateTime endMinute;
    private Integer minuteCount;
    private Integer dataQuality;
    private FillSourceType sourceType;
    private String algorithmVersion;
    private String evidenceJson;
    private String typicalConfigId;
    private Integer typicalConfigVersion;
    private FillApplyStatus applyStatus;
    private Integer appliedCount;
    private Integer failedCount;
    private Integer replacedCount;
    private Integer voidedCount;
    private String failedMinutesJson;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime generatedAt;
    private LocalDateTime closedAt;
    private Long voidBy;
    private String voidReason;
    private LocalDateTime voidAt;
    private String supersedesTaskId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
