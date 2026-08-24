package com.platform.iot.collection.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_collection_policy")
/** 来源别名的稳定策略身份；活动版与草稿版通过显式指针并存。 */
public class BizCollectionPolicy {
    @TableId(type = IdType.INPUT)
    private String policyId;
    private String sourceId;
    private String aliasId;
    private String buildingId;
    private String activeVersionId;
    private String draftVersionId;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
