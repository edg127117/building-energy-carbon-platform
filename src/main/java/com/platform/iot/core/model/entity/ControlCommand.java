package com.platform.iot.core.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 控制指令下发追踪表
 */
@Data
@TableName("control_commands")
public class ControlCommand {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一指令流水号 (Trace ID) */
    private String commandId;

    /** 目标设备ID */
    private String deviceId;

    /** 指令类型: 1-遥控(开关), 2-遥调(设参) */
    private Integer commandType;

    /** 控制参数 (这里存 JSON 字符串，例如 {"switch": "OFF"}) */
    private String commandValue;

    /** 状态: 0-待下发, 1-已下发, 2-执行成功, 3-超时/失败 */
    private Integer status;

    private Date createdAt;
}
