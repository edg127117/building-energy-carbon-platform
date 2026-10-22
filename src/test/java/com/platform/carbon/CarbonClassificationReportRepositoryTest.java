package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离H2验证新增报表仓储查询与证据恢复；不是MySQL迁移或现场验收。 */
class CarbonClassificationReportRepositoryTest {
    private JdbcTemplate jdbc;
    private CarbonCalculationService calculations;
    private CarbonClassificationReportService service;
    private CalculationBatch batch;
    private final List<String> roles=List.of("ENERGY_MANAGER");

    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:report_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE biz_carbon_calculation_batch(calculation_batch_id VARCHAR(32) PRIMARY KEY,shared_evidence_json CLOB)");
        jdbc.execute("CREATE TABLE biz_carbon_calculation_item(calculation_item_id VARCHAR(32) PRIMARY KEY,calculation_batch_id VARCHAR(32),evidence_json CLOB,evidence_hash VARCHAR(64))");
        jdbc.update("INSERT INTO biz_carbon_calculation_batch VALUES ('BATCH','{}')");
        calculations=mock(CarbonCalculationService.class);
        service=new CarbonClassificationReportService(calculations,new CarbonCalculationRepository(jdbc));
        batch=mock(CalculationBatch.class);when(batch.buildingId()).thenReturn("BLD");when(batch.status()).thenReturn("COMPLETED_COMPLETE");
    }

    @Test void readsBatchEvidenceThroughActualSqlAndUsesFrozenRawPrecision() {
        String content="""
                {"schemaVersion":1,"rawKgCO2e":"50.0000000000000000001",
                 "activity":{"startInclusive":"2026-08-01T00:00:00Z"},
                 "upstream":{"sourceSlice":{"classifications":{"U":["N"]}},
                   "classificationConfiguration":{"schemes":[{"code":"U","mode":"ADDITIVE"}],
                     "nodes":[{"schemeCode":"U","code":"N","name":"空调"}]}}}
                """;
        String stored=CarbonEvidence.canonical(CarbonEvidence.parse(content));
        String hash=CarbonCalculationCore.sha256(stored);
        jdbc.update("INSERT INTO biz_carbon_calculation_item VALUES ('ITEM','BATCH',?,?)",stored,hash);
        var item=new StoredCalculationItem("ITEM","BATCH","SOURCE","CUSTOM_ELEC",ScopeType.SCOPE_2,new BigDecimal("100"),"KWH","FACTOR","FORMULA",null,new BigDecimal("50.000000000000000000"),"",hash);
        when(calculations.detail(1,roles,"BATCH")).thenReturn(new CalculationDetail(batch,List.of(item),List.of(),List.of()));
        var report=service.report(1,roles,"BATCH");
        assertThat(report.classificationComplete()).isTrue();
        assertThat(report.totalKgCo2e()).isEqualByComparingTo("50.0000000000000000001");
        assertThat(report.nodes()).singleElement().satisfies(node->assertThat(node.emissionKgCo2e()).isEqualByComparingTo(report.totalKgCo2e()));
        verify(calculations,never()).evidence(anyLong(),any(),any(),any());
    }

    @Test void fullyExcludedBatchRetainsProofAndRejectsTampering() {
        var evidence=CarbonEvidence.parse("{\"activityStatus\":\"EXCLUDED\",\"grossQuantity\":\"1000\",\"excludedSources\":[{\"pointId\":\"A\"}],\"projectVersion\":{\"versionId\":\"PV\"}}");
        var shared=CarbonEvidence.object();var activity=shared.putArray("excludedActivities").addObject();
        activity.set("evidence",evidence);activity.put("evidenceHash",CarbonCalculationCore.sha256(CarbonEvidence.canonical(evidence)));
        jdbc.update("UPDATE biz_carbon_calculation_batch SET shared_evidence_json=? WHERE calculation_batch_id='BATCH'",CarbonEvidence.canonical(shared));
        when(calculations.detail(1,roles,"BATCH")).thenReturn(new CalculationDetail(batch,List.of(),List.of(),List.of()));
        var report=service.report(1,roles,"BATCH");
        assertThat(report.totalKgCo2e()).isZero();assertThat(report.excludedSources()).hasSize(1);
        activity.put("evidenceHash","incorrect");
        jdbc.update("UPDATE biz_carbon_calculation_batch SET shared_evidence_json=? WHERE calculation_batch_id='BATCH'",CarbonEvidence.canonical(shared));
        assertThatThrownBy(()->service.report(1,roles,"BATCH")).hasMessageContaining("摘要不一致");
    }

    @Test void failedBatchIsUnavailableAndAuthorizationRunsBeforeReadingEvidence() {
        when(batch.status()).thenReturn("FAILED");
        when(calculations.detail(1,roles,"BATCH")).thenReturn(new CalculationDetail(batch,List.of(),List.of(),List.of()));
        assertThat(service.report(1,roles,"BATCH").totalKgCo2e()).isNull();
        when(calculations.detail(2,roles,"BATCH")).thenThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"建筑越权"));
        assertThatThrownBy(()->service.report(2,roles,"BATCH")).hasMessageContaining("建筑越权");
    }
}
