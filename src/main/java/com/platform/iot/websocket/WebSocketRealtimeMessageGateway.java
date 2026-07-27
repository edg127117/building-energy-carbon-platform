package com.platform.iot.websocket;

import org.springframework.stereotype.Component;

@Component
public class WebSocketRealtimeMessageGateway implements RealtimeMessageGateway {

    @Override
    public void broadcast(String message) {
        WebSocketServer.broadcastMessage(message);
    }
}
