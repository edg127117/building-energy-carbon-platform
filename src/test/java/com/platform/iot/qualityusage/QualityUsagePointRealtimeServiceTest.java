package com.platform.iot.qualityusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.hvac.service.BizDataPointService;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.websocket.RealtimeMessageGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import static com.platform.iot.qualityusage.QualityUsageModels.POINT_REALTIME_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityUsagePointRealtimeServiceTest {

    @Test
    void blockedPointIsRedactedBeforeWebSocketSerialization() {
        QualityUsagePolicyResolver resolver = mock(QualityUsagePolicyResolver.class);
        HvacMinuteRepository minutes = mock(HvacMinuteRepository.class);
        BizDataPointService points = mock(BizDataPointService.class);
        RealtimeMessageGateway gateway = mock(RealtimeMessageGateway.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        ResolutionContext context = mock(ResolutionContext.class);
        when(resolver.runtimeContext()).thenReturn(context);
        when(resolver.resolve(context, "POINT001", POINT_REALTIME_VIEW, 60_000L, 2))
                .thenReturn(new Resolution(
                        Decision.BLOCK, 2, POINT_REALTIME_VIEW,
                        PolicySource.PUBLISHED_POLICY, 3, 9,
                        "QUALITY_NOT_ALLOWED"));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        QualityUsagePointRealtimeService service = new QualityUsagePointRealtimeService(
                resolver, minutes, points, gateway, new ObjectMapper(), executor);
        RawMinuteAggregate row = new RawMinuteAggregate(
                "POINT001", "TEMP", "BLD001", "GROUP001", "EQUIP001", "EQ001",
                "TEMP", "ENV", "T", 1, 60_000L,
                12.3, 12.0, 12.8, 4, 2, 60_000L, 60_100L, 61_000L);

        service.onMinuteReady(new HvacMinuteQualityReadyEvent(
                60_000L, 61_000L, false, List.of(row)));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(gateway).sendToBuilding(eq("BLD001"), json.capture());
        assertThat(json.getValue())
                .contains("\"type\":\"HVAC_POINT\"")
                .contains("\"usageStatus\":\"QUALITY_BLOCKED\"")
                .contains("\"average\":null")
                .contains("\"minimum\":null")
                .contains("\"maximum\":null")
                .doesNotContain("12.3", "12.8");
    }
}
