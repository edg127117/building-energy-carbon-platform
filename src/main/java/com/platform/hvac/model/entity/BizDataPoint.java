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

/**
 * MySQL 中 HVAC 标准测点的业务档案。
 *
 * <p>测点配置提供者将启用档案与协议别名组合为 MQTT 接入快照；查询服务使用名称、
 * 单位和设备归属补充 TDengine 分钟值后返回前端。{@code pointId} 同时是时序数据的
 * 身份键，但本实体本身不保存采样值、分钟聚合或数据质量证据。</p>
 */
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

    /** 历史兼容字段；质量 2 运行链只读取已审批的典型值版本，不读取本字段 */
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
