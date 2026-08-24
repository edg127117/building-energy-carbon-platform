package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_quality_usage_review_request")
/** 提交时冻结的轻量审核证据；运行解析从正式版本而不是快照 JSON 读取。 */
public class BizQualityUsageReviewRequest {
    @TableId(type = IdType.INPUT)
    private String requestId;
    private String changeSetId;
    private Integer requestNo;
    private String status;
    private String reviewMode;
    private Integer submittedRevision;
    private String snapshotJson;
    private String snapshotSha256;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime reviewedAt;
    private Long withdrawnBy;
    private LocalDateTime withdrawnAt;
    private String idempotencyKey;
    private String requestSha256;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
