package com.platform.iot.ingest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MQTT 回调与 HVAC 业务接入之间的边界适配器。
 *
 * <p>{@link com.platform.config.MqttConfig} 完成报文大小、JSON 格式和旧协议拦截后，
 * 把载荷及服务器接收时间交给本类；本类只调用 {@link HvacIngestionService}，并把
 * 接入结果原样返回给 MQTT 回调决定是否手动确认。测点身份、迟到判定和 TDengine
 * 写入均由接入服务负责，避免协议配置层承载业务规则。</p>
 */
@Component
@RequiredArgsConstructor
public class HvacMqttMessageHandler {

    private final HvacIngestionService ingestionService;

    /**
     * 将已解析的上行载荷交给接入主流程。
     *
     * @param payload 来自 {@code device/data/up} 的 JSON 对象
     * @param receivedTime 平台收到 MQTT 消息的服务器时间，用于迟到判定
     * @return 包含业务结果及 ACK 决策的接入结果
     */
    public HvacIngestionResult handle(Map<String, Object> payload, long receivedTime) {
        return ingestionService.ingest(payload, receivedTime);
    }
}
