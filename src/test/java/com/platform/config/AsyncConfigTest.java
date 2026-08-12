package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void exposesTheFrameworkDefaultAndDedicatedScanSchedulerByExplicitNames()
            throws Exception {
        Bean businessScheduler = AsyncConfig.class
                .getMethod("taskScheduler")
                .getAnnotation(Bean.class);
        Bean scanScheduler = AsyncConfig.class
                .getMethod("recalculationScanTaskScheduler")
                .getAnnotation(Bean.class);

        assertThat(businessScheduler.value()).containsExactly("taskScheduler");
        assertThat(scanScheduler.value())
                .containsExactly("recalculationScanTaskScheduler");
    }

    @Test
    void registersDedicatedBusinessSchedulerInsteadOfUsingSpecializedSchedulers() {
        AsyncConfig config = new AsyncConfig(properties(), 4);
        ThreadPoolTaskScheduler scheduler = config.taskScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor()
                    .getCorePoolSize()).isEqualTo(4);
            assertThat(scheduler.getThreadNamePrefix())
                    .isEqualTo("iot-business-scheduling-");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void createsBoundedExecutorsForLateCorrectionAndRecalculationJobs() {
        DataQualityProperties properties = properties();
        properties.setLateRealCorrectionConcurrency(6);
        properties.setLateRealCorrectionQueueCapacity(30);
        properties.setRecalculationWorkerConcurrency(2);
        AsyncConfig config = new AsyncConfig(properties, 4);

        assertExecutor(
                config.lateRealCorrectionExecutor(),
                6, 30, "iot-late-real-");
        assertExecutor(
                config.recalculationJobExecutor(),
                2, 0, "iot-recalculation-");

        ThreadPoolTaskScheduler scanScheduler =
                config.recalculationScanTaskScheduler();
        scanScheduler.initialize();
        try {
            assertThat(scanScheduler.getScheduledThreadPoolExecutor()
                    .getCorePoolSize()).isEqualTo(1);
            assertThat(scanScheduler.getThreadNamePrefix())
                    .isEqualTo("iot-recalculation-scan-");
        } finally {
            scanScheduler.shutdown();
        }
    }

    private void assertExecutor(
            ThreadPoolTaskExecutor executor,
            int concurrency,
            int queueCapacity,
            String threadPrefix) {
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(concurrency);
            assertThat(executor.getMaxPoolSize()).isEqualTo(concurrency);
            assertThat(executor.getThreadNamePrefix()).isEqualTo(threadPrefix);
            assertThat(executor.getThreadPoolExecutor().getQueue()
                    .remainingCapacity()).isEqualTo(queueCapacity);
        } finally {
            executor.shutdown();
        }
    }

    private DataQualityProperties properties() {
        return new DataQualityProperties();
    }
}
