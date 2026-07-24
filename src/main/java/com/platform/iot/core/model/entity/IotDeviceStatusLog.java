package com.platform.iot.core.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 设备上下线轨迹日志
 * 对应 MySQL 中的 iot_device_status_log 表
 */
@Data
@TableName("iot_device_status_log")
public class IotDeviceStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备物理编号 */
    private String deviceId;

    /** 变更后的状态: 0-离线, 1-上线 */
    private Integer status;

    /** 发生时间 */
    private Date createTime;
}