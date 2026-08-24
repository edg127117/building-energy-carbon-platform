package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("biz_point_alias")
/**
 * MySQL 中外部协议测点地址到平台标准测点的映射。
 *
 * <p>MQTT 接入前由测点配置提供者加载启用映射，报文中的来源编码据此转换为
 * {@link BizDataPoint} 的内部标识。该实体只描述身份映射，不保存上报值或质量结果。</p>
 */
public class BizPointAlias implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String aliasId;
    private String buildingId;
    private String sourceId;
    private String sourceSystem;
    private String sourcePointCode;
    private String pointId;
    private Integer status;
    private Integer revision;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
