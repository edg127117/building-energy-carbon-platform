package com.platform.iot.dataquality;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据质量失败恢复的低频调度入口。
 *
 * <p>开关关闭时组件不注册，普通测试不会因定时任务连接 MySQL 或 TDengine。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "true")
public class DataQualityRecoveryScheduler {

    private final DataQualityRecoveryService service;

    @Scheduled(
            fixedDelayString = "${data-quality.retry-delay-ms:600000}",
            initialDelayString = "${data-quality.retry-delay-ms:600000}")
    public void recover() {
        try {
            service.recover(System.currentTimeMillis());
        } catch (RuntimeException exception) {
            log.error("数据质量低频恢复调度失败，等待下一轮", exception);
        }
    }
}
