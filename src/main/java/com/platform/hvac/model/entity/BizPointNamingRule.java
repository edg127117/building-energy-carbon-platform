package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * MySQL 中的标准测点命名模板及规范来源。
 *
 * <p>测点档案服务把规则中的设备族、部件角色和编码模板交给命名校验器，防止
 * 非标准编码进入配置快照并影响 MQTT 身份解析。该实体不保存外部协议别名，
 * 也不负责执行正则校验。</p>
 */
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
