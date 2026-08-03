package com.platform.iot.formula;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.websocket.RealtimeMessageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把最新指标状态转换成实时通道的 WebSocket 消息信封。
 *
 * <p>该适配层只在 TDengine 已成功持久化且 Redis 接受最新分钟后调用。
 * 序列化或广播失败只记录告警，不回滚历史数据，也不让实时通道故障阻断
 * 后续指标计算。</p>
 */
@Component
public class IndicatorRealtimePublisher {

    private static final Logger log =
            LoggerFactory.getLogger(IndicatorRealtimePublisher.class);

    private final ObjectMapper objectMapper;
    private final RealtimeMessageGateway gateway;

    public IndicatorRealtimePublisher(
            ObjectMapper objectMapper,
            RealtimeMessageGateway gateway) {
        this.objectMapper = objectMapper;
        this.gateway = gateway;
    }

    /**
     * 以 {@code HVAC_INDICATOR} 类型广播成功或失败状态；该方法只负责尽力提交到
     * 后端网关，不负责客户端是否订阅或最终展示。
     */
    public void publish(IndicatorLatestState state) {
        final String message;
        try {
            message = objectMapper.writeValueAsString(
                    Map.of("type", "HVAC_INDICATOR", "data", state));
        } catch (Exception e) {
            log.warn("Unable to serialize latest indicator message: indicatorId={}",
                    state.indicatorId(), e);
            return;
        }

        try {
            gateway.broadcast(message);
        } catch (RuntimeException e) {
            log.warn("Unable to deliver latest indicator message: indicatorId={}",
                    state.indicatorId(), e);
        }
    }
}
