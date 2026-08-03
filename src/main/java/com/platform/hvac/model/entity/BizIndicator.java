package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * MySQL 中建筑、系统或设备作用域下的 HVAC 性能指标实例。
 *
 * <p>公式配置提供者使用该实体确定四类指标的计算对象，查询服务以其建筑和设备
 * 身份校验 Redis、TDengine 返回行并组织接口结果。该实体不保存指标分钟值、
 * 计算异常或公式步骤。</p>
 */
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
