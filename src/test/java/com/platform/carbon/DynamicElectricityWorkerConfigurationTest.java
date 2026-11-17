package com.platform.carbon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DynamicElectricityWorkerConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DynamicElectricityLiveWorker.class, DynamicElectricityEvidenceRetention.class)
            .withBean(DynamicElectricityLiveService.class, () -> mock(DynamicElectricityLiveService.class))
            .withBean("mysqlJdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(DynamicElectricityLiveRepository.class, () -> mock(DynamicElectricityLiveRepository.class))
            .withBean(DynamicElectricityRepository.class, () -> mock(DynamicElectricityRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void createsBothWorkersByDefault() {
        context.run(app -> assertThat(app).hasNotFailed()
                .hasSingleBean(DynamicElectricityLiveWorker.class)
                .hasSingleBean(DynamicElectricityEvidenceRetention.class));
    }

    @Test
    void explicitFalseDisablesEachWorkerIndependently() {
        context.withPropertyValues("dynamic-electricity.live-enabled=false")
                .run(app -> assertThat(app).hasNotFailed()
                        .doesNotHaveBean(DynamicElectricityLiveWorker.class)
                        .hasSingleBean(DynamicElectricityEvidenceRetention.class));
        context.withPropertyValues("dynamic-electricity.evidence-retention-enabled=false")
                .run(app -> assertThat(app).hasNotFailed()
                        .hasSingleBean(DynamicElectricityLiveWorker.class)
                        .doesNotHaveBean(DynamicElectricityEvidenceRetention.class));
    }

    @Test
    void explicitTrueCreatesBothWorkers() {
        context.withPropertyValues(
                        "dynamic-electricity.live-enabled=true",
                        "dynamic-electricity.evidence-retention-enabled=true")
                .run(app -> assertThat(app).hasNotFailed()
                        .hasSingleBean(DynamicElectricityLiveWorker.class)
                        .hasSingleBean(DynamicElectricityEvidenceRetention.class));
    }

    @Test
    void testProfileStillExcludesBothWorkers() {
        context.withPropertyValues(
                        "spring.profiles.active=test",
                        "dynamic-electricity.live-enabled=true",
                        "dynamic-electricity.evidence-retention-enabled=true")
                .run(app -> assertThat(app).hasNotFailed()
                        .doesNotHaveBean(DynamicElectricityLiveWorker.class)
                        .doesNotHaveBean(DynamicElectricityEvidenceRetention.class));
    }
}
