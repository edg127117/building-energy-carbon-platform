package com.platform.iot.core.bus;

import com.platform.iot.core.model.DeviceMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * IoT 内部消息总线发布者
 * 作用：用最轻量的 Spring Event 替代沉重的 中间件
 * MQTT 组件收到报文后，只管调用这个类把消息扔出去，不关心谁来处理。
 */
@Component
public class IotMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(IotMessagePublisher.class);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 将标准化后的设备消息发布到系统总线
     */
    public void publish(DeviceMessage message) {
        log.debug("总线发布设备消息: deviceId={}", message.getDeviceId());
        // Spring 自带的事件发布机制
        eventPublisher.publishEvent(message);
    }
}