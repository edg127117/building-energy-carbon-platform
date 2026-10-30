package com.platform.energy.history;

import com.platform.carbon.CarbonHistoricalConfigurationReader;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.classification.ClassificationContracts.*;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.period.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.energy.history.HistoricalConfigurationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricalConfigurationServiceTest {
    private final EnergyPeriodAuthorization authorization = mock(EnergyPeriodAuthorization.class);
    private final ClassificationService classifications = mock(ClassificationService.class);
    private final EnergyPeriodGovernanceService policies = mock(EnergyPeriodGovernanceService.class);
    private final EnergyCatalogService catalog = mock(EnergyCatalogService.class);
    private final EnergyConversionService conversion = mock(EnergyConversionService.class);
    private final CarbonHistoricalConfigurationReader carbon = mock(CarbonHistoricalConfigurationReader.class);
    private final Set<String> roles = Set.of("ENERGY_MANAGER");
    private final Instant from = Instant.parse("2025-01-01T00:00:00Z"), to = Instant.parse("2025-02-01T00:00:00Z");
    private final com.platform.carbon.CarbonProjectService projects = mock(com.platform.carbon.CarbonProjectService.class);
    private final com.platform.energy.summary.EnergyBoundarySummaryService summaries = mock(com.platform.energy.summary.EnergyBoundarySummaryService.class);
    private HistoricalConfigurationService service;
    @BeforeEach void setup() {
        service = new HistoricalConfigurationService(authorization, classifications, policies, catalog, conversion, carbon, projects, summaries);
        when(classifications.preview(anyLong(), anyCollection(), anyString(), any())).thenReturn(new ImportView(null, "PREVIEW", 0, List.of()));
    }
    private Request request() {
        var reading = new Reading("P1", from, to, "ELECTRICITY", "kWh", "PERIOD_TOTAL", null, null,
                BigDecimal.TEN, BigDecimal.ONE, true, "CONFIRMED", "历史账册", "EXACT", null);
        return new Request(new ImportRequest("key", List.of(reading), "覆盖检查"), null, "UTC",
                "DIRECT_TCE_FACTOR", "CALORIFIC_EQUIVALENT", "PURCHASED_ELECTRICITY", "GLOBAL");
    }
    private void configured() {
        when(catalog.effectiveBindingForPeriod(anyLong(), anyCollection(), anyString(), anyString(), any(), any()))
                .thenReturn(new BindingVersionView("b", "bv", 2, "BUILDING", "P1", "P1", "kWh", "ELECTRICITY", "item", null, null, "CONFIRMED", "证据", 1, 1L, null, 2L, null));
        when(policies.policyForPeriod(anyString(), any(), any())).thenReturn(new EnergyPeriodModels.PeriodPolicyVersion(
                "p", "pv", 1, "BUILDING", "UTC", 0, "REVIEW_REQUIRED", "APPROVED", "FORMAL", "证据",
                LocalDateTime.of(2025,1,1,0,0), null, 1, 1, null, 2L, null));
        when(classifications.configurationForPeriod(anyLong(), anyCollection(), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new VersionView("cv", "BUILDING", 2, 1, "APPROVED", from, to, 1, 2L, new Configuration("rel", List.of(), List.of(), List.of(),
                        List.of(new BoundaryRule("bd", "MAIN_METER_TOTAL", "CONFIRMED", "证据", "ELECTRICITY", "kWh"))), List.of()));
        when(conversion.evaluateFormalRules(anyLong(), anyCollection(), any(), any())).thenReturn(
                new EnergyConversionService.FormalConversion(BigDecimal.ZERO, "TCE", "tce", "formula", null, null));
        when(classifications.version(anyLong(), anyCollection(), anyString(), eq("cv"))).thenAnswer(invocation ->
                classifications.configurationForPeriod(1, roles, "BUILDING", null, from, to, "ELECTRICITY", "kWh"));
        when(projects.configurationForPeriod(anyLong(), anyCollection(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new com.platform.carbon.CarbonProjectContracts.Version("project", "BUILDING", 2, 1, "RETIRED", from, to, 1, 2L, null));
        var summary = mock(com.platform.energy.summary.api.EnergySummaryContracts.BoundaryPolicyView.class);
        when(summary.versionId()).thenReturn("summary");
        when(summaries.policyForPeriod(anyLong(), anyCollection(), anyString(), anyString(), anyString(), any(), any())).thenReturn(summary);
        when(carbon.factor(anyLong(), anyCollection(), anyString(), anyString(), anyString(), any(), any(), anyString())).thenReturn("factor");
        when(carbon.area(anyLong(), anyCollection(), anyString(), any(), any(), anyString())).thenReturn("area");
    }
    @Test void formatPreviewCanPassWhileHistoricalConfigurationRemainsBlocked() {
        configured();
        when(policies.policyForPeriod(anyString(), any(), any())).thenThrow(new BusinessException(409, "期间未配置"));
        var result = service.coverage(1, roles, "BUILDING", request());
        assertThat(result.formatStatus()).isEqualTo("PREVIEW");
        assertThat(result.configurationStatus()).isEqualTo("BLOCKED");
        assertThat(result.executionStatus()).isEqualTo("NOT_RUN");
        assertThat(result.checks()).anyMatch(c -> "PERIOD_POLICY".equals(c.domain()) && "BLOCKED".equals(c.status()));
        verify(classifications, never()).importReadings(anyLong(), anyCollection(), anyString(), any());
        verify(conversion, never()).simulate(anyLong(), anyCollection(), any());
    }
    @Test void coveredConfigurationReturnsVersionReferencesWithoutExecutingBusinessWork() {
        configured();
        var result = service.coverage(1, roles, "BUILDING", request());
        assertThat(result.configurationStatus()).isEqualTo("COVERED");
        assertThat(result.executionStatus()).isEqualTo("NOT_RUN");
        assertThat(result.checks()).extracting(Check::versionId).contains("bv", "pv", "cv", "project", "summary", "tce", "factor", "area");
        verify(classifications, never()).importReadings(anyLong(), anyCollection(), anyString(), any());
    }
    @Test void authorizationFailureIsNotConvertedIntoMissingConfiguration() {
        doThrow(new BusinessException(403, "跨建筑")).when(authorization).checkBuilding(1, roles, "OTHER");
        assertThatThrownBy(() -> service.coverage(1, roles, "OTHER", request())).hasMessageContaining("跨建筑");
        verifyNoInteractions(classifications, policies, catalog, conversion, carbon, projects, summaries);
    }
    @Test void invalidRawInputStopsConfigurationChecks() {
        when(classifications.preview(anyLong(), anyCollection(), anyString(), any())).thenReturn(
                new ImportView(null, "INVALID", 0, List.of(new Issue("ERROR", "INVALID_READING", "1", "读数无效"))));
        assertThat(service.coverage(1, roles, "BUILDING", request()).configurationStatus()).isEqualTo("NOT_CHECKED");
        verifyNoInteractions(policies, catalog, conversion, carbon, projects, summaries);
    }
}
