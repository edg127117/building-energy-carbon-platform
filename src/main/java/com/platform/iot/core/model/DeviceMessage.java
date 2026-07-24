package com.platform.iot.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 简化版物模型标准消息
 * 作用：系统的“通用货币”。无论底层是什么协议(MQTT/HTTP)，
 * 解析后全部转换成这个标准格式在 Spring 内部流转。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceMessage {

    /**
     * 消息的全局唯一ID (用于日志追踪)
     */
    private String messageId;

    /**
     * 设备物理编号 (对应 MySQL 和 TDengine 的 device_id)
     */
    private String deviceId;

    /**
     * 数据类型：property(属性/遥测数据), event(事件/告警), reply(控制指令回复)
     */
    private String type;

    /**
     * 实际的业务数据载荷 (存放电压、电流、开关状态等)
     * 使用 Map 方便动态扩展，无需写死具体的字段
     */
    private Map<String, Object> data;

    /**
     * 硬件采集时间戳 (毫秒)
     */
    private Long timestamp;
}