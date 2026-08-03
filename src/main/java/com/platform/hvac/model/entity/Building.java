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
 * MySQL {@code building} 表中的建筑业务档案。
 *
 * <p>建筑是用户数据范围、设备与测点归属、HVAC 查询和数据质量任务的共同边界；
 * Controller/Service 将该实体用于档案维护和权限过滤。它只描述结构化配置，
 * 不包含 TDengine 分钟数据或实时指标值。</p>
 */
@Data
@TableName("building")
public class Building implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 建筑ID，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private String buildingId;

    /** 建筑主体全称 */
    private String buildingName;

    /** 不动产登记号或项目编号 */
    private String buildingCode;

    /** 办公/商业/教育/医疗/文化体育/综合 */
    private String buildingType;

    /** 竣工年份 */
    private Integer constructionYear;

    /** 总建筑面积(m²) */
    private BigDecimal totalGfa;

    /** 地上建筑面积 */
    private BigDecimal aboveGroundGfa;

    /** 地下建筑面积 */
    private BigDecimal undergroundGfa;

    /** 严寒/寒冷/夏热冬冷/夏热冬暖/温和 */
    private String climateZone;

    /** 设计人数 */
    private Integer designOccupancy;

    /** 运营时间 */
    private String operatingHours;

    /** 占用时间表(JSON) */
    private String occupancySchedule;

    /** BEMS系统型号 */
    private String bemsSystem;

    /** 通讯协议：BACnet/Modbus/OPC UA */
    private String bemsProtocol;

    /** 6位行政区划代码 */
    private String regionCode;

    /** 纬度 */
    private BigDecimal latitude;

    /** 经度 */
    private BigDecimal longitude;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 0-正常, 1-已删除 */
    @TableLogic
    private Integer delFlag;
}
