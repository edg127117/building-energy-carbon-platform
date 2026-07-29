package com.platform.iot.dataquality.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MySQL 中一次低频、异步且可续跑的人工数据质量重算批次。
 *
 * <p>该实体只保存管理员请求、分块游标和汇总审计，不保存逐分钟明细。重算产生的
 * Q1/Q2 通过补全任务的 {@code recalcJobId} 关联，分钟事实仍由 TDengine 管理。</p>
 */
@Data
@TableName("biz_data_quality_recalc_job")
public class BizDataQualityRecalcJob implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String jobId;
    private String idempotencyKey;
    private RecalculationJobType jobType;
    private String buildingId;
    private String pointIdsJson;
    private LocalDateTime fromMinute;
    private LocalDateTime toMinute;
    private String supersedesTaskId;
    private String reason;
    private Long operatorId;
    private RecalculationJobStatus status;
    private RecalculationJobPhase phase;
    private LocalDateTime cursorMinute;
    private String voidTargetMinutesJson;
    private Integer q0Count;
    private Integer q1Count;
    private Integer q2Count;
    private Integer missingCount;
    private Integer voidedCount;
    private Integer replacedCount;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
