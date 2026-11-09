package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real DAY service, transaction and H2 persistence; approved input and rule ports are isolated fixtures. */
class DailyStaticCarbonPipelineTest {
    final CarbonAuthorization auth=mock(CarbonAuthorization.class);
    final DailyStaticCarbonInput inputs=mock(DailyStaticCarbonInput.class);
    final HourlyCarbonRuleService rules=mock(HourlyCarbonRuleService.class);
    final DailyTrendSummaryRepository versions=mock(DailyTrendSummaryRepository.class);
    final AuditEvidenceWriter audit=mock(AuditEvidenceWriter.class);
    static final LocalDate DATE=LocalDate.of(2026,9,1);
    static final Instant START=Instant.parse("2026-08-31T16:00:00Z");
    static final Set<String> ROLES=Set.of("ENERGY_MANAGER");
    JdbcTemplate jdbc;
    DailyStaticCarbonRepository repository;
    DailyStaticCarbonService service;
    @BeforeEach void setup() throws Exception {
        var data=new DriverManagerDataSource("jdbc:h2:mem:daily-static-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(data);
        String ddl=java.nio.file.Files.readString(java.nio.file.Path.of("src/env/init/V113__mysql_daily_static_carbon.sql"));
        jdbc.execute(ddl.replaceAll("(?s)ENGINE=InnoDB.*?;",";"));
        repository=new DailyStaticCarbonRepository(jdbc,new ObjectMapper().findAndRegisterModules(),audit,new AuditGovernanceProperties());
        service=new DailyStaticCarbonService(auth,inputs,rules,repository,versions,new CarbonProperties(),new DataSourceTransactionManager(data));
        when(versions.inputVersion("B",YearMonth.of(2026,9))).thenReturn("a".repeat(64));
        when(inputs.read(1,ROLES,"B",DATE)).thenReturn(input("100"));
        when(rules.calculate(any(),eq(CarbonModels.UsageNature.FORMAL))).thenReturn(rule());
    }
    static DailyStaticCarbonContracts.Request request(String key) {
        return new DailyStaticCarbonContracts.Request("B",DATE,START.plusSeconds(86400),key,"SIMULATED","reviewed-day-fixture","FORMAL");
    }
    static DailyStaticCarbonInput.Input input(String quantity) {
        var evidence=CarbonEvidence.object();evidence.put("periodType","DAY");evidence.put("quantity",quantity);evidence.put("manualFactId","reviewed-day-fact");
        evidence.putObject("classification").put("versionId","C");evidence.putObject("project").put("versionId","P");
        evidence.putObject("projection").put("relationVersionId","R").put("resultNature","SOURCE_LABELLED_STATISTICS");
        return new DailyStaticCarbonInput.Input(new BigDecimal(quantity),"C","R","P","SOURCE_LABELLED_STATISTICS",evidence);
    }
    static HourlyCarbonRuleService.Calculated rule() {
        var e=CarbonEvidence.object();e.put("factorVersionId","F");e.put("formulaVersionId","FORMULA");e.put("gwpValue","1");e.put("activityQuantity","100");e.put("rawKgCO2e","56.000000000000000001");
        var f=e.putObject("factor");f.put("scopeType","SCOPE_2");f.put("category","PURCHASED_ELECTRICITY_LOCATION");
        f.put("energyItemCode","ELECTRICITY");f.put("inputUnitCode","KWH");f.put("resultBasis","GAS_MASS");
        f.put("gasCode","CO2");f.put("gasCoverage","CO2_ONLY_ELECTRICITY");
        var component=f.putArray("components").addObject();component.put("type","DIRECT_EMISSION_FACTOR");component.put("unit","KG_CO2/KWH");component.put("value","0.56");
        e.putObject("gwp").put("gasCode","CO2");e.putObject("formulaDefinition").put("versionId","FORMULA");
        e.putObject("roundingDefinition").put("versionId","ROUND");e.putObject("factorSource").put("sourceVersionId","SOURCE");
        return new HourlyCarbonRuleService.Calculated(new BigDecimal("56.000000000000000001"),"F","FORMULA","GWP","ROUND","CO2_ONLY_ELECTRICITY",e);
    }
    @Test void publishesActualDayWithFrozenRulesAndUnchangedSourceLabels() {
        var view=service.calculate(1,ROLES,request("one"));
        assertThat(view.periodType()).isEqualTo("DAY");assertThat(view.resultNature()).isEqualTo("MONITORING");
        assertThat(view.declaredSourceNature()).isEqualTo("SIMULATED");
        assertThat(view.electricitySourceNature()).isEqualTo("SOURCE_LABELLED_STATISTICS");
        assertThat(view.startInclusive()).isEqualTo(START);assertThat(view.endExclusive()).isEqualTo(START.plusSeconds(86400));
        assertThat(view.emissionKgCo2e()).isEqualByComparingTo("56.000000000000000001");
        var activity=org.mockito.ArgumentCaptor.forClass(CarbonModels.ActivitySegment.class);
        verify(rules,times(2)).calculate(activity.capture(),eq(CarbonModels.UsageNature.FORMAL));
        assertThat(activity.getAllValues()).allSatisfy(a->{assertThat(a.periodType()).isEqualTo(CarbonModels.PeriodType.DAY);assertThat(a.quantity()).isEqualByComparingTo("100");});
        var frozen=repository.byDate("B",DATE,null);
        assertThat(frozen.view()).isEqualTo(view);
        assertThat(frozen.evidence().at("/activity/periodType").asText()).isEqualTo("DAY");
        assertThat(frozen.evidence().has("hours")).isFalse();
        assertThat(frozen.evidence().at("/calculation/formulaDefinition/versionId").asText()).isEqualTo("FORMULA");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_daily_static_batch",Integer.class)).isEqualTo(1);
        verify(audit).append(any());
    }
    @Test void replayReadsFrozenResultAndRejectsDifferentPayloadUnderSameKey() {
        var original=service.calculate(1,ROLES,request("one"));clearInvocations(inputs,rules,audit);
        assertThat(service.calculate(1,ROLES,request("one"))).isEqualTo(original);
        verifyNoInteractions(inputs,rules,audit);
        var changed=new DailyStaticCarbonContracts.Request("B",DATE,START.plusSeconds(86400),"one","SOURCE_LABELLED","different","FORMAL");
        assertThatThrownBy(()->service.calculate(1,ROLES,changed)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(CarbonErrors.IDEMPOTENCY_CONFLICT));
    }
    @Test void changedInputWithSameVersionHeadsRollsBackWithoutPublication() {
        when(inputs.read(1,ROLES,"B",DATE)).thenReturn(input("100"),input("101"));
        assertThatThrownBy(()->service.calculate(1,ROLES,request("one"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("DAILY_STATIC_SOURCE_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_daily_static_batch",Integer.class)).isZero();verifyNoInteractions(audit);
    }
    @Test void changedRulesWithSameVersionIdAbortPublication() {
        var changed=rule();((com.fasterxml.jackson.databind.node.ObjectNode)changed.evidence()).put("changedDefinition",true);
        when(rules.calculate(any(),any())).thenReturn(rule(),changed);
        assertThatThrownBy(()->service.calculate(1,ROLES,request("one"))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_daily_static_batch",Integer.class)).isZero();verifyNoInteractions(audit);
    }
    @Test void sourceHeadChangeAndAuditFailureLeaveNoBatch() {
        when(versions.inputVersion("B",YearMonth.of(2026,9))).thenReturn("a".repeat(64),"b".repeat(64));
        assertThatThrownBy(()->service.calculate(1,ROLES,request("one"))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_daily_static_batch",Integer.class)).isZero();
        when(versions.inputVersion("B",YearMonth.of(2026,9))).thenReturn("a".repeat(64));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).append(any());
        assertThatThrownBy(()->service.calculate(1,ROLES,request("two"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_daily_static_batch",Integer.class)).isZero();
    }
    @Test void bodyTamperingWithUnchangedHashIsRejectedByQueryAndReplay() {
        service.calculate(1,ROLES,request("one"));
        jdbc.update("UPDATE biz_carbon_daily_static_batch SET evidence_json='{}'");
        assertThatThrownBy(()->service.query(1,ROLES,"B",DATE,null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("DAILY_STATIC_INTEGRITY_VIOLATION"));
        assertThatThrownBy(()->service.calculate(1,ROLES,request("one"))).isInstanceOf(BusinessException.class);
    }
    @Test void changedOtherDayHeadDoesNotStaleUnchangedDayButChangedDayInputDoes() {
        service.calculate(1,ROLES,request("one"));
        when(versions.inputVersion(anyString(),any())).thenReturn("b".repeat(64));
        assertThat(service.readForTrend(1,ROLES,"B",DATE).current()).isTrue();
        when(inputs.read(1,ROLES,"B",DATE)).thenReturn(input("101"));
        assertThat(service.readForTrend(1,ROLES,"B",DATE).current()).isFalse();
    }
    @Test void unfinishedDayOrMissingScopeCannotReadOrPublish() {
        var unfinished=new DailyStaticCarbonContracts.Request("B",DATE,START.plusSeconds(3600),"one","SIMULATED","fixture","FORMAL");
        assertThatThrownBy(()->service.calculate(1,ROLES,unfinished)).isInstanceOf(BusinessException.class);
        doThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"denied")).when(auth).requireCalculationRunner(1,ROLES,"B");
        assertThatThrownBy(()->service.calculate(1,ROLES,request("one"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inputs,rules,audit);
    }
    @Test void existingDailyTrendReadsPersistedDayAndPublishesAnExplicitDayPointer() {
        var calculated=service.calculate(1,ROLES,request("one"));
        var electricity=mock(com.platform.energy.classification.BuildingDailyElectricityReader.class);
        var hourly=mock(HourlyCarbonRepository.class);
        when(electricity.read(anyLong(),anyCollection(),eq("B"),any())).thenAnswer(call->{
            LocalDate day=call.getArgument(3);Instant from=day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            return new com.platform.energy.classification.ClassificationContracts.QueryView("B","C","R","KWH",from,from.plusSeconds(86400),
                    new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"CALCULABLE","SOURCE_LABELLED_STATISTICS","BOUNDARY_TOTAL",true,List.of(),List.of(),List.of(),List.of("day-fact"));
        });
        try(var trend=new DailyElectricityCarbonService(auth,electricity,hourly,new CarbonProperties(),
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"),ZoneOffset.UTC),service)) {
            var view=trend.query(1,ROLES,"B","2026-09");var day=view.days().getFirst();
            assertThat(day.staticEmissionTco2e()).isEqualByComparingTo("0.056000000000000000001");
            assertThat(day.staticCarbonStatus()).isEqualTo("AVAILABLE");assertThat(day.hourlyBatchId()).isNull();
            assertThat(day.staticSource().grain()).isEqualTo("DAY");
            assertThat(day.staticSource().calculationBatchId()).isEqualTo(calculated.calculationBatchId());
            assertThat(day.staticSource().declaredSourceNature()).isEqualTo("SIMULATED");
            assertThat(DailyTrendSummaryContracts.from(view,"source").days().getFirst().staticSource()).isEqualTo(day.staticSource());
            when(inputs.read(1,ROLES,"B",DATE)).thenReturn(input("101"));
            var stale=trend.query(1,ROLES,"B","2026-09").days().getFirst();
            assertThat(stale.staticCarbonStatus()).isEqualTo("DAILY_STATIC_SOURCE_CHANGED");assertThat(stale.staticEmissionTco2e()).isNull();
        }
        verify(hourly,never()).publish(anyLong(),any(),anyString(),any(),anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_daily_static_batch",Integer.class)).isEqualTo(1);
    }

    @Test void independentlyValidViewHashCannotHideContradictionWithFrozenCalculation() {
        service.calculate(1,ROLES,request("one"));
        String json=jdbc.queryForObject("SELECT view_json FROM biz_carbon_daily_static_batch",String.class);
        var changed=(com.fasterxml.jackson.databind.node.ObjectNode)CarbonEvidence.parse(json);changed.put("emissionKgCo2e","999");
        String body=CarbonEvidence.canonical(changed);
        jdbc.update("UPDATE biz_carbon_daily_static_batch SET view_json=?,view_hash=?",body,CarbonCalculationCore.sha256(body));
        assertThatThrownBy(()->repository.byDate("B",DATE,null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("DAILY_STATIC_INTEGRITY_VIOLATION"));
    }

    @Test void newestDayRevisionWinsEvenWhenCallerSuppliesAnEarlierAsOf() {
        var first=new DailyStaticCarbonContracts.Request("B",DATE,START.plusSeconds(2*86400),"later-asof","SIMULATED","fixture","FORMAL");
        var original=service.calculate(1,ROLES,first);
        var correction=service.calculate(1,ROLES,request("earlier-asof-correction"));
        assertThat(correction.revision()).isEqualTo(2);
        assertThat(correction.calculationAsOf()).isBefore(original.calculationAsOf());
        assertThat(correction.activityWatermarkMeaning()).isEqualTo("CURRENT_APPROVED_AT_CALCULATION");
        assertThat(repository.byDate("B",DATE,null).view()).isEqualTo(correction);
        assertThat(repository.byDate("B",DATE,1).view()).isEqualTo(original);
    }

}
