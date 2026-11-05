package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.platform.audit.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.DynamicElectricityContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实V104的隔离H2验收：修订、幂等、事务回滚、粗细粒度去重和只读失效判定。 */
class DynamicElectricityServiceTest {
    private static final Instant FROM = Instant.parse("2024-12-31T16:00:00Z");
    private static final Instant TO = Instant.parse("2025-01-31T16:00:00Z");
    private static final Set<String> ROLES = Set.of("ENERGY_MANAGER");
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private DynamicElectricityRepository repository;
    private DynamicElectricityService service;
    private DynamicElectricityInputReader reader;
    private DailyTrendSummaryRepository sources;
    private CarbonAuthorization authorization;
    private List<AuditEvidence> audits;

    @BeforeEach void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:dynamic" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE biz_telemetry_raw_intent(building_id VARCHAR(32),point_id VARCHAR(32),event_ms BIGINT,payload_hash VARCHAR(64),storage_state VARCHAR(20),conflict_revision INT,selection_version INT)");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/env/init/V104__mysql_dynamic_electricity_carbon.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/env/init/V105__mysql_dynamic_electricity_tasks.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/env/init/V106__mysql_dynamic_interval_evidence.sql"));
        }
        jdbc.execute("CREATE TABLE biz_data_point(building_id VARCHAR(32),point_id VARCHAR(80),del_flag INT,unit VARCHAR(32))");
        jdbc.execute("ALTER TABLE biz_data_point ADD COLUMN equip_id VARCHAR(32)");
        jdbc.execute("ALTER TABLE biz_data_point ADD COLUMN status VARCHAR(16)");
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32),del_flag INT)");
        jdbc.update("INSERT INTO building VALUES ('B',0)");
        jdbc.execute("CREATE TABLE biz_carbon_project_version(building_id VARCHAR(32),version_id VARCHAR(32),revision BIGINT,status VARCHAR(32),effective_from BIGINT,effective_to BIGINT)");
        jdbc.update("INSERT INTO biz_data_point(building_id,point_id,del_flag,unit) VALUES ('B','P',0,'kWh'),('OTHER','FOREIGN',0,'kWh')");
        jdbc.execute("CREATE TABLE biz_energy_point_item_binding(binding_id VARCHAR(32),building_id VARCHAR(32),point_id VARCHAR(80))");
        jdbc.execute("CREATE TABLE biz_energy_point_item_binding_version(binding_id VARCHAR(32),binding_version_id VARCHAR(32),config_revision INT,confirmation_status VARCHAR(32),effective_from TIMESTAMP,effective_to TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_energy_item(item_id VARCHAR(32),item_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_energy_item_version(item_id VARCHAR(32),version_id VARCHAR(32),config_revision INT,status VARCHAR(32),effective_from TIMESTAMP,effective_to TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_energy_item_version_scope(version_id VARCHAR(32),usage_scope VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_measurement_unit(unit_id VARCHAR(32),unit_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_measurement_unit_version(unit_id VARCHAR(32),version_id VARCHAR(32),config_revision INT,status VARCHAR(32),effective_from TIMESTAMP,effective_to TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_energy_point_profile(building_id VARCHAR(32),point_id VARCHAR(80),config_revision INT)");
        var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        repository = spy(new DynamicElectricityRepository(jdbc, mapper));
        doReturn("source-v1").when(repository).electricityVersion(anyString(),any(),any(),eq("REVIEWED_INTERVAL"));
        jdbc.execute("CREATE TABLE biz_energy_classification_version(building_id VARCHAR(32),version_id VARCHAR(32),revision INT,status VARCHAR(32),effective_from BIGINT,effective_to BIGINT)");
        jdbc.execute("CREATE TABLE biz_relation_version(building_id VARCHAR(32),version_id VARCHAR(32),config_revision INT,status VARCHAR(32),snapshot_sha256 VARCHAR(64),effective_at TIMESTAMP,superseded_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_relation_model(building_id VARCHAR(32),model_id VARCHAR(32),governance_mode VARCHAR(32),active_version_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_historical_metering_scope(building_id VARCHAR(32),scope_id VARCHAR(32),relation_version_id VARCHAR(32),start_epoch_ms BIGINT,end_epoch_ms BIGINT,status VARCHAR(32))");
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        sources = mock(DailyTrendSummaryRepository.class);
        when(sources.sourceVersion(anyString(), any())).thenReturn("source-v1");
        reader = mock(DynamicElectricityInputReader.class);
        var session = mock(DynamicElectricityInputReader.Session.class);
        when(reader.session()).thenReturn(session);
        when(reader.session(anyString())).thenReturn(session);
        var input = new DynamicElectricityInputReader.Input(List.of(new DynamicElectricityInputReader.Contribution("P", Set.of("P"),
                new BigDecimal("100"), List.of("reviewed-test-source"))), "electricity-hash", CarbonEvidence.tree(Map.of("source", "reviewed-test-source")));
        when(session.read(anyLong(), anyCollection(), anyString(), any(), any(), anyString())).thenReturn(input);
        authorization = mock(CarbonAuthorization.class);
        audits = new ArrayList<>();
        var properties = new AuditGovernanceProperties();
        properties.setEnvironmentMode(AuditEnvironmentMode.TEST);
        service = new DynamicElectricityService(authorization, repository, reader, manager, audits::add, properties, new DynamicElectricityLiveRepository(jdbc,mapper));
    }

    @Test void importedReplayConflictAndRevisionDoNotTouchStaticTables() {
        var first = ingest("one", "*", "0.5", FROM, TO);
        assertThat(ingest("one", "*", "0.5", FROM, TO)).isEqualTo(first);
        assertThatThrownBy(() -> ingest("one", "*", "0.6", FROM, TO)).hasMessageContaining("幂等键");
        ingest("revision", "*", "0.6", FROM, TO);
        assertThat(repository.factors("B", FROM, TO)).hasSize(1);
        assertThat(repository.factors("B", FROM, TO).getFirst().revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_electricity_factor", Integer.class)).isEqualTo(2);
        assertThat(audits).hasSize(2);
        assertThatThrownBy(() -> ingest("overlap", "*", "0.5", FROM.plusSeconds(3600), TO)).hasMessageContaining("重叠");
        assertThatThrownBy(() -> ingest("foreign", "FOREIGN", "0.5", FROM, TO)).hasMessageContaining("不属于建筑");
    }

    @Test void computesMonthlyResultAndQueryNeverReadsElectricity() {
        ingest("one", "*", "0.5", FROM, TO);
        var ids = service.calculate(1, ROLES, "B", new CalculationRequest("MONTH", FROM, TO));
        clearInvocations(reader);
        var year = service.query(1, ROLES, "B", "YEAR", "2025");
        assertThat(year.points()).hasSize(12);
        var january = year.points().getFirst();
        assertThat(january.status()).isEqualTo("COMPLETE");
        assertThat(january.electricityKwh()).isEqualByComparingTo("100");
        assertThat(january.emissionTco2()).isEqualByComparingTo("0.05");
        assertThat(january.factorKgCo2PerKwh()).isEqualByComparingTo("0.5");
        assertThat(january.grains()).containsExactly("MONTH");
        assertThat(service.evidence(1, ROLES, "B", ids.getFirst()).electricityHash()).isEqualTo("electricity-hash");
        verifyNoInteractions(reader);
        // 月结果不能反拆成日、小时曲线。
        assertThat(service.query(1, ROLES, "B", "MONTH", "2025-01").points()).allMatch(p -> p.emissionTco2() == null);
        assertThat(service.query(1, ROLES, "B", "DAY", "2025-01-01").points()).hasSize(24);
    }

    @Test void dirtySourcesInvalidateSavedResultsAndRecalculationIsIdempotent() {
        ingest("one", "*", "0.5", FROM, TO);
        var request = new CalculationRequest("MONTH", FROM, TO);
        var first = service.calculate(1, ROLES, "B", request);
        assertThat(service.calculate(1, ROLES, "B", request)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_electricity_result WHERE grain='MONTH'", Integer.class)).isEqualTo(1);
        doReturn("source-v2").when(repository).electricityVersion(anyString(),any(),any(),eq("REVIEWED_INTERVAL"));
        assertThat(service.query(1, ROLES, "B", "YEAR", "2025").points().getFirst().status()).isEqualTo("STALE");
        assertThat(service.calculate(1, ROLES, "B", request)).isNotEqualTo(first);
        assertThat(service.query(1, ROLES, "B", "YEAR", "2025").points().getFirst().status()).isEqualTo("COMPLETE");
    }

    @Test void neverDoubleCountsMonthAndDayAndChecksAuthorization() {
        ingest("one", "*", "0.5", FROM, TO);
        service.calculate(1, ROLES, "B", new CalculationRequest("MONTH", FROM, TO));
        service.calculate(1, ROLES, "B", new CalculationRequest("DAY", FROM, FROM.plusSeconds(86400)));
        var january = service.query(1, ROLES, "B", "YEAR", "2025").points().getFirst();
        assertThat(january.electricityKwh()).isEqualByComparingTo("100");
        assertThat(january.resultIds()).hasSize(1);
        doThrow(new com.platform.framework.exception.BusinessException(403, "DENIED", "拒绝")).when(authorization).requireReader(9, ROLES, "B");
        assertThatThrownBy(() -> service.query(9, ROLES, "B", "YEAR", "2025")).hasMessageContaining("拒绝");
    }

    @Test void rejectsSourceRevisionDuringCalculationWithoutPublishing() {
        ingest("one", "*", "0.5", FROM, TO);
        doReturn("before", "after").when(repository).electricityVersion(anyString(),any(),any(),eq("REVIEWED_INTERVAL"));
        assertThatThrownBy(() -> service.calculate(1, ROLES, "B", new CalculationRequest("MONTH", FROM, TO)))
                .hasMessageContaining("来源已修订");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_electricity_result WHERE grain<>'LOCK'", Integer.class)).isZero();
    }

    @Test void rawReceiptAndSelectionRevisionsInvalidateRawResults() {
        jdbc.execute("CREATE TABLE biz_telemetry_receipt(building_id VARCHAR(32),persisted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE biz_quality_usage_config_revision(singleton_id INT,config_revision BIGINT)");
        jdbc.update("INSERT INTO biz_quality_usage_config_revision VALUES (1,1)");
        ingest("one", "*", "0.5", FROM, TO);
        var command = new CalculationRequest("HOUR", FROM, FROM.plusSeconds(3600), "RAW_CUMULATIVE");
        service.calculate(1, ROLES, "B", command);
        jdbc.update("INSERT INTO biz_telemetry_raw_intent VALUES ('B','P',?,'raw1','PERSISTED',0,0)",FROM.toEpochMilli());
        assertThat(service.query(1, ROLES, "B", "DAY", "2025-01-01").points().getFirst().status()).isEqualTo("STALE");
        service.calculate(1, ROLES, "B", command);
        jdbc.update("UPDATE biz_quality_usage_config_revision SET config_revision=2");
        assertThat(service.query(1, ROLES, "B", "DAY", "2025-01-01").points().getFirst().status()).isEqualTo("STALE");
        service.calculate(1, ROLES, "B", command);
        jdbc.update("INSERT INTO biz_energy_point_profile VALUES ('B','P',1)");
        assertThat(service.query(1, ROLES, "B", "DAY", "2025-01-01").points().getFirst().status()).isEqualTo("STALE");
    }

    @Test void catalogBindingUnitAndScopeChangesInvalidateSavedResults() {
        ingest("one", "*", "0.5", FROM, TO);
        jdbc.update("INSERT INTO biz_energy_point_item_binding VALUES ('binding','B','P')");
        jdbc.update("INSERT INTO biz_energy_point_item_binding_version VALUES ('binding','bv',1,'CONFIRMED',NULL,NULL)");
        jdbc.update("INSERT INTO biz_energy_item VALUES ('item','ELECTRICITY')");
        jdbc.update("INSERT INTO biz_energy_item_version VALUES ('item','iv',1,'APPROVED',NULL,NULL)");
        jdbc.update("INSERT INTO biz_measurement_unit VALUES ('unit','KWH')");
        jdbc.update("INSERT INTO biz_measurement_unit_version VALUES ('unit','uv',1,'APPROVED',NULL,NULL)");
        var command = new CalculationRequest("HOUR", FROM, FROM.plusSeconds(3600));
        for (String change : List.of(
                "UPDATE biz_energy_point_item_binding_version SET config_revision=2",
                "UPDATE biz_energy_item_version SET config_revision=2",
                "INSERT INTO biz_energy_item_version_scope VALUES ('iv','PURCHASED_ELECTRICITY')",
                "UPDATE biz_measurement_unit_version SET config_revision=2",
                "UPDATE biz_data_point SET unit='MWh' WHERE point_id='P'")) {
            service.calculate(1, ROLES, "B", command);
            assertThat(service.query(1, ROLES, "B", "DAY", "2025-01-01").points().getFirst().status()).isEqualTo("COMPLETE");
            jdbc.update(change);
            assertThat(service.query(1, ROLES, "B", "DAY", "2025-01-01").points().getFirst().status()).isEqualTo("STALE");
        }
    }

    @Test void factorCorrectionAutomaticallyRebuildsOnlyItsRegisteredDay() {
        Instant dayEnd=FROM.plusSeconds(86400),nextEnd=dayEnd.plusSeconds(86400);
        ingest("day-one","*","0.5",FROM,dayEnd);ingest("day-two","*","0.5",dayEnd,nextEnd);
        var initial=service.calculate(1,ROLES,"B",new CalculationRequest("DAY",FROM,nextEnd));
        ingest("day-one-corrected","*","0.6",FROM,dayEnd);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_dynamic_electricity_task WHERE state='PENDING'",Integer.class)).isEqualTo(1);
        var users=mock(com.platform.system.mapper.SysUserMapper.class);var roles=mock(com.platform.system.mapper.SysRoleMapper.class);
        var user=new com.platform.system.model.entity.SysUser();user.setStatus(1);user.setDelFlag(0);user.setActivationPending(0);
        when(users.selectSecurityState(1L)).thenReturn(user);when(roles.selectRoleKeysByUserId(1L)).thenReturn(new ArrayList<>(ROLES));
        var live=new DynamicElectricityLiveService(authorization,mock(com.platform.iot.identity.DeviceIdentityProvider.class),
                new DynamicElectricityLiveRepository(jdbc,new ObjectMapper().findAndRegisterModules()),repository,mock(DynamicElectricityLiveInput.class),service,users,roles,
                new DataSourceTransactionManager(jdbc.getDataSource()));
        live.refresh();
        var points=service.query(1,ROLES,"B","MONTH","2025-01").points();
        assertThat(points.getFirst().emissionTco2()).isEqualByComparingTo("0.06");
        assertThat(points.get(1).resultIds()).containsExactly(initial.get(1));
    }

    @Test void rawBoundaryRevisionAffectsBothAdjacentHoursButKeepsTheFollowingHour() {
        jdbc.execute("CREATE TABLE biz_quality_usage_config_revision(singleton_id INT,config_revision BIGINT)");
        jdbc.update("INSERT INTO biz_quality_usage_config_revision VALUES (1,1)");
        for(int i=0;i<4;i++)jdbc.update("INSERT INTO biz_telemetry_raw_intent VALUES ('B','P',?,'raw','PERSISTED',0,0)",FROM.plusSeconds(i*3600L).toEpochMilli());
        ingest("raw-factor","*","0.5",FROM,TO);
        service.calculate(1,ROLES,"B",new CalculationRequest("HOUR",FROM,FROM.plusSeconds(10800),"RAW_CUMULATIVE"));
        jdbc.update("UPDATE biz_telemetry_raw_intent SET selection_version=1 WHERE event_ms=?",FROM.plusSeconds(3600).toEpochMilli());
        var points=service.query(1,ROLES,"B","DAY","2025-01-01").points();
        assertThat(points.subList(0,3).stream().map(Point::status)).containsExactly("STALE","STALE","COMPLETE");
    }

    private ImportReceipt ingest(String key, String point, String value, Instant from, Instant to) {
        return transactions.execute(status -> service.ingest(1, ROLES, "B", new ImportRequest(key, "isolated source", "420000",
                "NO_EXTERNAL_HYDRO", "SIMULATED", "kgCO2/kWh", List.of(new Observation(point, from, to, new BigDecimal(value))))));
    }
}
