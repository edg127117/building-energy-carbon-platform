package com.platform.iot.communication;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 平台消费者连接/订阅及处理活性租约，不把平台到 Broker 的断线归因到每台电表。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="mqtt.enabled",havingValue="true",matchIfMissing=true)
public class MqttCommunicationHealth {
    private final IMqttClient client;
    private final CommunicationEvidenceRecorder recorder;
    private final DeviceCommunicationService communication;
    private final CommunicationProperties properties;
    @Scheduled(fixedDelayString="${device-communication.source-pulse-ms:10000}")
    public void pulse() {
        long now=System.currentTimeMillis();
        try { communication.sourceHealth("MQTT",client.isConnected()&&recorder.mqttHealthy(now,properties.getSourceLeaseSeconds()*1000L)?"HEALTHY":"FAILED",now); }
        catch(RuntimeException failed){log.warn("平台通讯活性租约暂不可写 cause={}",failed.getClass().getSimpleName());}
    }
}
