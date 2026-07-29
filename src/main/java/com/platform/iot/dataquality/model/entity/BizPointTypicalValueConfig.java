package com.platform.iot.dataquality.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MySQL 中测点典型值的审批版本。
 *
 * <p>批准后该记录作为质量 2 数据的业务依据；运行链路不会读取
 * {@code biz_data_point.default_value}，也不会把草稿或待审批记录当作默认值。</p>
 */
@Data
@TableName("biz_point_typical_value_config")
public class BizPointTypicalValueConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String configId;
    private String pointId;
    private String buildingId;
    private BigDecimal typicalValue;
    private String unit;
    private String sourceDescription;
    private String reason;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private TypicalValueStatus status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime submittedAt;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime reviewedAt;
    private Long disabledBy;
    private String disabledReason;
    private LocalDateTime disabledAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
