package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DataQualityPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void usesIndustrialDefaultsWhenNoPropertiesAreOverridden() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DataQualityProperties.class);
            DataQualityProperties properties = context.getBean(DataQualityProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getInterpolation().getMaxGapMinutes()).isEqualTo(5);
            assertThat(properties.getLateRealCorrectionHours()).isEqualTo(24);
            assertThat(properties.getTypicalConfigRefreshMs()).isEqualTo(60_000L);
            assertThat(properties.getRetryDelayMs()).isEqualTo(600_000L);
            assertThat(properties.isReconciliationEnabled()).isTrue();
        });
    }

    @Test
    void bindsAllQualityFillSettings() {
        contextRunner.withPropertyValues(
                        "data-quality.enabled=false",
                        "data-quality.interpolation.max-gap-minutes=7",
                        "data-quality.late-real-correction-hours=48",
                        "data-quality.typical-config-refresh-ms=120000",
                        "data-quality.retry-delay-ms=300000",
                        "data-quality.reconciliation-enabled=false")
                .run(context -> {
                    DataQualityProperties properties = context.getBean(DataQualityProperties.class);

                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getInterpolation().getMaxGapMinutes()).isEqualTo(7);
                    assertThat(properties.getLateRealCorrectionHours()).isEqualTo(48);
                    assertThat(properties.getTypicalConfigRefreshMs()).isEqualTo(120_000L);
                    assertThat(properties.getRetryDelayMs()).isEqualTo(300_000L);
                    assertThat(properties.isReconciliationEnabled()).isFalse();
                });
    }

    @Test
    void rejectsNonPositiveScanAndRetrySettings() {
        for (String property : new String[]{
                "data-quality.interpolation.max-gap-minutes=0",
                "data-quality.late-real-correction-hours=0",
                "data-quality.typical-config-refresh-ms=0",
                "data-quality.retry-delay-ms=0"}) {
            contextRunner.withPropertyValues(property)
                    .run(context -> assertThat(context)
                            .as("配置必须拒绝非正数: %s", property)
                            .hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataQualityProperties.class)
    static class PropertiesConfiguration {
    }
}
