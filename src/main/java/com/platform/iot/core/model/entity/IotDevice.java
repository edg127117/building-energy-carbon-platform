package com.platform.iot.core.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;
/**
 * 设备台账实体类
 * 对应 MySQL 中的 iot_device 表
 */
@Data
@TableName("iot_device") // MyBatis-Plus 注解：绑定数据库表名
public class IotDevice {

    @TableId(type = IdType.AUTO) // 主键自增策略
    private Long id;

    /** 设备唯一物理编号(MQTT客户端ID) */
    private String deviceId;

    /** 设备名称 */
    private String deviceName;

    /** 能源类型:1-电表,2-水表 */
    private Integer deviceType;

    /** 安装区域/位置 */
    private String location;

    /** 所属建筑；NULL 表示尚未归属，只允许平台管理员查看 */
    private String buildingId;

    /** 设备状态:0-离线,1-在线,2-故障 */
    private Integer status;

    /** 终端网络IP */
    private String ipAddress;

    private Date createTime;

    private Date updateTime;
}

