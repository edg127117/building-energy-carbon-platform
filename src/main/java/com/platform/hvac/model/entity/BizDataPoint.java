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
@TableName("biz_data_point")
public class BizDataPoint implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 平台内部全局ID，作为MySQL关联键和TDengine子表身份 */
    @TableId(type = IdType.ASSIGN_ID)
    private String pointId;

    /** 建筑内标准测点编码，如 WCR1_TWin/WCR1_CT_TWin */
    private String pointCode;

    /** 如"1号水冷机组冷水进水温度" */
    private String pointName;

    /** 外键→building */
    private String buildingId;

    /** 外键→biz_equipment（全局环境测点为NULL） */
    private String equipId;

    /** 所属系统分组；建筑环境测点可为空 */
    private String systemGroupId;

    /** 关联正式测点命名规则 */
    private String namingRuleId;

    /** 标准设备族：WCR/Bh/Bs/AHU/DBO/RHO */
    private String familyCode;

    /** 部件角色：MAIN/Pc/CT/Pcd/Ph/ENV */
    private String componentCode;

    /** 参数后缀：TWin/TWout/Flow/PPE */
    private String suffixCode;

    /** ANALOG/DIGITAL/ACCUMULATE */
    private String dataType;

    /** ℃/kW/m³/h/V/A/Pa/% */
    private String unit;

    /** 1-参与计算, 0-仅展示 */
    private Integer isForCalc;

    /** 异常兜底典型值 */
    private BigDecimal defaultValue;

    /** 量程上限 */
    private BigDecimal valueMax;

    /** 量程下限 */
    private BigDecimal valueMin;

    /** ONLINE/OFFLINE */
    private String status;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 0-正常, 1-已删除 */
    @TableLogic
    private Integer delFlag;
}
