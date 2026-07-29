package com.platform.iot.dataquality;

import com.platform.iot.aggregation.HvacMinuteBatchFrozenEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DataQualityConditionalConfigurationTest {

    @Test
    void enabledRegistersCompletionAndOmitsBypass() {
        new ApplicationContextRunner()
                .withPropertyValues("data-quality.enabled=true")
                .withBean(DataPointConfigProvider.class,
                        () -> mock(DataPointConfigProvider.class))
                .withBean(HvacMinuteRepository.class,
                        () -> mock(HvacMinuteRepository.class))
                .withBean(TypicalValueFillService.class,
                        () -> mock(TypicalValueFillService.class))
                .withBean(InterpolationFillService.class,
                        () -> mock(InterpolationFillService.class))
                .withBean(ApplicationEventPublisher.class,
                        () -> mock(ApplicationEventPublisher.class))
                .withUserConfiguration(
                        HvacMinuteQualityCompletionService.class,
                        HvacMinuteQualityBypassListener.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            HvacMinuteQualityCompletionService.class);
                    assertThat(context).doesNotHaveBean(
                            HvacMinuteQualityBypassListener.class);
                });
    }

    @Test
    void disabledRegistersBypassAndOnlyForwardsNonEmptyRealBatch() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        new ApplicationContextRunner()
                .withPropertyValues("data-quality.enabled=false")
                .withBean(ApplicationEventPublisher.class, () -> publisher)
                .withUserConfiguration(
                        HvacMinuteQualityCompletionService.class,
                        HvacMinuteQualityBypassListener.class,
                        LateRealMinuteCorrectionService.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            HvacMinuteQualityBypassListener.class);
                    assertThat(context).doesNotHaveBean(
                            HvacMinuteQualityCompletionService.class);
                    assertThat(context).doesNotHaveBean(
                            LateRealMinuteCorrectionService.class);
                });

        // ApplicationEventPublisher 是 Spring 的可解析基础设施依赖，ContextRunner 会优先
        // 注入测试上下文本身；旁路行为因此使用显式 mock 单独验证，避免把条件装配测试
        // 错误地绑定到容器内部事件发布器。
        HvacMinuteQualityBypassListener bypass =
                new HvacMinuteQualityBypassListener(publisher);
        bypass.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                60_000L, 90_000L, false, Set.of("B1"), List.of()));
        verify(publisher, never()).publishEvent(any(Object.class));

        bypass.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                60_000L, 90_000L, false, Set.of("B1"),
                List.of(aggregate())));
        verify(publisher).publishEvent(any(HvacMinuteQualityReadyEvent.class));
    }

    private RawMinuteAggregate aggregate() {
        return new RawMinuteAggregate(
                "P1", "P1", "B1", "G1", "E1", "E1",
                "WCR", "MAIN", "TWin", 1,
                60_000L, 12.0, 12.0, 12.0, 1, 0,
                61_000L, 62_000L, 90_000L, null);
    }
}
