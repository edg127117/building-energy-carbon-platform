package com.platform.carbon;

import com.platform.carbon.HourlyCarbonContracts.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际小时编排与十进制合计；仅替换授权、来源端口及持久化，不连接现场。 */
class HourlyCarbonServiceTest {
    private final CarbonAuthorization auth=mock(CarbonAuthorization.class);
    private final HourlyCarbonInputService inputs=mock(HourlyCarbonInputService.class);
    private final HourlyCarbonRuleService rules=mock(HourlyCarbonRuleService.class);
    private final HourlyCarbonRepository repository=mock(HourlyCarbonRepository.class);
    private final CarbonProperties properties=new CarbonProperties();
    private HourlyCarbonService service;
    private static final Set<String> ROLES=Set.of("ENERGY_MANAGER");
    private static final LocalDate DATE=LocalDate.of(2026,1,1);
    private static final Instant START=Instant.parse("2025-12-31T16:00:00Z");

    @BeforeEach void setup() {
        service=new HourlyCarbonService(auth,inputs,rules,repository,properties);
        var trace=CarbonEvidence.object();trace.put("source","test");
        when(inputs.read(anyLong(),anyCollection(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new HourlyCarbonInputService.Input(List.of(new HourlyCarbonInputService.Activity("ELECTRICITY",BigDecimal.TEN,trace)),
                        List.of("input-hash"),List.of("Q0:1"),List.of("project-v1"),List.of("relation-v1"),trace));
        when(rules.calculate(any(),any())).thenReturn(new HourlyCarbonRuleService.Calculated(new BigDecimal("5.827"),
                "factor-v1","formula-v1","gwp-v1","round-v1","CO2_ONLY_ELECTRICITY",trace));
        when(repository.publish(anyLong(),any(),anyString(),any(),anyString())).thenAnswer(call->
                new HourlyCarbonRepository.Stored(HourlyCarbonContracts.identity(call.getArgument(3),"batch",1),call.getArgument(4)));
    }
    private Request request(Instant asOf) {return new Request("TEST",DATE,asOf,"key","RAW_CUMULATIVE","SIMULATED","manifest.json","FORMAL");}
    private View calculate(Instant asOf) {return service.calculate(1,ROLES,request(asOf));}

    @Test void twentyFourHoursAndDailyTotalUseUnroundedDecimalResults() {
        var view=calculate(START.plusSeconds(86400));
        assertThat(view.points()).hasSize(24).allSatisfy(p->{assertThat(p.status()).isEqualTo("COMPLETE");assertThat(p.emissionKgCo2e()).isEqualByComparingTo("5.827");});
        assertThat(view.state()).isEqualTo("COMPLETE");assertThat(view.dueHours()).isEqualTo(24);assertThat(view.availableHours()).isEqualTo(24);
        assertThat(view.dailyTotal()).isEqualByComparingTo("139.848");
        assertThat(view.unit()).isEqualTo("KG_CO2E");assertThat(view.resultNature()).isEqualTo("MONITORING");
        assertThat(view.declaredSourceNature()).isEqualTo("SIMULATED");
        verify(auth).requireCalculationRunner(1,ROLES,"TEST");
        var activity=org.mockito.ArgumentCaptor.forClass(CarbonModels.ActivitySegment.class);
        verify(rules,times(24)).calculate(activity.capture(),eq(CarbonModels.UsageNature.FORMAL));
        assertThat(activity.getValue().periodType()).isEqualTo(CarbonModels.PeriodType.HOUR);
        assertThat(activity.getValue().resultNature()).isEqualTo(CarbonModels.ResultNature.MONITORING);
        assertThat(view.points().getFirst().startInclusive()).isEqualTo(START);
        assertThat(view.points().getLast().endExclusive()).isEqualTo(START.plusSeconds(86400));
    }
    @Test void partialDayEndsAtCompletedHourAndPreservesTrueZero() {
        when(rules.calculate(any(),any())).thenReturn(new HourlyCarbonRuleService.Calculated(BigDecimal.ZERO,"f","formula",null,"round","CO2E",CarbonEvidence.object()));
        var view=calculate(START.plusSeconds(10*3600+1800));
        assertThat(view.dueHours()).isEqualTo(10);assertThat(view.availableHours()).isEqualTo(10);assertThat(view.dailyTotal()).isNull();
        assertThat(view.points().subList(0,10)).allSatisfy(p->assertThat(p.emissionKgCo2e()).isZero());
        assertThat(view.points().subList(10,24)).allSatisfy(p->{assertThat(p.status()).isEqualTo("NOT_DUE");assertThat(p.emissionKgCo2e()).isNull();});
        verify(inputs,times(10)).read(eq(1L),eq(ROLES),any(),any(),any(),eq(START),eq(START.plusSeconds(10*3600)),any(),any());
    }
    @Test void missingAnchorAndFactorConflictLeaveGapsWithoutDailyTotal() {
        when(inputs.read(anyLong(),anyCollection(),any(),eq(START),any(),any(),any(),any(),any()))
                .thenThrow(CarbonErrors.error(409,CarbonErrors.ACTIVITY_INCOMPLETE,"缺少起点"));
        when(rules.calculate(argThat(a->a!=null&&a.startInclusive().equals(START.plusSeconds(3600))),any()))
                .thenThrow(CarbonErrors.error(409,CarbonErrors.FACTOR_CONFLICT,"因子冲突"));
        var view=calculate(START.plusSeconds(86400));
        assertThat(view.availableHours()).isEqualTo(22);assertThat(view.dailyTotal()).isNull();
        assertThat(view.points().get(0).status()).isEqualTo("INCOMPLETE");assertThat(view.points().get(1).status()).isEqualTo("BLOCKED");
        assertThat(view.points().subList(0,2)).allSatisfy(p->assertThat(p.emissionKgCo2e()).isNull());
    }
    @Test void upstreamFailureDoesNotPublishNewRevision() {
        when(inputs.read(anyLong(),anyCollection(),any(),any(),any(),any(),any(),any(),any()))
                .thenThrow(CarbonErrors.error(503,CarbonErrors.DEPENDENCY_UNAVAILABLE,"来源不可用"));
        assertThatThrownBy(()->calculate(START.plusSeconds(86400))).isInstanceOf(BusinessException.class)
                .extracting(ex->((BusinessException)ex).getCode()).isEqualTo(503);
        verify(repository,never()).publish(anyLong(),any(),any(),any(),any());
    }
    @Test void idempotentReplayDoesNotReadSourcesOrRules() {
        var old=calculate(START.plusSeconds(86400));clearInvocations(inputs,rules,repository);
        when(repository.replay(eq("TEST"),eq("key"),anyString())).thenReturn(new HourlyCarbonRepository.Stored(old,"{}"));
        assertThat(calculate(START.plusSeconds(86400))).isSameAs(old);
        verifyNoInteractions(inputs,rules);verify(repository,never()).publish(anyLong(),any(),any(),any(),any());
    }
    @Test void missingQueryAndRevisionOnlyReadPersistedResults() {
        var missing=service.query(1,ROLES,"TEST",DATE,null);
        assertThat(missing.state()).isEqualTo("MISSING");assertThat(missing.points()).hasSize(24);
        assertThat(missing.points()).allSatisfy(p->assertThat(p.status()).isEqualTo("MISSING"));
        assertThat(missing.calculationAsOf()).isNull();assertThat(missing.dailyTotal()).isNull();
        assertThatThrownBy(()->service.query(1,ROLES,"TEST",DATE,2)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inputs,rules);
    }
    @Test void rejectsInvalidRequestAndUnauthorizedBuildingBeforeSourceRead() {
        assertThatThrownBy(()->calculate(Instant.now().plusSeconds(60))).isInstanceOf(BusinessException.class);
        doThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"无建筑权限")).when(auth).requireCalculationRunner(1,ROLES,"TEST");
        assertThatThrownBy(()->calculate(START.plusSeconds(86400))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inputs,rules,repository);
    }
    @Test void timeoutDoesNotPublishAfterSourceReturns() throws Exception {
        properties.setCalculationTimeout(Duration.ofMillis(60));
        when(inputs.read(anyLong(),anyCollection(),any(),any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            try {Thread.sleep(200);}catch(InterruptedException ignored) { }
            throw CarbonCalculationDeadline.timeout();
        });
        assertThatThrownBy(()->calculate(START.plusSeconds(86400))).isInstanceOf(BusinessException.class)
                .extracting(ex->((BusinessException)ex).getErrorCode()).isEqualTo(CarbonErrors.CALCULATION_TIMEOUT);
        verify(repository,never()).publish(anyLong(),any(),any(),any(),any());
    }
}
