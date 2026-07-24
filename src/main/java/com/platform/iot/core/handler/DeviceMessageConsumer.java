package com.platform.iot.core.handler;

import com.platform.cache.DeviceStatusCacheService;
import com.platform.iot.core.heartbeat.DeviceHeartbeatService;
import com.platform.iot.core.model.DeviceMessage;
import com.platform.iot.service.IotDeviceService;
import com.platform.iot.temporal.TimeSeriesRepository;
import com.platform.iot.websocket.WebSocketServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IoT 内部消息总线消费者 (核心业务分发)
 * 作用：监听总线上的设备消息，进行各个业务线的并发处理。
 */
@Component // 必须要有这个注解，Spring 才能发现它
public class DeviceMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeviceMessageConsumer.class);

    @Autowired
    private TimeSeriesRepository timeSeriesRepository;
    @Autowired
    private IotDeviceService iotDeviceService;
    // 引入 JSON 解析器
    @Autowired
    private ObjectMapper objectMapper;
    // Redis 设备状态缓存（冻结书 D-010：旁路写入，不参与数据写入热路径）
    @Autowired
    private DeviceStatusCacheService deviceStatusCacheService;
    // 设备心跳检测（每次 property 消息到达时更新时间戳）
    @Autowired
    private DeviceHeartbeatService heartbeatService;

    /**
     * Key: 设备ID, Value: 当前状态 (1在线, 0离线)
     * 本地内存缓存，作为 Redis 的本地兜底：当 Redis 不可用时，仍能防暴击保护 MySQL
     */
    private final Map<String, Integer> deviceStatusCache = new ConcurrentHashMap<>();

    /**
     * 处理器 1：专门负责将时序数据放入内存缓冲队列等待批量刷盘
     */
    @Async("virtualThreadExecutor")
    @EventListener
    public void handleTemporalStorage(DeviceMessage message) {
        if ("property".equals(message.getType())) {
            // 记录心跳：每次 property 数据到达即更新 Redis ZADD 时间戳（旁路，不参与写入热路径）
            heartbeatService.recordHeartbeat(message.getDeviceId());
            Map<String, Object> entry = new HashMap<>();
            entry.put("deviceId", message.getDeviceId());
            if (message.getData() != null) {
                entry.putAll(message.getData());
            }
            timeSeriesRepository.enqueue(entry);
        }
    }

    /**
     * 处理器 2：专门负责检查更新 MySQL 中的设备在线状态
     */
    @Async("virtualThreadExecutor")
    @EventListener
    public void handleDeviceStateCheck(DeviceMessage message) {
        String deviceId = message.getDeviceId();
        if (deviceId == null) return;

        // 1. 判断目标状态：如果是 MqttConfig 发出的 offline 类型，就是离线(0)，否则统统当做在线(1)
        int targetStatus = "offline".equals(message.getType()) ? 0 : 1;

        // 2. 从本地缓存获取该设备的当前已知状态
        Integer currentStatus = deviceStatusCache.get(deviceId);

        // 3. 【防穿透拦截】：只有当缓存里没有记录，或者状态发生【翻转】时，才去动数据库
        if (currentStatus == null || currentStatus != targetStatus) {
            String statusStr = targetStatus == 1 ? "上线" : "离线";
            log.info("【状态流转】感知到设备 [{}] {}, 正在更新数据库...", deviceId, statusStr);

            try {
                // 执行数据库 IO 操作
                iotDeviceService.updateStatusByDeviceId(deviceId, targetStatus);
                // 数据库更新成功后，同步刷新本地缓存
                deviceStatusCache.put(deviceId, targetStatus);
                // 旁路更新 Redis 缓存（冻结书 D-010：不参与写入热路径，Redis 不可用时仅打日志）
                deviceStatusCacheService.setStatus(deviceId, targetStatus);
                log.debug("✅ 设备 [{}] 状态已在 MySQL 刷新为: {}", deviceId, targetStatus);
            } catch (Exception e) {
                log.error("❌ 更新设备在线状态失败: ", e);
            }
        }
        // 如果状态没变，直接被外层 if 拦截。
    }
    /**
     * 处理器 3：WebSocket推送 (测试阶段临时放开 property，后续量大再关)
     */
    @Async("virtualThreadExecutor")
    @EventListener
    public void handleWebSocketPush(DeviceMessage message) {
        // 测试阶段放开 property 推送，硬件发数据后前端可实时看到
        // if ("property".equals(message.getType())) {
        //     return;
        // }
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            WebSocketServer.broadcastMessage(jsonMessage);
            log.debug("📡 [WebSocket] 已向前端大屏实时推送设备 {} 的最新特征数据", message.getDeviceId());
        } catch (Exception e) {
            log.error("❌ 推送 WebSocket 消息时发生异常: ", e);
        }
    }

}
