package com.platform.iot.temporal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * HVAC 逐条原始事件保留策略。
 *
 * <p>只清理 st_raw_event，不接触正式分钟汇总和后续指标结果。</p>
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

    public void cleanup(long now) {
        long cutoff = now - Duration.ofDays(retentionDays).toMillis();
        log.info("开始清理HVAC逐条原始事件: retentionDays={}, cutoff={}", retentionDays, cutoff);
        repository.deleteBefore(cutoff);
        log.info("HVAC逐条原始事件清理完成: retentionDays={}, cutoff={}", retentionDays, cutoff);
    }
}
