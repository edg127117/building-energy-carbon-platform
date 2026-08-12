# Configurable Multi-Field Telemetry Adapter Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时的实施步骤。文中的复选框表示原计划步骤，不代表当前完成状态；
> 执行任何命令前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试并重新核验。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a cloud-deployed, MySQL-configured JSON telemetry adapter and a local platform intake that resolves pre-registered device identities to building-scoped points without storing business telemetry in the cloud.

**Architecture:** A standalone Spring Boot application under `telemetry-adapter/` subscribes to raw device MQTT topics, applies immutable protocol-profile snapshots, and publishes one canonical multi-metric message to `device/telemetry/up`. The existing local Spring Boot platform subscribes to that canonical topic, resolves `MAC → equipment → building` and device-specific metric aliases from local MySQL, then reuses the current validated TDengine ingestion path. Cloud MySQL contains only adapter protocol metadata; all equipment, building, point and time-series data remain local.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Jackson, Spring JDBC, MySQL 8/H2, Eclipse Paho MQTT 1.2.5, JUnit 5, Mockito, Maven Wrapper.

## Global Constraints

- Cloud deployment contains EMQX, the standalone adapter and adapter protocol metadata only; it does not store formal telemetry, buildings, users, indicators or history.
- Local platform initiates the connection to cloud EMQX; no local HTTP endpoint or database is exposed to the public network.
- New devices must be pre-registered and MQTT-authorized before normal ingestion. Unknown-device handling is an exceptional safety path, not normal onboarding.
- Protocol profiles and field mappings live in MySQL; Java and YAML must not hard-code device business field names.
- The old `device/data/up` single-point path remains isolated during one migration window and is not converted into a batch protocol.
- Ordinary automated tests must not contact real MySQL, TDengine, Redis, EMQX or hardware.
- Production Java comments are concise Chinese and explain device/building ownership, time, unit, retry and ACK boundaries rather than narrating code.

---

## File Structure

### Standalone cloud adapter

- `telemetry-adapter/pom.xml`: independent build and dependency boundary.
- `telemetry-adapter/src/main/java/com/platform/adapter/TelemetryAdapterApplication.java`: adapter process entry point.
- `telemetry-adapter/src/main/java/com/platform/adapter/model/*`: canonical message, metric, identity and time-source records.
- `telemetry-adapter/src/main/java/com/platform/adapter/profile/*`: protocol profile, field mapping and atomic MySQL snapshot.
- `telemetry-adapter/src/main/java/com/platform/adapter/parser/*`: JSON path extraction, validation and conversion.
- `telemetry-adapter/src/main/java/com/platform/adapter/mqtt/*`: raw subscription, canonical publishing and ACK/retry boundary.
- `telemetry-adapter/src/main/resources/application.yml`: deploy-time broker/database settings only.
- `telemetry-adapter/src/main/resources/db/adapter-schema.sql`: cloud protocol metadata schema.
- `telemetry-adapter/src/test/**`: isolated parser, profile and MQTT behavior tests.

### Local platform

- `src/main/java/com/platform/hvac/model/entity/BizDeviceIdentity.java`: external identity to local equipment/building binding.
- `src/main/java/com/platform/hvac/mapper/BizDeviceIdentityMapper.java`: MyBatis access.
- `src/main/java/com/platform/iot/identity/*`: immutable local identity snapshot and unknown-device policy.
- `src/main/java/com/platform/iot/ingest/standard/*`: canonical contract parsing, preflight mapping and batch ingestion result.
- `src/main/java/com/platform/iot/ingest/HvacIngestionService.java`: trusted source-system overload while preserving old behavior.
- `src/main/java/com/platform/config/MqttConfig.java`: explicit routing of old single-point and new canonical topics plus initial reconnect.
- `src/env/init/12-migrate-multifield-telemetry.sql`: existing-database migration.
- `src/env/init/03-init-hvac-schema.sql`: clean-database identity table.
- `src/test/resources/schema-test.sql`: H2 identity schema.
- `src/test/resources/data-test.sql`: isolated pre-registered identity fixtures only.
- `src/test/java/com/platform/iot/ingest/standard/*`: mapping, rejection, unit and storage retry tests.
- `src/test/java/com/platform/config/MqttConfigTest.java`: topic routing and ACK tests.

## Task 1: Standalone Adapter Contract and Pure Parser

**Files:**
- Create: `telemetry-adapter/pom.xml`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/TelemetryAdapterApplication.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/model/DeviceIdentity.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/model/StandardMetric.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/model/StandardTelemetryMessage.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/model/TimeSource.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/profile/ProtocolProfile.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/profile/ProtocolFieldMapping.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/parser/TelemetryAdaptationException.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/parser/JsonTelemetryAdapter.java`
- Test: `telemetry-adapter/src/test/java/com/platform/adapter/parser/JsonTelemetryAdapterTest.java`

**Interfaces:**
- Consumes: raw MQTT topic, JSON bytes and adapter receive time.
- Produces: `StandardTelemetryMessage adapt(String topic, byte[] payload, long receivedTime, ProtocolProfile profile, List<ProtocolFieldMapping> mappings)`.

- [ ] **Step 1: Write failing parser tests**

Cover one six-field packet, nested `/data/energy` path, multiplier/offset, missing required field, non-finite value, device timestamp, server-time fallback and duplicate metric codes.

```java
StandardTelemetryMessage message = adapter.adapt(
        "device/raw/energy-meter/v1/up",
        payload.getBytes(UTF_8),
        1_785_398_400_000L,
        profile,
        mappings);

assertThat(message.deviceIdentity().value()).isEqualTo("123456789012345");
assertThat(message.metrics()).extracting(StandardMetric::code)
        .containsExactly("CURRENT_ENERGY", "CURRENT_CO2");
assertThat(message.timeSource()).isEqualTo(TimeSource.SERVER_RECEIVED);
```

- [ ] **Step 2: Run the parser test and verify failure**

Run: `.\mvnw.cmd -f telemetry-adapter/pom.xml -Dtest=JsonTelemetryAdapterTest test`

Expected: compilation fails because the adapter model and parser do not exist.

- [ ] **Step 3: Implement immutable contract records and parser**

Use `JsonNode.at(jsonPointer)` for configured paths and `BigDecimal` for conversion:

```java
BigDecimal converted = raw.multiply(mapping.scale()).add(mapping.offset());
return new StandardMetric(
        mapping.metricCode(), converted, mapping.targetUnit(), mapping.sourcePath());
```

Reject the entire packet before producing output when identity, required fields, configured numeric values or metric uniqueness fail. Ignore unmapped source fields.

- [ ] **Step 4: Run parser tests**

Run: `.\mvnw.cmd -f telemetry-adapter/pom.xml -Dtest=JsonTelemetryAdapterTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit parser slice**

```powershell
git add -- telemetry-adapter/pom.xml telemetry-adapter/src/main telemetry-adapter/src/test/java/com/platform/adapter/parser
git commit -m "feat(adapter): add configurable JSON telemetry parser"
```

## Task 2: Cloud MySQL Profile Snapshot and MQTT Bridge

**Files:**
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/profile/ProtocolProfileProvider.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/profile/JdbcProtocolProfileProvider.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/profile/ProtocolProfileSnapshot.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/mqtt/AdapterMqttProperties.java`
- Create: `telemetry-adapter/src/main/java/com/platform/adapter/mqtt/TelemetryAdapterMqttBridge.java`
- Create: `telemetry-adapter/src/main/resources/application.yml`
- Create: `telemetry-adapter/src/main/resources/db/adapter-schema.sql`
- Create: `telemetry-adapter/src/test/resources/application-test.yml`
- Create: `telemetry-adapter/src/test/resources/schema.sql`
- Create: `telemetry-adapter/src/test/resources/data.sql`
- Test: `telemetry-adapter/src/test/java/com/platform/adapter/profile/JdbcProtocolProfileProviderTest.java`
- Test: `telemetry-adapter/src/test/java/com/platform/adapter/mqtt/TelemetryAdapterMqttBridgeTest.java`
- Test: `telemetry-adapter/src/test/java/com/platform/adapter/mqtt/AdapterMqttTestIsolationTest.java`

**Interfaces:**
- Consumes: `find(String topic, String protocolVersion)`.
- Produces: one immutable `ResolvedProtocolProfile(profile, mappings)` or an explicit no-match/ambiguous result.

- [ ] **Step 1: Write failing H2 profile tests**

Assert exact-topic profile selection, optional version selection, disabled-row exclusion, complete mapping order and “refresh failure keeps previous snapshot”.

- [ ] **Step 2: Run profile tests and verify failure**

Run: `.\mvnw.cmd -f telemetry-adapter/pom.xml -Dtest=JdbcProtocolProfileProviderTest test`

Expected: compilation failure for missing provider types.

- [ ] **Step 3: Implement schema and atomic provider snapshot**

The schema creates `iot_protocol_profile` and `iot_protocol_field_mapping`. The provider builds both maps locally and replaces one `volatile` snapshot only after every query and validation succeeds.

```java
public record ResolvedProtocolProfile(
        ProtocolProfile profile,
        List<ProtocolFieldMapping> mappings) {
}
```

- [ ] **Step 4: Write MQTT bridge tests**

Verify valid raw payload publishes exactly one canonical JSON before ACK, invalid payload is ACKed without publishing, publish failure is not ACKed, startup connection failure schedules another attempt, and the test profile creates no real client.

- [ ] **Step 5: Implement MQTT bridge**

Use fixed client ID, QoS 1, `cleanSession=false`, manual ACK and bounded initial reconnect. Do not log credentials or full raw payloads.

```java
publisher.publish(properties.standardTopic(), canonicalBytes, 1, false);
subscriber.messageArrivedComplete(message.getId(), message.getQos());
```

- [ ] **Step 6: Run standalone adapter tests**

Run: `.\mvnw.cmd -f telemetry-adapter/pom.xml test`

Expected: `BUILD SUCCESS`; tests use H2 and mocks only.

- [ ] **Step 7: Commit adapter runtime slice**

```powershell
git add -- telemetry-adapter
git commit -m "feat(adapter): add MySQL profiles and MQTT bridge"
```

## Task 3: Local Pre-Registered Device Identity Snapshot

**Files:**
- Create: `src/main/java/com/platform/hvac/model/entity/BizDeviceIdentity.java`
- Create: `src/main/java/com/platform/hvac/mapper/BizDeviceIdentityMapper.java`
- Create: `src/main/java/com/platform/iot/identity/DeviceIdentityKey.java`
- Create: `src/main/java/com/platform/iot/identity/DeviceIdentityBinding.java`
- Create: `src/main/java/com/platform/iot/identity/DeviceIdentityBindingProvider.java`
- Create: `src/main/java/com/platform/iot/identity/MySqlDeviceIdentityBindingProvider.java`
- Create: `src/main/java/com/platform/iot/identity/UnknownDeviceHandler.java`
- Create: `src/main/java/com/platform/iot/identity/LoggingUnknownDeviceHandler.java`
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Create: `src/env/init/12-migrate-multifield-telemetry.sql`
- Modify: `src/test/resources/schema-test.sql`
- Modify: `src/test/resources/data-test.sql`
- Test: `src/test/java/com/platform/iot/identity/MySqlDeviceIdentityBindingProviderTest.java`

**Interfaces:**
- Consumes: `DeviceIdentityKey(type, value)`.
- Produces: `Optional<DeviceIdentityBinding> find(DeviceIdentityKey key)` containing equipment ID/code, building ID and expected profile code.

- [ ] **Step 1: Write failing identity-provider tests**

Cover enabled binding, unknown identity, disabled identity, missing equipment, building mismatch and snapshot refresh failure.

- [ ] **Step 2: Run focused test and verify failure**

Run: `.\mvnw.cmd -Dtest=MySqlDeviceIdentityBindingProviderTest test`

Expected: compilation failure for missing identity types.

- [ ] **Step 3: Add schema and provider**

Create `biz_device_identity` with unique `(identity_type, identity_value)`, composite equipment/building foreign key, expected profile and enabled status. Never infer or update building ownership from telemetry.

```java
public record DeviceIdentityBinding(
        String identityId,
        String identityType,
        String identityValue,
        String equipId,
        String equipCode,
        String buildingId,
        String expectedProfileCode) {
}
```

- [ ] **Step 4: Run provider and database initialization tests**

Run: `.\mvnw.cmd -Dtest=MySqlDeviceIdentityBindingProviderTest,DatabaseInitializationTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit identity slice**

```powershell
git add -- src/main/java/com/platform/hvac/model/entity/BizDeviceIdentity.java src/main/java/com/platform/hvac/mapper/BizDeviceIdentityMapper.java src/main/java/com/platform/iot/identity src/env/init/03-init-hvac-schema.sql src/env/init/12-migrate-multifield-telemetry.sql src/test/resources/schema-test.sql src/test/resources/data-test.sql src/test/java/com/platform/iot/identity
git commit -m "feat(iot): add pre-registered device identities"
```

## Task 4: Canonical Batch Intake and Existing TDengine Reuse

**Files:**
- Create: `src/main/java/com/platform/iot/ingest/standard/StandardTelemetryMessage.java`
- Create: `src/main/java/com/platform/iot/ingest/standard/StandardMetric.java`
- Create: `src/main/java/com/platform/iot/ingest/standard/StandardTelemetryOutcome.java`
- Create: `src/main/java/com/platform/iot/ingest/standard/StandardTelemetryResult.java`
- Create: `src/main/java/com/platform/iot/ingest/standard/StandardTelemetryIngestionService.java`
- Create: `src/main/java/com/platform/iot/ingest/standard/StandardTelemetryMqttMessageHandler.java`
- Modify: `src/main/java/com/platform/iot/ingest/HvacIngestionService.java`
- Test: `src/test/java/com/platform/iot/ingest/standard/StandardTelemetryIngestionServiceTest.java`
- Test: `src/test/java/com/platform/iot/ingest/standard/StandardTelemetryMqttMessageHandlerTest.java`

**Interfaces:**
- Consumes: canonical JSON map and local receive time.
- Produces: `StandardTelemetryResult` whose `shouldAcknowledge()` is false only when a retryable storage failure occurs.

- [ ] **Step 1: Write failing canonical-ingestion tests**

Cover pre-registered device, profile mismatch, unknown device, duplicate metric code, metric alias missing, point belonging to another device/building, unit mismatch, successful multi-point expansion and one storage failure after earlier successful points.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `.\mvnw.cmd -Dtest=StandardTelemetryIngestionServiceTest,StandardTelemetryMqttMessageHandlerTest test`

Expected: compilation failure for missing standard-ingestion types.

- [ ] **Step 3: Implement trusted source-system overload**

Keep the old method unchanged for existing callers:

```java
public HvacIngestionResult ingest(Map<String, Object> payload, long receivedTime) {
    return ingest(payload, receivedTime, sourceSystem);
}

public HvacIngestionResult ingest(
        Map<String, Object> payload,
        long receivedTime,
        String trustedSourceSystem) {
    // Existing validation and storage flow uses only the server-selected namespace.
}
```

- [ ] **Step 4: Implement whole-batch mapping preflight**

For each metric, construct the device-specific external alias key:

```java
String sourcePointCode = "%s:%s:%s".formatted(
        identity.type(), identity.value(), metric.code());
PointAliasKey aliasKey = new PointAliasKey(
        binding.buildingId(), standardSourceSystem, sourcePointCode);
```

Resolve all aliases and validate device ownership and unit before writing the first point. After preflight, call the existing ingestion service for each metric. Any `STORAGE_FAILED` result makes the MQTT message retryable; successful earlier rows become duplicates on redelivery.

- [ ] **Step 5: Run standard-ingestion tests**

Run: `.\mvnw.cmd -Dtest=StandardTelemetryIngestionServiceTest,StandardTelemetryMqttMessageHandlerTest,HvacIngestionServiceTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit local ingestion slice**

```powershell
git add -- src/main/java/com/platform/iot/ingest src/test/java/com/platform/iot/ingest
git commit -m "feat(iot): ingest canonical multi-field telemetry"
```

## Task 5: Local MQTT Topic Routing and Disconnection Recovery

**Files:**
- Modify: `src/main/java/com/platform/config/MqttConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `server.env.example`
- Modify: `src/test/java/com/platform/config/MqttConfigTest.java`
- Modify: `src/test/java/com/platform/config/MqttTestProfileIsolationTest.java`

**Interfaces:**
- Consumes: old `device/data/up` and new `device/telemetry/up` without semantic overlap.
- Produces: independent handler routing and manual ACK decisions on one stable local subscriber session.

- [ ] **Step 1: Add failing routing and initial-retry tests**

Assert canonical topic calls only `StandardTelemetryMqttMessageHandler`, old topic calls only `HvacMqttMessageHandler`, unknown topic is ACKed and rejected, standard storage failure is not ACKed, and initial connection failure is retried without blocking application startup.

- [ ] **Step 2: Run MQTT tests and verify failure**

Run: `.\mvnw.cmd -Dtest=MqttConfigTest,MqttTestProfileIsolationTest test`

Expected: tests fail because standard routing and initial retry do not exist.

- [ ] **Step 3: Refactor MQTT configuration**

Subscribe to a deduplicated union of old and standard topics at QoS 1. Route by exact configured topic set before applying the old `pointCode` gate. Use a dedicated Spring scheduler for bounded initial retry; Paho automatic reconnect continues handling later connection loss.

- [ ] **Step 4: Run MQTT and standard-ingestion tests**

Run: `.\mvnw.cmd -Dtest=MqttConfigTest,MqttTestProfileIsolationTest,StandardTelemetryIngestionServiceTest test`

Expected: `BUILD SUCCESS` without connecting to a real Broker.

- [ ] **Step 5: Commit MQTT routing slice**

```powershell
git add -- src/main/java/com/platform/config/MqttConfig.java src/main/resources/application.yml src/test/resources/application-test.yml server.env.example src/test/java/com/platform/config/MqttConfigTest.java src/test/java/com/platform/config/MqttTestProfileIsolationTest.java
git commit -m "feat(mqtt): route canonical telemetry with reconnect"
```

## Task 6: Documentation, Contract Checks and Full Verification

**Files:**
- Modify: `PROJECT_GUIDE.md`
- Modify: `PROJECT_STATUS.md`
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Modify: `docs/superpowers/README.md`
- Modify: `docs/superpowers/specs/2026-08-11-configurable-multifield-telemetry-adapter-design.md`
- Create: `telemetry-adapter/README.md`
- Create: `telemetry-adapter/adapter.env.example`
- Modify: `.github/workflows/backend-ci.yml`

**Interfaces:**
- Consumes: actual implemented field names, Topics, environment variables and test commands.
- Produces: deployment instructions that keep cloud adapter metadata separate from local business data.

- [ ] **Step 1: Update current project documents**

Document the confirmed chain, implemented/partial/not-field-tested status, new topic contract, pre-registration order, cloud/local data boundary and migration status. Do not describe the future management pages or pending-device workflow as implemented.

- [ ] **Step 2: Add adapter deployment documentation and CI verification**

The adapter README must show:

```text
Cloud: EMQX + telemetry-adapter + adapter metadata MySQL
Local: platform backend + business MySQL + TDengine + Redis + Vue
```

CI runs both:

```bash
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress -f telemetry-adapter/pom.xml verify
```

- [ ] **Step 3: Run focused backend tests**

Run:

```powershell
.\mvnw.cmd -Dtest=MySqlDeviceIdentityBindingProviderTest,StandardTelemetryIngestionServiceTest,StandardTelemetryMqttMessageHandlerTest,MqttConfigTest,MqttTestProfileIsolationTest,DatabaseInitializationTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Run complete local platform tests**

Run: `.\mvnw.cmd test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Run complete standalone adapter tests and package**

Run:

```powershell
.\mvnw.cmd -f telemetry-adapter/pom.xml test
.\mvnw.cmd -f telemetry-adapter/pom.xml -DskipTests package
```

Expected: both commands report `BUILD SUCCESS`.

- [ ] **Step 6: Run repository checks**

Run:

```powershell
git diff --check
git status --short
```

Review all changed production Java files at high comment risk because the task changes MQTT, external resources, device/building ownership, units, time and retry semantics.

- [ ] **Step 7: Commit documentation and verification metadata**

```powershell
git add -- PROJECT_GUIDE.md PROJECT_STATUS.md docs/MQTT-硬件数据对接说明.md docs/superpowers/README.md docs/superpowers/specs/2026-08-11-configurable-multifield-telemetry-adapter-design.md docs/superpowers/plans/2026-08-11-configurable-multifield-telemetry-adapter.md telemetry-adapter/README.md telemetry-adapter/adapter.env.example .github/workflows/backend-ci.yml
git commit -m "docs: document cloud adapter deployment"
```

## Execution Choice

The repository does not expose the `superpowers:executing-plans` or `superpowers:subagent-driven-development` skills in this session. The user explicitly requested implementation in the current task, and repository rules do not permit automatic subagents for this tightly coupled change. Execute this plan inline in the current session, preserving the task boundaries, focused test gates and review checkpoints above.
