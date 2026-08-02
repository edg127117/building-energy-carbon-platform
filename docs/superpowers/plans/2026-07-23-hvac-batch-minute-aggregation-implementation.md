# HVAC Batch Minute Aggregation Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-point ten-minute polling with one TDengine raw-window query per due minute, Java grouping, one batch minute write, and startup/low-frequency recovery.

**Architecture:** `HvacRawEventRepository` exposes a stable-wide one-minute query and `HvacMinuteRepository` exposes idempotent batch persistence. `HvacMinuteAggregationService` tracks a normal-processing watermark, groups one window in memory, persists the batch, and publishes an immutable frozen event. A separate recovery scheduler invokes the same aggregation service only at application startup and at a low frequency.

**Tech Stack:** Java 21, Spring Boot 3.2, Spring scheduling/events, Spring JDBC, TDengine 3, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Keep the event-time window exactly `[minuteStart, minuteStart + 60_000)`.
- Keep the 30-second finalization delay configurable.
- Exclude `late_flag = 1` from formal minute aggregation.
- Do not implement interpolation, `default_value`, quality 2, simulation, or COP formulas.
- Do not change cross-building `equip_id`, `point_code`, or child-table uniqueness in this change.
- Add Chinese comments for timing, idempotency, recovery, and formula handoff behavior.
- TDengine remains the durable source of truth; do not add a persistent JVM minute bucket.

---

### Task 1: Add Stable-Wide Raw Window Query

**Files:**
- Modify: `src/main/java/com/platform/iot/temporal/HvacRawEventRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacRawEventRepository.java`
- Modify: `src/test/java/com/platform/iot/temporal/TdengineHvacRawEventRepositoryTest.java`

**Interfaces:**
- Produces: `List<RawTelemetryEvent> findWindow(long startInclusive, long endExclusive, boolean includeLate)`
- Consumes: existing `RawTelemetryEvent` row mapping and TDengine `st_raw_event` stable.

- [ ] **Step 1: Write failing repository tests**

Add tests that call `findWindow` and assert the generated SQL targets `iot_telemetry.st_raw_event`, contains one exact event-time range, excludes late rows when requested, and maps events from multiple point codes.

- [ ] **Step 2: Run the focused test**

Run:

```powershell
mvn -q -Dtest=TdengineHvacRawEventRepositoryTest test
```

Expected: compilation failure because `findWindow` does not exist.

- [ ] **Step 3: Replace the per-point query contract**

Use:

```java
List<RawTelemetryEvent> findWindow(
        long startInclusive,
        long endExclusive,
        boolean includeLate);
```

Build one SQL query against the stable:

```java
String stable = safeIdentifier(properties.getDatabase()) + "."
        + safeIdentifier(properties.getStRawEvent());
String sql = "SELECT ts, received_time, val, data_quality, late_flag, "
        + "point_code, building_id, equip_id, suffix_code, is_for_calc FROM "
        + stable
        + " WHERE ts >= " + quote(new Timestamp(startInclusive).toString())
        + " AND ts < " + quote(new Timestamp(endExclusive).toString())
        + (includeLate ? "" : " AND late_flag=0")
        + " ORDER BY point_code, ts";
```

- [ ] **Step 4: Run the focused test**

Expected: all raw repository tests pass.

---

### Task 2: Add Batch Minute Repository Operations

**Files:**
- Modify: `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Create: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`

**Interfaces:**
- Produces: `void saveAll(List<RawMinuteAggregate> aggregates)`
- Produces: `Set<String> findExistingPointCodes(long minuteStart)`
- Consumes: existing `RawMinuteAggregate` and TDengine minute stable.

- [ ] **Step 1: Write failing batch repository tests**

Cover:

```java
repository.saveAll(List.of(firstAggregate, secondAggregate));
repository.findExistingPointCodes(MINUTE);
```

Assert one multi-child data `INSERT` contains both child tables and that one stable query returns all existing point codes for the minute.

- [ ] **Step 2: Run the focused test**

Expected: compilation failure because batch methods do not exist.

- [ ] **Step 3: Implement batch persistence**

Generate one TDengine statement:

```text
INSERT INTO
  child1 USING stable (...) TAGS (...) (...) VALUES (...)
  child2 USING stable (...) TAGS (...) (...) VALUES (...)
```

Return immediately for an empty list. Keep the existing one-time legacy building-tag repair before the batch data statement.

- [ ] **Step 4: Implement recovery existence query**

Use one stable query for a minute:

```sql
SELECT point_code
FROM iot_telemetry.st_raw_minute
WHERE ts = '...';
```

Return a de-duplicated `Set<String>`.

- [ ] **Step 5: Run the focused test**

Expected: all minute repository tests pass.

---

### Task 3: Refactor Normal Aggregation to One Window per Minute

**Files:**
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Replace tests in: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`

**Interfaces:**
- Consumes: `HvacRawEventRepository.findWindow(...)`
- Consumes: `HvacMinuteRepository.saveAll(...)`
- Produces: `void finalizeDueMinutes(long now)`
- Produces: `void recoverRecentMinutes(long now)`

- [ ] **Step 1: Write failing normal-flow tests**

Test that:

- two points in one minute cause exactly one `findWindow` call;
- Java grouping creates two correct aggregates;
- `saveAll` is called once;
- a minute before the 30-second boundary is not queried;
- the same due minute is skipped on the next 10-second tick;
- a failed batch save leaves the watermark unchanged so the next call retries.

- [ ] **Step 2: Run the focused test**

Expected: failures because the existing implementation still queries per point.

- [ ] **Step 3: Implement active configuration mapping**

Build a map of `ONLINE` points keyed by `pointCode`, discard raw events without an active configuration, and compute each aggregate using configured building/equipment/suffix metadata.

- [ ] **Step 4: Implement the normal watermark**

Use an `AtomicLong` initialized to `Long.MIN_VALUE`. On the first tick process only `latestDueMinute`; thereafter process each minute between `lastProcessedMinute + 60_000` and the latest due minute. Advance only after a successful query and batch save.

- [ ] **Step 5: Run the focused test**

Expected: all normal-flow tests pass.

---

### Task 4: Publish the Formula Handoff Event

**Files:**
- Create: `src/main/java/com/platform/iot/aggregation/HvacMinuteBatchFrozenEvent.java`
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Modify: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`

**Interfaces:**
- Produces:

```java
public record HvacMinuteBatchFrozenEvent(
        long minuteStart,
        long finalizedAt,
        boolean recovery,
        List<RawMinuteAggregate> aggregates) {
}
```

- [ ] **Step 1: Write a failing event test**

Capture the object passed to `ApplicationEventPublisher.publishEvent` and assert it is published after `saveAll`, contains the exact persisted aggregates, and has `recovery=false` for the normal path.

- [ ] **Step 2: Implement the immutable event**

Defensively copy the list in the record compact constructor:

```java
aggregates = List.copyOf(aggregates);
```

- [ ] **Step 3: Publish only after successful persistence**

Catch listener failures, log that the minute is already durable, and allow the watermark to advance. Do not publish an event for an empty minute.

- [ ] **Step 4: Run the focused test**

Expected: aggregation and event tests pass.

---

### Task 5: Move Ten-Minute Scanning to Recovery Only

**Files:**
- Create: `src/main/java/com/platform/iot/aggregation/HvacMinuteRecoveryScheduler.java`
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Create: `src/test/java/com/platform/iot/aggregation/HvacMinuteRecoverySchedulerTest.java`
- Modify: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`

**Interfaces:**
- Recovery scheduler calls `HvacMinuteAggregationService.recoverRecentMinutes(System.currentTimeMillis())`.
- Recovery service uses `findExistingPointCodes` once and `findWindow` at most once per recovered minute.

- [ ] **Step 1: Write failing recovery tests**

Cover:

- already complete minutes do not query raw events;
- partially complete minutes aggregate only missing point codes;
- recovery event has `recovery=true`;
- the scheduler delegates on application ready and scheduled execution.

- [ ] **Step 2: Implement synchronized recovery**

Serialize normal and recovery paths with the service monitor. For each of the last `catchUpMinutes`, compare active point codes with `findExistingPointCodes`, query the raw window once only when missing codes remain, then save only missing aggregates.

- [ ] **Step 3: Add conditional recovery scheduler**

Use:

```java
@ConditionalOnProperty(
        name = "aggregation.recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
```

Invoke once from `ApplicationReadyEvent` and then at `recovery-delay-ms`, default 600,000 ms.

- [ ] **Step 4: Add configuration**

Production:

```yaml
aggregation:
  recovery-enabled: true
  recovery-delay-ms: 600000
```

Tests disable scheduled recovery:

```yaml
aggregation:
  recovery-enabled: false
```

- [ ] **Step 5: Run focused aggregation tests**

Expected: normal and recovery tests pass.

---

### Task 6: Regression, Static Review, and Packaging

**Files:**
- Review: all files changed in Tasks 1–5
- Update if required: `docs/superpowers/specs/2026-07-23-hvac-batch-minute-aggregation-design.md`

**Interfaces:**
- Verifies the complete repository and application artifact.

- [ ] **Step 1: Run all new focused tests**

```powershell
mvn -q '-Dtest=HvacMinuteAggregationServiceTest,HvacMinuteRecoverySchedulerTest,TdengineHvacRawEventRepositoryTest,TdengineHvacMinuteRepositoryTest' test
```

Expected: zero failures and errors.

- [ ] **Step 2: Run the complete suite**

```powershell
mvn -q test
```

Expected: zero failures and errors.

- [ ] **Step 3: Run static searches**

Confirm normal aggregation no longer calls per-point `find` or `exists`, and no interpolation/default-value/quality-2 generation was added.

- [ ] **Step 4: Build the final artifact**

```powershell
mvn -q -DskipTests package
```

Expected artifact:

```text
target/iot-platform-demo-1.0-SNAPSHOT.jar
```

- [ ] **Step 5: Report limitations**

State that automated tests pass but live EMQX/TDengine acceptance still requires running external services. Also state that cross-building identifier uniqueness remains intentionally deferred.
