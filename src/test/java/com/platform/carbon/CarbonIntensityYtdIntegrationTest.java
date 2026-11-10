package com.platform.carbon;

import com.platform.audit.BackendDutyService;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.security.JwtService;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static com.platform.carbon.CarbonModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实HTTP、强度服务、年度服务和桥接；仅上游结果仓储与授权外部依赖采用隔离夹具。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/carbon-intensity-schema-test.sql")
@Transactional
class CarbonIntensityYtdIntegrationTest {
    private static final String BUILDING = "BLD001";
    private static final String PATH = "/v1/carbon-intensity/buildings/" + BUILDING;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired AnnualOverviewService annual;
    @Autowired CarbonIntensityYtdReader bridge;
    @Autowired CarbonIntensityCatalogService catalog;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc;
    @MockBean TokenCacheService tokens;
    @MockBean BackendDutyService duties;
    @MockBean BuildingScopeService scope;
    @MockBean CarbonCalculationRepository carbon;
    @MockBean CarbonRuleRepository areas;
    @MockBean MonthlyComparisonService monthly;

    @BeforeEach void prepare() {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        jdbc.update("UPDATE building SET building_type='办公',region_code='320115',carbon_evaluation_category=NULL,carbon_evaluation_subcategory=NULL WHERE building_id=?", BUILDING);
        catalog.save(1, List.of("PLATFORM_ADMIN"), new CarbonIntensityContracts.Save("INTEGRATION_REF", null,
                "隔离测试参考", "fixture-v1", "test://intensity-integration", LocalDate.of(2023, 1, 1),
                List.of(new CarbonIntensityContracts.Rule("3201", "办公", null, "测试分区",
                        new BigDecimal("56.71"), new BigDecimal("35.56"), new BigDecimal("20.72"),
                        "CO2_ONLY", "WHOLE_BUILDING_ENERGY", "BUILDING_AREA", "测试全建筑边界", "测试建筑面积",
                        "KNOWN", LocalDate.of(2023, 1, 1), null))));
        when(monthly.period(anyLong(), anyCollection(), eq(BUILDING), any(), eq(ZONE))).thenAnswer(call -> {
            LocalDate month = call.getArgument(3);
            String batch = "current-" + month;
            when(carbon.listSummaries(batch)).thenReturn(List.of(new SummaryMetric("TOTAL_EMISSION", "TOTAL",
                    BigDecimal.ONE, BigDecimal.ONE, "tCO2e", null, null, "monthly-hash")));
            return month(month, batch, "AVAILABLE", BigDecimal.ONE);
        });
        when(areas.areaSlices(eq(BUILDING), eq(UsageNature.FORMAL), any(), any())).thenAnswer(call -> {
            LocalDate start = call.getArgument(2), end = call.getArgument(3);
            if (start.getYear() != 2024) return List.of(slice("previous-area", "100", start, end));
            return List.of(slice("january-area", "100", start, start.plusMonths(1)),
                    slice("february-area", "200", start.plusMonths(1), end));
        });
    }

    @Test void ytdUsesProductionAreaWeightingAndCarriesEvidenceWithoutAnnualRating() throws Exception {
        assertThat(bridge).isInstanceOf(AnnualCarbonIntensityYtdReader.class);
        var overview = annual.overview(1, List.of("PLATFORM_ADMIN"), BUILDING, 2024, "MONTHLY_YTD", 2, ZONE.getId());
        var expected = overview.current().metrics().stream().filter(m -> "YTD_AREA_INTENSITY".equals(m.code())).findFirst().orElseThrow();
        // 闰年1月31天、2月29天；两月排放2t，平均面积=8900/60m²。
        assertThat(expected.value()).isEqualByComparingTo("13.483146");
        mvc.perform(query("MONTHLY_YTD", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.value").value("13.483146"))
                .andExpect(jsonPath("$.data.actual.evidenceHash").value(overview.evidenceHash()))
                .andExpect(jsonPath("$.data.actual.areaStatus").value("BUILDING_AREA"))
                .andExpect(jsonPath("$.data.endExclusive").value("2024-03-01"))
                .andExpect(jsonPath("$.data.comparisonStatus").value("NOT_COMPARABLE"))
                .andExpect(jsonPath("$.data.percentage").isEmpty());
        verify(carbon, never()).findCurrentFormal(anyString(), any(), any(), any(), anyString());
    }

    @Test void uniqueUndatedDirectoryAutomaticallyProvidesYtdAndAnnualReferencePercentagesWithoutProjectSelection() throws Exception {
        catalog.save(1, List.of("PLATFORM_ADMIN"), new CarbonIntensityContracts.Save("INTEGRATION_REF", catalog.list().getFirst().versionId(),
                "隔离测试参考", "fixture-v2", "test://intensity-integration", LocalDate.of(2023, 1, 1),
                List.of(new CarbonIntensityContracts.Rule("3201", "办公", null, "测试分区",
                        new BigDecimal("56.71"), new BigDecimal("35.56"), new BigDecimal("20.72"),
                        "CO2_ONLY", "STANDARD_SPECIFIC", "BUILDING_AREA", "标准专门边界", "测试建筑面积",
                        "PENDING", null, null))));
        String evidence = CarbonEvidence.canonical(CarbonEvidence.parse("""
                {"schemaVersion":1,"factor":{"resultBasis":"GAS_MASS","gasCode":"CO2","gasCoverage":"CO2_ONLY_ELECTRICITY"},
                 "gwp":{"gasCode":"CO2"},"gwpValue":"1"}
                """));
        for (int month = 1; month <= 2; month++) {
            String id = "current-" + LocalDate.of(2024, month, 1);
            var batch = mock(CalculationBatch.class);
            when(batch.batchId()).thenReturn(id); when(batch.buildingId()).thenReturn(BUILDING);
            when(batch.resultNature()).thenReturn(ResultNature.FORMAL); when(batch.status()).thenReturn("COMPLETED_COMPLETE");
            when(batch.publicationStatus()).thenReturn("DIRECT"); when(batch.detailCount()).thenReturn(1);
            when(carbon.findBatch(id)).thenReturn(batch);
            when(carbon.listEvidence(id)).thenReturn(List.of(new CarbonCalculationRepository.StoredEvidence(
                    "item", evidence, CarbonCalculationCore.sha256(evidence))));
        }
        mvc.perform(query("MONTHLY_YTD", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.value").value("13.483146"))
                .andExpect(jsonPath("$.data.actual.gasBasis").value("CO2_ONLY"))
                .andExpect(jsonPath("$.data.matchStatus").value("APPLICABILITY_TIME_UNCLEAR"))
                .andExpect(jsonPath("$.data.comparisonStatus").value("NOT_COMPARABLE"))
                .andExpect(jsonPath("$.data.percentageStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.percentageBasis").value("YTD_SHARE_OF_ANNUAL_REFERENCE"))
                .andExpect(jsonPath("$.data.percentage").value(new BigDecimal("13.483146")
                        .multiply(new BigDecimal("100")).divide(new BigDecimal("56.71"), 6, java.math.RoundingMode.HALF_UP).toPlainString()));
        frozen("YEAR", "AREA_INTENSITY", "80", "GAS_MASS");
        mvc.perform(query("FORMAL_YEAR", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparisonStatus").value("NOT_COMPARABLE"))
                .andExpect(jsonPath("$.data.percentageStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.percentageBasis").value("ANNUAL_REFERENCE"))
                .andExpect(jsonPath("$.data.percentage").value(new BigDecimal("80")
                        .multiply(new BigDecimal("100")).divide(new BigDecimal("56.71"), 6, java.math.RoundingMode.HALF_UP).toPlainString()));
        assertThat(catalog.list().getFirst().entries()).allSatisfy(rule -> {
            assertThat(rule.applicableFrom()).isNull();
            assertThat(rule.timeStatus()).isEqualTo("PENDING");
        });
    }

    @Test void missingMonthAndMissingAreaStayUnavailableInsteadOfUsingFrozenYearOrZero() throws Exception {
        LocalDate february = LocalDate.of(2024, 2, 1);
        doReturn(month(february, null, "NO_COMPLETE_CARBON_RESULT", null))
                .when(monthly).period(anyLong(), anyCollection(), eq(BUILDING), eq(february), eq(ZONE));
        mvc.perform(query("MONTHLY_YTD", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.status").value("EMISSION_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.actual.value").isEmpty())
                .andExpect(jsonPath("$.data.percentage").isEmpty());
        doReturn(month(february, "revised-february", "AVAILABLE", BigDecimal.ONE))
                .when(monthly).period(anyLong(), anyCollection(), eq(BUILDING), eq(february), eq(ZONE));
        when(carbon.listSummaries("revised-february")).thenReturn(List.of(new SummaryMetric("TOTAL_EMISSION", "TOTAL",
                BigDecimal.ONE, BigDecimal.ONE, "tCO2e", null, null, "revision-hash")));
        doReturn(List.of()).when(areas).areaSlices(eq(BUILDING), eq(UsageNature.FORMAL), any(), any());
        mvc.perform(query("MONTHLY_YTD", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.status").value("AREA_GAP"))
                .andExpect(jsonPath("$.data.actual.value").isEmpty());
    }

    @Test void publishedRevisionAndRealZeroReachTheCardWithoutCreatingAnAnnualPercentage() throws Exception {
        doAnswer(call -> {
            LocalDate date = call.getArgument(3);
            String id = "revised-zero-" + date;
            when(carbon.listSummaries(id)).thenReturn(List.of(new SummaryMetric("TOTAL_EMISSION", "TOTAL",
                    BigDecimal.ZERO, BigDecimal.ZERO, "tCO2e", null, null, "zero-hash")));
            return month(date, id, "AVAILABLE", BigDecimal.ZERO);
        }).when(monthly).period(anyLong(), anyCollection(), eq(BUILDING), any(), eq(ZONE));
        mvc.perform(query("MONTHLY_YTD", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.value").value("0.000000"))
                .andExpect(jsonPath("$.data.actual.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.percentage").isEmpty());
        verify(carbon, never()).listSummaries(startsWith("superseded-"));
    }

    @Test void frozenYearAndMonthRemainSeparateAndDoNotReadCurrentArea() throws Exception {
        frozen("YEAR", "AREA_INTENSITY", "80", "GAS_MASS");
        mvc.perform(query("FORMAL_YEAR", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.value").value("80"))
                .andExpect(jsonPath("$.data.actual.denominatorVersionId").value("frozen-area"))
                .andExpect(jsonPath("$.data.comparisonStatus").value("COMPARABLE"))
                .andExpect(jsonPath("$.data.percentage").value(org.hamcrest.Matchers.notNullValue()));
        frozen("MONTH", "MONTH_AREA_INTENSITY", "2.5", "GAS_MASS");
        mvc.perform(query("MONTH", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.value").value("2.5"))
                .andExpect(jsonPath("$.data.start").value("2024-02-01"))
                .andExpect(jsonPath("$.data.percentage").isEmpty());
        verifyNoInteractions(monthly, areas);
    }

    @Test void gasMismatchAndRegionChangeDoNotRelabelActualValuesOrBorrowReferences() throws Exception {
        frozen("YEAR", "AREA_INTENSITY", "80", "CO2E_DIRECT");
        mvc.perform(query("FORMAL_YEAR", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actual.gasBasis").value("GHG_CO2E"))
                .andExpect(jsonPath("$.data.actual.value").value("80"))
                .andExpect(jsonPath("$.data.percentage").isEmpty());
        jdbc.update("UPDATE building SET region_code='450103' WHERE building_id=?", BUILDING);
        // 测试事务跨两次HTTP调用共享SqlSession，直接JDBC更新后清除其一级缓存。
        sqlSession.clearCache();
        mvc.perform(query("FORMAL_YEAR", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchStatus").value("NO_APPLICABLE_REFERENCE"))
                .andExpect(jsonPath("$.data.reference").isEmpty())
                .andExpect(jsonPath("$.data.actual.value").value("80"));
    }

    @Test void rejectedBuildingNeverReachesTheAnnualOrStoredResultReaders() throws Exception {
        doThrow(new BusinessException(403, "SCOPE_DENIED", "无权访问建筑"))
                .when(scope).checkAccess(eq(1L), anyCollection(), eq("OTHER"));
        mvc.perform(get("/v1/carbon-intensity/buildings/OTHER").param("mode", "MONTHLY_YTD")
                        .param("year", "2024").param("month", "2").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(monthly, areas, carbon);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query(String mode, Integer month) {
        var request = get(PATH).param("mode", mode).param("year", "2024").header("Authorization", bearer());
        return month == null ? request : request.param("month", String.valueOf(month));
    }
    private String bearer() { return "Bearer " + jwt.generateToken(1L, "integration-reader", List.of("PLATFORM_ADMIN"), 1L); }
    private static MonthlyComparisonService.Period month(LocalDate date, String batch, String state, BigDecimal value) {
        return new MonthlyComparisonService.Period(date, date.atStartOfDay(ZONE).toInstant(), date.plusMonths(1).atStartOfDay(ZONE).toInstant(),
                List.of(new MonthlyComparisonService.Value("ELECTRICITY_KWH", "kWh", BigDecimal.TEN, "AVAILABLE", "bill", "hash", "unit"),
                        new MonthlyComparisonService.Value("ELECTRICITY_TCE", "tce", BigDecimal.ONE, "AVAILABLE", "bill", "hash", "unit"),
                        new MonthlyComparisonService.Value("TOTAL_EMISSION", "tCO2e", value, state, batch, "hash", "rounding")));
    }
    private static AreaTimeline.Slice slice(String id, String area, LocalDate from, LocalDate to) {
        var version = new DenominatorVersion("AREA", id, 1, BUILDING, DenominatorType.BUILDING_AREA, new BigDecimal(area), "M2",
                "test-source", "test-evidence", UsageNature.FORMAL, LifecycleStatus.ACTIVE, from, to, 1, 1,
                from.atStartOfDay(), 2L, from.atStartOfDay(), "approved", 3L, from.atStartOfDay());
        return new AreaTimeline.Slice(version, from, to);
    }
    private void frozen(String period, String code, String value, String gasBasis) {
        String id = "frozen-" + period;
        var batch = mock(CalculationBatch.class);
        when(batch.batchId()).thenReturn(id); when(batch.detailCount()).thenReturn(1);
        when(carbon.findCurrentFormal(eq(BUILDING), eq(PeriodType.valueOf(period)), any(), any(), eq(ZONE.getId()))).thenReturn(batch);
        when(carbon.listSummaries(id)).thenReturn(List.of(new SummaryMetric(code, "TOTAL", new BigDecimal(value), new BigDecimal(value),
                "kgCO2e/m²", "frozen-area", null, "frozen-hash")));
        var evidence = CarbonEvidence.parse("""
                {"schemaVersion":"test-v1","factor":{"resultBasis":"GAS_MASS","gasCode":"CO2","gasCoverage":"CO2_ONLY_ELECTRICITY"},
                 "gwp":{"gasCode":"CO2"},"gwpValue":"1",
                 "upstream":{"contractVersion":"ENERGY_BILL_CARBON_V1","billSource":{"source":{"configuration":{"coverage":"WHOLE_BUILDING"}}}},
                 "areaDenominator":{"value":{"denominatorVersionId":"frozen-area","type":"BUILDING_AREA","unitCode":"M2"}}}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("factor")).put("resultBasis", gasBasis);
        String json = CarbonEvidence.canonical(evidence);
        when(carbon.listEvidence(id)).thenReturn(List.of(new CarbonCalculationRepository.StoredEvidence("item", json, CarbonCalculationCore.sha256(json))));
    }
}
