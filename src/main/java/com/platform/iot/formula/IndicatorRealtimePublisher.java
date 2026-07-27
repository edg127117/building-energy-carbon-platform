package com.platform.iot.formula;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.websocket.RealtimeMessageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

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
