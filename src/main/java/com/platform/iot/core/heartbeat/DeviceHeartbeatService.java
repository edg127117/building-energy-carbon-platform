package com.platform.iot.core.heartbeat;

import com.platform.cache.CacheConstants;
import com.platform.cache.DeviceStatusCacheService;
import com.platform.iot.core.bus.IotMessagePublisher;
import com.platform.iot.core.model.DeviceMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 设备心跳超时检测服务
 *
 * 检测层级：应用层 — 设备是否还在上报数据（与 EMQX 传输层 disconnect 互补）
 *
 * 原理：
 * - 每次收到 property 消息时，用 Redis ZADD 更新该设备的心跳时间戳
 * - @Scheduled 每 30 秒扫描 Sorted Set，找出 N 秒内未上报数据的设备
 * - 超时设备通过 IotMessagePublisher 发布 offline 事件，走现有状态流转链路
 * - 已标记离线的设备从 Sorted Set 中 ZREM，下次有数据到达时重新 ZADD
 *
 * 时序设计（避免与 EMQX 冲突）：
 *   EMQX keepalive(60s)  <  心跳超时(120s)  <  data_quality 兜底标记(300s)
 */
@Service
public class DeviceHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(DeviceHeartbeatService.class);

    /** 心跳超时阈值（毫秒）：超过此时间无数据上报即判定离线，必须 > EMQX keepalive */
    static final long HEARTBEAT_TIMEOUT_MS = 120_000; // 2 分钟

    /** 过期心跳数据清理阈值：7 天未上报的设备，从 Sorted Set 中清理（避免僵尸数据堆积） */
    private static final long STALE_CLEANUP_MS = 7 * 24 * 3600_000L;

    private final StringRedisTemplate redis;
    private final IotMessagePublisher messagePublisher;
    private final DeviceStatusCacheService statusCacheService;

    public DeviceHeartbeatService(StringRedisTemplate redis,
                                  IotMessagePublisher messagePublisher,
                                  DeviceStatusCacheService statusCacheService) {
        this.redis = redis;
        this.messagePublisher = messagePublisher;
        this.statusCacheService = statusCacheService;
    }

    // ──────────── 记录心跳 ────────────

    /**
     * 记录设备心跳时间戳（每次收到 property 消息时调用）
     * 使用 Redis ZADD，同一 deviceId 多次写入会更新 score（覆盖写）
     */
    public void recordHeartbeat(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return;
        try {
            redis.opsForZSet().add(
                    CacheConstants.HEARTBEAT_TIMESTAMPS,
                    deviceId,
                    System.currentTimeMillis()
            );
        } catch (DataAccessException e) {
            log.debug("Redis 不可用，心跳记录跳过: deviceId={}", deviceId); // debug 级别避免刷屏
        }
    }

    // ──────────── 定时扫描超时设备 ────────────

    /**
     * 每 30 秒扫描一次：找出超过 HEARTBEAT_TIMEOUT_MS 无数据的设备，标记离线
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void scanTimeoutDevices() {
        try {
            long now = System.currentTimeMillis();
            long threshold = now - HEARTBEAT_TIMEOUT_MS;

            // ZRANGEBYSCORE：找出 score 在 [0, threshold] 之间的设备（即心跳时间早于阈值）
            Set<String> staleDevices = redis.opsForZSet()
                    .rangeByScore(CacheConstants.HEARTBEAT_TIMESTAMPS, 0, threshold);

            if (staleDevices == null || staleDevices.isEmpty()) return;

            log.info("🔍 心跳扫描发现 {} 个超时设备（无数据超过 120s），正在标记离线...", staleDevices.size());

            for (String deviceId : staleDevices) {
                // 发布离线事件，复用现有状态更新链路（MySQL → Redis → WebSocket）
                DeviceMessage offlineMsg = DeviceMessage.builder()
                        .deviceId(deviceId)
                        .type("offline")
                        .data(null)
                        .timestamp(System.currentTimeMillis())
                        .build();
                messagePublisher.publish(offlineMsg);
                log.warn("💔 设备心跳超时，已标记离线: {}", deviceId);
            }

            // 标记完成后从 Sorted Set 移除，防止下一轮重复触发
            redis.opsForZSet().remove(CacheConstants.HEARTBEAT_TIMESTAMPS,
                    staleDevices.toArray(new String[0]));

        } catch (DataAccessException e) {
            log.warn("Redis 不可用，心跳扫描跳过本轮", e);
        }
    }

    // ──────────── 僵尸数据清理 ────────────

    /**
     * 每天凌晨 3 点清理超过 7 天未上报的僵尸心跳记录
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupStaleRecords() {
        try {
            long threshold = System.currentTimeMillis() - STALE_CLEANUP_MS;
            Long removed = redis.opsForZSet()
                    .removeRangeByScore(CacheConstants.HEARTBEAT_TIMESTAMPS, 0, threshold);
            if (removed != null && removed > 0) {
                log.info("🧹 心跳清理：移除 {} 条超过 7 天的僵尸记录", removed);
            }
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，心跳清理跳过");
        }
    }
}
