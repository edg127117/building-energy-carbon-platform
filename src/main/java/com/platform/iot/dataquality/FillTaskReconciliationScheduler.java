package com.platform.iot.dataquality;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 小时批次收口与跨库核对的低频调度入口。
 *
 * <p>必须同时开启数据质量层和 reconciliation 开关；测试关闭任一开关后，
 * 不会注册可能访问真实 MySQL/TDengine 的后台任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "reconciliation-enabled"},
        havingValue = "true")
public class FillTaskReconciliationScheduler {

    private final FillTaskReconciliationService service;

    @Scheduled(
            fixedDelayString = "${data-quality.retry-delay-ms:600000}",
            initialDelayString = "${data-quality.retry-delay-ms:600000}")
    public void reconcile() {
        try {
            service.reconcile(System.currentTimeMillis());
        } catch (RuntimeException exception) {
            log.error("补全任务小时收口调度失败，等待下一轮", exception);
        }
    }
}
