# HVAC Unified Real History Trends Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one production-ready HVAC history module that shows four energy indicator trends and lets users compare 1–8 of the 19 frozen raw points for up to 31 days without weakening exact formula audit APIs.

**Architecture:** Keep the existing exact single-indicator history endpoint unchanged and add one building-scoped, batch indicator-trend endpoint with server-side 1/5/30-minute resolution. Adapt indicator and raw-point responses into one frontend trend model, render one ECharts panel per engineering unit, and isolate requests, building changes, persistence, gaps, and errors in a dedicated composable and history panel.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, TDengine 3.2.3 SQL, Vue 3, TypeScript, Vitest, Vue Test Utils, ECharts 5.6, Ant Design Vue.

## Global Constraints

- Work only on `feature/hvac-history-trends` in a clean isolated worktree; never develop or commit directly on `main`.
- Preserve `GET /api/hvac/indicators/{indicatorId}/history` as exact minute audit history; do not add aggregation or change its DTO.
- Add chart data through `GET /api/hvac/buildings/{buildingId}/indicators/trends` with 1–4 indicator IDs.
- Both trend modes use `[from,to)`, maximum 31 days, and automatic resolution: `<=24h → 1m`, `<=7d → 5m`, otherwise `30m`.
- Raw-point mode selects only the current building's 19 frozen points and sends at most 8 IDs.
- Trend aggregation uses average, minimum, maximum, successful sample count, and worst data quality; formula version is absent from aggregated trend records.
- Missing or failed minutes remain gaps; never fill, interpolate, connect, or convert them to zero in the browser.
- Keep history outside the existing 30-second latest-data polling; query on initialization, condition change, or manual refresh only.
- Use the existing `echarts` dependency through modular imports; add no chart library or backend table.
- Keep MySQL, TDengine, Redis, MQTT, and ordinary automated tests isolated according to current test profiles.
- Every new or changed production class, method, Vue component, and function must receive an accurate Chinese business-purpose comment when its role, data source, error semantics, or side effects are not obvious.
- Do not implement WebSocket, exports, audit tables/pages, alerts, prediction, favorites, backend management pages, or broad visual redesign in this branch.

---

## File Map

### Backend production

- Create `src/main/java/com/platform/iot/temporal/model/IndicatorTrendQueryRow.java` — TDengine trend row without formula-audit semantics.
- Modify `src/main/java/com/platform/iot/temporal/IndicatorMinuteRepository.java` — expose batch `findTrends`.
- Modify `src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java` — exact 1-minute and aggregated 5/30-minute SQL.
- Modify `src/main/java/com/platform/hvac/model/dto/HvacIndicatorDtos.java` — chart-specific response, series, and record DTOs.
- Modify `src/main/java/com/platform/hvac/service/HvacIndicatorQueryService.java` — authorization, ID/range validation, resolution, stable assembly.
- Modify `src/main/java/com/platform/hvac/controller/HvacIndicatorController.java` — new secured GET endpoint.

### Backend tests

- Modify `src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java`.
- Modify `src/test/java/com/platform/hvac/service/HvacIndicatorQueryServiceTest.java`.
- Modify `src/test/java/com/platform/HvacIndicatorControllerFlowTest.java`.

### Frontend production

- Modify `web/src/types/hvac.ts` — both history API contracts.
- Modify `web/src/api/hvac.ts` — indicator-trend and point-history clients.
- Create `web/src/domain/hvacHistoryTrends.ts` — range, selection, gap, quality, and unit adapters.
- Create `web/src/composables/useHvacHistoryTrends.ts` — request and persistence lifecycle.
- Create `web/src/components/hvac/HvacTrendChart.vue` — pure same-unit ECharts renderer.
- Create `web/src/components/hvac/HvacHistoryPanel.vue` — controls, states, and grouped charts.
- Modify `web/src/pages/HvacDemoPage.vue` — replace placeholder and provide current building data.

### Frontend tests and project state

- Modify `web/src/api/hvac.test.ts`.
- Create `web/src/domain/hvacHistoryTrends.test.ts`.
- Create `web/src/composables/useHvacHistoryTrends.test.ts`.
- Create `web/src/components/hvac/HvacTrendChart.test.ts`.
- Create `web/src/components/hvac/HvacHistoryPanel.test.ts`.
- Modify `web/src/pages/HvacDemoPage.contract.test.ts`.
- Modify `PROJECT_STATUS.md` only after the complete feature passes verification.

---

### Task 0: Enter the Prepared Worktree and Revalidate the Baseline

**Files:** None.

**Interfaces:**
- Consumes: local branch `feature/hvac-history-trends` containing the confirmed spec and this plan.
- Produces: clean worktree with guardrails and dependencies ready.

- [ ] **Step 1: Create or open the isolated worktree on the prepared branch**

If using the Codex worktree UI, select `feature/hvac-history-trends`. If creating manually after the main worktree has released the branch:

```powershell
git worktree add D:\word\iot-platform-demo-history feature/hvac-history-trends
Set-Location D:\word\iot-platform-demo-history
```

Expected: the new worktree checks out `feature/hvac-history-trends`; do not create a second feature branch.

- [ ] **Step 2: Verify branch, cleanliness, design, and plan**

```powershell
git status --short --branch
git branch --show-current
Test-Path docs/superpowers/specs/2026-08-05-hvac-unified-history-trends-design.md
Test-Path docs/superpowers/plans/2026-08-06-hvac-unified-history-trends.md
```

Expected: clean branch `feature/hvac-history-trends`; both paths return `True`.

- [ ] **Step 3: Install repository guardrails and run task preflight**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Install-GitGuardrails.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

Expected: `GIT_GUARDRAILS_INSTALLED` and `TASK_PREFLIGHT_OK`.

- [ ] **Step 4: Install frontend dependencies locally if absent**

```powershell
Set-Location web
npm ci --prefer-offline --no-audit --no-fund
Set-Location ..
```

Expected: local `web/node_modules` exists. Do not create a junction to another worktree.

---

### Task 1: Add the Batch Indicator Trend Repository Contract

**Files:**
- Create: `src/main/java/com/platform/iot/temporal/model/IndicatorTrendQueryRow.java`
- Modify: `src/main/java/com/platform/iot/temporal/IndicatorMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java`
- Test: `src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java`

**Interfaces:**
- Consumes: TDengine `st_indicator_minute` successful rows.
- Produces: `findTrends(List<String>, long, long, int)` returning identity-safe `IndicatorTrendQueryRow` values.

- [ ] **Step 1: Write failing repository SQL tests**

Add tests that capture the SQL without needing a live database:

```java
@Test
void oneMinuteTrendsReturnExactSuccessfulRowsForAllRequestedIndicators() {
    when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of());

    repository.findTrends(
            List.of("INDICATOR_B", "INDICATOR_A"),
            MINUTE, MINUTE + 3_600_000L, 1);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(template).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class));
    assertThat(sql.getValue())
            .contains("indicator_id IN ('INDICATOR_B','INDICATOR_A')")
            .contains("ts AS bucket_time", "val AS average_value")
            .contains("val AS minimum_value", "val AS maximum_value")
            .contains("1 AS sample_count", "data_quality")
            .doesNotContain("INTERVAL(")
            .contains("ORDER BY indicator_id,ts");
}

@Test
void fiveMinuteTrendsAggregatePerIndicatorAndKeepWorstQuality() {
    when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of());

    repository.findTrends(
            List.of("INDICATOR_A", "INDICATOR_B"),
            MINUTE, MINUTE + 86_400_001L, 5);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(template).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class));
    assertThat(sql.getValue())
            .contains("AVG(val) AS average_value")
            .contains("MIN(val) AS minimum_value")
            .contains("MAX(val) AS maximum_value")
            .contains("COUNT(*) AS sample_count")
            .contains("MAX(data_quality) AS data_quality")
            .contains("PARTITION BY indicator_id,indicator_code,building_id,system_group_id,equip_id")
            .contains("INTERVAL(5m)")
            .contains("ORDER BY indicator_id,_wstart");
}

@Test
void trendsRejectUnsupportedResolutionAndSkipEmptyIds() {
    assertThat(repository.findTrends(List.of(), MINUTE, MINUTE + 60_000L, 1))
            .isEmpty();
    assertThatThrownBy(() -> repository.findTrends(
            List.of("INDICATOR_A"), MINUTE, MINUTE + 60_000L, 15))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1、5、30");
    verifyNoInteractions(template);
}
```

- [ ] **Step 2: Run the repository test and verify it fails for the missing method**

```powershell
.\mvnw.cmd -Dtest=TdengineIndicatorMinuteRepositoryTest test
```

Expected: compilation failure because `findTrends` and `IndicatorTrendQueryRow` do not exist.

- [ ] **Step 3: Add the chart-specific row model and repository interface**

Create the complete record:

```java
package com.platform.iot.temporal.model;

/**
 * TDengine 返回的一个指标趋势分钟或降采样窗口。
 *
 * <p>该模型只服务图表，不携带公式版本；5/30 分钟窗口不代表可以审计的精确公式分钟。</p>
 */
public record IndicatorTrendQueryRow(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long time,
        double average,
        double minimum,
        double maximum,
        long sampleCount,
        int dataQuality) {
}
```

Add to `IndicatorMinuteRepository`:

```java
/**
 * 批量查询指标图表趋势；1 分钟返回精确成功值，5/30 分钟返回 TDengine 窗口统计。
 */
List<IndicatorTrendQueryRow> findTrends(
        List<String> indicatorIds,
        long fromInclusive,
        long toExclusive,
        int resolutionMinutes);
```

- [ ] **Step 4: Implement exact and downsampled SQL**

Add `findTrends`, `rawTrendSql`, `downsampledTrendSql`, and `mapTrendRow` to `TdengineIndicatorMinuteRepository`:

```java
@Override
public List<IndicatorTrendQueryRow> findTrends(
        List<String> indicatorIds,
        long fromInclusive,
        long toExclusive,
        int resolutionMinutes) {
    if (indicatorIds.isEmpty()) {
        return List.of();
    }
    String interval = switch (resolutionMinutes) {
        case 1 -> null;
        case 5 -> "5m";
        case 30 -> "30m";
        default -> throw new IllegalArgumentException(
                "指标趋势分辨率只允许 1、5、30 分钟");
    };
    String sql = interval == null
            ? rawTrendSql(indicatorIds, fromInclusive, toExclusive)
            : downsampledTrendSql(
                    indicatorIds, fromInclusive, toExclusive, interval);
    return template.query(sql, this::mapTrendRow);
}

private String rawTrendSql(
        List<String> indicatorIds, long fromInclusive, long toExclusive) {
    return """
            SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,
                   ts AS bucket_time,
                   val AS average_value,
                   val AS minimum_value,
                   val AS maximum_value,
                   1 AS sample_count,
                   data_quality
            FROM %s
            WHERE indicator_id IN (%s) AND %s
            ORDER BY indicator_id,ts
            """.formatted(
            indicatorStable(), values(indicatorIds),
            timeRange(fromInclusive, toExclusive));
}

private String downsampledTrendSql(
        List<String> indicatorIds,
        long fromInclusive,
        long toExclusive,
        String interval) {
    return """
            SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,
                   _wstart AS bucket_time,
                   AVG(val) AS average_value,
                   MIN(val) AS minimum_value,
                   MAX(val) AS maximum_value,
                   COUNT(*) AS sample_count,
                   MAX(data_quality) AS data_quality
            FROM %s
            WHERE indicator_id IN (%s) AND %s
            PARTITION BY indicator_id,indicator_code,building_id,system_group_id,equip_id
            INTERVAL(%s)
            ORDER BY indicator_id,_wstart
            """.formatted(
            indicatorStable(), values(indicatorIds),
            timeRange(fromInclusive, toExclusive), interval);
}

private IndicatorTrendQueryRow mapTrendRow(
        ResultSet resultSet, int rowNumber) throws SQLException {
    return new IndicatorTrendQueryRow(
            resultSet.getString("indicator_id"),
            resultSet.getString("indicator_code"),
            resultSet.getString("building_id"),
            resultSet.getString("system_group_id"),
            resultSet.getString("equip_id"),
            resultSet.getTimestamp("bucket_time").getTime(),
            resultSet.getDouble("average_value"),
            resultSet.getDouble("minimum_value"),
            resultSet.getDouble("maximum_value"),
            resultSet.getLong("sample_count"),
            resultSet.getInt("data_quality"));
}
```

Reuse the repository's existing escaped `values`, `timeRange`, and stable helpers. Do not build raw ID SQL in the Service.

- [ ] **Step 5: Run repository tests**

```powershell
.\mvnw.cmd -Dtest=TdengineIndicatorMinuteRepositoryTest test
```

Expected: all repository tests pass.

- [ ] **Step 6: Commit the repository boundary**

```powershell
git add -- src/main/java/com/platform/iot/temporal/model/IndicatorTrendQueryRow.java src/main/java/com/platform/iot/temporal/IndicatorMinuteRepository.java src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java
git diff --cached --check
git commit -m "feat(hvac): add batch indicator trend repository"
```

---

### Task 2: Add Indicator Trend DTOs and Service Semantics

**Files:**
- Modify: `src/main/java/com/platform/hvac/model/dto/HvacIndicatorDtos.java`
- Modify: `src/main/java/com/platform/hvac/service/HvacIndicatorQueryService.java`
- Test: `src/test/java/com/platform/hvac/service/HvacIndicatorQueryServiceTest.java`

**Interfaces:**
- Consumes: `IndicatorMinuteRepository.findTrends(...)` from Task 1.
- Produces: `HvacIndicatorQueryService.trends(...)` and stable `TrendResponse` DTO.

- [ ] **Step 1: Write failing service tests for order, resolution, empty series, and validation**

Use the existing service mocks and helper indicators. Add tests equivalent to:

```java
@Test
void trendsKeepRequestedOrderAndChooseOneMinuteWithinTwentyFourHours() {
    BizIndicator cop = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
    BizIndicator pump = indicator("I2", "PUMP_EFF", "BLD001", "PUMP");
    when(configProvider.findAllActive()).thenReturn(List.of(pump, cop));
    allowBuilding("BLD001");
    when(indicatorRepository.findTrends(
            List.of("I2", "I1"), MINUTE, MINUTE + Duration.ofHours(24).toMillis(), 1))
            .thenReturn(List.of(
                    trend(pump, MINUTE, 58.0, 57.0, 59.0, 1, 0),
                    trend(cop, MINUTE, 5.8, 5.8, 5.8, 1, 1)));

    HvacIndicatorDtos.TrendResponse response = service.trends(
            "BLD001", " I2,I1,I2 ", MINUTE,
            MINUTE + Duration.ofHours(24).toMillis(), USER_ID, ADMIN);

    assertThat(response.resolutionMinutes()).isEqualTo(1);
    assertThat(response.series())
            .extracting(HvacIndicatorDtos.TrendSeries::indicatorId)
            .containsExactly("I2", "I1");
    assertThat(response.series().get(1).records().get(0).dataQuality())
            .isEqualTo(1);
}

@Test
void trendsUseFiveAndThirtyMinuteResolutionAtBoundaries() {
    BizIndicator cop = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
    when(configProvider.findAllActive()).thenReturn(List.of(cop));
    allowBuilding("BLD001");
    when(indicatorRepository.findTrends(anyList(), anyLong(), anyLong(), anyInt()))
            .thenReturn(List.of());

    assertThat(service.trends("BLD001", "I1", MINUTE,
            MINUTE + Duration.ofHours(24).toMillis() + 1, USER_ID, ADMIN)
            .resolutionMinutes()).isEqualTo(5);
    assertThat(service.trends("BLD001", "I1", MINUTE,
            MINUTE + Duration.ofDays(7).toMillis() + 1, USER_ID, ADMIN)
            .resolutionMinutes()).isEqualTo(30);
}

@Test
void trendsRejectEmptyTooManyInactiveAndCrossBuildingIndicatorsBeforeTdengine() {
    allowBuilding("BLD001");
    assertBadRequest(() -> service.trends(
            "BLD001", " ", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN));
    assertBadRequest(() -> service.trends(
            "BLD001", "I1,I2,I3,I4,I5", MINUTE,
            MINUTE + 60_000L, USER_ID, ADMIN));

    BizIndicator other = indicator("I2", "PUMP_EFF", "BLD002", "PUMP");
    when(configProvider.findAllActive()).thenReturn(List.of(other));
    assertBadRequest(() -> service.trends(
            "BLD001", "I2", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN));
    verifyNoInteractions(indicatorRepository);
}
```

Add a helper returning `IndicatorTrendQueryRow` with the indicator's complete identity.

- [ ] **Step 2: Run the service test and verify it fails**

```powershell
.\mvnw.cmd -Dtest=HvacIndicatorQueryServiceTest test
```

Expected: compilation failure for missing trend DTOs and `trends` method.

- [ ] **Step 3: Add chart DTOs**

Append complete records to `HvacIndicatorDtos`:

```java
/** 建筑内批量指标图表趋势，分辨率由服务端按跨度选择。 */
public record TrendResponse(
        String buildingId,
        long from,
        long to,
        int resolutionMinutes,
        List<TrendSeries> series) {
    public TrendResponse { series = List.copyOf(series); }
}

/** 一个指标实例的图表序列；合法无数据指标保留空记录。 */
public record TrendSeries(
        String indicatorId,
        String indicatorCode,
        String equipId,
        List<TrendRecord> records) {
    public TrendSeries { records = List.copyOf(records); }
}

/** 一个精确分钟或降采样窗口，不携带可能歧义的公式版本。 */
public record TrendRecord(
        long time,
        double average,
        double minimum,
        double maximum,
        long sampleCount,
        int dataQuality) {
}
```

- [ ] **Step 4: Implement validation, resolution, identity filtering, and response assembly**

Add constants and public method:

```java
private static final int MAX_TREND_INDICATORS = 4;

public HvacIndicatorDtos.TrendResponse trends(
        String buildingId,
        String rawIndicatorIds,
        Long from,
        Long to,
        Long userId,
        Set<String> roles) {
    checkBuildingAccess(buildingId, userId, roles);
    long span = validateHistoryRange(from, to);
    List<String> indicatorIds = parseTrendIndicatorIds(rawIndicatorIds);
    Map<String, BizIndicator> indicators = validateTrendIndicators(
            buildingId, indicatorIds);
    int resolutionMinutes = historyResolution(span);
    List<IndicatorTrendQueryRow> rows = queryTdengine(
            () -> indicatorRepository.findTrends(
                    indicatorIds, from, to, resolutionMinutes));
    Map<String, List<IndicatorTrendQueryRow>> rowsByIndicator = rows.stream()
            .filter(row -> trendIdentityMatches(
                    indicators.get(row.indicatorId()), row))
            .collect(java.util.stream.Collectors.groupingBy(
                    IndicatorTrendQueryRow::indicatorId));
    List<HvacIndicatorDtos.TrendSeries> series = indicatorIds.stream()
            .map(id -> trendSeries(
                    indicators.get(id),
                    rowsByIndicator.getOrDefault(id, List.of())))
            .toList();
    return new HvacIndicatorDtos.TrendResponse(
            buildingId, from, to, resolutionMinutes, series);
}
```

Change `validateHistoryRange` to return `span` while keeping existing exact history behavior:

```java
private long validateHistoryRange(Long from, Long to) {
    // keep all current null, order, overflow, and 31-day checks
    return span;
}
```

Implement helpers with these exact rules:

```java
private int historyResolution(long span) {
    if (span <= Duration.ofHours(24).toMillis()) return 1;
    if (span <= Duration.ofDays(7).toMillis()) return 5;
    return 30;
}

private List<String> parseTrendIndicatorIds(String rawIndicatorIds) {
    if (rawIndicatorIds == null) {
        throw new BusinessException(400, "indicatorIds 不能为空");
    }
    java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
    for (String candidate : rawIndicatorIds.split(",", -1)) {
        String id = candidate.trim();
        if (!id.isEmpty()) unique.add(id);
    }
    if (unique.isEmpty()) {
        throw new BusinessException(400, "indicatorIds 不能为空");
    }
    if (unique.size() > MAX_TREND_INDICATORS) {
        throw new BusinessException(400, "一次最多查询 4 个指标");
    }
    return List.copyOf(unique);
}
```

`validateTrendIndicators` must load `configProvider.findAllActive()` once, keep only status `1` and the path building, and reject any size mismatch with the single message `指标不存在、已停用或不属于目标建筑`. `trendIdentityMatches` must compare indicator ID, code, building, system group, and equipment. `trendSeries` must sort rows by `time` and map every numeric field without defaulting.

- [ ] **Step 5: Run the service tests**

```powershell
.\mvnw.cmd -Dtest=HvacIndicatorQueryServiceTest test
```

Expected: all service tests pass, including existing latest, exact history, and calculation detail tests.

- [ ] **Step 6: Commit DTO and Service behavior**

```powershell
git add -- src/main/java/com/platform/hvac/model/dto/HvacIndicatorDtos.java src/main/java/com/platform/hvac/service/HvacIndicatorQueryService.java src/test/java/com/platform/hvac/service/HvacIndicatorQueryServiceTest.java
git diff --cached --check
git commit -m "feat(hvac): add secured indicator trend query"
```

---

### Task 3: Expose and Secure the Indicator Trend Endpoint

**Files:**
- Modify: `src/main/java/com/platform/hvac/controller/HvacIndicatorController.java`
- Test: `src/test/java/com/platform/HvacIndicatorControllerFlowTest.java`

**Interfaces:**
- Consumes: `HvacIndicatorQueryService.trends(...)`.
- Produces: `GET /api/hvac/buildings/{buildingId}/indicators/trends`.

- [ ] **Step 1: Extend flow tests before the Controller**

Add the new endpoint to authentication, role, and building-scope tests. Add one success assertion:

```java
mockMvc.perform(apiGet(
                "/api/hvac/buildings/BLD001/indicators/trends")
                .header(auth(), bearer(token))
                .param("indicatorIds", INDICATOR_ID)
                .param("from", Long.toString(MINUTE))
                .param("to", Long.toString(MINUTE + 60_000L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.buildingId").value("BLD001"))
        .andExpect(jsonPath("$.data.resolutionMinutes").value(1))
        .andExpect(jsonPath("$.data.series[0].indicatorId")
                .value(INDICATOR_ID))
        .andExpect(jsonPath("$.data.series[0].records.length()").value(0));
```

Also assert missing `indicatorIds`, five IDs, and a range over 31 days return 400.

- [ ] **Step 2: Run the flow test and verify 404 for the missing route**

```powershell
.\mvnw.cmd -Dtest=HvacIndicatorControllerFlowTest test
```

Expected: the new endpoint assertions fail with 404 while existing routes remain green.

- [ ] **Step 3: Add the Controller method and update class-level endpoint count wording**

```java
/**
 * 批量查询建筑内 1 至 4 个指标的图表趋势。
 *
 * <p>Service 校验具体建筑范围并按跨度选择 1/5/30 分钟分辨率；该接口不替代
 * 单指标精确历史，聚合窗口也不返回公式版本。</p>
 */
@GetMapping("/buildings/{buildingId}/indicators/trends")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
public Result<HvacIndicatorDtos.TrendResponse> trends(
        @PathVariable String buildingId,
        @RequestParam(required = false) String indicatorIds,
        @RequestParam(required = false) Long from,
        @RequestParam(required = false) Long to,
        Authentication authentication) {
    return Result.success(queryService.trends(
            buildingId,
            indicatorIds,
            from,
            to,
            SecurityUser.userId(authentication),
            SecurityUser.roles(authentication)));
}
```

- [ ] **Step 4: Run backend feature tests**

```powershell
.\mvnw.cmd -Dtest=TdengineIndicatorMinuteRepositoryTest,HvacIndicatorQueryServiceTest,HvacIndicatorControllerFlowTest test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit the HTTP boundary**

```powershell
git add -- src/main/java/com/platform/hvac/controller/HvacIndicatorController.java src/test/java/com/platform/HvacIndicatorControllerFlowTest.java
git diff --cached --check
git commit -m "feat(hvac): expose indicator trend endpoint"
```

---

### Task 4: Add Frontend History DTOs and API Clients

**Files:**
- Modify: `web/src/types/hvac.ts`
- Modify: `web/src/api/hvac.ts`
- Test: `web/src/api/hvac.test.ts`

**Interfaces:**
- Consumes: both backend history endpoints.
- Produces: `getHvacIndicatorTrends` and `getHvacPointHistory`.

- [ ] **Step 1: Write failing API tests**

```ts
it('queries chart-ready indicator trends in one request', async () => {
  const payload = {
    buildingId: 'BLD001', from: 100, to: 200,
    resolutionMinutes: 1, series: [],
  }
  get.mockResolvedValue({
    data: { success: true, code: 200, msg: 'success', data: payload },
  })

  await expect(
    getHvacIndicatorTrends('BLD 001', ['I/1', 'I2'], 100, 200),
  ).resolves.toEqual(payload)
  expect(get).toHaveBeenCalledWith(
    '/hvac/buildings/BLD%20001/indicators/trends',
    { params: { indicatorIds: 'I/1,I2', from: 100, to: 200 } },
  )
})

it('queries up to eight raw point histories in one request', async () => {
  const payload = {
    buildingId: 'BLD001', from: 100, to: 200,
    resolutionMinutes: 1, series: [],
  }
  get.mockResolvedValue({
    data: { success: true, code: 200, msg: 'success', data: payload },
  })

  await expect(
    getHvacPointHistory('BLD001', ['P1', 'P2'], 100, 200),
  ).resolves.toEqual(payload)
  expect(get).toHaveBeenCalledWith('/hvac/buildings/BLD001/history', {
    params: { pointIds: 'P1,P2', from: 100, to: 200 },
  })
})
```

- [ ] **Step 2: Run the API test and verify missing exports**

```powershell
Set-Location web
npm run test:run -- src/api/hvac.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: compilation failure for missing types and functions.

- [ ] **Step 3: Add exact TypeScript contracts**

Add these types to `web/src/types/hvac.ts`:

```ts
export type TrendRecord = {
  time: number
  average: number
  minimum: number
  maximum: number
  sampleCount: number
  dataQuality: number
}

export type IndicatorTrendSeries = {
  indicatorId: string
  indicatorCode: string
  equipId: string | null
  records: TrendRecord[]
}

export type HvacIndicatorTrendResponse = {
  buildingId: string
  from: number
  to: number
  resolutionMinutes: 1 | 5 | 30 | number
  series: IndicatorTrendSeries[]
}

export type PointHistorySeries = {
  pointId: string
  pointCode: string
  pointName: string
  unit: string | null
  records: TrendRecord[]
}

export type HvacPointHistoryResponse = {
  buildingId: string
  from: number
  to: number
  resolutionMinutes: 1 | 5 | 30 | number
  series: PointHistorySeries[]
}
```

- [ ] **Step 4: Implement both API functions**

```ts
export async function getHvacIndicatorTrends(
  buildingId: string,
  indicatorIds: string[],
  from: number,
  to: number,
): Promise<HvacIndicatorTrendResponse> {
  const response = await http.get<ApiResult<HvacIndicatorTrendResponse>>(
    `/hvac/buildings/${encodeURIComponent(buildingId)}/indicators/trends`,
    { params: { indicatorIds: indicatorIds.join(','), from, to } },
  )
  return response.data.data
}

export async function getHvacPointHistory(
  buildingId: string,
  pointIds: string[],
  from: number,
  to: number,
): Promise<HvacPointHistoryResponse> {
  const response = await http.get<ApiResult<HvacPointHistoryResponse>>(
    `/hvac/buildings/${encodeURIComponent(buildingId)}/history`,
    { params: { pointIds: pointIds.join(','), from, to } },
  )
  return response.data.data
}
```

Add Chinese comments that identify MySQL scope validation and TDengine as the source. Do not prepend `/api`.

- [ ] **Step 5: Run API tests and commit**

```powershell
npm run test:run -- src/api/hvac.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
Set-Location ..
git add -- web/src/types/hvac.ts web/src/api/hvac.ts web/src/api/hvac.test.ts
git diff --cached --check
git commit -m "feat(web): add HVAC history API clients"
```

Expected: API tests pass and only the three listed files are committed.

---

### Task 5: Build the Shared History Domain Adapter

**Files:**
- Create: `web/src/domain/hvacHistoryTrends.ts`
- Create: `web/src/domain/hvacHistoryTrends.test.ts`

**Interfaces:**
- Consumes: `HvacIndicatorTrendResponse`, `HvacPointHistoryResponse`, snapshot points, `INDICATOR_DEFINITIONS`, and `FROZEN_POINT_DEFINITIONS`.
- Produces: stable options, ranges, grouped series, and explicit null gap separators.

- [ ] **Step 1: Write failing domain tests**

Cover these exact behaviors:

```ts
it('groups indicators by unit and keeps percent series together', () => {
  const groups = adaptIndicatorTrends(indicatorResponse)
  expect(groups.map((group) => group.unit)).toEqual(['', '%', 'W/(m³·h)'])
  expect(groups[1].series.map((series) => series.code)).toEqual([
    'TOWER_EFF', 'PUMP_EFF',
  ])
})

it('inserts a null separator when a successful bucket is missing', () => {
  const response = indicatorResponseWithTimes([0, 60_000, 180_000])
  const points = adaptIndicatorTrends(response)[0].series[0].points
  expect(points.map((point) => [point.time, point.average])).toEqual([
    [0, 5.0], [60_000, 5.1], [120_000, null], [180_000, 5.3],
  ])
})

it('builds only frozen point options and preserves backend ids', () => {
  const options = buildHistoryPointOptions(snapshot.points)
  expect(options).toContainEqual(expect.objectContaining({
    pointId: 'POINT001', pointCode: 'WCR1_TWin', label: '冷冻水进水温度',
  }))
  expect(options.some((option) => option.pointCode === 'EXTRA_POINT')).toBe(false)
})

it('rejects invalid custom ranges before the API', () => {
  expect(validateHistoryRange(null, 100)).toBe('请选择完整的开始和结束时间')
  expect(validateHistoryRange(200, 100)).toBe('开始时间必须早于结束时间')
  expect(validateHistoryRange(0, 31 * 86_400_000 + 1))
    .toBe('查询跨度不能超过31天')
})
```

Also test quality labels 0/1/2/unknown, precision, stable response order, and no frontend averaging.

- [ ] **Step 2: Run and verify the new test fails**

```powershell
Set-Location web
npm run test:run -- src/domain/hvacHistoryTrends.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: module-not-found failure.

- [ ] **Step 3: Implement the complete domain contracts and pure functions**

Start the module with:

```ts
export type HvacHistoryMode = 'indicators' | 'points'
export type HvacHistoryPreset = '1h' | '6h' | '24h' | '7d' | 'custom'

export type HvacHistoryPointOption = {
  pointId: string
  pointCode: string
  label: string
  unit: string
  precision: number
}

export type HvacTrendPoint = {
  time: number
  average: number | null
  minimum: number | null
  maximum: number | null
  sampleCount: number
  dataQuality: number | null
}

export type HvacTrendSeries = {
  id: string
  code: string
  label: string
  unit: string
  precision: number
  points: HvacTrendPoint[]
}

export type HvacTrendGroup = {
  unit: string
  series: HvacTrendSeries[]
}
```

Implement and export:

```ts
export function presetRange(
  preset: Exclude<HvacHistoryPreset, 'custom'>,
  now = Date.now(),
): { from: number; to: number }

export function validateHistoryRange(
  from: number | null,
  to: number | null,
): string | null

export function buildHistoryPointOptions(
  points: SnapshotPoint[],
): HvacHistoryPointOption[]

export function adaptIndicatorTrends(
  response: HvacIndicatorTrendResponse,
): HvacTrendGroup[]

export function adaptPointTrends(
  response: HvacPointHistoryResponse,
  options: HvacHistoryPointOption[],
): HvacTrendGroup[]

export function historyQualityLabel(quality: number | null): string
```

`presetRange` floors `to` to the current minute and subtracts exactly 1, 6, 24 hours or 7 days. Gap insertion compares consecutive record times against `resolutionMinutes * 60_000`; insert one null point at `previous.time + interval` when the difference is larger. Do not create values for leading/trailing empty time.

- [ ] **Step 4: Run domain tests and commit**

```powershell
npm run test:run -- src/domain/hvacHistoryTrends.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
Set-Location ..
git add -- web/src/domain/hvacHistoryTrends.ts web/src/domain/hvacHistoryTrends.test.ts
git diff --cached --check
git commit -m "feat(web): add HVAC history domain adapter"
```

Expected: all domain tests pass.

---

### Task 6: Implement History Request State and Per-Building Selection Memory

**Files:**
- Create: `web/src/composables/useHvacHistoryTrends.ts`
- Create: `web/src/composables/useHvacHistoryTrends.test.ts`

**Interfaces:**
- Consumes: Task 4 API clients and Task 5 adapters.
- Produces: one state machine used by `HvacHistoryPanel`.

- [ ] **Step 1: Write failing composable tests with injected dependencies**

Test:

- default mode `indicators`, preset `24h`, and automatic load when a building plus indicator IDs arrives;
- point mode sends no request with no selection;
- selecting 1–8 points persists `hvac:history-point-ids:{buildingId}` and requests once;
- restore filters invalid IDs and keeps at most 8;
- building switch clears old response immediately and restores the new building only;
- range/mode/selection change invalidates old response;
- a slow old Promise cannot overwrite a newer request;
- same-condition manual refresh failure keeps old data and marks it stale;
- changed-condition failure does not keep mismatched data;
- `dispose()` prevents a late response from writing.

Use controlled Promises for race tests, never sleeps.

- [ ] **Step 2: Run and verify module-not-found failure**

```powershell
Set-Location web
npm run test:run -- src/composables/useHvacHistoryTrends.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

- [ ] **Step 3: Implement dependency and context interfaces**

```ts
export type HvacHistoryDependencies = {
  getIndicatorTrends: typeof getHvacIndicatorTrends
  getPointHistory: typeof getHvacPointHistory
  now: () => number
}

export type HvacHistoryBuildingContext = {
  buildingId: string
  indicatorIds: string[]
  points: SnapshotPoint[]
}

export type HvacHistoryError = {
  status: number | null
  message: string
}
```

The composable must expose:

```ts
{
  mode, preset, customFrom, customTo,
  pointOptions, selectedPointIds,
  groups, responseRange, resolutionMinutes,
  loading, error, stale, updatedAt,
  setBuildingContext, setMode, setPreset,
  setCustomRange, setSelectedPointIds,
  refresh, dispose,
}
```

- [ ] **Step 4: Implement one request-version state machine**

Use this invariant:

```ts
let requestVersion = 0
let activeConditionKey: string | null = null

async function runQuery(manual = false): Promise<void> {
  const request = buildCurrentRequest()
  if (!request) return
  const conditionKey = JSON.stringify(request)
  const version = ++requestVersion
  loading.value = true
  error.value = null
  if (!manual || activeConditionKey !== conditionKey) {
    groups.value = []
    stale.value = false
  }
  try {
    const response = request.mode === 'indicators'
      ? await dependencies.getIndicatorTrends(
          request.buildingId, request.ids, request.from, request.to)
      : await dependencies.getPointHistory(
          request.buildingId, request.ids, request.from, request.to)
    if (version !== requestVersion) return
    groups.value = request.mode === 'indicators'
      ? adaptIndicatorTrends(response)
      : adaptPointTrends(response, pointOptions.value)
    responseRange.value = { from: response.from, to: response.to }
    resolutionMinutes.value = response.resolutionMinutes
    activeConditionKey = conditionKey
    stale.value = false
    updatedAt.value = dependencies.now()
  } catch (cause) {
    if (version !== requestVersion) return
    error.value = mapHistoryError(cause)
    stale.value = manual && activeConditionKey === conditionKey
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
```

`setBuildingContext` must increment `requestVersion`, clear all old response state, validate stored point IDs against new options, and auto-run only when mode is indicators with at least one ID or point mode has restored IDs. `setSelectedPointIds` must filter to available IDs, preserve order, cap at 8, write only the current building key, then query.

- [ ] **Step 5: Run composable tests and commit**

```powershell
npm run test:run -- src/composables/useHvacHistoryTrends.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
Set-Location ..
git add -- web/src/composables/useHvacHistoryTrends.ts web/src/composables/useHvacHistoryTrends.test.ts
git diff --cached --check
git commit -m "feat(web): add HVAC history state lifecycle"
```

---

### Task 7: Build the Pure ECharts Trend Renderer

**Files:**
- Create: `web/src/components/hvac/HvacTrendChart.vue`
- Create: `web/src/components/hvac/HvacTrendChart.test.ts`

**Interfaces:**
- Consumes: one `HvacTrendGroup`, range, and resolution.
- Produces: responsive chart with quality-aware tooltip and no requests or storage.

- [ ] **Step 1: Write failing renderer lifecycle tests**

Mock `echarts/core` and `ResizeObserver`. Assert:

- `init` runs once on mount;
- `setOption` contains one line per series and `connectNulls: false`;
- null adapter points remain null;
- Q1/Q2 points receive visible symbols while Q0 uses no ordinary symbol;
- tooltip formatter includes average/min/max/sample count/quality;
- prop updates call `setOption` without reinitializing;
- ResizeObserver calls `resize`;
- unmount disconnects observer and calls `dispose`.

- [ ] **Step 2: Run and verify component-not-found failure**

```powershell
Set-Location web
npm run test:run -- src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

- [ ] **Step 3: Implement modular ECharts registration and lifecycle**

Use:

```ts
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

echarts.use([
  LineChart, GridComponent, TooltipComponent,
  LegendComponent, DataZoomComponent, CanvasRenderer,
])
```

Props:

```ts
const props = defineProps<{
  group: HvacTrendGroup
  from: number
  to: number
  resolutionMinutes: number
}>()
```

The template must include a visible group title (`无量纲` for empty unit), a textual series summary, and the chart container. Build time-series data as `[time, average]`; keep null values. Set `xAxis.min/max` from props, `animationDuration` to a short bounded value, and media/reduced-motion behavior without continuous animation.

- [ ] **Step 4: Run renderer tests and commit**

```powershell
npm run test:run -- src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
Set-Location ..
git add -- web/src/components/hvac/HvacTrendChart.vue web/src/components/hvac/HvacTrendChart.test.ts
git diff --cached --check
git commit -m "feat(web): add quality-aware HVAC trend chart"
```

---

### Task 8: Build the Unified History Panel

**Files:**
- Create: `web/src/components/hvac/HvacHistoryPanel.vue`
- Create: `web/src/components/hvac/HvacHistoryPanel.test.ts`

**Interfaces:**
- Consumes: `buildingId`, dashboard indicator views, and snapshot points.
- Produces: complete indicator/raw-point controls and grouped charts.

- [ ] **Step 1: Write failing panel behavior tests**

Mount with stubbed `HvacTrendChart` and mocked composable. Assert:

- mode labels are “能效指标” and “原始测点”;
- default copy describes recent 24 hours and automatic resolution;
- point mode shows multi-select, `已选 X/8`, and no-selection prompt;
- selecting the ninth point is prevented by `maxTagCount` plus state filtering;
- custom range validation is visible before calling the composable;
- loading skeleton, complete empty, partial empty, stale warning, 403, 503, and retry are distinguishable;
- one chart component is rendered per unit group;
- mode/range/select/refresh events call only the matching state functions;
- no fake values, `Math.random`, or local averaging appears.

- [ ] **Step 2: Run and verify component-not-found failure**

```powershell
Set-Location web
npm run test:run -- src/components/hvac/HvacHistoryPanel.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

- [ ] **Step 3: Implement panel props and context synchronization**

```ts
const props = defineProps<{
  buildingId: string | null
  indicators: DashboardIndicatorView[]
  snapshotPoints: SnapshotPoint[]
}>()
```

Build stable signatures:

```ts
const indicatorIds = computed(() =>
  props.indicators
    .map((item) => item.indicatorId)
    .filter((id): id is string => Boolean(id)),
)

const contextKey = computed(() =>
  `${props.buildingId ?? ''}|${indicatorIds.value.join(',')}|${props.snapshotPoints
    .map((point) => point.pointId)
    .join(',')}`,
)
```

Watch `contextKey`, but call `setBuildingContext` only when the building changes or the first complete IDs for that building become available. The existing 30-second polling returns new objects with the same IDs and must not refresh history.

- [ ] **Step 4: Implement the approved controls and states**

Use Ant Design Vue segmented/tabs, buttons, select, date range picker, alerts, skeleton, and empty state already in the dependency set. The panel must:

- default to indicators + 24h;
- expose 1h/6h/24h/7d/custom;
- show point selector only in point mode;
- disable point query when no points are selected;
- show response resolution and exact `[from,to)` summary;
- render one `HvacTrendChart` per unit group;
- list empty series names when only part of the request has data;
- retain stale same-condition charts behind a warning on refresh failure;
- call `dispose()` on unmount.

Keep all business fetching and storage in the composable. The panel formats controls and status text only.

- [ ] **Step 5: Run panel tests and commit**

```powershell
npm run test:run -- src/components/hvac/HvacHistoryPanel.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
Set-Location ..
git add -- web/src/components/hvac/HvacHistoryPanel.vue web/src/components/hvac/HvacHistoryPanel.test.ts
git diff --cached --check
git commit -m "feat(web): add unified HVAC history panel"
```

---

### Task 9: Integrate the History Panel into the Dashboard

**Files:**
- Modify: `web/src/pages/HvacDemoPage.vue`
- Modify: `web/src/pages/HvacDemoPage.contract.test.ts`

**Interfaces:**
- Consumes: `HvacHistoryPanel` and existing dashboard refs.
- Produces: real history UI in place of the placeholder without disturbing latest polling or calculation details.

- [ ] **Step 1: Update contract tests first**

Replace the historical placeholder assertions with:

```ts
it('connects unified real history without adding browser-side fake data', () => {
  expect(source).toContain('HvacHistoryPanel')
  expect(source).toContain(':building-id="selectedBuildingId"')
  expect(source).toContain(':indicators="indicatorViews"')
  expect(source).toContain(':snapshot-points="snapshot?.points ?? []"')
  expect(source).not.toContain('历史曲线将在下一迭代接入')
  expect(source).not.toContain('Math.random')
  expect(source).not.toContain('formulaMap')
})
```

Keep existing calculation-detail, stale snapshot, building change, and fake-data boundary tests.

- [ ] **Step 2: Run and verify the contract fails**

```powershell
Set-Location web
npm run test:run -- src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: failure because the placeholder still exists.

- [ ] **Step 3: Integrate the component with minimal page responsibility**

Destructure `snapshot` from `useHvacDashboard`, import `HvacHistoryPanel`, and replace only the deferred trend body:

```vue
<article class="panel trend-panel">
  <HvacHistoryPanel
    :building-id="selectedBuildingId"
    :indicators="indicatorViews"
    :snapshot-points="snapshot?.points ?? []"
  />
</article>
```

Update the page-level comment to say real trends are provided by `HvacHistoryPanel`. Remove the unused `TrendingUp` import and `.deferred-panel` styles only if no other page section uses them. Keep `bottom-grid`, point list, latest polling, calculation detail close behavior, and all unrelated visual sections.

- [ ] **Step 4: Run all frontend feature tests**

```powershell
npm run test:run -- src/api/hvac.test.ts src/domain/hvacHistoryTrends.test.ts src/composables/useHvacHistoryTrends.test.ts src/components/hvac/HvacTrendChart.test.ts src/components/hvac/HvacHistoryPanel.test.ts src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: all selected frontend tests pass.

- [ ] **Step 5: Commit dashboard integration**

```powershell
Set-Location ..
git add -- web/src/pages/HvacDemoPage.vue web/src/pages/HvacDemoPage.contract.test.ts
git diff --cached --check
git commit -m "feat(web): connect real HVAC history trends"
```

---

### Task 10: Verify Real TDengine Aggregation and Browser Behavior

**Files:**
- Modify tests only if actual TDengine 3.2.3 exposes a documented compatibility gap.
- Do not change production semantics merely to make a local fixture pass.

**Interfaces:**
- Consumes: full backend and frontend feature.
- Produces: real evidence for SQL compatibility, response volume, and user flow.

- [ ] **Step 1: Run all automated checks before Docker**

```powershell
.\mvnw.cmd test
Set-Location web
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run lint
npm run build
Set-Location ..
```

Expected: every command passes. Record actual test counts and skips; do not copy counts from old PRs.

- [ ] **Step 2: Start the repository's existing Docker environment safely**

Inspect first:

```powershell
docker compose -f src/env/docker-compose.yml ps
```

Use the existing repository start workflow and environment values. Do not delete volumes or reset data unless the user explicitly authorizes a clean-volume test for this task.

- [ ] **Step 3: Seed or publish enough timestamped test data for all resolutions**

Use the repository-supported simulator/test path or explicit TDengine test inserts in a disposable test scope. Ensure evidence includes:

- at least two indicators in the same 5-minute window;
- min, max, average, Q0/Q1/Q2, and a missing/failed minute;
- rows on `[from,to)` boundaries;
- enough date separation to trigger 1, 5, and 30-minute resolution without fabricating frontend data.

- [ ] **Step 4: Verify the API contracts directly**

After login, call:

```text
GET /api/hvac/buildings/BLD001/indicators/trends?indicatorIds=INDICATOR_WCR_COP_B1,INDICATOR_PUMP_EFF_B1&from=...&to=...
GET /api/hvac/buildings/BLD001/history?pointIds=...&from=...&to=...
GET /api/hvac/indicators/INDICATOR_WCR_COP_B1/history?from=...&to=...
```

Verify:

- trend resolutions are 1/5/30 at the designed boundaries;
- aggregate average/min/max/sampleCount/worst quality match SQL evidence;
- failures do not become zero/success;
- exact history still returns minute + formulaVersion unchanged;
- unauthorized building returns 403 and TDengine errors are sanitized 503;
- one batch query returns all requested indicators without N+1 behavior.

- [ ] **Step 5: Verify the browser flow**

In `/hvac-demo`:

1. default mode is “能效指标”, range is 24h, and all available four indicators load;
2. COP, percent efficiencies, and AHU energy value render in separate unit groups;
3. data gaps are visibly broken and Q1/Q2 are distinguishable in tooltip/markers;
4. 7-day and 31-day ranges report 5m and 30m resolution;
5. raw-point mode initially prompts selection without a request;
6. select 1 point, then 8; a ninth cannot enter the request;
7. switch units and confirm charts group correctly;
8. switch buildings and confirm no old chart flashes;
9. return to a building and confirm valid point selection restores;
10. simulate refresh failure and confirm only matching old data is marked stale;
11. latest 30-second polling and calculation drawer continue working;
12. console has no Vue, ECharts, ResizeObserver, or unhandled Promise errors.

- [ ] **Step 6: Fix only evidenced compatibility defects and rerun the bounded checks**

For any fix, first add a failing automated test reproducing the actual issue, make the minimal change, then rerun its directed test and the complete commands from Step 1. Do not start unrelated refactoring.

---

### Task 11: Update Project Status, Audit Comments, and Prepare the PR

**Files:**
- Modify: `PROJECT_STATUS.md`
- Modify: `docs/superpowers/README.md` to pair this design and plan if the task directory is updated in the current branch.
- Inspect: every changed production Java, TypeScript, and Vue file.

**Interfaces:**
- Consumes: verified implementation and actual test output.
- Produces: accurate repository state, clean commits, pushed branch, and complete PR material.

- [ ] **Step 1: Update current project status only after verification**

Move history trends into completed facts:

- four indicators use the new batch trend endpoint with 1/5/30-minute resolution;
- raw-point mode selects 1–8 frozen points;
- frontend groups units, preserves gaps/quality, and remembers selections per building.

Remove only the completed history item from “尚未完成”, technical debt, and next priority. Keep WebSocket, minimal admin pages, and real-site long-running acceptance unresolved.

- [ ] **Step 2: Add the design/plan pair to the task directory**

Add one row to `docs/superpowers/README.md`:

```markdown
| HVAC 统一真实历史趋势 | [设计](specs/2026-08-05-hvac-unified-history-trends-design.md) | [计划](plans/2026-08-06-hvac-unified-history-trends.md) | 成对历史记录 | 当前接口、分辨率和页面行为以 Controller、Service、Repository、前端历史模块和测试为准。 |
```

- [ ] **Step 3: Run the mandatory complete production-file comment audit**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 -BaseRef origin/main -HeadRef HEAD
```

Review every scanner-listed method, constructor, function, and component in every changed production file. Replace every pending marker with either:

- the exact updated Chinese business explanation; or
- a concrete exemption such as “单行纯格式化函数，命名完整表达且无数据源、状态或副作用”.

The PR report must contain a completed decision and business explanation for every scanned item, with no unfinished markers.

- [ ] **Step 4: Run final regression and repository checks**

```powershell
.\mvnw.cmd test
Set-Location web
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run lint
npm run build
Set-Location ..
git diff --check origin/main...HEAD
git status --short
```

Expected: all checks pass; status contains only intended final documentation changes before their commit.

- [ ] **Step 5: Commit final status documentation**

```powershell
git add -- PROJECT_STATUS.md docs/superpowers/README.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(status): record HVAC history trend completion"
```

- [ ] **Step 6: Inspect the final branch boundary**

```powershell
git diff --name-status origin/main...HEAD
git log --oneline origin/main..HEAD
git status --short --branch
```

Expected: only unified-history source, tests, spec, plan, project status, and task-directory files; no build output, environment secrets, temporary screenshots, or unrelated formatting.

- [ ] **Step 7: Push the task branch**

```powershell
git push -u origin feature/hvac-history-trends
```

- [ ] **Step 8: Prepare PR materials without creating or merging the PR**

Use:

```text
Base: main
Compare: feature/hvac-history-trends
URL: https://github.com/edg127117/iot-platform-demo/compare/main...feature/hvac-history-trends?expand=1
Title: feat(hvac): 接入统一真实历史趋势
```

The PR description must include:

- new batch chart-specific indicator trend endpoint and preserved exact audit endpoint;
- 1/5/30-minute aggregation semantics;
- four-indicator default and 1–8 raw-point selection;
- unit grouping, gaps, Q0/Q1/Q2, persistence, race isolation, stale refresh behavior;
- explicit exclusions: WebSocket, export, audit pages, management pages, formula changes, schema changes;
- actual backend/frontend test counts, skips, build, TDengine, Docker, and browser evidence;
- full comment audit for every changed production file;
- conflict and unrelated-file checks.

Stop at “等待用户创建并合并 PR”. After the user says “已合并”, follow the repository post-merge cleanup workflow; do not delete the branch or worktree beforehand.

---

## Plan Self-Review Result

- Spec coverage: backend batch trend, exact audit preservation, 1/5/30 resolution, both frontend modes, 1–8 point selection, persistence, gaps, quality, errors, races, performance, Docker, browser, status, comments, and Git flow all map to explicit tasks.
- Placeholder scan: no implementation placeholder remains; code-changing steps include concrete signatures, logic, commands, and expected outcomes.
- Type consistency: backend `TrendResponse/TrendSeries/TrendRecord`, repository `IndicatorTrendQueryRow`, frontend response types, unified trend model, composable context, and component props use the same names throughout.
- Scope check: WebSocket, exports, audit/drill-down pages, alerts, prediction, favorites, backend management, formula changes, and schema changes remain excluded.
