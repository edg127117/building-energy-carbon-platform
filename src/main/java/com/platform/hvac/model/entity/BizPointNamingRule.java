package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 标准测点命名模板及其规范来源。 */
@Data
@TableName("biz_point_naming_rule")
public class BizPointNamingRule implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String ruleId;
    private String standardVersion;
    private String familyCode;
    private String componentCode;
    private String codeTemplate;
    private String standardSource;
    private Integer status;
}
