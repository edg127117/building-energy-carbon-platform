package com.platform.iot.onboarding;

import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@Repository
/**
 * 使用 MyBatis Mapper 持久化待绑定设备的本地 MySQL 仓储。
 *
 * <p>外部身份唯一性和上报计数由单条数据库 upsert 保证；清理采用“有界选 ID +
 * 带状态和截止时间复核删除”，选择后发生新上报或绑定的记录不会被误删。</p>
 */
public class MySqlPendingDeviceRepository implements PendingDeviceRepository {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DELETE_LIMIT = 1_000;

    private final BizPendingDeviceMapper mapper;

    public MySqlPendingDeviceRepository(BizPendingDeviceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    }

    @Override
    public void upsertDiscovery(PendingDeviceDiscovery discovery) {
        Objects.requireNonNull(discovery, "discovery 不能为空");
        BizPendingDevice pending = new BizPendingDevice();
        pending.setPendingId(discovery.pendingId());
        pending.setIdentityType(discovery.identity().type());
        pending.setIdentityValue(discovery.identity().value());
        pending.setProfileCode(discovery.profileCode());
        pending.setLastProfileVersion(discovery.profileVersion());
        pending.setFirstSeenTime(discovery.seenAt());
        pending.setLastSeenTime(discovery.seenAt());
        pending.setLatestEventTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(discovery.eventTime()), PROJECT_ZONE));
        pending.setLatestTimeSource(discovery.timeSource());
        pending.setLatestMetricsJson(discovery.metricsJson());
        pending.setSampleTruncated(discovery.sampleTruncated() ? 1 : 0);
        int affected = mapper.upsertDiscovery(pending);
        if (affected < 1) {
            throw new IllegalStateException("待绑定设备发现写入未影响任何记录");
        }
    }

    @Override
    public int deleteExpired(LocalDateTime cutoffExclusive, int limit) {
        Objects.requireNonNull(cutoffExclusive, "cutoffExclusive 不能为空");
        if (limit < 1 || limit > MAX_DELETE_LIMIT) {
            throw new IllegalArgumentException("清理批次必须在 1 到 1000 之间");
        }
        List<String> pendingIds = mapper.selectExpiredEligibleIds(
                cutoffExclusive, limit);
        if (pendingIds.isEmpty()) {
            return 0;
        }
        return mapper.deleteExpiredEligibleByIds(pendingIds, cutoffExclusive);
    }
}
