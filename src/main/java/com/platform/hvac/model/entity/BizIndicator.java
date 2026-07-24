package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 建筑、系统或设备作用域下的性能指标实例。 */
@Data
@TableName("biz_indicator")
public class BizIndicator implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String indicatorId;
    private String buildingId;
    private String indicatorCode;
    private String scopeType;
    private String scopeId;
    private String equipId;
    private String systemGroupId;
    private Integer status;
}
