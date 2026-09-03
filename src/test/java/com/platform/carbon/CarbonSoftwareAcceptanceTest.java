package com.platform.carbon;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static com.platform.carbon.CarbonAcceptanceProcess.evidence;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式 opt-in 的真实 MySQL/HTTP/进程验收；断言设计要求，失败不得改成当前错误行为。 */
@EnabledIfEnvironmentVariable(named = "CARBON_ACCEPTANCE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CarbonSoftwareAcceptanceTest {
    private CarbonAcceptanceFixture fixture;
    private CarbonAcceptanceProcess app;

    @BeforeAll void database() { fixture = new CarbonAcceptanceFixture(); }
    @AfterAll void closeDatabase() { if (fixture != null) fixture.close(); }
    @BeforeEach void reset() { fixture.reset(); }
    @AfterEach void stop() throws Exception { if (app != null) { app.close(); app = null; } }

    @Test @Order(1)
    void realHttpCalculationPersistsCompleteEvidenceAndOnlyAnnualIntensities() throws Exception {
        fixture.building("AC_BASIC", 2025, "FORMAL", 12);
        fixture.denominator("AC_BASIC", "BUILDING_AREA", "1000", "M2", "FORMAL");
        fixture.denominator("AC_BASIC", "RESIDENT_POPULATION", "100", "PERSON", "FORMAL");
        app = new CarbonAcceptanceProcess("basic");
        var result = app.run("AC_BASIC", 2025, "FORMAL", "basic");
        evidence("basic", result);
        assertThat(result.status()).as(result.body().toString()).isEqualTo(200);
        assertThat(result.data().path("status").asText()).isEqualTo("COMPLETED_COMPLETE");
        assertThat(result.data().path("items")).hasSize(12);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT final_value FROM biz_carbon_calculation_summary WHERE metric_code='TOTAL_EMISSION'
                """, java.math.BigDecimal.class)).isEqualByComparingTo("60.3");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT final_value FROM biz_carbon_calculation_summary WHERE metric_code='AREA_INTENSITY'
                """, java.math.BigDecimal.class)).isEqualByComparingTo("60.3");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT final_value FROM biz_carbon_calculation_summary WHERE metric_code='POPULATION_INTENSITY'
                """, java.math.BigDecimal.class)).isEqualByComparingTo("603");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_calculation_item WHERE JSON_VALID(evidence_json)",
                Integer.class)).isEqualTo(12);
        assertThat(fixture.jdbc.queryForObject("SELECT MAX(read_in_transaction) FROM acceptance_activity_control",
                Integer.class)).isZero();
    }

    @Test @Order(2)
    void concurrentCapacityMatrixRecordsHttpAndPoolPressure() throws Exception {
        app = new CarbonAcceptanceProcess("capacity", "--carbon-management.recalculation-lease=30s");
        List<Object> stages = new ArrayList<>();
        var checks = new org.assertj.core.api.SoftAssertions();
        for (int snapshots : List.of(12, 96, 500)) {
            for (int concurrency : List.of(1, 4, 8, 16)) {
                int requests = 16;
                List<String> buildings = new ArrayList<>();
                for (int i = 0; i < requests; i++) {
                    String building = "AC_L" + snapshots + "_" + concurrency + "_" + i;
                    fixture.building(building, 2025, "FORMAL", snapshots);
                    buildings.add(building);
                }
                app.post("/__acceptance/reset-metrics", Map.of());
                long started = System.nanoTime();
                List<CarbonAcceptanceProcess.Reply> results = parallel(concurrency, requests,
                        index -> app.run(buildings.get(index), 2025, "FORMAL", "load"));
                double elapsed = (System.nanoTime() - started) / 1e9;
                List<Double> timings = results.stream().map(CarbonAcceptanceProcess.Reply::elapsedMs).sorted().toList();
                var metrics = app.get("/__acceptance/metrics").body();
                Map<String, Object> stage = new LinkedHashMap<>();
                stage.put("snapshotsPerRequest", snapshots); stage.put("concurrency", concurrency);
                stage.put("requests", requests); stage.put("durationSeconds", elapsed);
                stage.put("throughputPerSecond", requests / elapsed);
                stage.put("p50Ms", percentile(timings, .50)); stage.put("p95Ms", percentile(timings, .95));
                stage.put("p99Ms", percentile(timings, .99)); stage.put("maxMs", timings.getLast());
                stage.put("httpStatuses", results.stream().map(CarbonAcceptanceProcess.Reply::status).toList());
                stage.put("metrics", metrics); stages.add(stage);
                evidence("capacity", stages);
                for (var result : results) {
                    checks.assertThat(result.status()).as("snapshots=%s concurrency=%s body=%s",
                            snapshots, concurrency, result.body()).isEqualTo(200);
                    checks.assertThat(result.data().path("status").asText()).isEqualTo("COMPLETED_COMPLETE");
                    checks.assertThat(result.data().path("snapshotCount").asInt()).isEqualTo(snapshots);
                }
                checks.assertThat(metrics.path("httpBusyPeak").asInt()).isBetween(1, 8);
                checks.assertThat(metrics.path("dbActivePeak").asInt()).isBetween(1, 4);
                checks.assertThat(metrics.path("dbPendingNow").asInt()).isZero();
                checks.assertThat(timings.getLast()).isLessThan(20_000);
            }
        }
        checks.assertAll();
    }

    @Test @Order(3)
    void duplicateIdempotencyAndCompetingRequestsDoNotDuplicateResults() throws Exception {
        fixture.building("AC_DUP", 2025, "FORMAL", 12);
        fixture.building("AC_LOCK", 2025, "FORMAL", 12);
        fixture.jdbc.update("UPDATE acceptance_activity_control SET delay_ms=1000");
        app = new CarbonAcceptanceProcess("idempotency");
        var duplicates = parallel(16, 32, index -> app.run("AC_DUP", 2025, "FORMAL", "one-key"));
        var competitors = parallel(16, 32, index -> app.run("AC_LOCK", 2025, "FORMAL", "key-" + index));
        evidence("idempotency", Map.of("sameKey", duplicates, "competing", competitors,
                "metrics", app.get("/__acceptance/metrics").body()));
        assertThat(duplicates).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
        assertThat(duplicates.stream().map(result -> result.data().path("calculationBatchId").asText()).distinct()).hasSize(1);
        assertThat(competitors.stream().filter(result -> result.status() == 200)).hasSize(1);
        assertThat(competitors).allSatisfy(result -> assertThat(result.status()).isIn(200, 409));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_calculation_batch",
                Integer.class)).isEqualTo(2);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_calculation_item",
                Integer.class)).isEqualTo(24);
        assertThat(app.run("AC_DUP", 2024, "FORMAL", "one-key").status()).isEqualTo(409);
    }

    @Test @Order(4)
    void snapshotAndDetailLimitsFailClosedWithoutPartialPersistence() throws Exception {
        fixture.building("AC_LIMIT", 2025, "FORMAL", 501);
        fixture.building("AC_DETAIL", 2025, "FORMAL", 12);
        app = new CarbonAcceptanceProcess("limits", "--carbon-management.maximum-details=10");
        var snapshot = app.run("AC_LIMIT", 2025, "FORMAL", "snap-limit");
        var detail = app.run("AC_DETAIL", 2025, "FORMAL", "detail-limit");
        evidence("limits", Map.of("snapshot", snapshot, "detail", detail));
        assertThat(snapshot.status()).isEqualTo(409);
        assertThat(detail.status()).isEqualTo(409);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_calculation_item",
                Integer.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_calculation_batch WHERE active_lock_key IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test @Order(5)
    void databaseContentionRespectsCalculationDeadlineAndReleasesPool() throws Exception {
        fixture.building("AC_DBLOCK", 2025, "FORMAL", 12);
        app = new CarbonAcceptanceProcess("database-timeout", "--carbon-management.calculation-timeout=2s");
        app.post("/__acceptance/reset-metrics", Map.of());
        CarbonAcceptanceProcess.Reply reply;
        try (var connection = fixture.jdbc.getDataSource().getConnection();
             var lock = connection.prepareStatement("SELECT building_id FROM building WHERE building_id='AC_DBLOCK' FOR UPDATE");
             var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            lock.executeQuery().close();
            var request = executor.submit(() -> app.run("AC_DBLOCK", 2025, "FORMAL", "blocked"));
            // 外键校验会等候真实 InnoDB 行锁；固定持锁时间是故障注入，不是网络延时模拟。
            Thread.sleep(4_000);
            connection.rollback();
            reply = request.get(10, TimeUnit.SECONDS);
        }
        var metrics = app.get("/__acceptance/metrics").body();
        evidence("database-timeout", Map.of("response", reply, "metrics", metrics, "configuredTimeoutMs", 2000));
        assertThat(metrics.path("dbActiveNow").asInt()).isZero();
        assertThat(metrics.path("dbPendingNow").asInt()).isZero();
        assertThat(reply.elapsedMs()).as("2s deadline with 500ms observation allowance").isLessThan(2500);
    }

    @Test @Order(6)
    void slowInputDoesNotHoldTransactionAndIsInterruptedByDeadline() throws Exception {
        fixture.building("AC_SLOW", 2025, "FORMAL", 12);
        fixture.jdbc.update("UPDATE acceptance_activity_control SET delay_ms=4000");
        app = new CarbonAcceptanceProcess("slow-input", "--carbon-management.calculation-timeout=2s");
        var result = app.run("AC_SLOW", 2025, "FORMAL", "slow");
        var rows = fixture.jdbc.queryForList("SELECT status,duration_ms,slow_calculation,active_lock_key FROM biz_carbon_calculation_batch");
        evidence("slow-input", Map.of("response", result, "batches", rows,
                "metrics", app.get("/__acceptance/metrics").body()));
        assertThat(fixture.jdbc.queryForObject("SELECT MAX(read_in_transaction) FROM acceptance_activity_control",
                Integer.class)).isZero();
        assertThat(result.status()).isEqualTo(504);
        assertThat(result.elapsedMs()).isLessThan(2500);
    }

    @Test @Order(7)
    void backlogCoalescesBeforeFreezeAndPublishesWithBatchApproval() throws Exception {
        app = new CarbonAcceptanceProcess("backlog", "--carbon-management.recalculation-lease=30s");
        for (int i = 0; i < 12; i++) {
            for (int year : List.of(2024, 2025)) baseline("AC_BACK" + i, year, "FORMAL");
        }
        fixture.jdbc.update("UPDATE acceptance_activity SET quantity=120");
        for (int i = 0; i < 100; i++) fixture.change(null, "TEST_ORG", 2024, 2025, "ACTIVITY_SNAPSHOT");
        long started = System.nanoTime();
        app.post("/__acceptance/reset-metrics", Map.of());
        for (int i = 0; i < 100; i++) assertThat(app.step("analyze").status()).isEqualTo(200);
        var before = state();
        evidence("backlog-before-execution", before);
        assertThat(count("biz_carbon_recalculation_item")).isEqualTo(24);
        assertThat(count("biz_carbon_recalculation_batch")).isEqualTo(3);
        assertThat(count("biz_carbon_recalculation_trigger")).isEqualTo(100);
        app.post("/__acceptance/scheduler/true", Map.of());
        until(() -> countWhere("biz_carbon_recalculation_batch", "status='PENDING_APPROVAL'") == 3,
                Duration.ofSeconds(60));
        app.post("/__acceptance/scheduler/false", Map.of());
        var ready = state();
        List<String> batches = fixture.jdbc.queryForList(
                "SELECT recalculation_batch_id FROM biz_carbon_recalculation_batch ORDER BY created_at", String.class);
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='DIRECT'")).isEqualTo(24);
        assertThat(app.post("/v1/carbon-management/recalculations/" + batches.getFirst() + "/approve",
                Map.of("reviewComment", "self review must fail"), 9001).status()).isEqualTo(403);
        assertThat(app.post("/v1/carbon-management/recalculations/" + batches.getFirst() + "/approve",
                Map.of("reviewComment", "outside building scope must fail"), 9003).status()).isEqualTo(403);
        for (String batch : batches) assertThat(app.post("/v1/carbon-management/recalculations/" + batch + "/approve",
                Map.of("reviewComment", "isolated software acceptance"), 9002).status()).isEqualTo(200);
        evidence("backlog", Map.of("before", before, "ready", ready, "after", state(),
                "durationMs", (System.nanoTime() - started) / 1_000_000,
                "metrics", app.get("/__acceptance/metrics").body()));
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='PUBLISHED'")).isEqualTo(24);
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='SUPERSEDED'")).isEqualTo(24);
        assertThat(countWhere("biz_carbon_result_relation", "relation_status='SUPERSEDED'")).isEqualTo(24);
        assertThat(countWhere("biz_carbon_recalculation_item", "active_lock_key IS NOT NULL")).isZero();
    }

    @Test @Order(8)
    void correctionWindowsAndPermissionBoundariesMustNotBeSilentlyMerged() throws Exception {
        app = new CarbonAcceptanceProcess("merge-boundary");
        baseline("AC_W1", 2024, "FORMAL"); baseline("AC_W2", 2025, "FORMAL");
        fixture.change("AC_W1", "ORG_A", 2024, 2024, "ACTIVITY_SNAPSHOT");
        app.step("analyze");
        fixture.change("AC_W2", "ORG_A", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze");
        fixture.change("AC_W1", "ORG_B", 2024, 2024, "DENOMINATOR");
        app.step("analyze");
        evidence("merge-boundary", state());
        // 不同修正窗口不能共用审批容器；不同权限/原因的变化也不能吞入原批次。
        assertThat(count("biz_carbon_recalculation_batch")).isGreaterThanOrEqualTo(2);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_recalculation_batch_trigger bt
                JOIN biz_carbon_recalculation_batch b ON b.recalculation_batch_id=bt.recalculation_batch_id
                JOIN biz_carbon_recalculation_trigger t ON t.trigger_id=bt.trigger_id
                JOIN biz_carbon_dependency_change c ON c.change_id=t.change_id
                WHERE b.organization_boundary<>c.organization_boundary OR b.trigger_reason<>c.change_type
                """, Integer.class)).isZero();
    }

    @Test @Order(9)
    void changeAfterFreezeRemainsPendingOrCreatesSuccessorWithoutMutatingApproval() throws Exception {
        app = new CarbonAcceptanceProcess("frozen-change");
        baseline("AC_FROZEN", 2025, "FORMAL");
        fixture.change("AC_FROZEN", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze"); app.step("execute");
        String batch = batchId();
        assertThat(text("SELECT status FROM biz_carbon_recalculation_batch")).isEqualTo("PENDING_APPROVAL");
        fixture.jdbc.update("UPDATE acceptance_activity SET quantity=200,snapshot_id=REPLACE(UUID(),'-','')");
        String nextChange = fixture.change("AC_FROZEN", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze");
        evidence("frozen-change", state());
        int originalTriggers = fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_recalculation_batch_trigger WHERE recalculation_batch_id=?
                """, Integer.class, batch);
        assertThat(originalTriggers).as("冻结审批的依据不能被后来变化篡改").isEqualTo(1);
        boolean pending = fixture.jdbc.queryForObject("SELECT status FROM biz_carbon_dependency_change WHERE change_id=?",
                String.class, nextChange).equals("PENDING");
        assertThat(pending || count("biz_carbon_recalculation_batch") > 1)
                .as("候选生成后的新变化必须被保留供后继重算").isTrue();
    }

    @Test @Order(10)
    void retryableFailureDoesNotBlockSuccessfulItemApproval() throws Exception {
        app = new CarbonAcceptanceProcess("failure-isolation");
        baseline("AC_ISO1", 2025, "FORMAL"); baseline("AC_ISO2", 2025, "FORMAL");
        fixture.jdbc.update("UPDATE acceptance_activity_control SET fail_read=1 WHERE building_id='AC_ISO2'");
        fixture.change(null, "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze"); app.step("execute");
        evidence("failure-isolation", state());
        assertThat(countWhere("biz_carbon_recalculation_item", "status='SUCCEEDED'")).isEqualTo(1);
        assertThat(countWhere("biz_carbon_recalculation_item", "status='FAILED_RETRYABLE'")).isEqualTo(1);
        assertThat(countWhere("biz_carbon_recalculation_batch", "status='PENDING_APPROVAL'"))
                .as("成功项不应等待另一个失败项耗尽重试").isEqualTo(1);
    }

    @Test @Order(11)
    void retriesBackoffReachDeadAndExplicitRecoveryCreatesNewBatch() throws Exception {
        app = new CarbonAcceptanceProcess("retry-dead");
        baseline("AC_DEAD", 2025, "FORMAL");
        fixture.jdbc.update("UPDATE acceptance_activity_control SET fail_read=1");
        fixture.change("AC_DEAD", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.post("/__acceptance/scheduler/true", Map.of());
        until(() -> countWhere("biz_carbon_recalculation_item", "status='DEAD'") == 1, Duration.ofSeconds(15));
        app.post("/__acceptance/scheduler/false", Map.of());
        var dead = state();
        String item = itemId();
        assertThat(fixture.jdbc.queryForObject("SELECT retry_count FROM biz_carbon_recalculation_item", Integer.class))
                .isEqualTo(3);
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='DIRECT' AND status='COMPLETED_COMPLETE'"))
                .isEqualTo(1);
        fixture.jdbc.update("UPDATE acceptance_activity_control SET fail_read=0");
        var recover = app.post("/v1/carbon-management/recalculations/items/" + item + "/recover",
                Map.of("reason", "synthetic input restored"));
        assertThat(recover.status()).isEqualTo(200);
        app.step("analyze"); app.step("execute");
        evidence("retry-dead", Map.of("dead", dead, "recovered", state()));
        assertThat(countWhere("biz_carbon_recalculation_batch", "status='PENDING_APPROVAL'")).isEqualTo(1);
        assertThat(countWhere("biz_carbon_recalculation_item", "status='DEAD'")).isEqualTo(1);
    }

    @Test @Order(12)
    void productionSchedulerStormCoalescesAndSubsequentSimulationChangesAreNotLost() throws Exception {
        app = new CarbonAcceptanceProcess("scheduler-storm");
        baseline("AC_STORM", 2025, "DEVELOPMENT_SIMULATION");
        fixture.jdbc.update("UPDATE acceptance_activity SET quantity=120");
        for (int i = 0; i < 50; i++) fixture.change("AC_STORM", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        long started = System.nanoTime();
        app.post("/__acceptance/scheduler/true", Map.of());
        until(() -> countWhere("biz_carbon_dependency_change", "status IN ('PENDING','ANALYZING')") == 0,
                Duration.ofSeconds(30));
        app.post("/__acceptance/scheduler/false", Map.of());
        var first = state();
        fixture.jdbc.update("UPDATE acceptance_activity SET quantity=200,snapshot_id=REPLACE(UUID(),'-','')");
        fixture.change("AC_STORM", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze"); app.step("execute");
        evidence("scheduler-storm", Map.of("first", first, "second", state(),
                "drainMs", (System.nanoTime() - started) / 1_000_000,
                "metrics", app.get("/__acceptance/metrics").body()));
        assertThat(count("biz_carbon_recalculation_item")).as("后来有效变化必须再生成候选").isEqualTo(2);
    }

    @Test @Order(13)
    void actualRestartRecoversExpiredOrdinaryCalculationBySameIdempotencyKey() throws Exception {
        fixture.building("AC_CRASH", 2025, "FORMAL", 12);
        fixture.jdbc.update("UPDATE acceptance_activity_control SET delay_ms=10000");
        app = new CarbonAcceptanceProcess("ordinary-before-crash", "--carbon-management.calculation-timeout=2s");
        long originalPid = app.pid();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(() -> app.run("AC_CRASH", 2025, "FORMAL", "crash-key"));
            until(() -> fixture.jdbc.queryForObject("SELECT read_count FROM acceptance_activity_control", Integer.class) > 0,
                    Duration.ofSeconds(5));
            assertThat(countWhere("biz_carbon_calculation_batch", "status='CALCULATING'")).isEqualTo(1);
            app.kill();
            try { inFlight.get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) { }
        }
        fixture.jdbc.update("UPDATE acceptance_activity_control SET delay_ms=0");
        app = new CarbonAcceptanceProcess("ordinary-after-crash", "--carbon-management.calculation-timeout=2s");
        var sameKey = app.run("AC_CRASH", 2025, "FORMAL", "crash-key");
        var newKey = app.run("AC_CRASH", 2025, "FORMAL", "new-key");
        String id = text("SELECT calculation_batch_id FROM biz_carbon_calculation_batch WHERE idempotency_key='crash-key'");
        var lookup = app.get("/v1/carbon-management/calculations/" + id);
        evidence("ordinary-restart", Map.of("oldPid", originalPid, "newPid", app.pid(),
                "sameKey", sameKey, "newKey", newKey, "getById", lookup, "state", state()));
        assertThat(app.pid()).isNotEqualTo(originalPid);
        assertThat(lookup.data().path("status").asText()).isEqualTo("FAILED_TIMEOUT");
        assertThat(sameKey.data().path("status").asText()).isNotEqualTo("CALCULATING");
    }

    @Test @Order(14)
    void actualRestartRecoversCommittedAnalyzingDependencyChange() throws Exception {
        app = new CarbonAcceptanceProcess("analysis-before-crash");
        baseline("AC_ANALYSIS", 2025, "FORMAL");
        fixture.change("AC_ANALYSIS", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        assertThat(app.step("claim-change").status()).isEqualTo(200);
        assertThat(countWhere("biz_carbon_dependency_change", "status='ANALYZING'")).isEqualTo(1);
        long oldPid = app.pid(); app.kill();
        app = new CarbonAcceptanceProcess("analysis-after-crash");
        app.post("/__acceptance/scheduler/true", Map.of());
        Thread.sleep(4_000);
        app.post("/__acceptance/scheduler/false", Map.of());
        evidence("analysis-restart", Map.of("oldPid", oldPid, "newPid", app.pid(), "state", state()));
        assertThat(countWhere("biz_carbon_dependency_change", "status='ANALYZING'")).isZero();
        assertThat(countWhere("biz_carbon_recalculation_batch", "status='PENDING_APPROVAL'")).isEqualTo(1);
    }

    @Test @Order(15)
    void actualRestartBetweenBatchClaimAndItemStartDoesNotKillPendingWork() throws Exception {
        app = new CarbonAcceptanceProcess("claimed-before-crash");
        baseline("AC_CLAIM", 2025, "FORMAL");
        fixture.change("AC_CLAIM", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze"); app.step("claim-batch");
        assertThat(countWhere("biz_carbon_recalculation_item", "status='PENDING'")).isEqualTo(1);
        long oldPid = app.pid(); app.kill();
        app = new CarbonAcceptanceProcess("claimed-after-crash");
        app.post("/__acceptance/scheduler/true", Map.of());
        Thread.sleep(2_000);
        app.post("/__acceptance/scheduler/false", Map.of());
        evidence("claimed-restart", Map.of("oldPid", oldPid, "newPid", app.pid(), "state", state()));
        assertThat(text("SELECT status FROM biz_carbon_recalculation_batch")).isEqualTo("PENDING_APPROVAL");
    }

    @Test @Order(16)
    void actualRestartDuringCandidateCalculationRecoversWithoutOrphanLock() throws Exception {
        app = new CarbonAcceptanceProcess("candidate-before-crash", "--carbon-management.calculation-timeout=2s");
        baseline("AC_CAND", 2025, "FORMAL");
        fixture.change("AC_CAND", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze");
        fixture.jdbc.update("UPDATE acceptance_activity_control SET delay_ms=10000,read_count=0");
        long oldPid = app.pid();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(() -> app.step("execute"));
            until(() -> fixture.jdbc.queryForObject("SELECT read_count FROM acceptance_activity_control", Integer.class) > 0,
                    Duration.ofSeconds(5));
            app.kill();
            try { inFlight.get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) { }
        }
        fixture.jdbc.update("UPDATE acceptance_activity_control SET delay_ms=0");
        app = new CarbonAcceptanceProcess("candidate-after-crash", "--carbon-management.calculation-timeout=2s");
        app.post("/__acceptance/scheduler/true", Map.of());
        Thread.sleep(4_000);
        app.post("/__acceptance/scheduler/false", Map.of());
        evidence("candidate-restart", Map.of("oldPid", oldPid, "newPid", app.pid(), "state", state()));
        assertThat(text("SELECT status FROM biz_carbon_recalculation_batch")).isEqualTo("PENDING_APPROVAL");
        assertThat(countWhere("biz_carbon_calculation_batch", "status='CALCULATING'")).isZero();
    }

    @Test @Order(17)
    void actualRestartAfterSuccessBeforeBatchFinishPreservesApprovalEligibility() throws Exception {
        app = new CarbonAcceptanceProcess("success-before-crash");
        baseline("AC_SUCCESS", 2025, "FORMAL");
        fixture.change("AC_SUCCESS", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze"); app.step("claim-batch");
        assertThat(app.post("/__acceptance/item/" + itemId() + "/compute", Map.of()).status()).isEqualTo(200);
        assertThat(countWhere("biz_carbon_recalculation_item", "status='SUCCEEDED'")).isEqualTo(1);
        long oldPid = app.pid(); app.kill();
        app = new CarbonAcceptanceProcess("success-after-crash");
        app.post("/__acceptance/scheduler/true", Map.of());
        Thread.sleep(2_000);
        app.post("/__acceptance/scheduler/false", Map.of());
        evidence("success-restart", Map.of("oldPid", oldPid, "newPid", app.pid(), "state", state()));
        assertThat(text("SELECT status FROM biz_carbon_recalculation_batch")).isEqualTo("PENDING_APPROVAL");
    }

    @Test @Order(18)
    void expiredWorkerCannotOverwriteNewLeaseOwner() throws Exception {
        app = new CarbonAcceptanceProcess("worker-a");
        baseline("AC_FENCE", 2025, "FORMAL");
        fixture.change("AC_FENCE", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze");
        try (var second = new CarbonAcceptanceProcess("worker-b")) {
            app.step("claim-batch");
            String item = itemId();
            app.post("/__acceptance/item/" + item + "/start", Map.of());
            String tokenA = text("SELECT lease_token FROM biz_carbon_recalculation_batch");
            Thread.sleep(3_200);
            second.step("claim-batch");
            // DATETIME(3) 对 next_attempt_at 的舍入可能使同一毫秒的首次领取尚未到期。
            if (text("SELECT lease_token FROM biz_carbon_recalculation_batch") == null) {
                Thread.sleep(50);
                second.step("claim-batch");
            }
            second.post("/__acceptance/item/" + item + "/start", Map.of());
            String tokenB = text("SELECT lease_token FROM biz_carbon_recalculation_batch");
            app.post("/__acceptance/item/" + item + "/fail", Map.of());
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("tokenA", tokenA); observation.put("tokenB", tokenB); observation.put("state", state());
            evidence("lease-fencing", observation);
            assertThat(tokenB).isNotNull();
            assertThat(tokenA).isNotEqualTo(tokenB);
            assertThat(text("SELECT status FROM biz_carbon_recalculation_item")).isEqualTo("CALCULATING");
        }
    }

    @Test @Order(19)
    void denominatorConflictOnlyRemovesIntensityNotTotal() throws Exception {
        fixture.building("AC_DEN", 2025, "FORMAL", 12);
        fixture.denominator("AC_DEN", "BUILDING_AREA", "1000", "M2", "FORMAL");
        fixture.jdbc.update("""
                INSERT INTO biz_carbon_denominator_version
                (denominator_version_id,denominator_id,version_no,denominator_value,unit_code,
                 source_reference,evidence_reference,usage_nature,status,effective_from,created_by)
                SELECT REPLACE(UUID(),'-',''),denominator_id,2,2000,unit_code,source_reference,
                  evidence_reference,usage_nature,status,effective_from,created_by
                FROM biz_carbon_denominator_version
                """);
        app = new CarbonAcceptanceProcess("denominator-conflict");
        var result = app.run("AC_DEN", 2025, "FORMAL", "den-conflict");
        evidence("denominator-conflict", Map.of("response", result, "state", state()));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("status").asText()).isEqualTo("COMPLETED_COMPLETE");
        assertThat(fixture.jdbc.queryForObject("""
                SELECT final_value FROM biz_carbon_calculation_summary WHERE metric_code='TOTAL_EMISSION'
                """, java.math.BigDecimal.class)).isEqualByComparingTo("60.3");
    }

    @Test @Order(20)
    void monthlyQuarterlyAndMissingAnnualDenominatorsKeepEmissionResults() throws Exception {
        fixture.building("AC_PERIOD", 2025, "FORMAL", 12);
        app = new CarbonAcceptanceProcess("period-denominator");
        var year = app.run("AC_PERIOD", 2025, "FORMAL", "year");
        assertThat(year.status()).isEqualTo(200);
        assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_calculation_summary
                WHERE metric_code IN ('AREA_INTENSITY','POPULATION_INTENSITY')
                  AND final_value IS NULL AND unavailable_reason IS NOT NULL
                """, Integer.class)).isEqualTo(2);
        List<Object> values = new ArrayList<>(List.of(year));
        for (String period : List.of("MONTH", "QUARTER")) {
            Map<String, Object> request = new LinkedHashMap<>(
                    CarbonAcceptanceFixture.calculation("AC_PERIOD", 2025, "FORMAL", period));
            request.put("periodType", period);
            request.put("endExclusive", period.equals("MONTH") ? "2025-01-31T16:00:00Z" : "2025-03-31T16:00:00Z");
            var result = app.post("/v1/carbon-management/calculations", request);
            values.add(result);
            assertThat(result.status()).as(result.body().toString()).isEqualTo(200);
            assertThat(result.data().path("items")).hasSize(period.equals("MONTH") ? 1 : 3);
            assertThat(fixture.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_carbon_calculation_summary s
                    JOIN biz_carbon_calculation_batch b ON b.calculation_batch_id=s.calculation_batch_id
                    WHERE b.period_type=? AND s.metric_code LIKE '%INTENSITY'
                    """, Integer.class, period)).isZero();
        }
        evidence("period-denominator", values);
    }

    @Test @Order(21)
    void actualRestartWithPendingChangesContinuesScheduledExecution() throws Exception {
        app = new CarbonAcceptanceProcess("pending-before-crash");
        baseline("AC_PENDING", 2025, "FORMAL");
        fixture.change("AC_PENDING", "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        long oldPid = app.pid(); app.kill();
        app = new CarbonAcceptanceProcess("pending-after-crash");
        app.post("/__acceptance/scheduler/true", Map.of());
        until(() -> countWhere("biz_carbon_recalculation_batch", "status='PENDING_APPROVAL'") == 1,
                Duration.ofSeconds(15));
        app.post("/__acceptance/scheduler/false", Map.of());
        evidence("pending-restart", Map.of("oldPid", oldPid, "newPid", app.pid(), "state", state()));
        assertThat(countWhere("biz_carbon_recalculation_item", "status='SUCCEEDED'")).isEqualTo(1);
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='DIRECT'")).isEqualTo(1);
    }

    @Test @Order(22)
    void actualCrashDuringPublicationRollsBackAndApprovalCanBeRetried() throws Exception {
        app = new CarbonAcceptanceProcess("publishing-before-crash");
        baseline("AC_PUBLISH1", 2025, "FORMAL"); baseline("AC_PUBLISH2", 2025, "FORMAL");
        fixture.change(null, "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT");
        app.step("analyze"); app.step("execute");
        String batch = batchId();
        String old = text("SELECT calculation_batch_id FROM biz_carbon_calculation_batch WHERE publication_status='DIRECT' LIMIT 1");
        long oldPid = app.pid();
        try (var connection = fixture.jdbc.getDataSource().getConnection();
             var observer = fixture.jdbc.getDataSource().getConnection();
             var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT calculation_batch_id FROM biz_carbon_calculation_batch WHERE calculation_batch_id=? FOR UPDATE")) {
                lock.setString(1, old); lock.executeQuery().close();
            }
            // 脏读只用于确认注入命中了尚未提交的发布事务；正式可见性仍用默认隔离连接验证。
            observer.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_UNCOMMITTED);
            var publish = executor.submit(() -> app.post("/v1/carbon-management/recalculations/" + batch + "/approve",
                    Map.of("reviewComment", "kill inside publishing transaction"), 9002));
            try (var query = observer.createStatement()) {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                boolean publishing = false;
                while (!publishing && System.nanoTime() < deadline) {
                    try (var rows = query.executeQuery("SELECT status FROM biz_carbon_recalculation_batch")) {
                        rows.next(); publishing = "PUBLISHING".equals(rows.getString(1));
                    }
                    if (!publishing) Thread.sleep(50);
                }
                assertThat(publishing).isTrue();
            }
            assertThat(text("SELECT status FROM biz_carbon_recalculation_batch")).isEqualTo("PENDING_APPROVAL");
            app.kill(); connection.rollback();
            try { publish.get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) { }
        }
        app = new CarbonAcceptanceProcess("publishing-after-crash");
        var recovered = state();
        assertThat(text("SELECT status FROM biz_carbon_recalculation_batch")).isEqualTo("PENDING_APPROVAL");
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='DIRECT'")).isEqualTo(2);
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='PUBLISHED'")).isZero();
        var approved = app.post("/v1/carbon-management/recalculations/" + batch + "/approve",
                Map.of("reviewComment", "retry after process rollback"), 9002);
        evidence("publishing-restart", Map.of("oldPid", oldPid, "newPid", app.pid(), "recovered", recovered,
                "approval", approved, "final", state()));
        assertThat(approved.status()).isEqualTo(200);
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='PUBLISHED'")).isEqualTo(2);
        assertThat(countWhere("biz_carbon_calculation_batch", "publication_status='SUPERSEDED'")).isEqualTo(2);
    }

    @Test @Order(23)
    void concurrentManualTriggerFingerprintProducesOnlyOneDurableChange() throws Exception {
        app = new CarbonAcceptanceProcess("concurrent-trigger");
        baseline("AC_TRIGGER", 2025, "FORMAL");
        Map<String, Object> request = Map.of("buildingId", "AC_TRIGGER", "accountingYear", 2025,
                "resultNature", "FORMAL", "organizationBoundary", "TEST_ORG", "reason", "same correction");
        var results = parallel(16, 32, index -> app.post("/v1/carbon-management/recalculations/manual", request));
        evidence("concurrent-trigger", Map.of("responses", results, "state", state()));
        assertThat(count("biz_carbon_dependency_change")).isEqualTo(1);
        assertThat(results).allSatisfy(result -> assertThat(result.status()).as(result.body().toString()).isEqualTo(200));
    }

    @Test @Order(24)
    void concurrentWorkersClaimEveryBatchAtMostOnce() throws Exception {
        app = new CarbonAcceptanceProcess("claim-worker-a", "--carbon-management.recalculation-lease=30s",
                "--carbon-management.maximum-batch-items=1");
        for (int i = 0; i < 4; i++) baseline("AC_MULTI" + i, 2025, "FORMAL");
        fixture.change(null, "TEST_ORG", 2025, 2025, "ACTIVITY_SNAPSHOT"); app.step("analyze");
        try (var second = new CarbonAcceptanceProcess("claim-worker-b", "--carbon-management.recalculation-lease=30s")) {
            var results = parallel(8, 16, index -> (index % 2 == 0 ? app : second).step("claim-batch"));
            evidence("concurrent-claims", Map.of("responses", results, "state", state()));
            assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
            var claims = results.stream().map(result -> result.body().path("batchId").asText())
                    .filter(id -> !id.isBlank()).toList();
            assertThat(claims).hasSize(4).doesNotHaveDuplicates();
            assertThat(countWhere("biz_carbon_recalculation_batch", "status='CALCULATING'")).isEqualTo(4);
        }
    }

    private void baseline(String building, int year, String nature) throws Exception {
        fixture.building(building, year, nature, 12);
        var result = app.run(building, year, nature, "baseline-" + year);
        assertThat(result.status()).as(result.body().toString()).isEqualTo(200);
        assertThat(result.data().path("status").asText()).isEqualTo("COMPLETED_COMPLETE");
    }

    private Map<String, Object> state() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String table : List.of("biz_carbon_dependency_change", "biz_carbon_recalculation_trigger",
                "biz_carbon_recalculation_batch_trigger", "biz_carbon_recalculation_batch",
                "biz_carbon_recalculation_item", "biz_carbon_result_relation",
                "biz_carbon_calculation_batch")) values.put(table, fixture.jdbc.queryForList("SELECT * FROM " + table));
        return values;
    }

    private String batchId() { return text("SELECT recalculation_batch_id FROM biz_carbon_recalculation_batch LIMIT 1"); }
    private String itemId() { return text("SELECT recalculation_item_id FROM biz_carbon_recalculation_item LIMIT 1"); }
    private String text(String sql) { return fixture.jdbc.queryForObject(sql, String.class); }
    private int count(String table) { return countWhere(table, "1=1"); }
    private int countWhere(String table, String where) {
        return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
    }

    private static double percentile(List<Double> values, double quantile) {
        return values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * quantile) - 1));
    }

    private static List<CarbonAcceptanceProcess.Reply> parallel(int concurrency, int requests,
            Request action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CarbonAcceptanceProcess.Reply>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int index = i;
                futures.add(executor.submit(() -> { start.await(); return action.run(index); }));
            }
            start.countDown();
            List<CarbonAcceptanceProcess.Reply> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        }
    }

    @FunctionalInterface interface Request { CarbonAcceptanceProcess.Reply run(int index) throws Exception; }

    private static void until(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as("condition within " + timeout).isTrue();
    }
}
