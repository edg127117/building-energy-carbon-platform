package com.platform.iot.aggregation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * HVAC 分钟数据启动恢复与低频补漏调度器。
 *
 * <p>与每 10 秒发现新分钟的正常任务分离，确保“最近 10 分钟扫描”
 * 不再成为持续高频查询，只在启动和较低频率下用于故障恢复。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "aggregation.recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HvacMinuteRecoveryScheduler {

    private final HvacMinuteAggregationService aggregationService;

    /**
     * 所有 Spring Bean 和启动初始化完成后执行一次补漏。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnApplicationReady() {
        log.info("开始执行HVAC分钟数据启动恢复");
        aggregationService.recoverRecentMinutes(System.currentTimeMillis());
    }

    /**
     * 默认每10分钟低频核对一次，频率与正常10秒任务完全分离。
     */
    @Scheduled(
            fixedDelayString = "${aggregation.recovery-delay-ms:600000}",
            initialDelayString = "${aggregation.recovery-delay-ms:600000}")
    public void recoverPeriodically() {
        log.info("开始执行HVAC分钟数据低频补漏");
        aggregationService.recoverRecentMinutes(System.currentTimeMillis());
    }
}
