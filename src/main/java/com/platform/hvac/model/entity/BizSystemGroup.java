package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("biz_system_group")
public class BizSystemGroup implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 系统组ID，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private String systemGroupId;

    /** 建筑内可读业务编码，如 SG001 */
    private String systemGroupCode;

    /** 外键→building */
    private String buildingId;

    /** HVAC/LIGHTING/POWER */
    private String systemType;

    /** 如"冷水机组冷冻水循环系统" */
    private String systemGroupName;

    /** 系统描述 */
    private String groupDesc;

    /** 设计COP */
    private BigDecimal designCop;

    /** 设计容量(kW) */
    private BigDecimal designCapacity;

    /** 年度能耗预算 */
    private BigDecimal annualBudget;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 0-正常, 1-已删除 */
    @TableLogic
    private Integer delFlag;
}
