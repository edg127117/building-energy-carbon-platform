package com.platform.iot.temporal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 定时执行 HVAC 逐条原始事件的保留期清理。
 *
 * <p>保留天数和 Cron 均由配置提供，任务只删除 {@code st_raw_event} 中设备采集时间
 * 早于截止点的证据，不接触正式分钟、质量任务或指标结果。关闭
 * {@code data-retention.cleanup-enabled} 后不注册该外部资源任务。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "data-retention.cleanup-enabled",
        havingValue = "true", matchIfMissing = true)
public class HvacRawEventRetentionService {

    private final HvacRawEventRepository repository;
    private final int retentionDays;

    public HvacRawEventRetentionService(
            HvacRawEventRepository repository,
            @Value("${data-retention.raw-event-days:90}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("原始事件保留天数必须大于等于1");
        }
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${data-retention.cleanup-cron:0 30 3 * * ?}")
    public void cleanup() {
        cleanup(System.currentTimeMillis());
    }

    /** 使用同一个服务器时间计算本轮保留截止点，并交由原始事件仓储执行删除。 */
    public void cleanup(long now) {
        long cutoff = now - Duration.ofDays(retentionDays).toMillis();
        log.info("开始清理HVAC逐条原始事件: retentionDays={}, cutoff={}", retentionDays, cutoff);
        repository.deleteBefore(cutoff);
        log.info("HVAC逐条原始事件清理完成: retentionDays={}, cutoff={}", retentionDays, cutoff);
    }
}
