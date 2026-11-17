package com.platform.iot.communication;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 可信适配层的内部生命周期边界；浏览器/普通业务 API 不能发布上线证据。 */
@Component
@RequiredArgsConstructor
public class CommunicationLifecycleListener {
    private final DeviceCommunicationService communication;
    public record GatewayLifecycle(String gatewayKey,boolean online,long observedAt) { }
    public record DeviceLifecycle(CommunicationModels.Signal signal) { }
    @EventListener
    public void gateway(GatewayLifecycle event) {
        if(event.gatewayKey()==null||event.gatewayKey().isBlank()||event.gatewayKey().length()>200)
            throw new IllegalArgumentException("网关身份无效");
        communication.sourceHealth("GATEWAY:"+event.gatewayKey(),event.online()?"HEALTHY":"DOWN",event.observedAt());
    }
    @EventListener
    public void device(DeviceLifecycle event) { communication.observe(event.signal()); }
}
