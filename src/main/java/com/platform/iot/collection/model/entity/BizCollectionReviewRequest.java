package com.platform.iot.collection.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_collection_review_request")
/** 提交时冻结目标修订号的审核申请；审核状态不复用业务对象状态。 */
public class BizCollectionReviewRequest {
    @TableId(type = IdType.INPUT)
    private String requestId;
    private String buildingId;
    private String targetType;
    private String targetId;
    private Integer targetConfigRevision;
    private String status;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime reviewedAt;
    private LocalDateTime withdrawnAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
