package com.platform.iot.formula;

import com.platform.config.FormulaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指标缺口的启动检查与低频补算调度器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "formula",
        name = {"enabled", "recovery-enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class HvacFormulaRecoveryScheduler {

    private final HvacFormulaRecoveryService service;
    private final FormulaProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        log.info("开始执行HVAC指标启动缺口补算");
        service.recover(System.currentTimeMillis());
    }

    @Scheduled(
            fixedDelayString = "${formula.recovery-delay-ms:600000}",
            initialDelayString = "${formula.recovery-delay-ms:600000}")
    public void recoverPeriodically() {
        if (properties.isRecoveryEnabled()) {
            log.info("开始执行HVAC指标低频缺口补算");
            service.recover(System.currentTimeMillis());
        }
    }
}
