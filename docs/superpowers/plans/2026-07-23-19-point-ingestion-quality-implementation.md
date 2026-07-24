# 19 Point Ingestion and Data Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a validated, auditable 19-point HVAC ingestion path that persists accepted source events in TDengine and produces frozen event-time minute aggregates without enabling simulated, interpolated, or defaulted data.

**Architecture:** `MqttConfig` remains the protocol adapter and delegates HVAC payloads to a focused ingestion service. A MySQL-backed point configuration cache and pure validator normalize accepted real telemetry to quality 0; dedicated TDengine repositories persist source events and minute aggregates, while a scheduled service finalizes event-time windows after a configurable grace period.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis-Plus 3.5.5, Paho MQTT QoS 1, TDengine JDBC 3.2.7, Micrometer, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Keep the implementation inside the existing Spring Boot module; do not create a Maven submodule.
- Real MQTT telemetry is normalized to data quality `0`; never generate quality `1` or `2` in this phase.
- Do not read or apply `default_value` during ingestion or aggregation.
- Window membership uses device `eventTime`; server `receivedTime` is audit metadata only.
- Raw events default to 90-day retention through configuration; minute aggregates are not deleted by this policy.
- Rejected telemetry never enters TDengine normal tables.
- Add concise Chinese Javadoc to every new public type and Chinese comments around event-time arithmetic, manual MQTT acknowledgement, idempotency, late-event handling, and schema migration. Avoid comments that merely repeat a method name.
- Preserve the existing electric-meter path and all four-role authorization behavior.
- Use TDD for validator, ingestion, aggregation, and retention behavior.
- Git is unavailable in this environment, so execute all test cycles but omit commit commands during this run.

---

### Task 1: Telemetry contracts and pure quality validation

**Files:**
- Create: `src/main/java/com/platform/iot/quality/TelemetryRejectionReason.java`
- Create: `src/main/java/com/platform/iot/quality/ValidatedHvacTelemetry.java`
- Create: `src/main/java/com/platform/iot/quality/TelemetryValidationResult.java`
- Create: `src/main/java/com/platform/iot/quality/DataPointConfigProvider.java`
- Create: `src/main/java/com/platform/iot/quality/TelemetryQualityValidator.java`
- Test: `src/test/java/com/platform/iot/quality/TelemetryQualityValidatorTest.java`

**Interfaces:**
- Consumes: `Map<String,Object>` MQTT payload, server-generated `receivedTime`, and `BizDataPoint` configuration.
- Produces: `TelemetryValidationResult validate(Map<String,Object> payload, long receivedTime)`.
- Produces: accepted `ValidatedHvacTelemetry(deviceId, pointCode, buildingId, equipId, suffixCode, value, eventTime, receivedTime, dataQuality, isForCalc)`.

- [ ] **Step 1: Write validator tests for all accepted and rejected paths**

```java
@Test
void acceptsConfiguredRealPointAndForcesQualityZero() {
    Map<String, Object> payload = Map.of(
            "deviceId", "WCR1", "pointCode", "WCR1_TWin",
            "val", 12.3, "timestamp", 1_784_788_220_000L,
            "dataQuality", 2);
    TelemetryValidationResult result = validator.validate(payload, 1_784_788_221_000L);
    assertThat(result.accepted()).isTrue();
    assertThat(result.telemetry().dataQuality()).isZero();
}

@Test
void rejectsUnknownPointDeviceMismatchNonFiniteAndConfiguredBounds() {
    assertThat(validate(point("UNKNOWN", 12.3)).reason())
            .isEqualTo(TelemetryRejectionReason.POINT_NOT_FOUND);
    assertThat(validate(payload("OTHER", "WCR1_TWin", 12.3)).reason())
            .isEqualTo(TelemetryRejectionReason.DEVICE_MISMATCH);
    assertThat(validate(point("WCR1_TWin", Double.NaN)).reason())
            .isEqualTo(TelemetryRejectionReason.INVALID_NUMBER);
    assertThat(validate(point("WCR1_TWin", 61.0)).reason())
            .isEqualTo(TelemetryRejectionReason.ABOVE_MAXIMUM);
}
```

- [ ] **Step 2: Run the validator test and verify it fails because contracts do not exist**

Run: `mvn -q -Dtest=TelemetryQualityValidatorTest test`

Expected: test compilation fails for missing quality types.

- [ ] **Step 3: Implement fixed rejection reasons and immutable validation records**

```java
public enum TelemetryRejectionReason {
    MALFORMED_PAYLOAD, INVALID_TIMESTAMP, POINT_NOT_FOUND, POINT_DISABLED,
    DEVICE_MISMATCH, INVALID_NUMBER, BELOW_MINIMUM, ABOVE_MAXIMUM
}

public record TelemetryValidationResult(
        boolean accepted,
        ValidatedHvacTelemetry telemetry,
        TelemetryRejectionReason reason,
        String detail) {
    public static TelemetryValidationResult accept(ValidatedHvacTelemetry value) {
        return new TelemetryValidationResult(true, value, null, null);
    }
    public static TelemetryValidationResult reject(TelemetryRejectionReason reason, String detail) {
        return new TelemetryValidationResult(false, null, reason, detail);
    }
}
```

- [ ] **Step 4: Implement strict payload and point configuration validation**

```java
public TelemetryValidationResult validate(Map<String, Object> payload, long receivedTime) {
    Object device = payload.get("deviceId");
    Object point = payload.get("pointCode");
    Object value = payload.get("val");
    Object timestamp = payload.get("timestamp");
    if (!(device instanceof String deviceId) || !(point instanceof String pointCode)
            || !(value instanceof Number number) || !(timestamp instanceof Number time)) {
        return reject(MALFORMED_PAYLOAD, "缺少必填字段或字段类型错误");
    }
    long eventTime = time.longValue();
    if (eventTime < 1_000_000_000_000L || eventTime >= 10_000_000_000_000L) {
        return reject(INVALID_TIMESTAMP, "timestamp必须是13位Unix毫秒时间戳");
    }
    BizDataPoint config = configProvider.find(pointCode).orElse(null);
    if (config == null) return reject(POINT_NOT_FOUND, "测点未配置");
    if (!"ONLINE".equalsIgnoreCase(config.getStatus())) return reject(POINT_DISABLED, "测点未启用");
    if (config.getEquipId() != null && !config.getEquipId().equals(deviceId)) {
        return reject(DEVICE_MISMATCH, "deviceId与测点所属设备不一致");
    }
    double val = number.doubleValue();
    if (!Double.isFinite(val)) return reject(INVALID_NUMBER, "测点值不是有限数值");
    if (config.getValueMin() != null && BigDecimal.valueOf(val).compareTo(config.getValueMin()) < 0) {
        return reject(BELOW_MINIMUM, "测点值低于配置下限");
    }
    if (config.getValueMax() != null && BigDecimal.valueOf(val).compareTo(config.getValueMax()) > 0) {
        return reject(ABOVE_MAXIMUM, "测点值高于配置上限");
    }
    return accept(new ValidatedHvacTelemetry(deviceId, pointCode, config.getBuildingId(),
            config.getEquipId(), config.getSuffixCode(), val, eventTime, receivedTime,
            0, Integer.valueOf(1).equals(config.getIsForCalc()) ? 1 : 0));
}
```

- [ ] **Step 5: Run validator tests**

Run: `mvn -q -Dtest=TelemetryQualityValidatorTest test`

Expected: all validator tests pass.

---

### Task 2: Cached MySQL data-point configuration

**Files:**
- Create: `src/main/java/com/platform/iot/quality/MySqlDataPointConfigProvider.java`
- Modify: `src/main/java/com/platform/hvac/service/impl/BizDataPointServiceImpl.java`
- Test: `src/test/java/com/platform/iot/quality/MySqlDataPointConfigProviderTest.java`

**Interfaces:**
- Implements: `Optional<BizDataPoint> find(String pointCode)`.
- Produces: `void refreshAll()` and `void refresh(String pointCode)` for scheduled and CRUD-triggered invalidation.

- [ ] **Step 1: Write cache hit, refresh, unknown-point, and CRUD invalidation tests**

```java
@Test
void loadsActivePointOnceAndRefreshesAfterAdminUpdate() {
    when(mapper.selectList(any())).thenReturn(List.of(point("WCR1_TWin", "ONLINE")));
    provider.refreshAll();
    assertThat(provider.find("WCR1_TWin")).isPresent();
    assertThat(provider.find("UNKNOWN")).isEmpty();
    verify(mapper, times(1)).selectList(any());
}
```

- [ ] **Step 2: Run the cache test and verify it fails**

Run: `mvn -q -Dtest=MySqlDataPointConfigProviderTest test`

Expected: missing provider implementation.

- [ ] **Step 3: Implement a 19-point in-process cache with MySQL fallback on refresh failure**

```java
@Component
@RequiredArgsConstructor
public class MySqlDataPointConfigProvider implements DataPointConfigProvider {
    private final BizDataPointMapper mapper;
    private final ConcurrentMap<String, BizDataPoint> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() { refreshAll(); }

    @Scheduled(fixedDelayString = "${ingestion.point-config-refresh-ms:60000}")
    public void refreshAll() {
        List<BizDataPoint> points = mapper.selectList(new LambdaQueryWrapper<>());
        Map<String, BizDataPoint> replacement = points.stream()
                .collect(Collectors.toMap(BizDataPoint::getPointCode, Function.identity()));
        cache.clear();
        cache.putAll(replacement);
    }

    @Override
    public Optional<BizDataPoint> find(String pointCode) {
        return Optional.ofNullable(cache.get(pointCode));
    }
}
```

- [ ] **Step 4: Refresh the cache after point add/update/delete**

Inject `MySqlDataPointConfigProvider` into `BizDataPointServiceImpl`; call `refresh(pointCode)` after successful add/update and remove the deleted key after delete. Add Chinese comments explaining that this prevents the ingestion path from accepting stale point relationships.

- [ ] **Step 5: Run cache and existing HVAC tests**

Run: `mvn -q -Dtest=MySqlDataPointConfigProviderTest,FourRoleBackendFlowTest test`

Expected: both test classes pass.

---

### Task 3: TDengine raw-event and minute schema

**Files:**
- Modify: `src/main/java/com/platform/config/TdengineProperties.java`
- Modify: `src/main/java/com/platform/config/TdengineConfig.java`
- Modify: `src/env/init/04-init-tdengine-hvac.sql`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/platform/iot/temporal/model/RawTelemetryEvent.java`
- Create: `src/main/java/com/platform/iot/temporal/model/RawEventWriteResult.java`
- Create: `src/main/java/com/platform/iot/temporal/model/RawMinuteAggregate.java`
- Test: `src/test/java/com/platform/config/TdengineHvacSchemaTest.java`

**Interfaces:**
- Adds TDengine stable property `stRawEvent=st_raw_event`.
- Produces stable `st_raw_event` and additive columns on existing `st_raw_minute`.

- [ ] **Step 1: Write schema-generation tests using a mocked `JdbcTemplate`**

Assert that initialization executes SQL containing `st_raw_event`, `received_time`, `late_flag`, `avg_val`, `min_val`, `max_val`, `sample_count`, `first_received_time`, `last_received_time`, and `finalized_at`.

- [ ] **Step 2: Run the schema test and verify it fails**

Run: `mvn -q -Dtest=TdengineHvacSchemaTest test`

Expected: raw-event stable and minute aggregate columns are absent.

- [ ] **Step 3: Define the raw-event stable**

```sql
CREATE STABLE IF NOT EXISTS iot_telemetry.st_raw_event (
  ts TIMESTAMP,
  received_time TIMESTAMP,
  val DOUBLE,
  data_quality TINYINT,
  late_flag TINYINT
) TAGS (
  point_code NCHAR(100), building_id NCHAR(32), equip_id NCHAR(50),
  suffix_code NCHAR(20), is_for_calc TINYINT
);
```

- [ ] **Step 4: Expand minute storage additively for existing databases**

Keep legacy `val` for compatibility and add the approved fields. `TdengineConfig` must `DESCRIBE` the stable and execute `ALTER STABLE ... ADD COLUMN` only for missing columns. New writes set both `val` and `avg_val`; old reads use `COALESCE(avg_val,val)`.

```sql
avg_val DOUBLE,
min_val DOUBLE,
max_val DOUBLE,
sample_count INT,
first_received_time TIMESTAMP,
last_received_time TIMESTAMP,
finalized_at TIMESTAMP
```

- [ ] **Step 5: Add configuration defaults**

```yaml
tdengine:
  st-raw-event: st_raw_event

aggregation:
  finalization-delay-seconds: 30
  catch-up-minutes: 10

data-retention:
  raw-event-days: 90
  cleanup-enabled: true
```

- [ ] **Step 6: Run schema tests**

Run: `mvn -q -Dtest=TdengineHvacSchemaTest test`

Expected: schema tests pass.

---

### Task 4: Idempotent raw-event repository and ingestion orchestration

**Files:**
- Create: `src/main/java/com/platform/iot/temporal/HvacRawEventRepository.java`
- Create: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacRawEventRepository.java`
- Create: `src/main/java/com/platform/iot/ingest/IngestionOutcome.java`
- Create: `src/main/java/com/platform/iot/ingest/HvacIngestionResult.java`
- Create: `src/main/java/com/platform/iot/ingest/HvacIngestionService.java`
- Test: `src/test/java/com/platform/iot/ingest/HvacIngestionServiceTest.java`

**Interfaces:**
- `RawEventWriteResult upsert(RawTelemetryEvent event)` returns `INSERTED`, `DUPLICATE`, or `CONFLICT_UPDATED`.
- `void deleteBefore(long eventTimeExclusive)` removes only expired rows from `st_raw_event`.
- `HvacIngestionResult ingest(Map<String,Object> payload, long receivedTime)` returns `ACCEPTED`, `DUPLICATE`, `CONFLICT_UPDATED`, `REJECTED`, or `STORAGE_FAILED`.
- `HvacIngestionResult.shouldAcknowledge()` returns false only for `STORAGE_FAILED`.

- [ ] **Step 1: Write ingestion tests before the implementation**

```java
@Test
void acceptedEventIsPersistedWithPlatformQualityAndLateFlag() {
    when(validator.validate(payload, receivedAt)).thenReturn(acceptedTelemetry());
    when(repository.upsert(any())).thenReturn(RawEventWriteResult.INSERTED);
    HvacIngestionResult result = service.ingest(payload, receivedAt);
    assertThat(result.outcome()).isEqualTo(IngestionOutcome.ACCEPTED);
    verify(repository).upsert(argThat(event -> event.dataQuality() == 0 && !event.late()));
}

@Test
void rejectedEventNeverTouchesTdengine() {
    when(validator.validate(payload, receivedAt)).thenReturn(rejected(POINT_NOT_FOUND));
    assertThat(service.ingest(payload, receivedAt).outcome()).isEqualTo(IngestionOutcome.REJECTED);
    verifyNoInteractions(repository);
}
```

- [ ] **Step 2: Run the ingestion test and verify it fails**

Run: `mvn -q -Dtest=HvacIngestionServiceTest test`

Expected: missing ingestion classes.

- [ ] **Step 3: Implement late-event calculation and retry**

```java
long minuteStart = eventTime - Math.floorMod(eventTime, 60_000L);
boolean late = receivedTime >= minuteStart + 60_000L + finalizationDelay.toMillis();
```

Use `RetryTemplate` with 3 total attempts and exponential backoff `100ms → 200ms → 400ms`. Return `STORAGE_FAILED` after the final exception. Add a Chinese comment explaining that the delay changes publication time, never the one-minute event-time window.

- [ ] **Step 4: Implement repository idempotency**

Before inserting, query the child table at the exact event timestamp. Equal value returns `DUPLICATE`; a different value inserts the newer row at the same timestamp and returns `CONFLICT_UPDATED`. TDengine 3 replaces a row inserted with the same subtable timestamp. Only identifiers derived from validated point configuration may be interpolated into SQL.

- [ ] **Step 5: Add Micrometer counters and structured logs**

Counters:

```text
iot.hvac.ingestion.accepted
iot.hvac.ingestion.duplicate
iot.hvac.ingestion.conflict
iot.hvac.ingestion.rejected{reason=...}
iot.hvac.ingestion.storage_failed
```

- [ ] **Step 6: Run ingestion tests**

Run: `mvn -q -Dtest=HvacIngestionServiceTest test`

Expected: all ingestion outcomes pass.

---

### Task 5: MQTT routing and QoS 1 manual acknowledgement

**Files:**
- Create: `src/main/java/com/platform/iot/ingest/HvacMqttMessageHandler.java`
- Modify: `src/main/java/com/platform/config/MqttConfig.java`
- Delete: `src/main/java/com/platform/iot/core/handler/HvacMessageConsumer.java`
- Delete: `src/main/java/com/platform/iot/core/model/HvacTelemetryMessage.java`
- Test: `src/test/java/com/platform/iot/ingest/HvacMqttMessageHandlerTest.java`

**Interfaces:**
- `HvacIngestionResult handle(String payload, long receivedTime)` parses JSON and delegates to `HvacIngestionService`.
- `boolean shouldAcknowledge()` is true for accepted, duplicate, conflict, and deterministic rejection; false only for storage failure.

- [ ] **Step 1: Write handler tests for acknowledgement decisions**

```java
assertThat(handler.handle(validJson, now).shouldAcknowledge()).isTrue();
assertThat(handler.handle(malformedJson, now).shouldAcknowledge()).isTrue();
assertThat(storageFailure.shouldAcknowledge()).isFalse();
```

- [ ] **Step 2: Run handler tests and verify they fail**

Run: `mvn -q -Dtest=HvacMqttMessageHandlerTest test`

- [ ] **Step 3: Move HVAC JSON handling out of `MqttConfig`**

`MqttConfig` detects a payload containing `pointCode`, then delegates the original JSON to `HvacMqttMessageHandler`. Remove the Spring event publication path for `HvacTelemetryMessage` so acknowledgement is tied to the TDengine write outcome.

- [ ] **Step 4: Enable manual acknowledgements for every MQTT branch**

```java
client.setManualAcks(true);
boolean acknowledge = true;
try {
    acknowledge = routeMessage(topic, message);
} finally {
    if (acknowledge) {
        // 只有确定完成或确定拒绝的消息才向EMQX确认，存储故障保留QoS 1重投机会。
        client.messageArrivedComplete(message.getId(), message.getQos());
    }
}
```

Ensure electric telemetry, system offline events, and demo control messages still acknowledge after their existing synchronous routing completes.

- [ ] **Step 5: Run MQTT handler and existing RBAC flow tests**

Run: `mvn -q -Dtest=HvacMqttMessageHandlerTest,AuthRbacFlowTest test`

Expected: new acknowledgement tests and existing HTTP behavior pass.

---

### Task 6: Frozen event-time minute aggregation

**Files:**
- Create: `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`
- Create: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Create: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Modify: `src/main/java/com/platform/iot/temporal/HvacTimeSeriesRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/HvacTimeSeriesRepositoryImpl.java`
- Test: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`

**Interfaces:**
- `List<RawTelemetryEvent> findOnTimeEvents(String pointCode, long startInclusive, long endExclusive)`.
- `boolean exists(String pointCode, long minuteStart)`.
- `void save(RawMinuteAggregate aggregate)`.
- `void finalizeDueMinutes(long now)` for deterministic tests and scheduled production invocation.

- [ ] **Step 1: Write aggregation tests**

Cover one sample, multiple samples, next-minute isolation, late exclusion, existing-minute freeze, and a 10-minute restart catch-up range.

```java
assertThat(aggregate.avgValue()).isEqualTo(35.0);
assertThat(aggregate.minValue()).isEqualTo(34.0);
assertThat(aggregate.maxValue()).isEqualTo(36.0);
assertThat(aggregate.sampleCount()).isEqualTo(3);
```

- [ ] **Step 2: Run aggregation tests and verify they fail**

Run: `mvn -q -Dtest=HvacMinuteAggregationServiceTest test`

- [ ] **Step 3: Implement exact event-time due-minute arithmetic**

```java
long effectiveNow = now - finalizationDelay.toMillis();
long currentEffectiveMinute = effectiveNow - Math.floorMod(effectiveNow, 60_000L);
long latestDueMinute = currentEffectiveMinute - 60_000L;
```

Scan only `[latestDueMinute - (catchUpMinutes - 1) * 60_000, latestDueMinute]`. Skip any point/minute already present so late conflicts cannot silently overwrite a frozen result.

- [ ] **Step 4: Implement aggregate calculation**

Use accepted, non-late, already de-duplicated events. Calculate `avg`, `min`, `max`, `sampleCount`, worst quality, first receive time, and last receive time. Save both legacy `val` and new `avg_val` to keep current readers compatible.

- [ ] **Step 5: Remove the unsafe in-memory minute bucket**

Delete minute bucket accumulation and `flushMinuteAverages()` from `HvacTimeSeriesRepositoryImpl`. Keep indicator methods and adapt raw-minute queries to return `COALESCE(avg_val,val) AS val`.

- [ ] **Step 6: Schedule finalization**

```java
@Scheduled(fixedDelayString = "${aggregation.scan-delay-ms:10000}")
public void finalizeDueMinutes() {
    finalizeDueMinutes(System.currentTimeMillis());
}
```

- [ ] **Step 7: Run aggregation and repository tests**

Run: `mvn -q -Dtest=HvacMinuteAggregationServiceTest test`

Expected: all event-time and freeze rules pass.

---

### Task 7: Configurable raw-event retention

**Files:**
- Create: `src/main/java/com/platform/iot/temporal/HvacRawEventRetentionService.java`
- Test: `src/test/java/com/platform/iot/temporal/HvacRawEventRetentionServiceTest.java`

**Interfaces:**
- `void cleanup(long now)` deletes only raw events older than the configured number of days.

- [ ] **Step 1: Write cutoff, disabled-cleanup, and minute-table-safety tests**

```java
service.cleanup(Instant.parse("2026-07-23T00:00:00Z").toEpochMilli());
verify(rawRepository).deleteBefore(Instant.parse("2026-04-24T00:00:00Z").toEpochMilli());
verifyNoInteractions(minuteRepository);
```

- [ ] **Step 2: Run retention tests and verify they fail**

Run: `mvn -q -Dtest=HvacRawEventRetentionServiceTest test`

- [ ] **Step 3: Implement a guarded daily cleanup**

Use `@ConditionalOnProperty(name="data-retention.cleanup-enabled", havingValue="true")` and `@Scheduled(cron="${data-retention.cleanup-cron:0 30 3 * * ?}")`. Validate `raw-event-days >= 1` before deleting. Log the configured days, cutoff, and completion status in Chinese.

- [ ] **Step 4: Run retention tests**

Run: `mvn -q -Dtest=HvacRawEventRetentionServiceTest test`

Expected: only `st_raw_event` receives deletion SQL.

---

### Task 8: Nineteen-point fixtures and full acceptance verification

**Files:**
- Modify: `src/test/resources/data-test.sql`
- Create: `src/test/java/com/platform/iot/ingest/NineteenPointIngestionAcceptanceTest.java`
- Modify: `docs/superpowers/specs/2026-07-23-19-point-ingestion-quality-design.md` only if implementation reveals a necessary non-behavioral clarification.

**Interfaces:**
- Uses all 19 exact point codes from `src/env/init/03-init-hvac-schema.sql`.
- Verifies every point is accepted with platform quality 0 and correct device/global-point handling.

- [ ] **Step 1: Seed all 19 point configurations in H2**

Add WCR1 (7), TOWER1 (3), PUMP1 (5), AHU1 (2), and DBO (2) rows. Set representative optional bounds only in test-specific rows used by range tests; do not invent production bounds.

- [ ] **Step 2: Write the 19-point acceptance test**

Loop through an explicit fixture list and assert accepted outcomes. Verify `DBO_TDB` and `DBO_RH` accept a valid source device even though `equip_id` is NULL. Verify no fixture generates quality 1 or 2.

- [ ] **Step 3: Run focused IoT tests**

Run: `mvn -q -Dtest='com.platform.iot.*' test`

Expected: validator, cache, schema, ingestion, MQTT, aggregation, retention, and 19-point acceptance tests pass.

- [ ] **Step 4: Run the complete backend test suite**

Run with JDK 21:

```powershell
$env:JAVA_HOME='C:\Users\yang\.jdks\zulu-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q test
```

Expected: zero failures and zero errors.

- [ ] **Step 5: Package the application**

Run: `mvn -q -DskipTests package`

Expected: `target/iot-platform-demo-1.0-SNAPSHOT.jar` is generated successfully.

- [ ] **Step 6: Final static checks**

Run:

```powershell
rg -n "default_value|dataQuality\s*=\s*[12]|simulate|interpol" src/main/java/com/platform/iot
```

Expected: no runtime fallback generation and no simulation path added.
