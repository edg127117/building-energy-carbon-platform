package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_quality_usage_change_set")
/** 单建筑策略草稿包；revision 是编辑方提交的乐观并发令牌。 */
public class BizQualityUsageChangeSet {
    @TableId(type = IdType.INPUT)
    private String changeSetId;
    private String buildingId;
    private String status;
    private Integer revision;
    private Integer submittedRevision;
    private Long createdBy;
    private Integer hasBeenSubmitted;
    private String title;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime submittedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime cancelledAt;
    private String lastFailureCode;
}
