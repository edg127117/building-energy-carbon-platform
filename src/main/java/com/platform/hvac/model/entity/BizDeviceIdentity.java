package com.platform.hvac.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@TableName("biz_device_identity")
/**
 * 设备外部身份与平台设备台账的预注册绑定。
 *
 * <p>该表只保存身份归属和期望协议，不保存遥测值。建筑 ID 与设备 ID 使用复合外键约束，
 * 防止把同一外部身份绑定到错误建筑；只有启用记录可以进入本地正式采集链。</p>
 */
public class BizDeviceIdentity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String identityId;

    /** 身份类型，如 MAC、SERIAL_NO。 */
    private String identityType;

    /** 设备实际随报文携带的外部身份值。 */
    private String identityValue;

    /** 外键指向已登记的物理设备。 */
    private String equipId;

    /** 冗余参与复合外键，禁止设备身份跨建筑绑定。 */
    private String buildingId;

    /** 本地允许该设备使用的云端协议模板代码。 */
    private String expectedProfileCode;

    /** 1-启用，0-停用。 */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}
