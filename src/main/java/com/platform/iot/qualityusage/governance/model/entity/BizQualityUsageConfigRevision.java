package com.platform.iot.qualityusage.governance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_quality_usage_config_revision")
/** 全局单行修订号；一次完整变更集发布只递增一次。 */
public class BizQualityUsageConfigRevision {
    @TableId(type = IdType.INPUT)
    private Integer singletonId;
    private Long configRevision;
    private String lastChangeSummary;
    private LocalDateTime updatedAt;
}
