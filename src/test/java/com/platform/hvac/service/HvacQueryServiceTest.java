package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.dto.HvacQueryDtos;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.Building;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HvacQueryServiceTest {

    private static final long FROM = 1_800_000_000_000L;
    private static final Long USER_ID = 1L;
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    @Mock private BuildingService buildingService;
    @Mock private BuildingScopeService buildingScopeService;
    @Mock private BizDataPointService dataPointService;
    @Mock private BizEquipmentService equipmentService;
    @Mock private HvacMinuteRepository minuteRepository;

    private HvacQueryService service;

    @BeforeEach
    void setUp() {
        service = new HvacQueryService(
                buildingService,
                buildingScopeService,
                dataPointService,
                equipmentService,
                minuteRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "86400000,1",
            "86400001,5",
            "604800000,5",
            "604800001,30",
            "2678400000,30"
    })
    void selectsResolutionAtApprovedBoundaries(long span, int expected) {
        allowBuilding("BLD001");
        BizDataPoint point = point("POINT001", "A", "BLD001", "ONLINE", null);
        when(dataPointService.listByIds(List.of("POINT001"))).thenReturn(List.of(point));
        when(minuteRepository.findHistory(
                List.of("POINT001"), FROM, FROM + span, expected)).thenReturn(List.of());

        HvacQueryDtos.HistoryResponse response = service.history(
                "BLD001", "POINT001", FROM, FROM + span, USER_ID, ADMIN);

        assertThat(response.resolutionMinutes()).isEqualTo(expected);
    }

    @Test
    void trimsDeduplicatesAndPreservesFirstPointOrder() {
        allowBuilding("BLD001");
        BizDataPoint first = point("POINT002", "B", "BLD001", "ONLINE", null);
        BizDataPoint second = point("POINT001", "A", "BLD001", "ONLINE", null);
        when(dataPointService.listByIds(List.of("POINT002", "POINT001")))
                .thenReturn(List.of(second, first));
        when(minuteRepository.findHistory(
                List.of("POINT002", "POINT001"), FROM, FROM + 60_000L, 1))
                .thenReturn(List.of());

        HvacQueryDtos.HistoryResponse response = service.history(
                "BLD001",
                " POINT002, POINT001, POINT002, ",
                FROM,
                FROM + 60_000L,
                USER_ID,
                ADMIN);

        assertThat(response.series())
                .extracting(HvacQueryDtos.HistorySeries::pointId)
                .containsExactly("POINT002", "POINT001");
    }

    @Test
    void acceptsEightPointsButRejectsNine() {
        allowBuilding("BLD001");
        List<String> eightIds = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(number -> "P" + number).toList();
        List<BizDataPoint> eightPoints = eightIds.stream()
                .map(id -> point(id, id, "BLD001", "ONLINE", null)).toList();
        when(dataPointService.listByIds(eightIds)).thenReturn(eightPoints);
        when(minuteRepository.findHistory(
                eightIds, FROM, FROM + 60_000L, 1)).thenReturn(List.of());

        assertThat(service.history(
                "BLD001", String.join(",", eightIds), FROM, FROM + 60_000L,
                USER_ID, ADMIN).series()).hasSize(8);

        assertThatThrownBy(() -> service.history(
                "BLD001", "P1,P2,P3,P4,P5,P6,P7,P8,P9",
                FROM, FROM + 60_000L, USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(400));
    }

    @Test
    void rejectsMissingReversedAndTooLargeTimeRanges() {
        allowBuilding("BLD001");

        assertBadRequest(() -> service.history(
                "BLD001", "POINT001", null, FROM, USER_ID, ADMIN));
        assertBadRequest(() -> service.history(
                "BLD001", "POINT001", FROM, FROM, USER_ID, ADMIN));
        assertBadRequest(() -> service.history(
                "BLD001", "POINT001", FROM, FROM + 2_678_400_001L,
                USER_ID, ADMIN));
        assertBadRequest(() -> service.history(
                "BLD001", "POINT001", Long.MIN_VALUE, Long.MAX_VALUE,
                USER_ID, ADMIN));

        verifyNoInteractions(dataPointService, minuteRepository);
    }

    @Test
    void rejectsMissingDisabledAndCrossBuildingPoints() {
        allowBuilding("BLD001");
        when(dataPointService.listByIds(List.of("MISSING"))).thenReturn(List.of());
        assertBadRequest(() -> service.history(
                "BLD001", "MISSING", FROM, FROM + 60_000L, USER_ID, ADMIN));

        when(dataPointService.listByIds(List.of("OFFLINE"))).thenReturn(List.of(
                point("OFFLINE", "B", "BLD001", "OFFLINE", null)));
        assertBadRequest(() -> service.history(
                "BLD001", "OFFLINE", FROM, FROM + 60_000L, USER_ID, ADMIN));

        when(dataPointService.listByIds(List.of("OTHER"))).thenReturn(List.of(
                point("OTHER", "C", "BLD002", "ONLINE", null)));
        assertBadRequest(() -> service.history(
                "BLD001", "OTHER", FROM, FROM + 60_000L, USER_ID, ADMIN));
    }

    @Test
    void historyKeepsRequestedSeriesOrderAndSortsRecordsByTime() {
        allowBuilding("BLD001");
        BizDataPoint first = point("POINT002", "B", "BLD001", "ONLINE", null);
        BizDataPoint second = point("POINT001", "A", "BLD001", "ONLINE", null);
        when(dataPointService.listByIds(List.of("POINT002", "POINT001")))
                .thenReturn(List.of(first, second));
        when(minuteRepository.findHistory(
                List.of("POINT002", "POINT001"), FROM, FROM + 180_000L, 1))
                .thenReturn(List.of(
                        row("POINT001", FROM + 120_000L, 12.0),
                        row("POINT002", FROM + 60_000L, 20.0),
                        row("POINT001", FROM, 10.0)));

        HvacQueryDtos.HistoryResponse response = service.history(
                "BLD001", "POINT002,POINT001", FROM, FROM + 180_000L,
                USER_ID, ADMIN);

        assertThat(response.series())
                .extracting(HvacQueryDtos.HistorySeries::pointId)
                .containsExactly("POINT002", "POINT001");
        assertThat(response.series().get(1).records())
                .extracting(HvacQueryDtos.HistoryRecord::time)
                .containsExactly(FROM, FROM + 120_000L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void snapshotIncludesNoDataPointAndSortsByPointCode() {
        allowBuilding("BLD001");
        BizDataPoint pointB =
                point("POINT002", "B", "BLD001", "ONLINE", "EQUIP001");
        BizDataPoint pointA =
                point("POINT001", "A", "BLD001", "ONLINE", "EQUIP001");
        when(dataPointService.list(any(Wrapper.class)))
                .thenReturn(List.of(pointB, pointA));
        BizEquipment equipment = new BizEquipment();
        equipment.setEquipId("EQUIP001");
        equipment.setEquipCode("WCR1");
        when(equipmentService.listByIds(Set.of("EQUIP001")))
                .thenReturn(List.of(equipment));
        when(minuteRepository.findLatestByPointIds(
                List.of("POINT001", "POINT002")))
                .thenReturn(List.of(row("POINT001", FROM, 12.3)));

        HvacQueryDtos.SnapshotResponse response =
                service.snapshot("BLD001", USER_ID, ADMIN);

        assertThat(response.points())
                .extracting(HvacQueryDtos.SnapshotPoint::pointCode)
                .containsExactly("A", "B");
        assertThat(response.points().get(0).status()).isEqualTo("NORMAL");
        assertThat(response.points().get(0).equipCode()).isEqualTo("WCR1");
        assertThat(response.points().get(1).status()).isEqualTo("NO_DATA");
        assertThat(response.points().get(1).minute()).isNull();
        assertThat(response.points().get(1).sampleCount()).isZero();
    }

    @Test
    void mapsTdengineReadFailureToSanitized503() {
        allowBuilding("BLD001");
        BizDataPoint point = point("POINT001", "A", "BLD001", "ONLINE", null);
        when(dataPointService.listByIds(List.of("POINT001"))).thenReturn(List.of(point));
        when(minuteRepository.findHistory(anyList(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException(
                        "jdbc:TAOS sql secret"));

        assertThatThrownBy(() -> service.history(
                "BLD001", "POINT001", FROM, FROM + 60_000L, USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503);
                    assertThat(error.getMessage())
                            .doesNotContain("jdbc")
                            .doesNotContain("sql")
                            .contains("暂不可用");
                });
    }

    @Test
    void rejectsMissingBuildingBeforeCheckingScope() {
        when(buildingService.getById("UNKNOWN")).thenReturn(null);

        assertThatThrownBy(() ->
                service.snapshot("UNKNOWN", USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getMessage()).contains("建筑不存在");
                });

        verifyNoInteractions(buildingScopeService, minuteRepository);
    }

    private void allowBuilding(String buildingId) {
        Building building = new Building();
        building.setBuildingId(buildingId);
        when(buildingService.getById(buildingId)).thenReturn(building);
    }

    private BizDataPoint point(
            String pointId,
            String pointCode,
            String buildingId,
            String status,
            String equipId) {
        BizDataPoint point = new BizDataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode + " name");
        point.setBuildingId(buildingId);
        point.setStatus(status);
        point.setEquipId(equipId);
        point.setUnit("℃");
        return point;
    }

    private HvacMinuteQueryRow row(String pointId, long time, double average) {
        return new HvacMinuteQueryRow(
                pointId, time, average, average - 0.5, average + 0.5,
                6L, 0);
    }

    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(400));
    }
}
