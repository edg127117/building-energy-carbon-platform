# HVAC Latest Indicator Query Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the latest-indicator API return every requested indicator from TDengine after Redis expires, including both latest success and latest exception states.

**Architecture:** Keep Redis, Service merging, DTOs, formulas, and TDengine schemas unchanged. Replace the repository's `PARTITION BY ... ORDER BY ... LIMIT 1` queries with TDengine 3.2.3-verified `LAST_ROW` projections partitioned by the complete persisted indicator identity, then prove both cache-fallback paths with automated tests and the real Docker stack.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Spring JDBC, TDengine 3.2.3, Redis 7.2, JUnit 5, Mockito, AssertJ, Maven, Docker Compose, Node.js 22.

## Global Constraints

- Work only on `fix/hvac-latest-indicator-query`, based on the latest `origin/main`.
- Do not change formula behavior, TDengine schemas, Redis TTL, API DTOs, Docker image versions, or environment port configuration.
- Keep MySQL and TDengine access boundaries explicit; this repository continues to use `@Qualifier("taosJdbcTemplate")`.
- Add concise Chinese comments explaining why `LAST_ROW` is required for the supported TDengine version.
- Ordinary Maven tests must not connect to real MySQL, TDengine, MQTT, or Redis.
- Real external-resource verification is a separate Docker smoke step.
- Do not stage or commit `outputs/` or any unrelated file.
- Do not upgrade TDengine as part of this fix.

---

### Task 1: Replace the latest-row SQL with TDengine `LAST_ROW`

**Files:**
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java:131-152`
- Test: `src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java:120-165`

**Interfaces:**
- Consumes: `IndicatorMinuteRepository.findLatestSuccesses(List<String>)` and `findLatestExceptions(List<String>)`.
- Produces: the same `List<IndicatorMinuteResult>` and `List<FormulaCalculationException>` contracts, with at most one latest row per complete indicator identity.

- [ ] **Step 1: Strengthen the repository regression test**

Replace `latestSuccessAndExceptionUseSeparateStableQueries()` with:

```java
@Test
void latestSuccessAndExceptionUseLastRowPerCompleteIndicatorIdentity() {
    when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of());

    repository.findLatestSuccesses(List.of("INDICATOR_B", "INDICATOR_A"));
    repository.findLatestExceptions(List.of("INDICATOR_B", "INDICATOR_A"));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(template, times(2)).query(
            sqlCaptor.capture(),
            any(org.springframework.jdbc.core.RowMapper.class));
    String successSql = sqlCaptor.getAllValues().get(0);
    String exceptionSql = sqlCaptor.getAllValues().get(1);
    String identityPartition = "PARTITION BY indicator_id,indicator_code,"
            + "building_id,system_group_id,equip_id";

    assertThat(successSql)
            .contains("st_indicator_minute")
            .doesNotContain("st_formula_calc_exception")
            .contains(
                    "LAST_ROW(ts) AS ts",
                    "LAST_ROW(val) AS val",
                    "LAST_ROW(data_quality) AS data_quality",
                    "LAST_ROW(formula_version) AS formula_version",
                    "LAST_ROW(calculated_at) AS calculated_at",
                    identityPartition)
            .doesNotContain("ORDER BY ts DESC LIMIT 1");
    assertThat(exceptionSql)
            .contains("st_formula_calc_exception")
            .doesNotContain("FROM iot_telemetry.st_indicator_minute")
            .contains(
                    "LAST_ROW(ts) AS ts",
                    "LAST_ROW(calc_status) AS calc_status",
                    "LAST_ROW(reason_code) AS reason_code",
                    "LAST_ROW(missing_inputs) AS missing_inputs",
                    "LAST_ROW(formula_version) AS formula_version",
                    "LAST_ROW(calculated_at) AS calculated_at",
                    identityPartition)
            .doesNotContain("ORDER BY ts DESC");
}
```

Add an empty-query contract test:

```java
@Test
void emptyLatestIndicatorIdsDoNotTouchTdengine() {
    assertThat(repository.findLatestSuccesses(List.of())).isEmpty();
    assertThat(repository.findLatestExceptions(List.of())).isEmpty();
    verifyNoInteractions(template);
}
```

- [ ] **Step 2: Run the focused test and verify the old implementation fails**

Run:

```powershell
mvn -Dtest=TdengineIndicatorMinuteRepositoryTest test
```

Expected: `latestSuccessAndExceptionUseLastRowPerCompleteIndicatorIdentity` fails because the old SQL has no `LAST_ROW` projection and still contains `ORDER BY ... LIMIT 1`. The empty-ID test passes.

- [ ] **Step 3: Implement the minimum latest-row query change**

Replace the two repository methods with:

```java
@Override
public List<IndicatorMinuteResult> findLatestSuccesses(List<String> indicatorIds) {
    if (indicatorIds.isEmpty()) {
        return List.of();
    }
    // TDengine 3.2.3 对超级表分区后再排序截断可能只返回一个分区；
    // LAST_ROW 按完整指标身份取值，保证批量请求中每个指标最多返回一行。
    String sql = latestSuccessSelect()
            + " WHERE indicator_id IN (" + values(indicatorIds) + ")"
            + latestIdentityPartition();
    return template.query(sql, this::mapSuccess).stream()
            .sorted(Comparator.comparing(IndicatorMinuteResult::indicatorId))
            .toList();
}

@Override
public List<FormulaCalculationException> findLatestExceptions(
        List<String> indicatorIds) {
    if (indicatorIds.isEmpty()) {
        return List.of();
    }
    // 异常表必须使用与成功表相同的取最新规则，否则 Redis 过期后仍可能漏掉失败指标。
    String sql = latestExceptionSelect()
            + " WHERE indicator_id IN (" + values(indicatorIds) + ")"
            + latestIdentityPartition();
    return template.query(sql, this::mapException).stream()
            .sorted(Comparator.comparing(FormulaCalculationException::indicatorId))
            .toList();
}
```

Add these helpers next to the existing SELECT helpers:

```java
private String latestSuccessSelect() {
    return "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
            + "LAST_ROW(ts) AS ts,LAST_ROW(val) AS val,"
            + "LAST_ROW(data_quality) AS data_quality,"
            + "LAST_ROW(formula_version) AS formula_version,"
            + "LAST_ROW(calculated_at) AS calculated_at FROM " + indicatorStable();
}

private String latestExceptionSelect() {
    return "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
            + "LAST_ROW(ts) AS ts,LAST_ROW(calc_status) AS calc_status,"
            + "LAST_ROW(reason_code) AS reason_code,"
            + "LAST_ROW(missing_inputs) AS missing_inputs,"
            + "LAST_ROW(formula_version) AS formula_version,"
            + "LAST_ROW(calculated_at) AS calculated_at FROM " + exceptionStable();
}

private String latestIdentityPartition() {
    return " PARTITION BY indicator_id,indicator_code,building_id,"
            + "system_group_id,equip_id";
}
```

Keep `successSelect()` and `exceptionSelect()` unchanged because exact-minute and history queries still require ordinary row selection.

- [ ] **Step 4: Run the focused repository test**

Run:

```powershell
mvn -Dtest=TdengineIndicatorMinuteRepositoryTest test
```

Expected: all tests in `TdengineIndicatorMinuteRepositoryTest` pass with zero failures and zero errors.

- [ ] **Step 5: Review scope, comments, and staged files**

Run:

```powershell
git diff --check
git diff -- src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java
git diff -- src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java
```

Confirm:

- comments explain the TDengine 3.2.3 behavior and failure impact;
- no formula, DTO, schema, configuration, or unrelated file changed;
- `outputs/` remains untracked.

- [ ] **Step 6: Commit the production fix and regression test**

Run:

```powershell
git add -- `
  src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java `
  src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(hvac): query every latest indicator row"
```

Expected staged files: exactly the repository and its test.

---

### Task 2: Run the complete automated regression

**Files:**
- Verify only: `pom.xml`
- Verify only: `src/test/java`

**Interfaces:**
- Consumes: the repository implementation committed in Task 1.
- Produces: evidence that the production-code change does not regress the backend test suite.

- [ ] **Step 1: Run the complete Maven suite**

Run:

```powershell
mvn test
```

Expected: every test passes with zero failures, zero errors, and zero skipped tests. Record the exact total from Maven output for the PR.

- [ ] **Step 2: Verify ordinary tests did not use real external services**

Run:

```powershell
rg -n -g "TEST-*.xml" `
  "Connect to 127\\.0\\.0\\.1:6041|storage_failed|Connection refused|iot-tdengine" `
  target/surefire-reports
```

Expected: no evidence that ordinary tests attempted to use the real Docker TDengine, MySQL, MQTT, or Redis services.

---

### Task 3: Prove the success cache-fallback path in the real Docker stack

**Files:**
- Verify only: `src/env/docker-compose.yml`
- Verify only: `docs/MQTT-硬件数据对接说明.md`

**Interfaces:**
- Consumes: existing MySQL on host port `13306`, Redis on host port `16379`, EMQX on `1883`, TDengine REST on `6041`, and the patched backend.
- Produces: real evidence that Redis misses return all four TDengine success rows.

- [ ] **Step 1: Confirm the four infrastructure containers are running**

Run:

```powershell
docker ps --format "table {{.Names}}`t{{.Status}}`t{{.Ports}}"
docker exec iot-mysql mysqladmin ping -h 127.0.0.1 -uroot -pchange-me
docker exec iot-redis redis-cli PING
docker exec iot-tdengine taos -s "SHOW DATABASES;"
docker exec iot-emqx emqx ctl status
```

Expected: all four containers are running; MySQL is alive; Redis returns `PONG`;
`iot_telemetry` exists; EMQX reports started.

- [ ] **Step 2: Restart the backend from the patched branch**

Stop only the Java process listening on port `8081`, then run:

```powershell
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
mvn spring-boot:run
```

Expected: backend starts on `127.0.0.1:8081`, refreshes MySQL point/indicator snapshots, connects to EMQX, and initializes TDengine without fatal errors.

- [ ] **Step 3: Force all latest indicators through TDengine fallback**

Run:

```powershell
docker exec iot-redis redis-cli DEL `
  "iot:indicator:latest:INDICATOR_WCR_COP_B1" `
  "iot:indicator:latest:INDICATOR_TOWER_EFF_B1" `
  "iot:indicator:latest:INDICATOR_PUMP_EFF_B1" `
  "iot:indicator:latest:INDICATOR_AHU_EFF_B1"
```

Expected: Redis deletes zero to four keys; the exact number does not affect the fallback assertion.

- [ ] **Step 4: Assert that the latest API returns four TDengine successes**

Run:

```powershell
$login = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8081/api/auth/login" `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"123456"}'
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$latest = Invoke-RestMethod -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/hvac/buildings/BLD001/indicators/latest"
$successes = @($latest.data.indicators |
  Where-Object status -eq "SUCCESS")
if ($latest.data.indicators.Count -ne 4 -or $successes.Count -ne 4) {
  throw "Expected four SUCCESS indicators from TDengine fallback"
}
$latest.data.indicators |
  Sort-Object indicatorCode |
  Format-Table indicatorCode,status,value,dataQuality,formulaVersion
```

Expected:

```text
AHU_POW_EFF SUCCESS 0.462962962962963 0 AHU_POW_EFF_V1
PUMP_EFF    SUCCESS 58.2777777777778  0 PUMP_EFF_V1
TOWER_EFF   SUCCESS 50.0              0 TOWER_EFF_V1
WCR_COP     SUCCESS 5.80555555555556  0 WCR_COP_V1
```

- [ ] **Step 5: Recheck history and calculation details**

Use the WCR minute returned by the latest response:

```powershell
$minuteStart = ($latest.data.indicators |
  Where-Object indicatorCode -eq "WCR_COP").minuteStart
$minuteEnd = $minuteStart + 60000
$history = Invoke-RestMethod -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/hvac/indicators/INDICATOR_WCR_COP_B1/history?from=$minuteStart&to=$minuteEnd"
$detail = Invoke-RestMethod -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/hvac/indicators/INDICATOR_WCR_COP_B1/calculations/$minuteStart"
if ($history.data.records.Count -ne 1
    -or $detail.data.status -ne "SUCCESS"
    -or $detail.data.steps.Count -ne 3) {
  throw "History or calculation detail regression detected"
}
```

Expected: one WCR history row and a successful calculation detail with three steps.

---

### Task 4: Prove the exception fallback and WebSocket paths

**Files:**
- Verify only: `.scripts/simulate-hvac-19-points.mjs`
- Verify only: `docs/MQTT-硬件数据对接说明.md`

**Interfaces:**
- Consumes: the patched backend and existing Docker services from Task 3.
- Produces: evidence that the latest exception query returns the missing pump state without affecting the other formulas, and that WebSocket still publishes the same state.

- [ ] **Step 1: Start a bounded WebSocket listener**

In a separate terminal, run:

```powershell
node -e "const ws=new WebSocket('ws://127.0.0.1:8081/api/ws/dashboard');const timeout=setTimeout(()=>{console.error('WEBSOCKET_TIMEOUT');process.exit(1)},240000);ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.type==='HVAC_INDICATOR'&&m.data?.indicatorCode==='PUMP_EFF'&&m.data?.status==='MISSING_INPUT'){console.log(e.data);clearTimeout(timeout);ws.close();process.exit(0)}};ws.onerror=e=>{console.error(e);clearTimeout(timeout);process.exit(1)}"
```

Expected: the listener remains connected and exits successfully only after receiving the missing pump state.

- [ ] **Step 2: Publish an isolated minute without pump power**

In another terminal, run:

```powershell
Start-Sleep -Seconds (61 - (Get-Date).Second)
$env:HVAC_OMIT_POINT = "PUMP1_Power"
try {
  node .scripts/simulate-hvac-19-points.mjs
} finally {
  Remove-Item Env:HVAC_OMIT_POINT -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 90
```

Expected: the simulator publishes 18 points per round, the minute freezes, and the WebSocket listener prints a `PUMP_EFF/MISSING_INPUT` message before its timeout.

- [ ] **Step 3: Verify the persisted exception and successful sibling formulas**

Run:

```powershell
docker exec iot-tdengine taos -s "SELECT indicator_code,ts,calc_status,reason_code,missing_inputs,formula_version FROM iot_telemetry.st_formula_calc_exception WHERE indicator_code='PUMP_EFF' ORDER BY ts DESC LIMIT 1;"
docker exec iot-tdengine taos -s "SELECT indicator_code,ts,val,data_quality,formula_version FROM iot_telemetry.st_indicator_minute ORDER BY ts DESC LIMIT 4;"
```

Expected:

- pump exception status is `MISSING_INPUT`;
- reason is `PUMP_INPUT_MISSING`;
- `missing_inputs` contains `Pc/PPE`;
- the same minute has no successful `PUMP_EFF` row;
- WCR, tower, and AHU succeed for that minute.

- [ ] **Step 4: Force the exception through TDengine fallback**

Run:

```powershell
docker exec iot-redis redis-cli DEL `
  "iot:indicator:latest:INDICATOR_WCR_COP_B1" `
  "iot:indicator:latest:INDICATOR_TOWER_EFF_B1" `
  "iot:indicator:latest:INDICATOR_PUMP_EFF_B1" `
  "iot:indicator:latest:INDICATOR_AHU_EFF_B1"
$latestAfterFailure = Invoke-RestMethod -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/hvac/buildings/BLD001/indicators/latest"
$pump = $latestAfterFailure.data.indicators |
  Where-Object indicatorCode -eq "PUMP_EFF"
if ($pump.status -ne "MISSING_INPUT"
    -or $pump.value -ne $null
    -or $pump.missingInputs -notcontains "Pc/PPE") {
  throw "Expected persisted pump exception from TDengine fallback"
}
if (@($latestAfterFailure.data.indicators |
    Where-Object {
      $_.indicatorCode -ne "PUMP_EFF" -and $_.status -ne "SUCCESS"
    }).Count -ne 0) {
  throw "A sibling formula did not remain successful"
}
```

Expected: water pump is `MISSING_INPUT` with `value=null` and `Pc/PPE`; the other three indicators are `SUCCESS`.

---

### Task 5: Final review, push, and PR handoff

**Files:**
- Verify: all files changed on `fix/hvac-latest-indicator-query`

**Interfaces:**
- Consumes: commits and verification evidence from Tasks 1–4.
- Produces: a clean remote task branch and complete user-owned PR materials.

- [ ] **Step 1: Review branch scope and unrelated files**

Run:

```powershell
git status --short --branch
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD
git log --oneline origin/main..HEAD
```

Expected committed files:

```text
docs/superpowers/specs/2026-07-28-hvac-latest-indicator-query-design.md
docs/superpowers/plans/2026-07-28-hvac-latest-indicator-query.md
src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java
src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java
```

Expected unrelated state: only the pre-existing untracked `outputs/` directory, which remains uncommitted.

- [ ] **Step 2: Push the task branch**

Run:

```powershell
git push -u origin fix/hvac-latest-indicator-query
```

Expected: remote branch is created and tracks `origin/fix/hvac-latest-indicator-query`.

- [ ] **Step 3: Deliver PR materials**

Provide:

- Compare link:
  `https://github.com/edg127117/iot-platform-demo/compare/main...fix/hvac-latest-indicator-query?expand=1`
- Base: `main`
- Compare: `fix/hvac-latest-indicator-query`
- Suggested title: `fix(hvac): 修复最新指标 TDengine 回退查询`
- exact focused/full test counts;
- Docker success fallback, exception fallback, and WebSocket results;
- explicit confirmation that no schema, formula, image version, port configuration, or `outputs/` file is included;
- conflict status;
- status: waiting for the user to create and merge the PR.
