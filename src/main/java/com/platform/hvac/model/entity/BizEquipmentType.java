package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * MySQL 中的物理设备类型字典。
 *
 * <p>设备服务读取启用类型的 {@code assetCodePrefix} 和设备分类，为新增设备生成
 * 建筑内资产编号。{@code assetCodePrefix} 不等同于标准测点前缀；
 * 例如 WCT 类型生成 TOWER1，但它的标准测点仍可归属于 WCR1_CT_*。</p>
 */
@Data
@TableName("biz_equipment_type")
public class BizEquipmentType implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private String typeCode;
    private String typeName;
    private String assetCodePrefix;
    private String equipCategory;
    private String standardSource;
    private Integer status;
}
