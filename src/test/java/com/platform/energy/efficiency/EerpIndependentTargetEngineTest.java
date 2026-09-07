package com.platform.energy.efficiency;

import com.platform.config.TdengineConfig;
import com.platform.config.TdengineProperties;
import com.platform.energy.activity.TdengineEnergyActivityDataReader;
import com.platform.energy.period.*;
import com.platform.iot.calculation.CalculationPointReadService;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageModels.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.platform.energy.efficiency.EerpContracts.*;
import static com.platform.energy.efficiency.EerpServiceTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 独立验收：真实目标数据库及公共计算链路；资产、关系、职责和专业规则为合成夹具。 */
@EnabledIfEnvironmentVariable(named = "EERP_ACCEPTANCE_ENABLED", matches = "true")
class EerpIndependentTargetEngineTest {
    @Test
    void twoSourcesReachSealedNaturalYearAndReplayFrozenInputsOnTargetEngines() {
        assertThat(System.getenv("EERP_IT_ISOLATED")).isEqualTo("true");
        var mysql = new JdbcTemplate(new DriverManagerDataSource(System.getenv("EERP_IT_MYSQL_URL"),
                System.getenv("EERP_IT_MYSQL_USER"), System.getenv("EERP_IT_MYSQL_PASSWORD")));
        assertThat(mysql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class))
                .as("独立验收必须从一次性空库开始").isZero();
        Flyway.configure().dataSource(mysql.getDataSource()).locations("filesystem:src/env/init").load().migrate();
        assertThat(mysql.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history", Integer.class)).isEqualTo(43);
        var taos = new JdbcTemplate(new DriverManagerDataSource(System.getenv("EERP_IT_TDENGINE_URL"),
                System.getenv("EERP_IT_TDENGINE_USER"), System.getenv("EERP_IT_TDENGINE_PASSWORD")));
        String db = "eerp_accept_" + UUID.randomUUID().toString().replace("-", "");
        var properties = new TdengineProperties();
        properties.setDatabase(db);
        taos.execute("CREATE DATABASE " + db + " KEEP 3650 DURATION 10d WAL_LEVEL 1");
        try {
            var initializer = new TdengineConfig(properties);
            ReflectionTestUtils.invokeMethod(initializer, "initializeHvacSchema", taos);
            ReflectionTestUtils.invokeMethod(initializer, "initializeEnergyPeriodSchema", taos);
            for (boolean flow : List.of(false, true)) {
                var f = new EerpServiceTest();
                f.setup();
                f.jdbc = mysql;
                f.repo = new EerpRepository(mysql);
                f.service = f.newService();
                var original = f.configuration(flow);
                // 两条来源分别覆盖半年，以精确切换边界组成同一个完整自然年。
                Instant cut = Instant.parse("2025-07-01T00:00:00Z");
                var configuration = flow ? dailyFlowRules(original) : original;
                var config = f.activate(interval(configuration, flow ? cut : START, flow ? END : cut));
                var quality = mock(QualityUsagePolicyResolver.class);
                var context = mock(ResolutionContext.class);
                when(context.configRevision()).thenReturn(1L);
                when(quality.historyContext(anySet(), eq("INDICATOR_CALCULATION"), anyLong(), anyLong())).thenReturn(context);
                when(quality.resolve(eq(context), anyString(), eq("INDICATOR_CALCULATION"), anyLong(), anyInt()))
                        .thenAnswer(i -> new Resolution(i.<Integer>getArgument(4) == 0 ? Decision.ALLOW : Decision.BLOCK,
                                i.getArgument(4), "INDICATOR_CALCULATION", PolicySource.SYSTEM_DEFAULT_Q0_ONLY, null, 1, "ACCEPTANCE_Q0"));
                f.reader = spy(new TdengineEnergyActivityDataReader(taos, properties));
                f.calculator = new EerpPeriodCalculator(new CalculationPointReadService(f.scope, f.pointService, f.reader, quality), f.nativeAggregation, f.codec);
                var realStore = new TdengineEnergyPeriodValueStore(taos, properties);
                AtomicBoolean fail = new AtomicBoolean(false);
                f.snapshots = new NativePeriodSnapshotService(mysql, value -> {
                    if (fail.get()) throw new IllegalStateException("acceptance injected numerical write failure");
                    realStore.write(value);
                }, new EnergyPeriodAuthorization(f.duties, f.scope), f.mapper);
                f.service = f.newService();
                int first = flow ? 181 : 0, last = flow ? 365 : 181;
                for (String point : flow ? List.of("flow", "supply", "return", "electric") : List.of("cold", "electric")) {
                    taos.execute("CREATE TABLE IF NOT EXISTS " + db + ".raw_" + point + " USING " + db
                            + ".st_raw_event TAGS ('" + point + "','" + point + "','b','station','chiller','chiller','test','test','test',1)");
                    StringBuilder rows = new StringBuilder();
                    for (int day = first; day <= last; day++) {
                        long at = START.plusSeconds(day * 86400L).toEpochMilli();
                        // 1000*3.6*100*5/3600=500 kW，全天12000 kWh；累计表同量。
                        int value = switch (point) { case "cold" -> day * 12000; case "electric" -> day * 2400;
                            case "flow" -> 100; case "supply" -> 7; default -> 12; };
                        rows.append('(').append(at).append(',').append(END.toEpochMilli()).append(',').append(value)
                                .append(",0,0,'SYNTHETIC','").append(point).append("','fixture') ");
                    }
                    taos.execute("INSERT INTO " + db + ".raw_" + point + " VALUES " + rows);
                }
                for (int day = first; day < last; day++) {
                    var task = f.period(config, START.plusSeconds(day * 86400L), START.plusSeconds((day + 1) * 86400L), "accept-day-" + day);
                    assertThat(task.status()).as("day %s failure %s", day, task.failureCode()).isEqualTo("SUCCEEDED");
                    var result = (PeriodResult) task.result();
                    assertThat(result.complete()).isTrue();
                    assertThat(result.coolingKwh()).isEqualByComparingTo("12000");
                    assertThat(result.electricityKwh()).isEqualByComparingTo("2400");
                    f.seal(task);
                }
                if (flow) {
                    List<String> ids = mysql.queryForList("SELECT task_id FROM energy_eerp_task WHERE status='SEALED' ORDER BY idempotency_key", String.class);
                    clearInvocations(f.reader);
                    var annual = f.service.createAnnual(1, ROLES, new AnnualRequest("accept-year", B, S, 2025, "UTC", "tz1", ids, null));
                    assertThat(annual.status()).as("annual failure %s", annual.failureCode()).isEqualTo("SUCCEEDED");
                    var result = (AnnualResult) annual.result();
                    assertThat(result.inputTaskIds()).hasSize(365);
                    assertThat(result.coolingKwh()).isEqualByComparingTo("4380000");
                    assertThat(result.electricityKwh()).isEqualByComparingTo("876000");
                    assertThat(result.eerp()).isEqualByComparingTo("5");
                    assertThat(result.evaluationBand()).isEqualTo("GUIDANCE_ONLY");
                    verifyNoInteractions(f.reader);
                    // 原始事实删除后，从已固定失败stage恢复；再按保存的三路输入重算功率段。
                    fail.set(true);
                    var failed = f.period(config, cut, cut.plusSeconds(86400), "accept-recovery");
                    assertThat(failed.status()).isEqualTo("FAILED");
                    String evidence = f.service.trace(1, ROLES, failed.taskId()).evidenceJson();
                    taos.execute("DROP TABLE " + db + ".raw_flow");
                    clearInvocations(f.reader); fail.set(false); f.service = f.newService();
                    var restored = f.service.execute(1, ROLES, failed.taskId());
                    assertThat(restored.status()).isEqualTo("SUCCEEDED");
                    assertThat(restored.evidenceHash()).isEqualTo(failed.evidenceHash());
                    assertThat(f.service.trace(1, ROLES, restored.taskId()).evidenceJson()).isEqualTo(evidence);
                    verifyNoInteractions(f.reader);
                    var saved = f.mapper.readTree(evidence);
                    var savedConfig = f.mapper.treeToValue(saved.path("config").path("configuration"), Configuration.class);
                    var savedRules = savedConfig.coolingSources().getFirst().rules();
                    Map<String, BigDecimal> raw = new HashMap<>();
                    for (var fact : saved.path("input").path("facts")) {
                        if (f.mapper.treeToValue(fact.path("eventTime"), Instant.class).equals(cut))
                            raw.put(fact.path("pointId").asText(), new BigDecimal(fact.path("rawValue").asText()));
                    }
                    BigDecimal manualPower = savedRules.rhoKgPerM3().multiply(savedRules.cpKjPerKgK())
                            .multiply(raw.get("flow")).multiply(raw.get("return").subtract(raw.get("supply")))
                            .divide(new BigDecimal("3600"));
                    assertThat(manualPower).isEqualByComparingTo("500");
                    BigDecimal replay = BigDecimal.ZERO;
                    for (var fact : saved.path("cold-source").path("powerIntervals")) {
                        BigDecimal power = new BigDecimal(fact.path("powerKw").asText());
                        assertThat(power).isEqualByComparingTo(manualPower);
                        Instant from = f.mapper.treeToValue(fact.path("startInclusive"), Instant.class);
                        Instant to = f.mapper.treeToValue(fact.path("endExclusive"), Instant.class);
                        replay = replay.add(power.multiply(BigDecimal.valueOf(to.toEpochMilli() - from.toEpochMilli())).divide(new BigDecimal("3600000")));
                    }
                    assertThat(replay).isEqualByComparingTo("12000");
                    assertThat(taos.queryForObject("SELECT COUNT(*) FROM " + db + ".st_energy_period_result WHERE tce_value IS NOT NULL", Long.class)).isZero();
                    System.out.println("EERP_ACCEPTANCE_YEAR periods=365 coolingKwh=4380000 electricityKwh=876000 eerp=5 band=GUIDANCE_ONLY sources=METER_CUMULATIVE,FLOW_TEMPERATURE recovery=FROZEN_STAGE");
                }
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            taos.execute("DROP DATABASE " + db);
        }
    }

    private static Configuration dailyFlowRules(Configuration c) {
        var source = c.coolingSources().getFirst();
        var rules = new CoolingComputationCore.Rules("accept-water", "synthetic daily hold approved", new BigDecimal("1000"),
                new BigDecimal("3.6"), BigDecimal.ZERO, new BigDecimal("40"), 86400000, 0, 86400000, true, true,
                CoolingComputationCore.AlignmentMode.EXACT_SYNCHRONOUS);
        var modified = new CoolingSource(source.sourceId(), source.mode(), source.coveredChillerIds(), source.waterCircuitId(),
                source.meteringPosition(), source.flowMeterSide(), null, source.flow(), source.supply(), source.returnTemperature(), rules, source.mappingEvidence());
        return new Configuration(c.buildingId(), c.stationId(), c.boundaryId(), c.relationVersionId(), c.timezoneId(), c.timezoneVersion(),
                c.effectiveFrom(), c.effectiveTo(), c.equipment(), List.of(modified), c.electricitySources(), c.professionalEvidence(), c.evaluationRuleVersion(), c.evaluationReference());
    }
}
