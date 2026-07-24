package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 外部协议测点地址到平台标准测点的映射。 */
@Data
@TableName("biz_point_alias")
public class BizPointAlias implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String aliasId;
    private String buildingId;
    private String sourceSystem;
    private String sourcePointCode;
    private String pointId;
    private Integer status;
}
