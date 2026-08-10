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
 * 序列化或建筑定向发送失败只记录告警，不回滚历史数据，也不让实时通道故障阻断
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
     * 以 {@code HVAC_INDICATOR} 类型尽力发送成功或失败状态。
     *
     * <p>建筑 ID 来自已持久化的指标状态，缺失时直接丢弃而不退化为全局广播；WebSocket 只提供
     * 四项指标的时效通知，客户端完整 19 测点状态仍需通过 HTTP 权威查询。</p>
     */
    public void publish(IndicatorLatestState state) {
        if (state == null) {
            log.warn("Skip latest indicator realtime delivery: reason=STATE_MISSING");
            return;
        }
        if (state.buildingId() == null || state.buildingId().isBlank()) {
            log.warn("Skip latest indicator realtime delivery: indicatorId={}, reason=BUILDING_ID_MISSING",
                    state.indicatorId());
            return;
        }
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
            gateway.sendToBuilding(state.buildingId(), message);
        } catch (RuntimeException e) {
            log.warn("Unable to deliver latest indicator message: indicatorId={}",
                    state.indicatorId(), e);
        }
    }
}
