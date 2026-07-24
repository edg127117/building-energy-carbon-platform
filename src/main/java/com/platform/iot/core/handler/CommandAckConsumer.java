package com.platform.iot.core.handler;


import com.platform.iot.core.model.DeviceMessage;
import com.platform.iot.service.impl.ControlCommandServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 指令执行回执(ACK) 消费者
 * 作用：监听总线上类型为 "reply" 的消息，完成控制状态机的最后一步。
 */
@Component
public class CommandAckConsumer {
    private static final Logger log = LoggerFactory.getLogger(CommandAckConsumer.class);
    @Autowired
    private ControlCommandServiceImpl controlCommandService;
    /**
     * 专门负责处理设备的控制反馈
     */
    @Async("virtualThreadExecutor")
    @EventListener
    public void handleCommandAck(DeviceMessage message) {
        // 只拦截类型为 "reply" (回执) 的消息
        if ("reply".equals(message.getType())) {
            Map<String, Object> data = message.getData();
            String commandId = (String) data.get("commandId");

            // 假设硬件返回 "success": true 代表执行成功，否则代表执行失败
            boolean isSuccess = data.containsKey("success") && (Boolean) data.get("success");

            // 状态映射：2-执行成功，3-执行失败 (对应 ControlCommand 实体类里的注释)
            Integer finalStatus = isSuccess ? 2 : 3;

            log.info("📥 [总线监听] 收到设备 {} 的指令回执: {}, 执行结果: {}",
                    message.getDeviceId(), commandId, isSuccess ? "成功" : "失败");

            try {
                // 调用 Service 更新 MySQL
                controlCommandService.updateCommandStatus(commandId, finalStatus);
            } catch (Exception e) {
                log.error("❌ 更新指令 {} 回执状态失败", commandId, e);
            }
        }
    }

}
