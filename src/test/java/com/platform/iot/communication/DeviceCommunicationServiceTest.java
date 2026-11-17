package com.platform.iot.communication;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.communication.CommunicationModels.GatewayRequest;
import com.platform.iot.communication.CommunicationModels.PolicyRequest;
import com.platform.iot.communication.CommunicationModels.Signal;
import com.platform.iot.communication.CommunicationModels.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 直接以最小身份域表和隔离 H2 驱动通讯状态机，不装配 MQTT、TDengine 或质量链路。 */
class DeviceCommunicationServiceTest {
    private static final long NOW = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli();
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private CommunicationProperties properties;
    private MutableClock clock;
    private DeviceCommunicationService service;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:device-communication-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createIdentityDomainTables();
        new ResourceDatabasePopulator(new ClassPathResource("device-communication-test.sql")).execute(dataSource);

        properties = new CommunicationProperties();
        properties.setDefaultPeriodSeconds(60);
        properties.setSourceLeaseSeconds(300);
        clock = new MutableClock(NOW);
        service = newService();
    }

    @Test
    void transitionsFromFreshContactToDelayedThenOfflineAtTheConfiguredBoundaries() {
        addMqttIdentity("I1", "E1", "B1");
        healthy("MQTT");

        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", NOW, false, "LIVE_TELEMETRY"))).isTrue();
        assertView(service.equipment("E1", "B1"), "ONLINE", "LIVE_TELEMETRY", false, false);

        long delayedAt = NOW + 2L * 60_000 + DeviceCommunicationService.jitter(60);
        long offlineAt = NOW + DeviceCommunicationService.offlineMillis(60);
        clock.set(delayedAt - 1);
        assertView(service.equipment("E1", "B1"), "ONLINE", "LIVE_TELEMETRY", false, false);

        clock.set(delayedAt);
        assertView(service.equipment("E1", "B1"), "ONLINE", "REPORT_DELAYED", true, false);

        clock.set(offlineAt - 1);
        assertView(service.equipment("E1", "B1"), "ONLINE", "REPORT_DELAYED", true, false);

        clock.set(offlineAt);
        assertView(service.equipment("E1", "B1"), "OFFLINE", "DEVICE_TIMEOUT", false, true);
        service.reconcile("I1");
        assertThat(target("I1", "state")).isEqualTo("OFFLINE");
        assertThat(service.history("I1", 10, ADMIN))
                .extracting(transition -> transition.afterState(), transition -> transition.reason())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ONLINE", "LIVE_TELEMETRY"),
                        org.assertj.core.groups.Tuple.tuple("OFFLINE", "DEVICE_TIMEOUT"));
    }

    @Test
    void expiredPlatformHealthLeaseIsUnknownAndTwoNewContactsCloseAnOfflineAlarm() {
        addMqttIdentity("I1", "E1", "B1");
        healthy("MQTT");
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", NOW, false, "LIVE_TELEMETRY"))).isTrue();

        clock.advance(301_000);
        service.reconcile("I1");
        assertView(service.equipment("E1", "B1"), "UNKNOWN", "PLATFORM_PIPELINE_UNAVAILABLE", false, false);

        healthy("MQTT");
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "negative-1", clock.millis(), true, "DEVICE_REPORTED_OFFLINE"))).isTrue();
        assertView(service.equipment("E1", "B1"), "OFFLINE", "DEVICE_REPORTED_OFFLINE", false, true);

        clock.advance(1);
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "recovery-1", clock.millis(), false, "LIVE_TELEMETRY"))).isTrue();
        assertView(service.equipment("E1", "B1"), "ONLINE", "LIVE_TELEMETRY", false, true);

        clock.advance(1);
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "recovery-2", clock.millis(), false, "LIVE_TELEMETRY"))).isTrue();
        assertView(service.equipment("E1", "B1"), "ONLINE", "LIVE_TELEMETRY", false, false);
    }

    @Test
    void rejectsReplayOutOfOrderHistoricalAndWrongScopeSignalsWithoutRenewingTheLease() {
        addMqttIdentity("I1", "E1", "B1");
        healthy("MQTT");
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", NOW, false, "LIVE_TELEMETRY"))).isTrue();
        long initialContact = targetLong("I1", "last_contact_at");
        long initialEvidence = targetLong("I1", "last_evidence_at");

        clock.advance(1);
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", clock.millis(), false, "LIVE_TELEMETRY"))).isFalse();
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "late-order", NOW, false, "LIVE_TELEMETRY"))).isFalse();
        assertThat(service.observe(signal("I1", "OTHER", "B1", "MQTT", "wrong-equipment", clock.millis(), false, "LIVE_TELEMETRY"))).isFalse();
        assertThat(service.observe(signal("I1", "E1", "OTHER", "MQTT", "wrong-building", clock.millis(), false, "LIVE_TELEMETRY"))).isFalse();
        assertThat(service.observe(signal("I1", "E1", "B1", "OTHER", "wrong-source", clock.millis(), false, "LIVE_TELEMETRY"))).isFalse();
        long future = clock.millis() + DeviceCommunicationService.jitter(60) + 1;
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "future", future, false, "LIVE_TELEMETRY"))).isFalse();

        clock.advance(DeviceCommunicationService.offlineMillis(60) + 1);
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "historical-replay", NOW, false, "LIVE_TELEMETRY"))).isFalse();
        assertThat(targetLong("I1", "last_contact_at")).isEqualTo(initialContact);
        assertThat(targetLong("I1", "last_evidence_at")).isEqualTo(initialEvidence);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_event WHERE identity_id='I1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void freshTrustedEvidenceNeedsNoTelemetryOrQualityStorageToEstablishCommunicationOnline() {
        addMqttIdentity("I1", "E1", "B1");
        healthy("MQTT");

        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "quality-independent", NOW, false,
                "LIVE_TELEMETRY"))).isTrue();
        assertView(service.equipment("E1", "B1"), "ONLINE", "LIVE_TELEMETRY", false, false);
    }

    @Test
    void projectionUsesOnlyCurrentApplicableTargetsAndNeverInventsAnOnlineDevice() {
        addMqttIdentity("I1", "E1", "B1");
        assertProjection("E1", "B1", "UNKNOWN", "WAITING_INITIAL_CONTACT");

        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", NOW, false, "LIVE_TELEMETRY"))).isTrue();
        assertProjection("E1", "B1", "ONLINE", "LIVE_TELEMETRY");

        healthy("MQTT");
        assertProjection("E1", "B1", "ONLINE", "LIVE_TELEMETRY");

        jdbc.update("UPDATE biz_device_product SET status='DISABLED' WHERE product_id='PRODUCT_I1'");
        assertThat(new NamedParameterJdbcTemplate(jdbc)
                .queryForList(service.equipmentProjectionSql(), java.util.Map.of("commNow", clock.millis())))
                .isEmpty();
    }

    @Test
    void projectionMatchesEquipmentAcrossAggregationTimeHealthAndDaikinMappingBoundaries() {
        addMqttIdentity("I1", "E1", "B1");
        addIdentityForExistingEquipment("I2", "E1", "B1", "MQTT_DEVICE", "PROFILE_A");
        healthy("MQTT");
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "one", NOW, false, "LIVE_TELEMETRY"))).isTrue();
        assertThat(service.observe(signal("I2", "E1", "B1", "MQTT", "two", NOW, false, "LIVE_TELEMETRY"))).isTrue();
        assertProjectionMatchesEquipment("E1", "B1");

        clock.set(NOW + 2L * 60_000 + DeviceCommunicationService.jitter(60));
        assertProjectionMatchesEquipment("E1", "B1");

        clock.set(NOW + DeviceCommunicationService.offlineMillis(60));
        assertProjectionMatchesEquipment("E1", "B1");

        clock.set(NOW + 301_000);
        assertProjectionMatchesEquipment("E1", "B1");

        addDaikinIdentity("I3", "E2", "B2", "SOURCE_A");
        healthy("DAIKIN:SOURCE_A");
        assertThat(service.observe(signal("I3", "E2", "B2", "DAIKIN:SOURCE_A", "three", clock.millis(), false, "LIVE_POLL"))).isTrue();
        assertProjectionMatchesEquipment("E2", "B2");

        jdbc.update("DELETE FROM biz_daikin_project_mapping WHERE source_id='SOURCE_A' AND site_id='SITE_A'");
        service.reconcile("I3");
        assertProjectionMatchesEquipment("E2", "B2");
    }

    @Test
    void resetsCurrentTargetWhenBuildingOrProtocolChangesAndExcludesDisabledProducts() {
        addMqttIdentity("I1", "E1", "B1");
        healthy("MQTT");
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", NOW, false, "LIVE_TELEMETRY"))).isTrue();

        jdbc.update("UPDATE biz_equipment SET building_id='B2' WHERE equip_id='E1'");
        jdbc.update("UPDATE biz_device_identity SET building_id='B2',update_time=? WHERE identity_id='I1'",
                new Timestamp(NOW + 1_000));
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "old-building", clock.millis(), false, "LIVE_TELEMETRY"))).isFalse();

        clock.advance(1);
        assertThat(service.observe(signal("I1", "E1", "B2", "MQTT", "before-binding-effective", clock.millis(), false, "LIVE_TELEMETRY"))).isFalse();
        clock.set(NOW+1_001);
        assertThat(service.observe(signal("I1", "E1", "B2", "MQTT", "new-building", clock.millis(), false, "LIVE_TELEMETRY"))).isTrue();
        assertView(service.equipment("E1", "B2"), "ONLINE", "LIVE_TELEMETRY", false, false);

        jdbc.update("UPDATE biz_device_identity SET expected_profile_code='PROFILE_B',update_time=? WHERE identity_id='I1'",
                new Timestamp(NOW + 2_000));
        service.reconcile("I1");
        assertThat(target("I1", "scope_profile")).isEqualTo("PROFILE_B");
        assertThat(targetLong("I1", "last_contact_at")).isZero();
        assertView(service.equipment("E1", "B2"), "UNKNOWN", "WAITING_INITIAL_CONTACT", false, false);
        clock.set(NOW+2_001);
        assertThat(service.observe(new Signal("I1","E1","B2","MQTT","old-protocol",clock.millis(),clock.millis(),false,"LIVE_TELEMETRY",null,"PROFILE_A"))).isFalse();
        assertThat(service.observe(new Signal("I1","E1","B2","MQTT","new-protocol",clock.millis(),clock.millis(),false,"LIVE_TELEMETRY",null,"PROFILE_B"))).isTrue();

        jdbc.update("UPDATE biz_device_product SET status='DISABLED' WHERE product_id='PRODUCT_I1'");
        service.reconcile("I1");
        assertThat(targetLong("I1", "monitored")).isZero();
        assertThat(service.equipment("E1", "B2").applicable()).isFalse();
        assertThat(service.observe(signal("I1", "E1", "B2", "MQTT", "disabled-product", clock.millis(), false, "LIVE_TELEMETRY"))).isFalse();
    }

    @Test
    void requiresCurrentDaikinMappingBeforeItCountsOrAcceptsEvidence() {
        addDaikinIdentity("I1", "E1", "B1", "SOURCE_A");
        healthy("DAIKIN:SOURCE_A");
        assertThat(service.observe(signal("I1", "E1", "B1", "DAIKIN:SOURCE_A", "evidence-1", NOW, false, "LIVE_POLL"))).isTrue();
        assertView(service.equipment("E1", "B1"), "ONLINE", "LIVE_POLL", false, false);

        jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=2 WHERE source_id='SOURCE_A'");
        service.reconcile("I1");
        clock.advance(1);
        assertThat(service.observe(signal("I1","E1","B1","DAIKIN:SOURCE_A","stale-version",clock.millis(),false,"LIVE_POLL"))).isFalse();
        assertThat(service.observe(new Signal("I1","E1","B1","DAIKIN:SOURCE_A","current-version",clock.millis(),clock.millis(),false,"LIVE_POLL",2))).isTrue();

        jdbc.update("DELETE FROM biz_daikin_project_mapping WHERE source_id='SOURCE_A' AND site_id='SITE_A'");
        service.reconcile("I1");

        assertThat(targetLong("I1", "monitored")).isZero();
        assertThat(service.equipment("E1", "B1").applicable()).isFalse();
        assertThat(service.observe(signal("I1", "E1", "B1", "DAIKIN:SOURCE_A", "old-mapping", clock.millis(), false, "LIVE_POLL"))).isFalse();
    }

    @Test
    void aConcurrentDeadlineScanCannotOverwriteANewerLiveContact() throws Exception {
        addMqttIdentity("I1","E1","B1");
        service.observe(signal("I1","E1","B1","MQTT","first",NOW,false,"LIVE_TELEMETRY"));
        clock.advance(DeviceCommunicationService.offlineMillis(60));
        healthy("MQTT");
        var ready=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var scan=workers.submit(()->{try{ready.await();}catch(InterruptedException e){throw new RuntimeException(e);}service.reconcile("I1");});
            var contact=workers.submit(()->{try{ready.await();}catch(InterruptedException e){throw new RuntimeException(e);}return service.observe(signal("I1","E1","B1","MQTT","newer",clock.millis(),false,"LIVE_TELEMETRY"));});
            ready.countDown();scan.get(10,TimeUnit.SECONDS);assertThat(contact.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(service.equipment("E1","B1").value()).isEqualTo("ONLINE");
            assertThat(targetLong("I1","last_contact_at")).isEqualTo(clock.millis());
        } finally {workers.shutdownNow();}
    }

    @Test
    void jvmTimezoneChangesDoNotResetTheIdentityScopeOrItsContact() {
        addMqttIdentity("I1","E1","B1");
        service.observe(signal("I1","E1","B1","MQTT","first",NOW,false,"LIVE_TELEMETRY"));
        Object token=target("I1","scope_token");
        var previous=java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            newService().reconcile("I1");
            assertThat(target("I1","scope_token")).isEqualTo(token);
            assertThat(service.equipment("E1","B1").value()).isEqualTo("ONLINE");
        } finally {java.util.TimeZone.setDefault(previous);}
    }

    @Test
    void gatewayDownImmediatelyOverridesTheDeviceLeaseAndGatewayCasIsIndependentOfScanRevision() {
        addMqttIdentity("I1", "E1", "B1");
        healthy("MQTT");
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", NOW, false, "LIVE_TELEMETRY"))).isTrue();

        service.reconcile("I1");
        long initialGatewayRevision = gatewayRevision();
        assertThat(initialGatewayRevision).isZero();
        var first = service.attachGateway("I1", new GatewayRequest("edge-a", "已核对拓扑绑定", initialGatewayRevision), 7L, ADMIN);
        assertThat(first.get("gateway_key")).isEqualTo("GATEWAY:edge-a");
        assertThat(((Number) first.get("revision")).longValue()).isEqualTo(1L);

        service.reconcile("I1");
        assertThat(gatewayRevision()).isEqualTo(1L);
        var second = service.attachGateway("I1", new GatewayRequest("edge-b", "扫描不得消耗网关配置版本", 1L), 8L, ADMIN);
        assertThat(second.get("gateway_key")).isEqualTo("GATEWAY:edge-b");
        assertThat(((Number) second.get("revision")).longValue()).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT gateway_key FROM biz_device_comm_policy_audit "
                + "WHERE scope_key='GATEWAY:I1' ORDER BY revision", String.class))
                .containsExactly("GATEWAY:edge-a", "GATEWAY:edge-b");

        service.sourceHealth("GATEWAY:edge-b", "DOWN", clock.millis());
        assertView(service.equipment("E1", "B1"), "OFFLINE", "UPSTREAM_GATEWAY_OFFLINE", false, true);

        service.sourceHealth("GATEWAY:edge-b", "HEALTHY", clock.millis());
        assertView(service.equipment("E1", "B1"), "ONLINE", "LIVE_TELEMETRY", false, false);

        assertThatThrownBy(() -> service.attachGateway("I1",
                new GatewayRequest("edge-c", "陈旧版本不得覆盖", 1L), 9L, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("COMMUNICATION_REVISION_CONFLICT"));
        assertThat(target("I1", "gateway_key")).isEqualTo("GATEWAY:edge-b");
        assertThat(targetLong("I1", "gateway_revision")).isEqualTo(2L);
    }

    @Test
    void rollsBackGatewayBindingWhenItsAuditCannotBePersisted() {
        addMqttIdentity("I1", "E1", "B1");
        long revision = gatewayRevision();
        jdbc.update("INSERT INTO biz_device_comm_policy_audit(scope_key,period_seconds,monitored,evidence,revision,changed_by,gateway_key) "
                + "VALUES('GATEWAY:I1',60,1,'reserved gateway audit',1,9,'GATEWAY:reserved')");

        assertThatThrownBy(() -> service.attachGateway("I1",
                new GatewayRequest("edge-a", "审计冲突必须回滚绑定", revision), 7L, ADMIN))
                .isInstanceOf(RuntimeException.class);

        assertThat(target("I1", "gateway_key")).isNull();
        assertThat(targetLong("I1", "gateway_revision")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_policy_audit "
                + "WHERE scope_key='GATEWAY:I1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void persistsDeadlineAcrossRestartInsteadOfRecreatingAnOnlineLease() {
        addMqttIdentity("I1", "E1", "B1");
        healthy("MQTT");
        assertThat(service.observe(signal("I1", "E1", "B1", "MQTT", "evidence-1", NOW, false, "LIVE_TELEMETRY"))).isTrue();

        clock.advance(DeviceCommunicationService.offlineMillis(60) + 1);
        DeviceCommunicationService restarted = newService();
        restarted.reconcile("I1");

        assertView(restarted.equipment("E1", "B1"), "OFFLINE", "DEVICE_TIMEOUT", false, true);
        assertThat(target("I1", "state")).isEqualTo("OFFLINE");
        assertThat(restarted.history("I1", 10, ADMIN))
                .extracting(transition -> transition.afterState())
                .contains("OFFLINE");
    }

    @Test
    void serializesConcurrentPolicyCasSoOnlyOneRevisionAndAuditAreCommitted() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        DeviceCommunicationService otherInstance = newService();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(() -> writeDefaultAfter(start, service, 61));
            Future<String> second = workers.submit(() -> writeDefaultAfter(start, otherInstance, 62));
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "COMMUNICATION_REVISION_CONFLICT");
        } finally {
            workers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT revision FROM biz_device_comm_policy WHERE scope_key='DEFAULT'", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_policy_audit "
                + "WHERE scope_key='DEFAULT' AND revision=2", Integer.class)).isEqualTo(1);
    }

    @Test
    void rollsBackPolicyChangeWhenItsAuditRecordCannotBePersisted() {
        jdbc.update("INSERT INTO biz_device_comm_policy_audit(scope_key,period_seconds,monitored,evidence,revision,changed_by) "
                + "VALUES('DEFAULT',60,1,'reserved revision',2,9)");

        assertThatThrownBy(() -> service.savePolicy("DEFAULT", new PolicyRequest(90, true, "new policy", 1), 7L, ADMIN))
                .isInstanceOf(RuntimeException.class);

        var policy = service.policies().getFirst();
        assertThat(policy.periodSeconds()).isEqualTo(60);
        assertThat(policy.revision()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_policy_audit "
                + "WHERE scope_key='DEFAULT'", Integer.class)).isEqualTo(1);
    }

    private String writeDefaultAfter(CountDownLatch start, DeviceCommunicationService candidate, int period) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            candidate.savePolicy("DEFAULT", new PolicyRequest(period, true, "concurrent policy " + period, 1), 7L, ADMIN);
            return "SUCCESS";
        } catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }

    private DeviceCommunicationService newService() {
        return new DeviceCommunicationService(jdbc, new DataSourceTransactionManager(dataSource), properties, clock, 60);
    }

    private void createIdentityDomainTables() {
        jdbc.execute("""
                CREATE TABLE biz_equipment (
                  equip_id VARCHAR(32) PRIMARY KEY, building_id VARCHAR(32) NOT NULL,
                  product_id VARCHAR(32), del_flag TINYINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE biz_device_identity (
                  identity_id VARCHAR(32) PRIMARY KEY, identity_value VARCHAR(100) NOT NULL,
                  identity_type VARCHAR(20) NOT NULL, expected_profile_code VARCHAR(100) NOT NULL,
                  status TINYINT NOT NULL, update_time TIMESTAMP(3), equip_id VARCHAR(32) NOT NULL,
                  building_id VARCHAR(32) NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE biz_device_product(product_id VARCHAR(32) PRIMARY KEY,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE biz_pending_device(pending_id VARCHAR(32) PRIMARY KEY,bound_identity_id VARCHAR(32),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE biz_daikin_directory(pending_id VARCHAR(32),source_id VARCHAR(240),site_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE biz_daikin_project_mapping(source_id VARCHAR(240),site_id VARCHAR(100),building_id VARCHAR(32),mapping_version INT)");
    }

    private void addMqttIdentity(String identity, String equipment, String building) {
        addIdentity(identity, equipment, building, "MQTT_DEVICE", "PROFILE_A");
    }

    private void addDaikinIdentity(String identity, String equipment, String building, String source) {
        addIdentity(identity, equipment, building, "DAIKIN_UNIT", "DAIKIN_PROFILE");
        jdbc.update("INSERT INTO biz_pending_device(pending_id,bound_identity_id,status) VALUES(?,?,?)",
                "PENDING_" + identity, identity, "BOUND");
        jdbc.update("INSERT INTO biz_daikin_directory(pending_id,source_id,site_id) VALUES(?,?,?)",
                "PENDING_" + identity, source, "SITE_A");
        jdbc.update("INSERT INTO biz_daikin_project_mapping(source_id,site_id,building_id,mapping_version) VALUES(?,?,?,?)",
                source, "SITE_A", building, 1);
    }

    private void addIdentity(String identity, String equipment, String building, String type, String profile) {
        String product = "PRODUCT_" + identity;
        jdbc.update("INSERT INTO biz_device_product(product_id,status) VALUES(?,?)", product, "ENABLED");
        jdbc.update("INSERT INTO biz_equipment(equip_id,building_id,product_id,del_flag) VALUES(?,?,?,0)",
                equipment, building, product);
        jdbc.update("""
                INSERT INTO biz_device_identity(identity_id,identity_value,identity_type,expected_profile_code,status,update_time,equip_id,building_id)
                VALUES(?,?,?,?,?,?,?,?)
                """, identity, "VALUE_" + identity, type, profile, 1, new Timestamp(NOW), equipment, building);
    }

    private void addIdentityForExistingEquipment(String identity, String equipment, String building, String type, String profile) {
        jdbc.update("""
                INSERT INTO biz_device_identity(identity_id,identity_value,identity_type,expected_profile_code,status,update_time,equip_id,building_id)
                VALUES(?,?,?,?,?,?,?,?)
                """, identity, "VALUE_" + identity, type, profile, 1, new Timestamp(NOW), equipment, building);
    }

    private Signal signal(String identity, String equipment, String building, String source, String evidence,
                          long observedAt, boolean offline, String reason) {
        return new Signal(identity, equipment, building, source, evidence, observedAt, observedAt, offline, reason,source.startsWith("DAIKIN:")?1:null);
    }

    private void healthy(String source) {
        service.sourceHealth(source, "HEALTHY", clock.millis());
    }

    private Object target(String identity, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM biz_device_comm_target WHERE identity_id=?", Object.class, identity);
    }

    private long targetLong(String identity, String column) {
        return ((Number) target(identity, column)).longValue();
    }

    private long gatewayRevision() {
        return ((Number) service.gateway("I1", ADMIN).get("revision")).longValue();
    }

    private void assertProjection(String equipment, String building, String value, String reason) {
        var rows = new NamedParameterJdbcTemplate(jdbc)
                .queryForList(service.equipmentProjectionSql(), java.util.Map.of("commNow", clock.millis()));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("equip_id")).isEqualTo(equipment);
            assertThat(row.get("building_id")).isEqualTo(building);
            assertThat(row.get("communication_value")).isEqualTo(value);
            assertThat(row.get("reason")).isEqualTo(reason);
        });
    }

    private void assertProjectionMatchesEquipment(String equipment, String building) {
        View expected = service.equipment(equipment, building);
        var rows = new NamedParameterJdbcTemplate(jdbc)
                .queryForList(service.equipmentProjectionSql(), java.util.Map.of("commNow", clock.millis()))
                .stream()
                .filter(row -> equipment.equals(row.get("equip_id")) && building.equals(row.get("building_id")))
                .toList();
        if (!expected.applicable()) {
            assertThat(rows).isEmpty();
            return;
        }
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("communication_value")).isEqualTo(expected.value());
            assertThat(row.get("reason")).isEqualTo(expected.reason());
        });
    }

    private static void assertView(View view, String value, String reason, boolean delayed, boolean alarmOpen) {
        assertThat(view.applicable()).isTrue();
        assertThat(view.value()).isEqualTo(value);
        assertThat(view.reason()).isEqualTo(reason);
        assertThat(view.delayed()).isEqualTo(delayed);
        assertThat(view.alarmOpen()).isEqualTo(alarmOpen);
    }

    private static final class MutableClock extends Clock {
        private long now;

        private MutableClock(long now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }

        private void set(long now) {
            this.now = now;
        }

        private void advance(long milliseconds) {
            now += milliseconds;
        }
    }
}
