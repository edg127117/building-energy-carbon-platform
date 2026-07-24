package com.platform.iot.ingest;

import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.quality.TelemetryQualityValidator;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设计冻结书 19 个来源测点的接入验收。
 *
 * <p>硬件继续上报冻结编码；平台先解析别名，再以交底规范编码和 pointId
 * 写入时序链路。该测试逐一覆盖全部 19 条映射。</p>
 */
@ExtendWith(MockitoExtension.class)
class NineteenPointIngestionAcceptanceTest {

    private static final long EVENT_TIME = 1_800_000_000_000L;

    @Mock private HvacRawEventRepository repository;

    @Test
    void allNineteenFrozenAliasesResolveToCanonicalPointIdentities() {
        List<PointFixture> fixtures = fixtures();
        Map<PointAliasKey, PointRuntimeConfig> configs = new LinkedHashMap<>();
        fixtures.forEach(fixture -> configs.put(
                new PointAliasKey("BLD001", "MQTT_FREEZE_V1", fixture.sourceCode()),
                point(fixture)));

        DataPointConfigProvider provider = key -> Optional.ofNullable(configs.get(key));
        HvacIngestionService service = new HvacIngestionService(
                new TelemetryQualityValidator(provider),
                repository,
                new SimpleMeterRegistry(),
                30,
                "MQTT_FREEZE_V1");
        when(repository.upsert(any())).thenReturn(RawEventWriteResult.INSERTED);

        for (int index = 0; index < fixtures.size(); index++) {
            PointFixture fixture = fixtures.get(index);
            Map<String, Object> payload = Map.of(
                    "buildingId", "BLD001",
                    "deviceId", fixture.deviceId(),
                    "pointCode", fixture.sourceCode(),
                    "val", 10.0 + index,
                    "timestamp", EVENT_TIME + index);

            HvacIngestionResult result = service.ingest(payload, EVENT_TIME + index + 100);

            assertThat(result.outcome())
                    .as("来源测点 %s 应通过接入校验", fixture.sourceCode())
                    .isEqualTo(IngestionOutcome.ACCEPTED);
        }

        ArgumentCaptor<RawTelemetryEvent> captor =
                ArgumentCaptor.forClass(RawTelemetryEvent.class);
        verify(repository, times(19)).upsert(captor.capture());
        List<RawTelemetryEvent> written = captor.getAllValues();
        assertThat(written).extracting(RawTelemetryEvent::pointId)
                .containsExactlyElementsOf(fixtures.stream().map(PointFixture::pointId).toList());
        assertThat(written).extracting(RawTelemetryEvent::pointCode)
                .containsExactlyElementsOf(fixtures.stream().map(PointFixture::canonicalCode).toList());
        assertThat(written).extracting(RawTelemetryEvent::sourcePointCode)
                .containsExactlyElementsOf(fixtures.stream().map(PointFixture::sourceCode).toList());
        assertThat(written).allSatisfy(event -> {
            assertThat(event.dataQuality()).isZero();
            assertThat(event.buildingId()).isEqualTo("BLD001");
            assertThat(event.sourceSystem()).isEqualTo("MQTT_FREEZE_V1");
            assertThat(event.late()).isFalse();
        });
    }

    private PointRuntimeConfig point(PointFixture fixture) {
        return new PointRuntimeConfig(
                fixture.pointId(), fixture.canonicalCode(), fixture.canonicalCode(),
                "BLD001", fixture.systemGroupId(), fixture.equipId(), fixture.equipCode(),
                fixture.familyCode(), fixture.componentCode(), fixture.suffixCode(),
                "ONLINE", 1, null, null);
    }

    private List<PointFixture> fixtures() {
        return List.of(
                f(1, "WCR1_TWin", "WCR1_TWin", "EQUIP_WCR", "WCR1", "WCR", "MAIN", "TWin"),
                f(2, "WCR1_TWout", "WCR1_TWout", "EQUIP_WCR", "WCR1", "WCR", "MAIN", "TWout"),
                f(3, "WCR1_Flow", "WCR1_GW", "EQUIP_WCR", "WCR1", "WCR", "MAIN", "GW"),
                f(4, "WCR1_PPE", "WCR1_PPE", "EQUIP_WCR", "WCR1", "WCR", "MAIN", "PPE"),
                f(5, "WCR1_Voltage", "WCR1_Voltage", "EQUIP_WCR", "WCR1", "WCR", "MAIN", "Voltage"),
                f(6, "WCR1_Current", "WCR1_Current", "EQUIP_WCR", "WCR1", "WCR", "MAIN", "Current"),
                f(7, "WCR1_PF", "WCR1_PF", "EQUIP_WCR", "WCR1", "WCR", "MAIN", "PF"),
                f(8, "TOWER1_TCWin", "WCR1_CT_TWin", "EQUIP_TOWER", "TOWER1", "WCR", "CT", "TWin"),
                f(9, "TOWER1_TCWout", "WCR1_CT_TWout", "EQUIP_TOWER", "TOWER1", "WCR", "CT", "TWout"),
                f(10, "TOWER1_TWB", "WCR1_CT_TWB", "EQUIP_TOWER", "TOWER1", "WCR", "CT", "TWB"),
                f(11, "PUMP1_Flow", "WCR1_Pc_GW", "EQUIP_PUMP", "PUMP1", "WCR", "Pc", "GW"),
                f(12, "PUMP1_Pout", "WCR1_Pc_Pout", "EQUIP_PUMP", "PUMP1", "WCR", "Pc", "Pout"),
                f(13, "PUMP1_Pin", "WCR1_Pc_Pin", "EQUIP_PUMP", "PUMP1", "WCR", "Pc", "Pin"),
                f(14, "PUMP1_Z", "WCR1_Pc_Z", "EQUIP_PUMP", "PUMP1", "WCR", "Pc", "Z"),
                f(15, "PUMP1_Power", "WCR1_Pc_PPE", "EQUIP_PUMP", "PUMP1", "WCR", "Pc", "PPE"),
                f(16, "AHU1_TotalPress", "AHU1_TotalPress", "EQUIP_AHU", "AHU1", "AHU", "MAIN", "TotalPress"),
                f(17, "AHU1_EtaT", "AHU1_EtaT", "EQUIP_AHU", "AHU1", "AHU", "MAIN", "EtaT"),
                env(18, "DBO_TDB", "DBO", "DBO", "TDB"),
                env(19, "DBO_RH", "RHO", "RHO", "RH")
        );
    }

    private PointFixture f(
            int number, String source, String canonical, String equipId, String equipCode,
            String family, String component, String suffix) {
        return new PointFixture(
                "POINT%03d".formatted(number), source, canonical,
                "GROUP001", equipId, equipCode, equipCode, family, component, suffix);
    }

    private PointFixture env(
            int number, String source, String canonical, String family, String suffix) {
        return new PointFixture(
                "POINT%03d".formatted(number), source, canonical,
                null, null, null, "WEATHER_GATEWAY", family, "ENV", suffix);
    }

    private record PointFixture(
            String pointId,
            String sourceCode,
            String canonicalCode,
            String systemGroupId,
            String equipId,
            String equipCode,
            String deviceId,
            String familyCode,
            String componentCode,
            String suffixCode) {
    }
}
