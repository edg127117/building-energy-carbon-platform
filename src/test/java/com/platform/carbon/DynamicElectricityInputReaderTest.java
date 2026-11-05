package com.platform.carbon;

import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationCore;
import com.platform.energy.classification.ClassificationService;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 只使用替身验证动态电量适配，不连接计量、消息或数据库外部资源。 */
class DynamicElectricityInputReaderTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ROLES = Set.of("ENERGY_MANAGER");
    private static final Instant DAY_ONE = Instant.parse("2026-10-31T16:00:00Z");

    private final HourlyCarbonInputService hourlyInputs = mock(HourlyCarbonInputService.class);
    private final CarbonProjectService projects = mock(CarbonProjectService.class);
    private final ClassificationService classifications = mock(ClassificationService.class);
    private final DynamicElectricityInputReader reader = new DynamicElectricityInputReader(hourlyInputs, projects,
            new CarbonProjectCore(), classifications);

    @Test
    void hourSessionReusesSameDayCaptureAndPreservesReviewedSourceEvidence() {
        when(hourlyInputs.read(anyLong(), anyCollection(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(hourlyInput("12", List.of(slice("MAIN", "12", "MAIN"))));

        var session = reader.session();
        var first = session.read(1, ROLES, "B", DAY_ONE, DAY_ONE.plusSeconds(3600), "HOUR");
        session.read(1, ROLES, "B", DAY_ONE.plusSeconds(3600), DAY_ONE.plusSeconds(7200), "HOUR");
        session.read(1, ROLES, "B", DAY_ONE.plusSeconds(86400), DAY_ONE.plusSeconds(90000), "HOUR");

        assertThat(first.contributions()).containsExactly(new DynamicElectricityInputReader.Contribution("MAIN", Set.of("MAIN"),
                new BigDecimal("12"), List.of("fact-MAIN")));
        assertThat(first.evidence().path("resultNature").asText()).isEqualTo("MONITORING");
        assertThat(first.evidence().path("sourceNatureAssurance").asText()).isEqualTo("MONITORING_NOT_FIELD_ACCEPTANCE");
        assertThat(first.evidence().at("/hourlyInput/activities/0/quantityKwh").decimalValue()).isEqualByComparingTo("12");
        assertThat(CarbonCalculationCore.sha256(CarbonEvidence.canonical(first.evidence()))).isEqualTo(first.evidenceHash());

        var request = ArgumentCaptor.forClass(HourlyCarbonContracts.Request.class);
        var readFrom = ArgumentCaptor.forClass(Instant.class);
        var readTo = ArgumentCaptor.forClass(Instant.class);
        var captures = ArgumentCaptor.forClass(HourlyCarbonInputService.Capture.class);
        verify(hourlyInputs, times(3)).read(eq(1L), eq(ROLES), request.capture(), any(), any(),
                readFrom.capture(), readTo.capture(), captures.capture(), any());
        assertThat(request.getAllValues()).allSatisfy(value -> {
            assertThat(value.sourceKind()).isEqualTo("REVIEWED_INTERVAL");
            assertThat(value.declaredSourceNature()).isEqualTo("SOURCE_LABELLED");
            assertThat(value.sourceReference()).isEqualTo("DYNAMIC_ELECTRICITY_READ");
            assertThat(value.calculationAsOf().getNano() % 1_000_000).isZero();
        });
        assertThat(captures.getAllValues().get(0)).isSameAs(captures.getAllValues().get(1));
        assertThat(captures.getAllValues().get(2)).isNotSameAs(captures.getAllValues().get(0));
        assertThat(readFrom.getAllValues()).containsExactly(DAY_ONE, DAY_ONE, DAY_ONE.plusSeconds(86400));
        assertThat(readTo.getAllValues()).containsExactly(DAY_ONE.plusSeconds(86400), DAY_ONE.plusSeconds(86400),
                DAY_ONE.plusSeconds(172800));
        verifyNoInteractions(projects, classifications);
    }

    @Test
    void dayUsesClassificationProjectionThenAppliesProjectExclusionToDeduplicatedSlices() {
        Instant from = DAY_ONE;
        Instant to = from.plusSeconds(86400);
        var projectConfig = new CarbonProjectContracts.Configuration("CLASS-V1", List.of(
                new CarbonProjectContracts.Energy("ELEC", "PURCHASED_ELECTRICITY", "ENABLED")),
                List.of(new CarbonProjectContracts.Exclusion("ELEC", List.of("TENANT"), "独立租户", "signed-evidence")));
        var project = new CarbonProjectContracts.Version("PROJECT-V1", "B", 1, 1, "EFFECTIVE", from, null,
                1, 2L, projectConfig);
        var classificationConfig = new ClassificationContracts.Configuration("REL-V1", List.of(
                new ClassificationContracts.Scheme("USE", "用途", "USAGE", "ADDITIVE")), List.of(), List.of(),
                List.of(new ClassificationContracts.BoundaryRule("B-1", "MAIN_WITH_SUBMETER_BREAKDOWN", "CONFIRMED",
                        "boundary-evidence", "ELEC", "kWh")));
        var version = new ClassificationContracts.VersionView("CLASS-V1", "B", 1, 1, "EFFECTIVE", from, null,
                1, 2L, classificationConfig, List.of());
        var main = slice("MAIN", "100", "MAIN");
        var tenant = slice("TENANT", "20", "MAIN", "TENANT");
        var view = new ClassificationContracts.QueryView("B", "CLASS-V1", "REL-V1", "KWH", from, to,
                new BigDecimal("120"), new BigDecimal("100"), new BigDecimal("20"), BigDecimal.ZERO,
                "CALCULABLE", "FORMAL_SOURCE", "BOUNDARY_TOTAL", true, List.of(), List.of(), List.of(),
                List.of("fact-MAIN", "fact-TENANT"));
        var projection = new ClassificationService.QueryProjection(view, List.of(main, tenant), classificationConfig);
        when(projects.effective("B", from, to)).thenReturn(project);
        when(classifications.effectiveVersion(eq(1L), eq(ROLES), eq("B"), eq(from), eq(to), eq("CLASS-V1"))).thenReturn(version);
        when(classifications.queryProjection(eq(1L), eq(ROLES), eq("B"), any())).thenReturn(projection);

        var input = reader.read(1, ROLES, "B", from, to, "DAY");

        assertThat(input.contributions()).containsExactly(new DynamicElectricityInputReader.Contribution("MAIN", Set.of("MAIN"),
                new BigDecimal("100"), List.of("fact-MAIN")));
        assertThat(input.evidence().at("/energies/0/sourceSlices")).hasSize(2);
        assertThat(input.evidence().at("/energies/0/projectApplied/included").decimalValue()).isEqualByComparingTo("100");
        assertThat(input.evidence().at("/energies/0/projectApplied/excluded").decimalValue()).isEqualByComparingTo("20");
        var request = ArgumentCaptor.forClass(ClassificationContracts.QueryRequest.class);
        verify(classifications).queryProjection(eq(1L), eq(ROLES), eq("B"), request.capture());
        assertThat(request.getValue()).isEqualTo(new ClassificationContracts.QueryRequest(from, to, "DAY", "ELEC", "kWh", "USE",
                "CLASS-V1", List.of()));
        verify(projects).effective("B", from, to);
    }

    @Test
    void sessionRejectsCrossUserOrBuildingReuseBeforeItCanReuseCapturedSources() {
        when(hourlyInputs.read(anyLong(), anyCollection(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(hourlyInput("12", List.of(slice("MAIN", "12", "MAIN"))));
        var session = reader.session();
        session.read(1, ROLES, "B", DAY_ONE, DAY_ONE.plusSeconds(3600), "HOUR");

        assertThatThrownBy(() -> session.read(2, ROLES, "B", DAY_ONE.plusSeconds(3600), DAY_ONE.plusSeconds(7200), "HOUR"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能跨用户、角色或建筑");
        assertThatThrownBy(() -> session.read(1, ROLES, "OTHER", DAY_ONE.plusSeconds(3600), DAY_ONE.plusSeconds(7200), "HOUR"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能跨用户、角色或建筑");
        verify(hourlyInputs, times(1)).read(anyLong(), anyCollection(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsHourlyEvidenceWhenActivityDoesNotEqualIncludedDeduplicatedSlices() {
        when(hourlyInputs.read(anyLong(), anyCollection(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(hourlyInput("11", "12", List.of(slice("MAIN", "12", "MAIN"))));

        assertThatThrownBy(() -> reader.read(1, ROLES, "B", DAY_ONE, DAY_ONE.plusSeconds(3600), "HOUR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("活动量与去重排除后的来源组成不一致");
    }

    @Test
    void rawCumulativeSessionPassesExistingHourlySourceKindAndKeepsItInEvidence() {
        when(hourlyInputs.read(anyLong(), anyCollection(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(hourlyInput("12", List.of(slice("MAIN", "12", "MAIN"))));

        var input = reader.session("RAW_CUMULATIVE").read(1, ROLES, "B", DAY_ONE, DAY_ONE.plusSeconds(3600), "HOUR");

        var request = ArgumentCaptor.forClass(HourlyCarbonContracts.Request.class);
        verify(hourlyInputs).read(eq(1L), eq(ROLES), request.capture(), any(), any(), any(), any(), any(), any());
        assertThat(request.getValue().sourceKind()).isEqualTo("RAW_CUMULATIVE");
        assertThat(input.evidence().path("sourceKind").asText()).isEqualTo("RAW_CUMULATIVE");
        assertThatThrownBy(() -> reader.session("UNREVIEWED"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("来源类型无效");
    }

    @Test
    void rejectsUnfinishedOrGrainMisalignedWindowBeforeReadingSources() {
        assertThatThrownBy(() -> reader.read(1, ROLES, "B", DAY_ONE.plusSeconds(1), DAY_ONE.plusSeconds(3601), "HOUR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("精确对齐");
        Instant future = Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        assertThatThrownBy(() -> reader.read(1, ROLES, "B", future, future.plusSeconds(3600), "HOUR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已结束");
        verifyNoInteractions(hourlyInputs, projects, classifications);
    }

    private static HourlyCarbonInputService.Input hourlyInput(String activityQuantity, List<ClassificationCore.SourceSlice> slices) {
        return hourlyInput(activityQuantity, activityQuantity, slices);
    }

    private static HourlyCarbonInputService.Input hourlyInput(String activityQuantity, String includedQuantity,
                                                               List<ClassificationCore.SourceSlice> slices) {
        var evidence = CarbonEvidence.object();
        var energy = evidence.putArray("energies").addObject();
        energy.put("energyItemCode", "ELECTRICITY");
        energy.set("projectApplied", CarbonEvidence.tree(new CarbonProjectCore.Applied(new BigDecimal(includedQuantity), BigDecimal.ZERO,
                slices, List.of())));
        return new HourlyCarbonInputService.Input(List.of(new HourlyCarbonInputService.Activity("ELECTRICITY",
                new BigDecimal(activityQuantity), energy.deepCopy())), List.of("hour-source"), List.of("Q0:1"),
                List.of("PROJECT-V1"), List.of("REL-V1"), evidence);
    }

    private static ClassificationCore.SourceSlice slice(String point, String quantity, String... ancestors) {
        return new ClassificationCore.SourceSlice(point, new BigDecimal(quantity), Set.of(ancestors), false,
                List.of("fact-" + point), Map.of());
    }
}
