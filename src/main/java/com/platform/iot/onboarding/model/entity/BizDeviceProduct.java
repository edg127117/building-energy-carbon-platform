package com.platform.iot.onboarding.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("biz_device_product")
/**
 * 可复用的设备产品接入模板。
 *
 * <p>该档案只约束可接受的身份类型、平台设备类型和兼容协议族；它不是具体物理设备的
 * 归属，也不参与已绑定设备的正式 MQTT、TDengine 写入热路径。</p>
 */
public class BizDeviceProduct implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String productId;
    private String productCode;
    private String productName;
    private String manufacturer;
    private String model;
    private String equipmentTypeCode;
    private String expectedProfileCode;
    private String identityType;
    /** DRAFT、ENABLED、DISABLED；后续绑定服务必须只接受启用产品。 */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
