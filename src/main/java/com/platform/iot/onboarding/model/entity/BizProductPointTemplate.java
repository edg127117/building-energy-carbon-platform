package com.platform.iot.onboarding.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("biz_product_point_template")
/**
 * 产品型号下可确定性实例化的测点默认值。
 *
 * <p>模板只在后续绑定事务中生成具体测点和设备专属别名；正式采集链仍读取既有具体测点配置，
 * 不能在接入热路径按产品模板猜测指标语义或单位。</p>
 */
public class BizProductPointTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String templatePointId;
    private String productId;
    private String metricCode;
    private String pointNameTemplate;
    private String suffixCode;
    private String unit;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private Integer forCalc;
    private Integer requiredFlag;
    private Integer sortOrder;
    /** 1-启用，0-停用。 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
