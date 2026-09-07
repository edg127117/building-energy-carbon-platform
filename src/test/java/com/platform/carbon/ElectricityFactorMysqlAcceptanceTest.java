package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在显式隔离的 MySQL 与真实 HTTP 链验证录入、审核、计算及报告因子锁定。 */
@EnabledIfEnvironmentVariable(named = "CARBON_ACCEPTANCE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElectricityFactorMysqlAcceptanceTest {
    private static final String API = "/v1/carbon-management";
    private CarbonAcceptanceFixture fixture;
    private CarbonAcceptanceProcess app;

    @BeforeAll void database() { fixture = new CarbonAcceptanceFixture(); }
    @AfterAll void closeDatabase() { if (fixture != null) fixture.close(); }
    @BeforeEach void reset() throws Exception {
        fixture.reset();
        app = new CarbonAcceptanceProcess("electricity-rules");
    }
    @AfterEach void stop() throws Exception { if (app != null) app.close(); }

    @Test
    void catalogImportIsIdempotentAuditedSeparatedAndRequiresReview() throws Exception {
        assertThat(app.get(API + "/electricity-factor-catalog").data()).hasSize(114);
        fixture.jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=9003");
        assertThat(app.post(API + "/electricity-factor-catalog/ELECTRICITY_AVERAGE_2023_NATIONAL/import",
                Map.of("usageNature", "FORMAL"), 9003).status()).isEqualTo(403);
        assertThat(app.post(API + "/electricity-factor-catalog/UNKNOWN/import",
                Map.of("usageNature", "FORMAL")).status()).isEqualTo(404);
        assertThat(app.post(API + "/electricity-factor-catalog/ELECTRICITY_AVERAGE_2023_NATIONAL/import",
                Map.of("usageNature", "UNKNOWN")).status()).isEqualTo(400);

        JsonNode formal = imported("ELECTRICITY_AVERAGE_2023_NATIONAL", "FORMAL");
        String version = formal.path("factorVersionId").asText();
        assertThat(formal.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(formal.path("dataYear").asInt()).isEqualTo(2021);
        assertThat(formal.path("accountingYear").asInt()).isEqualTo(2023);
        assertThat(formal.path("resultBasis").asText()).isEqualTo("GAS_MASS");
        assertThat(imported("electricity_average_2023_national", "FORMAL")
                .path("factorVersionId").asText()).isEqualTo(version);
        assertThat(imported("ELECTRICITY_AVERAGE_2023_NATIONAL", "DEVELOPMENT_REFERENCE")
                .path("factorVersionId").asText()).isNotEqualTo(version);
        // 同一来源跨地区复用，两个年度相互独立；每次录入仍保留待审核边界。
        imported("ELECTRICITY_AVERAGE_2023_GRID_EAST", "FORMAL");
        imported("ELECTRICITY_AVERAGE_2024_NATIONAL", "FORMAL");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_electricity_catalog_import",
                Integer.class)).isEqualTo(4);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_factor_source WHERE source_code LIKE 'MEE_NBS_%'
                """, Integer.class)).isEqualTo(3);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event WHERE source_module='CARBON_MANAGEMENT'
                """, Integer.class)).isGreaterThanOrEqualTo(7);
        assertThat(app.post(API + "/factors/" + version + "/activate",
                Map.of("expectedRevision", 0)).status()).isEqualTo(409);
        assertThat(app.post(API + "/factors/" + version + "/review",
                Map.of("expectedRevision", 0, "approved", true, "reviewComment", "self review"))
                .status()).isEqualTo(403);
        activate(formal);
        assertThat(imported("ELECTRICITY_AVERAGE_2023_NATIONAL", "FORMAL")
                .path("status").asText()).isEqualTo("ACTIVE");

        ObjectNode request = formal.deepCopy();
        request.retain("factorCode", "scopeType", "energyItemCode", "factorCategory", "resultBasis",
                "gasCode", "gasCoverage", "sourceVersionId", "applicabilityLevel", "buildingId",
                "regionCode", "inputUnitCode", "standardConditionCode", "usageNature",
                "effectiveFrom", "effectiveTo", "formulaVersionId", "roundingPolicyVersionId",
                "components", "dataYear", "accountingYear");
        request.put("factorCode", "ISOLATED_INVALID_ELECTRICITY");
        ObjectNode missingYear = request.deepCopy();
        missingYear.remove("dataYear");
        assertThat(app.post(API + "/factors", missingYear).status()).isEqualTo(400);
        ObjectNode wrongPeriod = request.deepCopy();
        wrongPeriod.put("effectiveTo", "2025-01-01T00:00:00");
        assertThat(app.post(API + "/factors", wrongPeriod).status()).isEqualTo(400);
        ObjectNode wrongUnit = request.deepCopy();
        ((ObjectNode) wrongUnit.path("components").get(0)).put("unit", "KG_CO2E/KWH");
        assertThat(app.post(API + "/factors", wrongUnit).status()).isEqualTo(400);
        ObjectNode wrongRegion = request.deepCopy();
        wrongRegion.put("applicabilityLevel", "GRID_REGION").put("regionCode", "GRID_UNKNOWN");
        assertThat(app.post(API + "/factors", wrongRegion).status()).isEqualTo(400);
    }

    @Test
    void upgradesLeaveReportUnchangedAndActivityCorrectionPinsItsFactorAndGwp() throws Exception {
        fixture.building("AC_ELECTRICITY", 2023, "FORMAL", 1);
        JsonNode national = imported("ELECTRICITY_AVERAGE_2023_NATIONAL", "FORMAL");
        activate(national);
        // 移除夹具自带的旧直接因子竞争，正式版本通过生产审核接口激活。
        fixture.jdbc.update("""
                UPDATE biz_carbon_factor_version v JOIN biz_carbon_factor f ON f.factor_id=v.factor_id
                SET v.status='DISABLED' WHERE f.energy_item_code='ELECTRICITY' AND v.data_year IS NULL
                """);
        JsonNode report = ok(app.run("AC_ELECTRICITY", 2023, "FORMAL", "initial"));
        String oldBatch = report.path("calculationBatchId").asText();
        String oldFactor = national.path("factorVersionId").asText();
        String evidenceUrl = report.path("items").get(0).path("evidenceUrl").asText();
        JsonNode originalTrace = ok(app.get(evidenceUrl)).deepCopy();
        assertThat(originalTrace.at("/factor/factorVersionId").asText()).isEqualTo(oldFactor);
        assertThat(originalTrace.at("/factor/components/0/value").asText()).isEqualTo("0.5568");
        assertThat(originalTrace.at("/factorSources/0/sourceVersionId").asText())
                .isEqualTo(national.path("sourceVersionId").asText());
        assertThat(originalTrace.at("/gwp/value").asText()).isEqualTo("1");
        assertThat(originalTrace.path("rawKgCO2e").asText()).startsWith("55.68");
        assertThat(originalTrace.at("/calculation/requestHash").asText())
                .isEqualTo(report.path("requestHash").asText()).hasSize(64);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT raw_emission_kg_co2e FROM biz_carbon_calculation_item WHERE calculation_batch_id=?
                """, BigDecimal.class, oldBatch)).isEqualByComparingTo("55.68");
        String evidence = fixture.jdbc.queryForObject("""
                SELECT evidence_hash FROM biz_carbon_calculation_item WHERE calculation_batch_id=?
                """, String.class, oldBatch);
        String oldGwp = fixture.jdbc.queryForObject("""
                SELECT gwp_version_id FROM biz_carbon_calculation_item WHERE calculation_batch_id=?
                """, String.class, oldBatch);
        assertThat(oldGwp).isNotBlank();

        JsonNode province = imported("ELECTRICITY_AVERAGE_2023_PROVINCE_330000", "FORMAL");
        activate(province);
        drainChanges();
        assertThat(count("biz_carbon_recalculation_item")).isZero();
        assertThat(fixture.jdbc.queryForObject("""
                SELECT evidence_hash FROM biz_carbon_calculation_item WHERE calculation_batch_id=?
                """, String.class, oldBatch)).isEqualTo(evidence);

        fixture.jdbc.update("UPDATE acceptance_activity SET quantity=120 WHERE building_id='AC_ELECTRICITY'");
        fixture.change("AC_ELECTRICITY", "TEST_ORG", 2023, 2023, "ACTIVITY_SNAPSHOT");
        drainChanges();
        ok(app.step("execute"));
        String candidate = fixture.jdbc.queryForObject("""
                SELECT candidate_calculation_batch_id FROM biz_carbon_recalculation_item
                """, String.class);
        assertThat(candidate).isNotBlank();
        Map<String, Object> item = fixture.jdbc.queryForMap("""
                SELECT factor_version_id,gwp_version_id,raw_emission_kg_co2e FROM biz_carbon_calculation_item
                WHERE calculation_batch_id=?
                """, candidate);
        assertThat(item.get("factor_version_id")).isEqualTo(oldFactor);
        assertThat(item.get("gwp_version_id")).isEqualTo(oldGwp);
        assertThat((BigDecimal) item.get("raw_emission_kg_co2e")).isEqualByComparingTo("66.816");
        String batch = fixture.jdbc.queryForObject("""
                SELECT recalculation_batch_id FROM biz_carbon_recalculation_item
                """, String.class);
        ok(app.post(API + "/recalculations/" + batch + "/approve",
                Map.of("reviewComment", "isolated quantity correction"), 9002));
        assertThat(fixture.jdbc.queryForObject("""
                SELECT publication_status FROM biz_carbon_calculation_batch WHERE calculation_batch_id=?
                """, String.class, candidate)).isEqualTo("PUBLISHED");
        assertThat(ok(app.get(evidenceUrl))).isEqualTo(originalTrace);
    }

    private JsonNode imported(String code, String nature) throws Exception {
        return ok(app.post(API + "/electricity-factor-catalog/" + code + "/import",
                Map.of("usageNature", nature)));
    }

    @Test
    void evidenceInsertRollsBackEarlierChunksWhenALaterRowViolatesItsForeignKey() throws Exception {
        fixture.building("AC_TRACE_TX", 2025, "FORMAL", 1);
        JsonNode report = ok(app.run("AC_TRACE_TX", 2025, "FORMAL", "trace-write-baseline"));
        String batchId = report.path("calculationBatchId").asText();
        var factor = new CarbonRuleRepository(fixture.jdbc).findFactorVersion(
                report.path("items").get(0).path("factorVersionId").asText());
        var core = new CarbonCalculationCore();
        var items = new java.util.ArrayList<CarbonModels.CalculatedItem>();
        for (int index = 0; index < 80; index++) {
            var activity = new CarbonModels.ActivitySegment(
                    java.util.UUID.randomUUID().toString().replace("-", ""), "AC_TRACE_TX",
                    CarbonModels.PeriodType.YEAR, java.time.Instant.parse("2024-12-31T16:00:00Z"),
                    java.time.Instant.parse("2025-12-31T16:00:00Z"), "Asia/Shanghai", "ELECTRICITY",
                    BigDecimal.ONE, "KWH", "LOCKED_COMPLETE", "COMPLETE", CarbonModels.ResultNature.FORMAL,
                    "test-evidence");
            var calculated = core.calculate(activity, core.match(activity, "330000",
                    CarbonModels.ResultNature.FORMAL, java.util.List.of(factor)), null);
            items.add(index == 79 ? new CarbonModels.CalculatedItem(activity, factor,
                    calculated.convertedActivity(), "MISSING_FORMULA", null,
                    calculated.exactEmissionKgCo2e(), calculated.persistedEmissionKgCo2e(),
                    calculated.matchReason(), calculated.evidenceJson(), calculated.evidenceHash()) : calculated);
        }
        int before = count("biz_carbon_calculation_item");
        String sharedBefore = fixture.jdbc.queryForObject("""
                SELECT shared_evidence_json FROM biz_carbon_calculation_batch WHERE calculation_batch_id=?
                """, String.class, batchId);
        fixture.jdbc.update("UPDATE biz_carbon_calculation_batch SET status='CALCULATING' WHERE calculation_batch_id=?", batchId);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.jdbc.getDataSource()));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            var repository = new CarbonCalculationRepository(fixture.jdbc);
            repository.saveSharedEvidence(batchId, "{}");
            repository.insertItems(batchId, items);
        }))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("biz_carbon_calculation_item")).isEqualTo(before);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT shared_evidence_json FROM biz_carbon_calculation_batch WHERE calculation_batch_id=?
                """, String.class, batchId)).isEqualTo(sharedBefore);
    }
    private void activate(JsonNode factor) throws Exception {
        String path = API + "/factors/" + factor.path("factorVersionId").asText();
        JsonNode reviewed = ok(app.post(path + "/review", Map.of("expectedRevision", 0,
                "approved", true, "reviewComment", "isolated software verification"), 9002));
        ok(app.post(path + "/activate", Map.of("expectedRevision",
                reviewed.path("configRevision").asInt())));
    }
    private void drainChanges() throws Exception {
        int changes = count("biz_carbon_dependency_change");
        for (int i = 0; i <= changes; i++) ok(app.step("analyze"));
    }
    private int count(String table) {
        return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
    private static JsonNode ok(CarbonAcceptanceProcess.Reply reply) {
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }
}
