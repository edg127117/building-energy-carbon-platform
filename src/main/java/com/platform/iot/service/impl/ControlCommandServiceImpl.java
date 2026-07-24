package com.platform.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.algorithm.MpcAlgorithmService;
import com.platform.iot.core.bus.IotMessagePublisher;
import com.platform.iot.core.model.DeviceMessage;
import com.platform.iot.core.model.entity.ControlCommand;
import com.platform.iot.core.model.entity.IotDevice;
import com.platform.iot.mapper.ControlCommandMapper;
import com.platform.iot.mapper.IotDeviceMapper;
import com.platform.iot.mqtt.MqttPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 指令流转核心业务
 * 负责：指令创建/下发/ACK状态更新/超时扫描
 */
@Service
@Slf4j
public class ControlCommandServiceImpl extends ServiceImpl<ControlCommandMapper, ControlCommand> {

    // 指令从"已下发"到"超时"的最大等待时间(秒)，超过则自动标记为失败
    private static final int COMMAND_TIMEOUT_SECONDS = 30;

    @Value("${features.control-enabled:false}")
    private boolean controlEnabled;

    @Autowired
    private MpcAlgorithmService mpcAlgorithmService;
    @Autowired
    private MqttPublisher mqttPublisher;
    @Autowired
    private ObjectMapper objectMapper;
    // 引入设备 Mapper 用于发前查询状态
    @Autowired
    private IotDeviceMapper iotDeviceMapper;
    // 用于推送超时后的 reply 事件给前端
    @Autowired
    private IotMessagePublisher iotMessagePublisher;

    /**
     * 下发控制指令（完整流程：校验 → 落库 → MQTT下发 → 更新状态）
     */
    public String issueCommand(String deviceId, Integer commandType, Map<String, Object> params) {
        try {
            // 0. 【核心新增防线】：前置校验设备是否在线
            LambdaQueryWrapper<IotDevice> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(IotDevice::getDeviceId, deviceId);
            IotDevice device = iotDeviceMapper.selectOne(queryWrapper);

            if (device == null) {
                throw new BusinessException(404, "目标设备不存在！");
            }
            if (device.getStatus() != 1) { // 1 代表在线
                // 离线设备只允许"开启/上线"指令，拒绝其他操作
                boolean isPowerOn = isPowerOnCommand(params);
                if (!isPowerOn) {
                    throw new BusinessException(400, "设备 [" + deviceId + "] 当前处于离线状态，仅允许执行上线操作！");
                }
                log.info("⚡ 设备 {} 离线，但收到开机指令，允许执行", deviceId);
            }
            // 1. 算法拦截与校验
            if (!mpcAlgorithmService.validateControlParams(deviceId, params)) {
                throw new BusinessException(400, "算法推演未通过，参数存在安全隐患！");
            }
            // 2. 生成流水号并落库 (状态 0)
            String commandId = "CMD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            ControlCommand command = new ControlCommand();
            command.setCommandId(commandId);
            command.setDeviceId(deviceId);
            command.setCommandType(commandType);
            command.setCommandValue(objectMapper.writeValueAsString(params));
            command.setStatus(0);
            command.setCreatedAt(new Date());
            this.save(command);

            // 3. 构建报文并下发给 EMQX
            String downTopic = "device/control/down/" + deviceId;
            Map<String, Object> payloadMap = Map.of("commandId", commandId, "action", params);
            mqttPublisher.publish(downTopic, objectMapper.writeValueAsString(payloadMap));

            // 4. 下发成功，更新数据库状态 (状态 1 = 已下发)
            command.setStatus(1);
            this.updateById(command);

            return commandId;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("指令下发流程中断", e);
            throw new BusinessException(500, "控制指令发送失败，网络异常");
        }
    }

    /**
     * 【超时扫描定时任务】：每 10 秒扫描一次，将超时未收到回执的指令标记为失败（status=3）
     * 同时通过内部总线推送一个模拟的 reply 消息，让前端结束 loading 并收到通知
     */
    @Scheduled(fixedDelayString = "${features.control-timeout-scan-delay-ms:10000}")
    public void scanTimeoutCommands() {
        if (!controlEnabled) {
            return;
        }
        LambdaQueryWrapper<ControlCommand> wrapper = new LambdaQueryWrapper<>();
        // 状态为 1（已下发），且创建时间超过 COMMAND_TIMEOUT_SECONDS 秒
        wrapper.eq(ControlCommand::getStatus, 1);
        long timeoutThreshold = System.currentTimeMillis() - COMMAND_TIMEOUT_SECONDS * 1000L;
        wrapper.lt(ControlCommand::getCreatedAt, new Date(timeoutThreshold));
        List<ControlCommand> timeoutList = this.list(wrapper);

        if (timeoutList.isEmpty()) return;

        log.warn("⏰ [指令超时] 发现 {} 条指令超过 {} 秒未收到回执，自动标记为失败",
                timeoutList.size(), COMMAND_TIMEOUT_SECONDS);

        for (ControlCommand cmd : timeoutList) {
            // 更新数据库：状态 3 = 超时/失败
            updateCommandStatus(cmd.getCommandId(), 3);
            // 通过内部总线推送模拟 reply，让前端结束 loading 并弹出失败通知
            DeviceMessage timeoutReply = DeviceMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .deviceId(cmd.getDeviceId())
                    .type("reply")
                    .timestamp(System.currentTimeMillis())
                    .data(Map.of("commandId", cmd.getCommandId(), "success", false,
                            "message", "指令超时：设备未在 " + COMMAND_TIMEOUT_SECONDS + " 秒内回复"))
                    .build();
            iotMessagePublisher.publish(timeoutReply);
        }
    }

    /**
     * 【新增】：处理设备返回的 ACK 确认，更新 MySQL 状态
     */
    public void updateCommandStatus(String commandId, Integer status) {
        LambdaUpdateWrapper<ControlCommand> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ControlCommand::getCommandId, commandId)
                .set(ControlCommand::getStatus, status);
        this.update(updateWrapper);
        log.info("🔄 [控制回执] 成功将指令 {} 的状态更新为: {}", commandId, status);
    }

    /**
     * 判断指令是否为"开启/上线"操作（switch=true 或 switch="on"）
     * 用于允许离线设备执行开机指令
     */
    private boolean isPowerOnCommand(Map<String, Object> params) {
        if (params == null) return false;
        Object sw = params.get("switch");
        if (sw instanceof Boolean) return (Boolean) sw;
        if (sw != null) return "on".equalsIgnoreCase(String.valueOf(sw));
        return false;
    }
}
