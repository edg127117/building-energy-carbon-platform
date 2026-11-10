package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonPlanContracts.*;
import static com.platform.carbon.CarbonModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CarbonPlanServiceTest {
    private final CarbonPlanRepository repository=mock(CarbonPlanRepository.class);
    private final CarbonCalculationRepository carbon=mock(CarbonCalculationRepository.class);
    private final CarbonAuthorization authorization=mock(CarbonAuthorization.class);
    private final AuditEvidenceWriter audit=mock(AuditEvidenceWriter.class);
    private final AuditGovernanceProperties properties=new AuditGovernanceProperties();
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final CarbonPlanCore core=new CarbonPlanCore();
    private CarbonPlanService service;
    private final List<String> roles=List.of("PLATFORM_ADMIN");
    @BeforeEach void setup() throws Exception {
        properties.setEnvironmentMode(AuditEnvironmentMode.TEST);
        service=at("2026-10-15T00:00:00Z");
        when(repository.buildingExists("b")).thenReturn(true);
        when(repository.findForUpdate(anyString(),anyString())).thenAnswer(call->repository.find(call.getArgument(0),call.getArgument(1)));
        active(2026);
    }
    @Test void cumulativeThroughSelectedMonthPreservesPositiveNegativeZeroAndDoesNotSumOnlySelectedMonth() {
        month(2026,1,"40");month(2026,2,"64");month(2026,3,"36");
        var feb=service.query(1,roles,"b",2026,"2026-02");
        assertThat(feb.actualCumulative()).isEqualByComparingTo("104");assertThat(feb.remainingQuota()).isEqualByComparingTo("32");
        var mar=service.query(1,roles,"b",2026,"2026-03");
        assertThat(mar.remainingQuota()).isEqualByComparingTo("-4");
        month(2026,3,"32");assertThat(service.query(1,roles,"b",2026,"2026-03").remainingQuota()).isEqualByComparingTo("0");
        month(2026,1,"0");assertThat(service.query(1,roles,"b",2026,"2026-01").actualCumulative()).isEqualByComparingTo("0");
        assertThat(feb.months()).hasSize(12);assertThat(feb.annualBaseline()).isEqualByComparingTo(mar.annualBaseline());
        verify(carbon,never()).listBatches(anyString());
    }
    @Test void missingMonthNeverBecomesZeroOrPartialSumAndKnownLaterBarsRemain() {
        month(2026,1,"10");month(2026,3,"30");
        var response=service.query(1,roles,"b",2026,"2026-03");
        assertThat(response.status()).isEqualTo("INCOMPLETE_ACTUAL");assertThat(response.missingMonths()).containsExactly(2);
        assertThat(response.actualCumulative()).isNull();assertThat(response.remainingQuota()).isNull();
        assertThat(response.months().get(2).actual()).isEqualByComparingTo("30");
        assertThat(response.throughMonth()).isEqualTo("2026-03");
    }
    @Test void januaryFutureHistoricalAndUnendedMonthsRespectBeijingNaturalMonth() throws Exception {
        service=at("2025-12-31T16:01:00Z");
        var jan=service.query(1,roles,"b",2026,null);
        assertThat(jan.status()).isEqualTo("NO_COMPLETED_MONTH");assertThat(jan.throughMonth()).isNull();
        assertThat(jan.remainingQuota()).isNull();verifyNoInteractions(carbon);
        assertThat(service.query(1,roles,"b",2026,"2026-01").status()).isEqualTo("PERIOD_NOT_ENDED");
        service=at("2026-10-15T00:00:00Z");
        assertThat(service.query(1,roles,"b",2026,null).throughMonth()).isEqualTo("2026-09");
        assertThat(service.query(1,roles,"b",2026,"2026-10").status()).isEqualTo("PERIOD_NOT_ENDED");
        active(2025);assertThat(service.query(1,roles,"b",2025,null).throughMonth()).isEqualTo("2025-12");
        active(2027);assertThat(service.query(1,roles,"b",2027,null).status()).isEqualTo("NO_COMPLETED_MONTH");
        assertThatThrownBy(()->service.query(1,roles,"b",2026,"2025-12")).isInstanceOf(BusinessException.class);
    }
    @Test void formalBaselineReadsTwelveExactPeriodsAndFreezesValuesWhileSourceLaterChanges() throws Exception {
        for(int m=1;m<=12;m++) month(2025,m,"10");
        var baseline=service.baselineYear(1,roles,"b",2025);
        assertThat(baseline.annualBaseline()).isEqualByComparingTo("120");assertThat(baseline.months()).allSatisfy(m->assertThat(m.batchId()).startsWith("formal-2025-"));
        var config=new Configuration(baseline,new BigDecimal("15"));
        when(repository.active("b",2026)).thenReturn(row("ACTIVE",config,1));
        month(2025,1,"999");
        assertThat(service.query(1,roles,"b",2026,"2026-01").annualBaseline()).isEqualByComparingTo("120");
        verify(carbon,never()).listBatches(anyString());
        when(carbon.findCurrentFormal(eq("b"),eq(PeriodType.MONTH),eq(start(2025,2)),any(),eq("Asia/Shanghai"))).thenReturn(null);
        assertThatThrownBy(()->service.baselineYear(1,roles,"b",2025)).hasMessageContaining("2月");
    }
    @Test void unauthorizedMissingPlanAndTestNatureCannotSilentlyUseExamples() {
        when(repository.active("b",2026)).thenReturn(null);
        var response=service.query(1,roles,"b",2026,null);
        assertThat(response.status()).isEqualTo("NO_ACTIVE_PLAN");assertThat(response.annualBaseline()).isNull();assertThat(response.months()).allSatisfy(m->assertThat(m.baseline()).isNull());
        properties.setEnvironmentMode(AuditEnvironmentMode.PRODUCTION);
        assertThatThrownBy(()->service.imported(1,roles,"b",CarbonPlanFixture.configuration().baseline())).hasMessageContaining("TEST");
        doThrow(new BusinessException(403,"无建筑权限")).when(authorization).requireReader(1,roles,"b");
        clearInvocations(carbon);
        assertThatThrownBy(()->service.query(1,roles,"b",2026,null)).hasMessageContaining("权限");verifyNoInteractions(carbon);
    }
    @Test void duplicateMetricAndInvalidUnitRemainUnavailableRatherThanSummed() {
        month(2026,1,"10");
        var metric=new SummaryMetric("TOTAL_EMISSION","TOTAL",BigDecimal.ONE,BigDecimal.ONE,"tCO2e",null,null,"hash");
        when(carbon.listSummaries("formal-2026-1")).thenReturn(List.of(metric,metric));
        assertThat(service.query(1,roles,"b",2026,"2026-01").actualCumulative()).isNull();
        when(carbon.listSummaries("formal-2026-1")).thenReturn(List.of(new SummaryMetric("TOTAL_EMISSION","TOTAL",BigDecimal.ONE,BigDecimal.ONE,"kgCO2e",null,null,"hash")));
        assertThat(service.query(1,roles,"b",2026,"2026-01").actualCumulative()).isNull();
    }
    @Test void writesReuseDutySeparationRevisionAndAuditAndRejectForgedFormalSource() throws Exception {
        var draft=row("DRAFT",CarbonPlanFixture.configuration(),1);
        when(repository.find("b","v")).thenReturn(draft);
        when(repository.save(any(),eq(7L),anyString(),anyString())).thenReturn(0);
        assertThatThrownBy(()->service.save(1,roles,"b","v",new Save(7L,CarbonPlanFixture.configuration(),"测试保存"))).hasMessageContaining("修订");
        verify(authorization).requireRuleMaintainer(1,roles,"b");verifyNoInteractions(audit);
        when(repository.transition(any(),eq(0L),eq("PENDING_REVIEW"),eq("APPROVED"),eq(2L),anyString())).thenReturn(1);
        service.transition(2,roles,"b","v","approve",new Transition(0L,"独立审核测试"));
        verify(authorization).requireSeparation(1,2);verify(authorization).requireRuleReviewer(2,roles,"b");verify(audit).append(any());
        var fake=CarbonPlanFixture.configuration().baseline();
        var formal=new Baseline("FORMAL_YEAR",2025,fake.timezoneId(),fake.boundary(),fake.source(),fake.months(),fake.annualBaseline(),null);
        assertThatThrownBy(()->service.save(1,roles,"b","v",new Save(0L,new Configuration(formal,BigDecimal.ONE),"伪造正式来源"))).isInstanceOf(BusinessException.class);
    }
    @Test void existingDevelopmentSelfApprovalExceptionIsTruthfullyAudited() throws Exception {
        properties.setAllowSelfApproval(true);
        when(repository.find("b","v")).thenReturn(row("PENDING_REVIEW",CarbonPlanFixture.configuration(),1));
        when(repository.transition(any(),eq(0L),eq("PENDING_REVIEW"),eq("APPROVED"),eq(1L),anyString())).thenReturn(1);
        service.transition(1,roles,"b","v","approve",new Transition(0L,"TEST既有自审例外"));
        verify(authorization).requireSeparation(1,1);
        verify(audit).append(argThat(e->e.selfApprovalDevMode() && e.environmentMode()==AuditEnvironmentMode.TEST));
    }
    @Test void guessedFutureRevisionCannotActivateAnEarlierValidatedSnapshot() throws Exception {
        when(repository.find("b","v")).thenReturn(row("APPROVED",CarbonPlanFixture.configuration(),1));
        assertThatThrownBy(()->service.transition(3,roles,"b","v","activate",new Transition(7L,"并发修订保护"))).hasMessageContaining("修订");
        verify(repository,never()).activate(any(),anyLong(),anyLong(),anyString());
        verifyNoInteractions(audit);
    }
    private void active(int year) throws Exception { when(repository.active("b",year)).thenReturn(row("ACTIVE",CarbonPlanFixture.configuration(),1)); }
    private CarbonPlanRepository.Row row(String status,Configuration config,long author) throws Exception {
        return new CarbonPlanRepository.Row("v","b",2026,1,0,status,json.writeValueAsString(core.normalize(config,"b")),null,author,2L,3L,0L);
    }
    private CarbonPlanService at(String time) { return new CarbonPlanService(repository,carbon,authorization,core,json,audit,properties,Clock.fixed(Instant.parse(time),ZoneOffset.UTC)); }
    private static Instant start(int year,int month) { return YearMonth.of(year,month).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    private void month(int year,int month,String value) {
        String id="formal-"+year+"-"+month;var batch=mock(CalculationBatch.class);when(batch.batchId()).thenReturn(id);when(batch.roundingPolicyVersionId()).thenReturn("rounding-v1");
        when(carbon.findCurrentFormal("b",PeriodType.MONTH,start(year,month),YearMonth.of(year,month).plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),"Asia/Shanghai")).thenReturn(batch);
        var n=new BigDecimal(value);when(carbon.listSummaries(id)).thenReturn(List.of(new SummaryMetric("TOTAL_EMISSION","TOTAL",n,n,"tCO2e",null,null,"hash-"+id)));
    }
}
