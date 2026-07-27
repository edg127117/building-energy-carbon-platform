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
 * 指标缺口的启动检查与低频补算调度入口。
 *
 * <p>调度器只决定何时执行，不包含缺口判断或数据库逻辑；开关关闭后不会注册
 * 这些启动和定时任务，保证普通自动化测试无需连接真实 TDengine。</p>
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

    /** 应用就绪后立即检查一次，补偿停机期间已经冻结但未生成指标的分钟。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        log.info("开始执行HVAC指标启动缺口补算");
        service.recover(System.currentTimeMillis());
    }

    /**
     * 按低频间隔补查近期窗口。
     *
     * <p>运行时再次读取开关，便于配置关闭后停止实际补算动作。</p>
     */
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
