# Industrial Asset and Point Identity Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace building-ambiguous HVAC business-code primary keys with stable internal IDs, canonical point naming, source aliases, and ID-based TDengine storage without breaking the frozen 19-point MQTT contract.

**Architecture:** MySQL becomes the asset/configuration source of truth with global IDs and building-scoped business codes. MQTT resolves a building/source alias to a canonical point before validation. TDengine child tables, minute aggregation, recovery, and future indicators use internal point/indicator IDs while preserving readable tags and raw source provenance.

**Tech Stack:** Java 21, Spring Boot 3, MyBatis-Plus, MySQL 8, H2 tests, Eclipse Paho MQTT, TDengine 3, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Do not modify frontend pages.
- HVAC MQTT requires `buildingId`; current MQTT source is `MQTT_FREEZE_V1`.
- Internal IDs use MyBatis-Plus `IdType.ASSIGN_ID` and are never accepted from create callers.
- Business equipment numbering starts at 1 per `building_id + type_code`, never reuses logically deleted numbers, and is protected by a database unique key.
- Canonical point codes follow `HANDOFF_V1`; frozen codes remain source aliases.
- Raw source code is a TDengine data column, not a tag.
- TDengine raw/minute/indicator child tables are named by `point_id` or `indicator_id`.
- Do not implement interpolation, default-value filling, quality level 2, COP formulas, or simulated data.

---

### Task 1: Establish the MySQL identity schema and seed mapping

**Files:**
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Modify: `src/test/resources/schema-test.sql`
- Modify: `src/test/resources/data-test.sql`
- Create: `src/env/init/05-migrate-industrial-identity.sql`

**Interfaces:**
- Produces: `biz_equipment_type`, `biz_point_naming_rule`, `biz_point_alias`, and `biz_indicator`.
- Produces: global `system_group_id`, `equip_id`, `point_id`; building-scoped `system_group_code`, `equip_code`, `point_code`.
- Produces: all 19 `MQTT_FREEZE_V1` aliases.

- [ ] **Step 1: Write schema assertions that require the new IDs and unique keys**

Add H2 definitions matching:

```sql
CREATE TABLE biz_equipment (
  equip_id VARCHAR(32) PRIMARY KEY,
  equip_code VARCHAR(50) NOT NULL,
  type_code VARCHAR(20) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  del_flag TINYINT DEFAULT 0,
  UNIQUE (building_id, equip_code)
);

CREATE TABLE biz_data_point (
  point_id VARCHAR(32) PRIMARY KEY,
  point_code VARCHAR(100) NOT NULL,
  building_id VARCHAR(32) NOT NULL,
  equip_id VARCHAR(32),
  UNIQUE (building_id, point_code)
);
```

Seed two buildings with repeated equipment/source codes and assert Spring context initialization succeeds.

- [ ] **Step 2: Run schema-backed tests to verify the old model fails**

Run:

```powershell
mvn -Dtest=FourRoleBackendFlowTest,NineteenPointIngestionAcceptanceTest test
```

Expected: FAIL because entities and SQL still expect business codes as primary keys.

- [ ] **Step 3: Implement the fresh-install schema and 19-point canonical/alias seeds**

Use fixed, unique seed IDs so foreign-key relationships are deterministic. Insert these canonical mappings:

```text
WCR1_Flow       -> WCR1_GW
TOWER1_TCWin    -> WCR1_CT_TWin
TOWER1_TCWout   -> WCR1_CT_TWout
PUMP1_Flow      -> WCR1_Pc_GW
PUMP1_Power     -> WCR1_Pc_PPE
DBO_TDB         -> DBO
DBO_RH          -> RHO
```

Register every frozen-only suffix and AHU rule with `standard_source='FREEZE_EXTENSION'`.

- [ ] **Step 4: Add a guarded existing-database migration**

The migration must:

```sql
-- 1. stop if duplicate building-scoped legacy codes exist;
-- 2. create mapping tables for old and new IDs;
-- 3. copy legacy IDs into business-code columns;
-- 4. generate new internal IDs;
-- 5. rewrite dependent IDs;
-- 6. insert 19 aliases;
-- 7. add unique keys and foreign keys;
-- 8. preserve all rows and logical-delete flags.
```

It must not drop historical TDengine data or physically delete MySQL business rows.

- [ ] **Step 5: Run schema-backed tests**

Run:

```powershell
mvn -Dtest=FourRoleBackendFlowTest,NineteenPointIngestionAcceptanceTest test
```

Expected: schema loads; remaining failures are Java model mismatches addressed by Task 2.

### Task 2: Update asset entities, mappers, and management services

**Files:**
- Modify: `src/main/java/com/platform/hvac/model/entity/BizSystemGroup.java`
- Modify: `src/main/java/com/platform/hvac/model/entity/BizSpace.java`
- Modify: `src/main/java/com/platform/hvac/model/entity/BizEquipment.java`
- Modify: `src/main/java/com/platform/hvac/model/entity/BizDataPoint.java`
- Create: `src/main/java/com/platform/hvac/model/entity/BizEquipmentType.java`
- Create: `src/main/java/com/platform/hvac/model/entity/BizPointNamingRule.java`
- Create: `src/main/java/com/platform/hvac/model/entity/BizPointAlias.java`
- Create: `src/main/java/com/platform/hvac/model/entity/BizIndicator.java`
- Create: `src/main/java/com/platform/hvac/mapper/BizEquipmentTypeMapper.java`
- Create: `src/main/java/com/platform/hvac/mapper/BizPointNamingRuleMapper.java`
- Create: `src/main/java/com/platform/hvac/mapper/BizPointAliasMapper.java`
- Create: `src/main/java/com/platform/hvac/mapper/BizIndicatorMapper.java`
- Modify: HVAC service implementations and controllers.
- Test: `src/test/java/com/platform/hvac/IndustrialIdentityManagementTest.java`

**Interfaces:**
- Produces: `BizEquipment.equipId` as `@TableId(ASSIGN_ID)` and `equipCode` as business code.
- Produces: `BizDataPoint.pointId` as `@TableId(ASSIGN_ID)` and `pointCode` as canonical code.
- Produces: `EquipmentCodeAllocator.next(prefix, historicalCodes)`.

- [ ] **Step 1: Write failing management tests**

Cover:

```java
assertThat(firstBuildingCode).isEqualTo("WCR1");
assertThat(secondBuildingCode).isEqualTo("WCR1");
assertThat(first.getEquipId()).isNotEqualTo(second.getEquipId());
assertThat(afterDeletedWcr2.getEquipCode()).isEqualTo("WCR3");
```

Also verify cross-building system-group, space, equipment, and point relationships are rejected.

- [ ] **Step 2: Run the management test**

Run:

```powershell
mvn -Dtest=IndustrialIdentityManagementTest test
```

Expected: FAIL because the allocator and new fields do not exist.

- [ ] **Step 3: Implement entities and code allocation**

Implement:

```java
public String next(String prefix, Collection<String> historicalCodes)
```

It accepts only exact `prefix + positive integer` codes, returns `prefix + (max + 1)`, and starts at 1.

Equipment creation clears caller-provided IDs/codes, reads `assetCodePrefix`, includes deleted rows in allocation, and retries duplicate-key conflicts at most three times.

- [ ] **Step 4: Protect immutable identity and building relationships**

Update operations retain original internal ID, business code, and building. Point creation validates equipment/system/building consistency and naming-rule status before insert.

- [ ] **Step 5: Update controllers to address resources by internal ID**

Point delete becomes:

```java
@DeleteMapping("/delete/{pointId}")
public Result<Void> delete(@PathVariable String pointId)
```

Search uses names and business codes, not UUID/Snowflake IDs as the primary operator field.

- [ ] **Step 6: Run management and RBAC tests**

Run:

```powershell
mvn -Dtest=IndustrialIdentityManagementTest,FourRoleBackendFlowTest test
```

Expected: PASS.

### Task 3: Resolve MQTT source aliases before quality validation

**Files:**
- Create: `src/main/java/com/platform/iot/quality/PointAliasKey.java`
- Create: `src/main/java/com/platform/iot/quality/PointRuntimeConfig.java`
- Modify: `src/main/java/com/platform/iot/quality/DataPointConfigProvider.java`
- Modify: `src/main/java/com/platform/iot/quality/MySqlDataPointConfigProvider.java`
- Modify: `src/main/java/com/platform/iot/quality/TelemetryQualityValidator.java`
- Modify: `src/main/java/com/platform/iot/quality/ValidatedHvacTelemetry.java`
- Modify: `src/main/java/com/platform/iot/ingest/HvacIngestionService.java`
- Modify: `src/main/java/com/platform/iot/ingest/HvacMqttMessageHandler.java`
- Modify: `src/main/java/com/platform/config/MqttConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: existing quality and ingestion tests.

**Interfaces:**
- Consumes: `PointAliasKey(buildingId, sourceSystem, sourcePointCode)`.
- Produces: `PointRuntimeConfig` keyed by `pointId`.
- Produces: validated telemetry carrying canonical and source identities.

- [ ] **Step 1: Write failing alias-isolation tests**

Test two aliases with the same source point code:

```java
new PointAliasKey("BLD001", "MQTT_FREEZE_V1", "WCR1_TWin");
new PointAliasKey("BLD002", "MQTT_FREEZE_V1", "WCR1_TWin");
```

Verify each resolves to a different `pointId`. Verify missing `buildingId`, missing alias, and `deviceId != equipCode` are rejected.

- [ ] **Step 2: Run quality and ingestion tests**

Run:

```powershell
mvn -Dtest=TelemetryQualityValidatorTest,MySqlDataPointConfigProviderTest,HvacIngestionServiceTest,HvacMqttMessageHandlerTest,NineteenPointIngestionAcceptanceTest test
```

Expected: FAIL on the new protocol and provider interface.

- [ ] **Step 3: Implement two-map immutable configuration caching**

Atomically replace:

```java
Map<PointAliasKey, String> aliasToPointId;
Map<String, PointRuntimeConfig> pointById;
```

Alias lookup never queries MySQL on the MQTT hot path. Refresh failure keeps the last complete snapshot.

- [ ] **Step 4: Require building identity and resolve the configured source**

Add:

```yaml
ingestion:
  source-system: MQTT_FREEZE_V1
```

`MqttConfig` supplies this trusted source value. Payload values cannot override it.

- [ ] **Step 5: Run alias and 19-point acceptance tests**

Run the command from Step 2.

Expected: PASS; all 19 frozen source codes resolve to canonical point IDs.

### Task 4: Convert TDengine raw events and minute storage to internal IDs

**Files:**
- Modify: `src/env/init/04-init-tdengine-hvac.sql`
- Create: `src/env/init/06-migrate-tdengine-industrial-identity.sql`
- Modify: `src/main/java/com/platform/iot/temporal/model/RawTelemetryEvent.java`
- Modify: `src/main/java/com/platform/iot/temporal/model/RawMinuteAggregate.java`
- Modify: `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacRawEventRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/HvacTimeSeriesRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/HvacTimeSeriesRepositoryImpl.java`
- Test: TDengine repository tests.

**Interfaces:**
- Produces: `st_raw_event_{pointId}`, `st_raw_minute_{pointId}`, `st_indicator_minute_{indicatorId}`.
- Produces: `Set<String> findExistingPointIds(long minuteStart)`.

- [ ] **Step 1: Write failing SQL-generation tests**

Assert generated child names contain internal IDs and raw event inserts contain:

```text
source_system
source_point_code
source_device_id
```

Assert source fields are data columns, not `TAGS`.

- [ ] **Step 2: Run TDengine repository tests**

Run:

```powershell
mvn -Dtest=TdengineHvacRawEventRepositoryTest,TdengineHvacMinuteRepositoryTest,TdengineHvacSchemaTest test
```

Expected: FAIL because repositories still use `pointCode`.

- [ ] **Step 3: Implement ID-based raw event storage**

`qualifiedChild` accepts `pointId`. Super-table tags carry stable point/asset semantics. The exact-timestamp duplicate query and upsert operate only inside that point-ID child.

- [ ] **Step 4: Implement ID-based minute and indicator storage**

Rename recovery API to `findExistingPointIds`. Query methods accept internal IDs. Indicator writes require an `indicatorId`.

- [ ] **Step 5: Add non-destructive TDengine migration SQL**

Add tags/columns required by the new model, backfill legacy child tags from MySQL-exported mapping values, and preserve old children. New code alone writes ID-named children.

- [ ] **Step 6: Run TDengine tests**

Run the command from Step 2.

Expected: PASS.

### Task 5: Group and recover minute data by point ID

**Files:**
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteBatchFrozenEvent.java`
- Test: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`

**Interfaces:**
- Consumes: canonical `PointRuntimeConfig` keyed by point ID.
- Produces: one aggregate per `pointId + minuteStart`.

- [ ] **Step 1: Write a failing cross-building aggregation test**

Give two raw events with source/canonical code `WCR1_TWin` but distinct point IDs and buildings. Assert two aggregates are produced and each retains its own building/equipment identity.

- [ ] **Step 2: Run aggregation tests**

Run:

```powershell
mvn -Dtest=HvacMinuteAggregationServiceTest,HvacMinuteRecoverySchedulerTest test
```

Expected: FAIL because grouping and recovery still key on `pointCode`.

- [ ] **Step 3: Implement point-ID grouping and recovery**

Use:

```java
grouped.computeIfAbsent(event.pointId(), ignored -> new ArrayList<>()).add(event);
```

Active-point and missing-point sets use `pointId`. Frozen event ordering remains deterministic by configured canonical point order.

- [ ] **Step 4: Run aggregation tests**

Run the command from Step 2.

Expected: PASS.

### Task 6: Update acceptance documentation and run the full regression suite

**Files:**
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Modify: `src/test/resources/schema-test.sql`
- Modify: `src/test/resources/data-test.sql`
- Modify: `src/test/java/com/platform/iot/ingest/NineteenPointIngestionAcceptanceTest.java`
- Modify: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`
- Modify: `src/test/java/com/platform/iot/temporal/TdengineHvacRawEventRepositoryTest.java`
- Modify: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`

**Interfaces:**
- Documents: `buildingId`, frozen source `pointCode`, canonical mapping, and internal-ID behavior.

- [ ] **Step 1: Update the MQTT contract**

Document:

```json
{
  "buildingId": "BLD001",
  "deviceId": "WCR1",
  "pointCode": "WCR1_TWin",
  "val": 12.6,
  "timestamp": 1784781000000
}
```

State that `pointCode` is a source alias, internal IDs are never reported by hardware, and missing `buildingId` is rejected.

- [ ] **Step 2: Run the complete test suite**

Run:

```powershell
mvn test
```

Expected: all tests PASS.

- [ ] **Step 3: Build the application**

Run:

```powershell
mvn -DskipTests package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Review migration safety**

Verify:

- no SQL drops TDengine historical children;
- no MySQL migration physically deletes business rows;
- duplicate preflight runs before unique constraints;
- all 19 aliases exist exactly once;
- all Java child-table names use internal IDs.

- [ ] **Step 5: Record final results**

Summarize changed schema, API compatibility, tests, package result, and any migration command that must be run manually in a controlled environment.
