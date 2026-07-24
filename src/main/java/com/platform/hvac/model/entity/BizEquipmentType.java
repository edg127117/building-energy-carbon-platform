package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 物理设备类型字典。
 *
 * <p>assetCodePrefix 只用于生成现场资产编号，不等同于标准测点前缀。
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
