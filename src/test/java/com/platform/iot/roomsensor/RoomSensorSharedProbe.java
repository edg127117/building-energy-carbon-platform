package com.platform.iot.roomsensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.TdengineProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 显式运行的共享端隔离探针；不由普通测试执行，不访问运行中的业务数据库。 */
public final class RoomSensorSharedProbe {
    private RoomSensorSharedProbe() { }

    public static void main(String[] args) throws Exception {
        String mysqlUrl = env("ROOM_SENSOR_PROBE_MYSQL_URL");
        String tdUrl = env("ROOM_SENSOR_PROBE_TD_URL");
        if (!mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/room_sensor_probe\\?.*")
                || !tdUrl.matches("jdbc:TAOS-RS://127\\.0\\.0\\.1:[0-9]+/.*")) {
            throw new IllegalArgumentException("Probe requires isolated loopback databases");
        }
        String password = env("ROOM_SENSOR_PROBE_PASSWORD");
        var mysqlDs = new DriverManagerDataSource(mysqlUrl, "room_sensor_probe", password);
        var mysql = new JdbcTemplate(mysqlDs);
        var tdAdmin = new JdbcTemplate(new DriverManagerDataSource(tdUrl, "root", env("ROOM_SENSOR_PROBE_TD_ADMIN_PASSWORD")));
        tdAdmin.execute("CREATE DATABASE room_sensor_probe PRECISION 'ms'");
        tdAdmin.execute("CREATE USER room_sensor_probe PASS '" + password + "'");
        tdAdmin.execute("GRANT ALL ON room_sensor_probe.* TO room_sensor_probe");
        var td = new JdbcTemplate(new DriverManagerDataSource(tdUrl + "room_sensor_probe", "room_sensor_probe", password));
        mysql.execute("CREATE TABLE biz_space(space_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),del_flag INT)");
        mysql.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),space_id VARCHAR(32),del_flag INT)");
        mysql.execute("CREATE TABLE biz_device_identity(identity_id VARCHAR(32) PRIMARY KEY,identity_type VARCHAR(20),identity_value VARCHAR(50),equip_id VARCHAR(32),building_id VARCHAR(32),expected_profile_code VARCHAR(50),status INT)");
        new ResourceDatabasePopulator(new ByteArrayResource(Files.readAllBytes(Path.of(env("ROOM_SENSOR_PROBE_MYSQL_DDL"))))).execute(mysqlDs);
        for (String sql : Files.readString(Path.of(env("ROOM_SENSOR_PROBE_TD_DDL"))).replaceAll("(?m)^--.*$", "").split(";")) {
            if (!sql.isBlank()) td.execute(sql);
        }
        mysql.update("INSERT INTO biz_space VALUES (?,?,0)", env("ROOM_SENSOR_PROBE_SPACE_ID"), env("ROOM_SENSOR_PROBE_BUILDING_ID"));
        register(mysql, "radar", "AC233FC2B716", "MSR01_RADAR", "/MSR01/ac233fc2b716/radar");
        register(mysql, "door", "C300008A3BAE", "S4_DOOR", "/MSR01/ac233fc2b716/status");
        var mapper = new ObjectMapper();
        var meters = new SimpleMeterRegistry();
        var properties = new TdengineProperties(); properties.setDatabase("room_sensor_probe");
        var history = new RoomSensorHistoryStore(td, properties, mapper);
        var ingest = new RoomSensorIngestor(mysql, new RoomSensorParser(mapper), mapper, meters);
        var worker = new RoomSensorWorker(mysql, mapper, history, meters);

        long now = System.currentTimeMillis();
        // 仅在独立测试来源写入过期夹具，证明两张超级表都清理，绝不混入现场观测来源。
        for (String protocol : new String[]{"MSR01_RADAR", "S4_DOOR"}) {
            String source = protocol.equals("MSR01_RADAR") ? "expiry_radar" : "expiry_door";
            var old = new RoomSensorObservation(UUID.randomUUID().toString(), source, protocol,
                    now - RoomSensorWorker.RETENTION_MILLIS - 1000, now - RoomSensorWorker.RETENTION_MILLIS - 1000,
                    "expiry_fixture", "fixture", "fixture", "fixture", "fixture", "AC233FC2B716", "C300008A3BAE",
                    protocol.equals("MSR01_RADAR") ? 0 : null, protocol.equals("MSR01_RADAR") ? 0 : null,
                    protocol.equals("S4_DOOR") ? false : null);
            history.write(old);
            history.write(old); // 已冻结主键重放必须只保留同一行。
        }
        worker.cleanup();
        if (td.queryForObject("SELECT COUNT(*) FROM st_radar_observation", Long.class) != 0
                || td.queryForObject("SELECT COUNT(*) FROM st_door_observation", Long.class) != 0) {
            throw new IllegalStateException("Probe expiry verification failed");
        }

        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(env("ROOM_SENSOR_PROBE_TRUSTSTORE")))) {
            trust.load(input, env("ROOM_SENSOR_PROBE_TRUSTSTORE_PASSWORD").toCharArray());
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        var ssl = SSLContext.getInstance("TLS"); ssl.init(null, factory.getTrustManagers(), null);
        var options = new MqttConnectOptions();
        options.setUserName(env("ROOM_SENSOR_PROBE_MQTT_USER"));
        options.setPassword(env("ROOM_SENSOR_PROBE_MQTT_PASSWORD").toCharArray());
        options.setSocketFactory(ssl.getSocketFactory()); options.setHttpsHostnameVerificationEnabled(true);
        options.setCleanSession(true); options.setConnectionTimeout(10);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var client = new MqttClient(env("ROOM_SENSOR_PROBE_MQTT_URL"), "b314-storage-probe-" + UUID.randomUUID(), new MemoryPersistence())) {
            client.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) { failure.compareAndSet(null, cause); }
                public void deliveryComplete(IMqttDeliveryToken token) { }
                public void messageArrived(String topic, MqttMessage message) {
                    try { ingest.accept(topic, message.getPayload(), System.currentTimeMillis(), message.isRetained()); }
                    catch (RuntimeException error) { failure.compareAndSet(null, error); }
                }
            });
            client.connect(options);
            int[] qos = {0, 0};
            client.subscribe(new String[]{"/MSR01/ac233fc2b716/radar", "/MSR01/ac233fc2b716/status"}, qos);
            if (qos[0] == 128 || qos[1] == 128) throw new IllegalStateException("Probe MQTT subscription denied");
            long start = System.currentTimeMillis();
            long end = start + 180_000;
            while (System.currentTimeMillis() < end && failure.get() == null) {
                worker.flush(); Thread.sleep(1000);
            }
            client.disconnect(); worker.flush();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("verification", "ISOLATED_SHARED_HOST_REAL_DEVICE_PROBE");
            report.put("productionDeployed", false);
            report.put("startedAt", start); report.put("finishedAt", System.currentTimeMillis());
            report.put("expiryAndFrozenReplayVerified", true);
            report.put("pending", mysql.queryForObject("SELECT COUNT(*) FROM biz_room_sensor_ingest_pending", Integer.class));
            report.put("radar", td.queryForList("SELECT COUNT(*) AS observations,MIN(target_count) AS min_count,MAX(target_count) AS max_count,MIN(received_at) AS first_received_at,MAX(received_at) AS last_received_at FROM st_radar_observation WHERE source_id='radar'"));
            report.put("door", td.queryForList("SELECT door_open,COUNT(*) AS observations FROM st_door_observation WHERE source_id='door' GROUP BY door_open"));
            report.put("callbackFailure", failure.get() == null ? null : failure.get().getClass().getSimpleName());
            Files.writeString(Path.of(env("ROOM_SENSOR_PROBE_REPORT")), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("ROOM_SENSOR_PROBE_RECORDED");
            if (failure.get() != null || !Integer.valueOf(0).equals(report.get("pending"))) {
                throw new IllegalStateException("Probe requires inspection");
            }
        }
    }

    private static void register(JdbcTemplate mysql, String id, String mac, String protocol, String topic) {
        mysql.update("INSERT INTO biz_equipment VALUES (?,?,?,0)", id, env("ROOM_SENSOR_PROBE_BUILDING_ID"), env("ROOM_SENSOR_PROBE_SPACE_ID"));
        mysql.update("INSERT INTO biz_device_identity VALUES (?,'MAC',?,?,?,?,1)", id, mac, id, env("ROOM_SENSOR_PROBE_BUILDING_ID"), protocol);
        mysql.update("INSERT INTO biz_room_sensor_source(source_id,identity_id,gateway_mac,topic,protocol,enabled) VALUES (?,?,'AC233FC2B716',?,?,1)", id, id, topic, protocol);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
