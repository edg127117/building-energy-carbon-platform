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
@TableName("biz_equipment")
public class BizEquipment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 平台内部全局ID，设备端和运维人员不需要维护 */
    @TableId(type = IdType.ASSIGN_ID)
    private String equipId;

    /** 建筑内可读资产编码，如 WCR1/PUMP1/TOWER1/AHU1 */
    private String equipCode;

    /** 如"1号水冷冷水机组" */
    private String equipName;

    /** WCR/WCT/WCP/AHU/Bh/Bs */
    private String typeCode;

    /** CHILLER/TOWER/PUMP/AHU */
    private String equipCategory;

    /** 外键→biz_system_group */
    private String systemGroupId;

    /** 外键→building */
    private String buildingId;

    /** 外键→biz_space */
    private String spaceId;

    /** 生产厂家 */
    private String manufacturer;

    /** 额定容量(kW) */
    private BigDecimal ratedCapacity;

    /** 额定功率(kW) */
    private BigDecimal ratedPower;

    /** 出厂基准COP */
    private BigDecimal designCop;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 0-正常, 1-已删除 */
    @TableLogic
    private Integer delFlag;
}
