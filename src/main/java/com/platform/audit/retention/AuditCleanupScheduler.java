package com.platform.audit.retention;

import com.platform.audit.AuditGovernanceProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
/** 研发默认关闭；启用后只执行已审核且当前生效的策略，不为每个到期批次重复申请。 */
public class AuditCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(AuditCleanupScheduler.class);

    private final AuditCleanupService service;
    private final AuditGovernanceProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(fixedDelayString = "${audit-governance.retention-cleanup-delay:PT1H}")
    public void cleanup() {
        if (!running.compareAndSet(false, true)) return;
        try {
            service.runApprovedExceptions();
            if (properties.isRetentionCleanupEnabled()) service.runAutomatic();
        } catch (RuntimeException failure) {
            log.error("审计自动清理调度失败", failure);
        } finally {
            running.set(false);
        }
    }
}
