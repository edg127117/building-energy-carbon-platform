package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnnualOverviewServiceTest {
    final CarbonAuthorization auth=mock(CarbonAuthorization.class);
    final CarbonCalculationRepository carbon=mock(CarbonCalculationRepository.class);
    final CarbonRuleRepository rules=mock(CarbonRuleRepository.class);
    final MonthlyComparisonService monthly=mock(MonthlyComparisonService.class);
    final AnnualOverviewService service=new AnnualOverviewService(auth,carbon,rules,monthly);
    final List<String> roles=List.of("BUILDING_OWNER");
    void months() {
        when(monthly.period(anyLong(),anyCollection(),anyString(),any(),any())).thenAnswer(call->{
            LocalDate m=call.getArgument(3);var zone=ZoneId.of("Asia/Shanghai");
            String batch="latest-"+m;
            when(carbon.listSummaries(batch)).thenReturn(List.of(new SummaryMetric("TOTAL_EMISSION","TOTAL",new BigDecimal("1.0000004"),BigDecimal.ONE,"tCO2e",null,null,"hash")));
            return new MonthlyComparisonService.Period(m,m.atStartOfDay(zone).toInstant(),m.plusMonths(1).atStartOfDay(zone).toInstant(),List.of(
                    new MonthlyComparisonService.Value("ELECTRICITY_KWH","kWh",BigDecimal.TEN,"AVAILABLE","bill-"+m,"hash","unit"),
                    new MonthlyComparisonService.Value("ELECTRICITY_TCE","tce",BigDecimal.ONE,"AVAILABLE","bill-"+m,"hash","unit"),
                    new MonthlyComparisonService.Value("TOTAL_EMISSION","tCO2e",BigDecimal.ONE,"AVAILABLE",batch,"hash","rounding")));
        });
        when(rules.areaSlices(anyString(),any(),any(),any())).thenAnswer(call->{LocalDate start=call.getArgument(2),end=call.getArgument(3);
            return List.of(new AreaTimeline.Slice(AreaTimelineTest.version("A","100",start,end),start,end));});
    }
    @Test void aggregatesRawPublishedMonthsAndUsesSameCutoffLastYearAndDayWeightedArea() {
        months();var response=service.overview(1,roles,"B",2024,"MONTHLY_YTD",2,"Asia/Shanghai");
        assertThat(response.current().metrics().get(2).value()).isEqualByComparingTo("2.000001");
        assertThat(response.current().metrics().get(3).value()).isEqualByComparingTo("20.000008");
        assertThat(response.samePeriodLastYear().endExclusive()).isEqualTo(LocalDate.of(2023,3,1));
        assertThat(response.current().metrics().get(2).sources()).extracting(AnnualOverviewService.Source::batchId).containsExactly("latest-2024-01-01","latest-2024-02-01");
        assertThat(response.evidenceHash()).hasSize(64);
    }
    @Test void missingMonthsAreNotZeroAndMissingAreaDoesNotRemoveEmission() {
        months();doReturn(List.of()).when(rules).areaSlices(anyString(),any(),any(),any());
        var response=service.overview(1,roles,"B",2024,"MONTHLY_YTD",12,"Asia/Shanghai");
        assertThat(response.current().metrics().get(2).status()).isEqualTo("AVAILABLE");
        assertThat(response.current().metrics().get(3).status()).isEqualTo("AREA_GAP");
        assertThat(response.current().metrics().get(3).value()).isNull();
    }
    @Test void formalYearNeverFallsBackToMonthsOrCurrentArea() {
        var response=service.overview(1,roles,"B",2024,"FORMAL_YEAR",null,"Asia/Shanghai");
        assertThat(response.current().metrics()).allSatisfy(m->assertThat(m.value()).isNull());
        verifyNoInteractions(monthly,rules);
    }
    @Test void missingCarbonMonthDoesNotRemoveAvailableElectricityOrInventZero() {
        months();LocalDate month=LocalDate.of(2024,2,1);var zone=ZoneId.of("Asia/Shanghai");
        var original=monthly.period(1,roles,"B",month,zone);var values=new ArrayList<>(original.metrics());
        values.set(2,new MonthlyComparisonService.Value("TOTAL_EMISSION","tCO2e",null,"NO_COMPLETE_CARBON_RESULT",null,null,null));
        when(monthly.period(1,roles,"B",month,zone)).thenReturn(new MonthlyComparisonService.Period(month,original.startInclusive(),original.endExclusive(),values));
        var result=service.overview(1,roles,"B",2024,"MONTHLY_YTD",2,zone.getId());
        assertThat(result.current().metrics().get(0).status()).isEqualTo("AVAILABLE");
        assertThat(result.current().metrics().get(2).value()).isNull();
        assertThat(result.current().metrics().get(2).missingMonths()).containsExactly(month);
        assertThat(result.comparisons().get(2).yearOnYear().status()).isEqualTo("CURRENT_UNAVAILABLE");
    }
    @Test void formalYearKeepsItsFrozenAreaVersionAndDoesNotConsultChangedArea() {
        var batch=mock(CalculationBatch.class);when(batch.batchId()).thenReturn("published-year");
        when(carbon.findCurrentFormal(eq("B"),eq(PeriodType.YEAR),any(),any(),eq("Asia/Shanghai"))).thenReturn(batch);
        when(carbon.listSummaries("published-year")).thenReturn(List.of(
                new SummaryMetric("TOTAL_EMISSION","TOTAL",BigDecimal.TEN,BigDecimal.TEN,"tCO2e",null,null,"emission-hash"),
                new SummaryMetric("AREA_INTENSITY","TOTAL",BigDecimal.ONE,BigDecimal.ONE,"kgCO2e/m²","old-frozen-area",null,"area-hash")));
        var result=service.overview(1,roles,"B",2024,"FORMAL_YEAR",null,"Asia/Shanghai");
        assertThat(result.current().metrics().get(1).value()).isEqualByComparingTo("1");
        assertThat(result.current().metrics().get(1).sources().getFirst().versionId()).isEqualTo("old-frozen-area");
        verifyNoInteractions(monthly,rules);
    }
    @Test void authorizationAndInputFailuresPrecedeDataRead() {
        doThrow(new com.platform.framework.exception.BusinessException(403,"denied")).when(auth).requireReader(1,roles,"B");
        assertThatThrownBy(()->service.overview(1,roles,"B",2024,"MONTHLY_YTD",2,"Asia/Shanghai")).hasMessageContaining("denied");
        verifyNoInteractions(carbon,rules,monthly);
        assertThatThrownBy(()->service.overview(1,roles,"B",2024,"MONTHLY_YTD",null,"Asia/Shanghai")).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        assertThatThrownBy(()->service.overview(1,roles,"B",2024,"FORMAL_YEAR",2,"Asia/Shanghai")).isInstanceOf(com.platform.framework.exception.BusinessException.class);
    }
}
