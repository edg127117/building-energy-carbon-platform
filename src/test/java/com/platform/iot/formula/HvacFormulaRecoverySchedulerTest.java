package com.platform.iot.formula;

import com.platform.config.FormulaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class HvacFormulaRecoverySchedulerTest {

    @Test
    void disabledPropertyDoesNotCreateBackgroundRecoveryScheduler() {
        new ApplicationContextRunner()
                .withPropertyValues("formula.recovery-enabled=false")
                .withUserConfiguration(SchedulerTestConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(HvacFormulaRecoveryScheduler.class);
                });
    }

    @Test
    void disabledFormulaCreatesNeitherRecoveryServiceNorScheduler() {
        new ApplicationContextRunner()
                .withPropertyValues("formula.enabled=false")
                .withUserConfiguration(DisabledFormulaTestConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(HvacFormulaRecoveryService.class);
                    assertThat(context)
                            .doesNotHaveBean(HvacFormulaRecoveryScheduler.class);
                });
    }

    @Test
    void applicationReadyEventTriggersStartupRecoveryExactlyOnce() throws Exception {
        new ApplicationContextRunner()
                .withPropertyValues("formula.recovery-enabled=true")
                .withUserConfiguration(SchedulerTestConfiguration.class)
                .run(context -> {
                    HvacFormulaRecoveryService service = context.getBean(
                            HvacFormulaRecoveryService.class);
                    context.publishEvent(mock(ApplicationReadyEvent.class));
                    verify(service, times(1)).recover(anyLong());
                });

        Method method = HvacFormulaRecoveryScheduler.class
                .getMethod("recoverOnStartup");
        EventListener listener = method.getAnnotation(EventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void eachPeriodicTriggerDelegatesExactlyOnceWhenEnabled() throws Exception {
        HvacFormulaRecoveryService service = mock(HvacFormulaRecoveryService.class);
        FormulaProperties properties = new FormulaProperties();
        HvacFormulaRecoveryScheduler scheduler =
                new HvacFormulaRecoveryScheduler(service, properties);

        scheduler.recoverPeriodically();

        verify(service, times(1)).recover(anyLong());
        Method method = HvacFormulaRecoveryScheduler.class
                .getMethod("recoverPeriodically");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${formula.recovery-delay-ms:600000}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${formula.recovery-delay-ms:600000}");
    }

    @Test
    void directPeriodicInvocationDoesNothingWhenRecoveryIsDisabled() {
        HvacFormulaRecoveryService service = mock(HvacFormulaRecoveryService.class);
        FormulaProperties properties = new FormulaProperties();
        properties.setRecoveryEnabled(false);
        HvacFormulaRecoveryScheduler scheduler =
                new HvacFormulaRecoveryScheduler(service, properties);

        scheduler.recoverPeriodically();

        verify(service, never()).recover(anyLong());
    }

    @Test
    void classConditionUsesFormulaRecoveryEnabledAndDefaultsToEnabled() {
        ConditionalOnProperty condition = HvacFormulaRecoveryScheduler.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.prefix()).isEqualTo("formula");
        assertThat(condition.name())
                .containsExactly("enabled", "recovery-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(HvacFormulaRecoveryScheduler.class)
    static class SchedulerTestConfiguration {

        @Bean
        HvacFormulaRecoveryService formulaRecoveryService() {
            return mock(HvacFormulaRecoveryService.class);
        }

        @Bean
        FormulaProperties formulaProperties() {
            return new FormulaProperties();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            HvacFormulaRecoveryService.class,
            HvacFormulaRecoveryScheduler.class
    })
    static class DisabledFormulaTestConfiguration {

        @Bean
        IndicatorConfigProvider indicatorConfigProvider() {
            return mock(IndicatorConfigProvider.class);
        }
    }
}
