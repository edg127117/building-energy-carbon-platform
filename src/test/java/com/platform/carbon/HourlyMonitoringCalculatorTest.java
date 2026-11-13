package com.platform.carbon;

import com.platform.energy.classification.ClassificationCore;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HourlyMonitoringCalculatorTest {
    final HourlyCarbonInputService input=mock(HourlyCarbonInputService.class);
    final HourlyCarbonRuleService rules=mock(HourlyCarbonRuleService.class);
    final DynamicElectricityRepository dynamic=mock(DynamicElectricityRepository.class);
    final CarbonAuthorization auth=mock(CarbonAuthorization.class);
    final HourlyMonitoringCalculator calculator=new HourlyMonitoringCalculator(input,rules,dynamic,auth,"FORMAL");
    final Instant start=Instant.parse("2026-11-10T01:00:00Z");

    @Test void reviewedBatchSharesOnlyOneClosedDayCaptureAndRejectsAnotherDay() {
        var day=start.atZone(HourlyMonitoringCalculator.ZONE).toLocalDate();
        var from=day.atStartOfDay(HourlyMonitoringCalculator.ZONE).toInstant();
        var asOf=from.plusSeconds(90000);
        var batch=calculator.reviewedBatch(day,asOf);
        when(input.read(anyLong(),anyCollection(),any(),any(),any(),any(),any(),any(),any()))
                .thenThrow(new BusinessException(409,"MISSING","no source"));
        calculator.calculateReviewed(7,Set.of("ENERGY_MANAGER"),"B",from,batch);
        calculator.calculateReviewed(7,Set.of("ENERGY_MANAGER"),"B",from.plusSeconds(3600),batch);
        verify(input,times(2)).read(eq(7L),anyCollection(),argThat(r->r.calculationAsOf().equals(asOf)),any(),any(),
                eq(from),eq(from.plusSeconds(86400)),same(batch.capture()),same(batch.deadline()));
        assertThatThrownBy(()->calculator.calculateReviewed(7,Set.of("ENERGY_MANAGER"),"B",from.plusSeconds(86400),batch))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->calculator.reviewedBatch(day,from.plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    void source(String quantity,Instant end) {
        var evidence=CarbonEvidence.object();var energy=evidence.putArray("energies").addObject();energy.put("energyItemCode","ELECTRICITY");
        var point=energy.putObject("pointInputs").putObject("P");point.put("actualStart",start.minusSeconds(120).toString());point.put("actualEnd",end.toString());
        var q=new BigDecimal(quantity);
        var slice=new ClassificationCore.SourceSlice("P",q,Set.of("P"),false,List.of("fact"),Map.of());
        energy.set("projectApplied",CarbonEvidence.tree(new CarbonProjectCore.Applied(q,BigDecimal.ZERO,List.of(slice),List.of())));
        when(input.read(anyLong(),anyCollection(),any(),any(),any(),any(),any(),any(),any())).thenReturn(new HourlyCarbonInputService.Input(
                List.of(new HourlyCarbonInputService.Activity("ELECTRICITY",q,energy)),List.of("s"),List.of("Q0:1"),List.of("project"),List.of("relation"),evidence));
        var factor=new DynamicElectricityCore.Factor("f","*",start.minusSeconds(360),start.plusSeconds(3600),new BigDecimal("0.5"),"SIMULATED","test","TEST","test");
        when(dynamic.factors(anyString(),any(),any())).thenReturn(List.of(new DynamicElectricityRepository.FactorRow(factor,1,"i","h","h")));
        when(rules.calculate(any(),any())).thenAnswer(call->{var activity=(CarbonModels.ActivitySegment)call.getArgument(0);
            return new HourlyCarbonRuleService.Calculated(activity.quantity().multiply(new BigDecimal("0.6")),"s","f",null,"r","CO2_ONLY",CarbonEvidence.object());});
    }
    @Test void closedHourUsesSameElectricityForBothCarbonSeriesAndRetainsActualBoundaries() {
        source("10",start.plusSeconds(3480));
        var result=calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"RAW_CUMULATIVE",start.plusSeconds(3700));
        var point=result.point();assertThat(point.electricityKwh()).isEqualByComparingTo("10");
        assertThat(point.staticEmissionTco2e()).isEqualByComparingTo("0.006");assertThat(point.dynamicEmissionTco2()).isEqualByComparingTo("0.005");
        assertThat(point.actualStart()).isEqualTo(start.minusSeconds(120));assertThat(point.actualEnd()).isEqualTo(start.plusSeconds(3480));
        assertThat(point.approximate()).isTrue();assertThat(point.staticGasCoverage()).isEqualTo("CO2_ONLY");
        verify(dynamic).factors("B",start.minusSeconds(120),start.plusSeconds(3480));
    }
    @Test void reviewedBatchKeepsExactlyTheSameElectricityAndCarbonPointAsSingleHourReading() {
        source("10",start.plusSeconds(3600));
        var day=start.atZone(HourlyMonitoringCalculator.ZONE).toLocalDate();
        var asOf=day.plusDays(1).atStartOfDay(HourlyMonitoringCalculator.ZONE).toInstant().plusSeconds(1);
        var single=calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"REVIEWED_INTERVAL",asOf);
        var batch=calculator.calculateReviewed(7,Set.of("ENERGY_MANAGER"),"B",start,calculator.reviewedBatch(day,asOf));
        assertThat(batch.point()).isEqualTo(single.point());
        assertThat(batch.point().electricityKwh()).isEqualByComparingTo("10");
        assertThat(batch.point().staticEmissionTco2e()).isEqualByComparingTo("0.006");
        assertThat(batch.point().dynamicEmissionTco2()).isEqualByComparingTo("0.005");
    }
    @Test void currentHourAccumulatesAndDoesNotPretendStaticHourIsFinal() {
        source("3",start.plusSeconds(900));
        var point=calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"RAW_CUMULATIVE",start.plusSeconds(1740)).point();
        assertThat(point.electricityStatus()).isEqualTo("ACCUMULATING");assertThat(point.dynamicEmissionTco2()).isEqualByComparingTo("0.0015");
        assertThat(point.staticStatus()).isEqualTo("NOT_ENDED");assertThat(point.staticEmissionTco2e()).isNull();verifyNoInteractions(rules);
    }
    @Test void trueZeroRemainsZeroWithoutInventingAnEquivalentFactor() {
        source("0",start.plusSeconds(3600));
        var point=calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"RAW_CUMULATIVE",start.plusSeconds(3601)).point();
        assertThat(point.electricityKwh()).isZero();assertThat(point.staticEmissionTco2e()).isZero();assertThat(point.dynamicEmissionTco2()).isZero();
        assertThat(point.staticFactorKgCo2ePerKwh()).isNull();assertThat(point.dynamicFactorKgCo2PerKwh()).isNull();
    }
    @Test void failedCarbonDependencyCannotEraseUsableElectricityOrOtherSeries() {
        source("10",start.plusSeconds(3600));
        doThrow(new BusinessException(503,"RULE_DOWN","unavailable")).when(rules).calculate(any(),any());
        var point=calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"RAW_CUMULATIVE",start.plusSeconds(3601)).point();
        assertThat(point.electricityKwh()).isEqualByComparingTo("10");assertThat(point.staticStatus()).isEqualTo("FAILED");
        assertThat(point.dynamicEmissionTco2()).isEqualByComparingTo("0.005");
        when(dynamic.factors(anyString(),any(),any())).thenReturn(List.of());
        assertThat(calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"RAW_CUMULATIVE",start.plusSeconds(3601)).point().dynamicStatus()).isEqualTo("MISSING");
    }
    @Test void missingSourceCannotTurnIntoZeroAndPermissionFailureIsNotDataMissing() {
        when(input.read(anyLong(),anyCollection(),any(),any(),any(),any(),any(),any(),any())).thenThrow(new BusinessException(409,"ANCHOR_MISSING","missing"));
        var point=calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"RAW_CUMULATIVE",start.plusSeconds(3601)).point();
        assertThat(point.electricityKwh()).isNull();assertThat(point.electricityStatus()).isEqualTo("MISSING");
        doThrow(new BusinessException(403,"DENIED","denied")).when(auth).requireCalculationRunner(anyLong(),anyCollection(),anyString());
        assertThatThrownBy(()->calculator.calculate(7,Set.of("ENERGY_MANAGER"),"B",start,"RAW_CUMULATIVE",start.plusSeconds(3601))).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getCode()).isEqualTo(403);
    }
}
