package com.platform.iot.energymetadata.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_energy_point_profile")
/** 标准测点的能源专业属性；单位和用能系统仍由既有测点及关系数据提供。 */
public class BizEnergyPointProfile {
    @TableId(type = IdType.INPUT)
    private String profileId;
    private String pointId;
    private String buildingId;
    private String energyType;
    private String energySubtype;
    private String valueSemantics;
    private String reportingPeriod;
    private Boolean annualSummary;
    private String confirmationStatus;
    private String evidenceReference;
    private Integer configRevision;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
