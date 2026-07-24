# HVAC Query API Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task.

**Goal:** 在现有 Spring Boot 单体项目中增加建筑 HVAC 最新快照和 1～8 测点历史趋势两个只读 API，查询 `st_raw_minute` 冻结分钟数据，支持最长 31 天与 1/5/30 分钟自动分辨率，不实现 COP/效率计算和前端页面。

**Architecture:** HTTP 层只负责接收参数、读取认证身份和包装 `Result<T>`；`HvacQueryService` 负责建筑/数据范围/测点/时间校验及 DTO 组装；扩展现有 `HvacMinuteRepository` 批量读取 TDengine。快照和趋势均先以 MySQL 中的测点配置为权限与元数据真相源，再按内部 `pointId` 一次查询 TDengine，避免跨建筑同名 `pointCode` 和 N+1 查询。

**Tech Stack:** Java 21、Spring Boot 3.2.4、Spring MVC/Security、MyBatis-Plus、Spring JDBC、TDengine、JUnit 5、Mockito、MockMvc、AssertJ。

**Approved design:** `docs/superpowers/specs/2026-07-24-hvac-query-api-design.md`

**TDengine syntax references:** [SELECT/PARTITION BY/LIMIT](https://docs.tdengine.com/reference/taos-sql/select/)、[INTERVAL 与 `_wstart`](https://docs.tdengine.com/get-started/docker/)、[LAST_ROW](https://docs.tdengine.com/reference/taos-sql/function/)

---

## Implementation rules

- 严格采用测试先行：每一任务先新增失败测试，确认失败原因与预期一致，再写最小实现。
- 不修改 COP/效率计算，不新增表、缓存或定时任务，不修改前端。
- 不改动或提交当前工作区中用户已有的
  `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java` 变更。
- 历史时间范围统一采用 `[from, to)`，即包含 `from`、不包含 `to`。
- 只有 TDengine 读取失败转换为业务码/HTTP `503`；MySQL、权限或未知错误不得伪装为 TDengine 故障。
- 每次提交只暂存本任务明确列出的文件，提交前执行 `git diff --cached --name-only`。

## Task 1: 定义时序查询模型并扩展仓储契约

**Files:**

- Create: `src/main/java/com/platform/iot/temporal/model/HvacMinuteQueryRow.java`
- Modify: `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`
- Test: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`

### Step 1: 先写仓储查询行为的失败测试

在现有 `TdengineHvacMinuteRepositoryTest` 增加三个测试：

```java
@Test
void readsLatestRowsForAllPointIdsInOnePartitionedQuery() {
    when(template.query(anyString(), any(RowMapper.class)))
            .thenReturn(List.of(row("POINT001", MINUTE, 12.3)));

    List<HvacMinuteQueryRow> result =
            repository.findLatestByPointIds(List.of("POINT001", "POINT002"));

    assertThat(result).extracting(HvacMinuteQueryRow::pointId)
            .containsExactly("POINT001");
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(template).query(sql.capture(), any(RowMapper.class));
    assertThat(sql.getValue())
            .contains("point_id IN ('POINT001','POINT002')")
            .contains("PARTITION BY point_id")
            .contains("ORDER BY ts DESC")
            .contains("LIMIT 1");
}

@Test
void readsOneMinuteHistoryWithoutDownsampling() {
    when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    repository.findHistory(
            List.of("POINT001", "POINT002"), MINUTE, MINUTE + 3_600_000L, 1);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(template).query(sql.capture(), any(RowMapper.class));
    assertThat(sql.getValue())
            .contains("ts>=")
            .contains("ts<")
            .contains("ORDER BY point_id,ts")
            .doesNotContain("INTERVAL(");
}

@Test
void downsamplesHistoryWithWeightedAverageAndWorstQuality() {
    when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    repository.findHistory(
            List.of("POINT001"), MINUTE, MINUTE + 604_800_000L, 30);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(template).query(sql.capture(), any(RowMapper.class));
    assertThat(sql.getValue())
            .contains("_wstart AS bucket_time")
            .contains("SUM(avg_val*sample_count)/SUM(sample_count)")
            .contains("MIN(min_val)")
            .contains("MAX(max_val)")
            .contains("SUM(sample_count)")
            .contains("MAX(data_quality)")
            .contains("PARTITION BY point_id")
            .contains("INTERVAL(30m)");
}
```

测试辅助方法返回稳定查询行：

```java
private HvacMinuteQueryRow row(String pointId, long time, double average) {
    return new HvacMinuteQueryRow(
            pointId, time, average, average - 0.5, average + 0.5, 6L, 0);
}
```

### Step 2: 运行测试并确认因接口缺失而失败

Run:

```powershell
mvn -Dtest=TdengineHvacMinuteRepositoryTest test
```

Expected: 编译失败，提示 `HvacMinuteQueryRow`、`findLatestByPointIds`、`findHistory` 不存在。

### Step 3: 增加稳定的查询行模型

创建：

```java
package com.platform.iot.temporal.model;

public record HvacMinuteQueryRow(
        String pointId,
        long time,
        double average,
        double minimum,
        double maximum,
        long sampleCount,
        int dataQuality) {
}
```

### Step 4: 扩展仓储接口但保持现有写入契约不变

在 `HvacMinuteRepository` 增加：

```java
List<HvacMinuteQueryRow> findLatestByPointIds(List<String> pointIds);

List<HvacMinuteQueryRow> findHistory(
        List<String> pointIds,
        long fromInclusive,
        long toExclusive,
        int resolutionMinutes);
```

保留 `saveAll(...)` 和 `findExistingPointIds(...)` 原样。

### Step 5: 临时加入最小方法桩并再次运行测试

在 `TdengineHvacMinuteRepository` 临时实现两个方法，返回空列表，用于确认测试已从“编译失败”推进到“SQL 断言失败”：

```java
@Override
public List<HvacMinuteQueryRow> findLatestByPointIds(List<String> pointIds) {
    return List.of();
}

@Override
public List<HvacMinuteQueryRow> findHistory(
        List<String> pointIds, long fromInclusive, long toExclusive, int resolutionMinutes) {
    return List.of();
}
```

Run:

```powershell
mvn -Dtest=TdengineHvacMinuteRepositoryTest test
```

Expected: 新测试失败于 `JdbcTemplate.query(...)` 未被调用；原有保存/恢复测试仍通过。

### Step 6: 不提交失败状态，直接继续 Task 2

此时测试仍为红色，只用于确认契约测试确实约束了实现。不要把临时方法桩或失败测试单独提交；继续完成 Task 2，待仓储测试转绿后一次提交查询模型、契约、实现和测试。

## Task 2: 实现 TDengine 批量快照与历史窗口查询

**Files:**

- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Modify: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`

### Step 1: 补齐边界和映射失败测试

增加：

```java
@Test
void emptyPointListDoesNotQueryTdengine() {
    assertThat(repository.findLatestByPointIds(List.of())).isEmpty();
    assertThat(repository.findHistory(List.of(), MINUTE, MINUTE + 60_000L, 1)).isEmpty();
    verifyNoInteractions(template);
}

@Test
void rejectsUnsupportedResolutionBeforeQuerying() {
    assertThatThrownBy(() ->
            repository.findHistory(List.of("POINT001"), MINUTE, MINUTE + 60_000L, 15))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1、5、30");
    verifyNoInteractions(template);
}

@Test
void escapesPointIdsAsSqlValues() {
    when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    repository.findLatestByPointIds(List.of("POINT'001"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(template).query(sql.capture(), any(RowMapper.class));
    assertThat(sql.getValue()).contains("'POINT''001'");
}
```

不要把 `pointId` 当成 TDengine 标识符；它是查询值，必须通过现有 `quote(...)` 转义。

### Step 2: 运行测试并确认失败

Run:

```powershell
mvn -Dtest=TdengineHvacMinuteRepositoryTest test
```

Expected: 新增测试和 Task 1 的 SQL 测试失败。

### Step 3: 实现统一 RowMapper 与 IN 子句

在仓储中增加：

```java
private HvacMinuteQueryRow mapQueryRow(ResultSet rs, int rowNum) throws SQLException {
    return new HvacMinuteQueryRow(
            rs.getString("point_id"),
            rs.getTimestamp("bucket_time").getTime(),
            rs.getDouble("average_value"),
            rs.getDouble("minimum_value"),
            rs.getDouble("maximum_value"),
            rs.getLong("sample_count"),
            rs.getInt("data_quality"));
}

private String pointIdIn(List<String> pointIds) {
    return pointIds.stream().map(this::quote).collect(Collectors.joining(","));
}
```

新增 imports：`ResultSet`、`SQLException`、`Collectors`、`HvacMinuteQueryRow`。

### Step 4: 实现一次批量最新快照查询

实现逻辑：

```java
@Override
public List<HvacMinuteQueryRow> findLatestByPointIds(List<String> pointIds) {
    if (pointIds.isEmpty()) return List.of();
    String stable = safe(properties.getDatabase()) + "."
            + safe(properties.getStRawMinute());
    String sql = """
            SELECT point_id,
                   ts AS bucket_time,
                   avg_val AS average_value,
                   min_val AS minimum_value,
                   max_val AS maximum_value,
                   sample_count,
                   data_quality
            FROM %s
            WHERE point_id IN (%s)
            PARTITION BY point_id
            ORDER BY ts DESC
            LIMIT 1
            """.formatted(stable, pointIdIn(pointIds));
    return template.query(sql, this::mapQueryRow);
}
```

该语句的 `LIMIT 1` 与 `PARTITION BY point_id` 组合表示每个测点分区取最新一条，不是全局只取一条。

### Step 5: 实现 1 分钟原样查询

构造公共时间条件：

```java
private String timeRange(long fromInclusive, long toExclusive) {
    return "ts>=" + quote(new Timestamp(fromInclusive).toString())
            + " AND ts<" + quote(new Timestamp(toExclusive).toString());
}
```

1 分钟 SQL：

```sql
SELECT point_id,
       ts AS bucket_time,
       avg_val AS average_value,
       min_val AS minimum_value,
       max_val AS maximum_value,
       sample_count,
       data_quality
FROM <stable>
WHERE point_id IN (...) AND ts>=... AND ts<...
ORDER BY point_id,ts
```

### Step 6: 实现 5/30 分钟服务端降采样

只允许 `resolutionMinutes` 为 `1`、`5`、`30`。5/30 分钟 SQL：

```sql
SELECT point_id,
       _wstart AS bucket_time,
       SUM(avg_val*sample_count)/SUM(sample_count) AS average_value,
       MIN(min_val) AS minimum_value,
       MAX(max_val) AS maximum_value,
       SUM(sample_count) AS sample_count,
       MAX(data_quality) AS data_quality
FROM <stable>
WHERE point_id IN (...) AND ts>=... AND ts<...
PARTITION BY point_id
INTERVAL(<5m-or-30m>)
ORDER BY point_id,_wstart
```

分辨率来自内部 `switch`，不得直接拼接任意请求值：

```java
String interval = switch (resolutionMinutes) {
    case 1 -> null;
    case 5 -> "5m";
    case 30 -> "30m";
    default -> throw new IllegalArgumentException("分辨率只允许 1、5、30 分钟");
};
```

### Step 7: 运行仓储测试

Run:

```powershell
mvn -Dtest=TdengineHvacMinuteRepositoryTest test
```

Expected: 全部通过，并确认每个 API 调用只执行一次 `JdbcTemplate.query(...)`。

### Step 8: 提交 TDengine 查询实现

```powershell
& 'F:\Git\cmd\git.exe' add `
  'src/main/java/com/platform/iot/temporal/model/HvacMinuteQueryRow.java' `
  'src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java' `
  'src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java' `
  'src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java'
& 'F:\Git\cmd\git.exe' diff --cached --name-only
& 'F:\Git\cmd\git.exe' commit -m "feat: query HVAC minute snapshots and trends"
```

## Task 3: 实现 HVAC 查询 DTO 与业务服务

**Files:**

- Create: `src/main/java/com/platform/hvac/model/dto/HvacQueryDtos.java`
- Create: `src/main/java/com/platform/hvac/service/HvacQueryService.java`
- Create: `src/test/java/com/platform/hvac/service/HvacQueryServiceTest.java`

### Step 1: 先写时间分辨率和 pointIds 规则测试

使用 Mockito mock：

```java
@Mock private BuildingService buildingService;
@Mock private BuildingScopeService buildingScopeService;
@Mock private BizDataPointService dataPointService;
@Mock private BizEquipmentService equipmentService;
@Mock private HvacMinuteRepository minuteRepository;
```

至少覆盖：

```java
@ParameterizedTest
@CsvSource({
        "86400000,1",
        "86400001,5",
        "604800000,5",
        "604800001,30",
        "2678400000,30"
})
void selectsResolutionAtApprovedBoundaries(long span, int expected) { ... }

@Test
void trimsDeduplicatesAndPreservesFirstPointOrder() { ... }

@Test
void acceptsOneAndEightPointsButRejectsNine() { ... }

@Test
void rejectsMissingTimesReversedRangeAndMoreThanThirtyOneDays() { ... }
```

测试通过 `history(...)` 的响应 `resolutionMinutes` 和 `series` 顺序观察行为，不直接测试私有方法。

### Step 2: 运行测试并确认类缺失

Run:

```powershell
mvn -Dtest=HvacQueryServiceTest test
```

Expected: 编译失败，提示 DTO/Service 不存在。

### Step 3: 定义与冻结书一致的 DTO

`HvacQueryDtos` 使用嵌套 record，数值允许无数据时为 `null`：

```java
public final class HvacQueryDtos {
    private HvacQueryDtos() {}

    public record SnapshotResponse(
            String buildingId, long generatedAt, List<SnapshotPoint> points) {}

    public record SnapshotPoint(
            String pointId, String pointCode, String pointName,
            String equipId, String equipCode, String unit,
            Long minute, Double average, Double minimum, Double maximum,
            long sampleCount, Integer dataQuality, String status) {}

    public record HistoryResponse(
            String buildingId, long from, long to, int resolutionMinutes,
            List<HistorySeries> series) {}

    public record HistorySeries(
            String pointId, String pointCode, String pointName,
            String unit, List<HistoryRecord> records) {}

    public record HistoryRecord(
            long time, double average, double minimum, double maximum,
            long sampleCount, int dataQuality) {}
}
```

### Step 4: 写最小 Service 骨架使测试进入行为失败

公开方法固定为：

```java
public HvacQueryDtos.SnapshotResponse snapshot(
        String buildingId, Long userId, Set<String> roles)

public HvacQueryDtos.HistoryResponse history(
        String buildingId, String rawPointIds, Long from, Long to,
        Long userId, Set<String> roles)
```

构造器依赖上述五个 service/repository。先返回空 DTO，运行测试确认失败在行为断言而不是编译。

### Step 5: 实现公共建筑与权限校验

每个入口按固定顺序执行：

```java
if (buildingService.getById(buildingId) == null) {
    throw new BusinessException(404, "建筑不存在");
}
buildingScopeService.checkAccess(userId, roles, buildingId);
```

控制器的 `@PreAuthorize` 负责角色白名单，Service 的 `BuildingScopeService` 负责建筑数据范围。

### Step 6: 实现历史请求校验

规则：

- `rawPointIds == null` 或清理后为空：400。
- 以逗号分割、`trim()`、过滤空串、`LinkedHashSet` 去重。
- 去重后超过 8 个：400。
- `from == null || to == null`、`from >= to`、跨度超过 `2_678_400_000L`：400。
- `dataPointService.listByIds(pointIds)` 必须恰好返回全部 ID。
- 每个点必须 `buildingId` 相同且 `status` 为 `ONLINE`，否则统一返回 400，不泄露跨建筑测点细节。

分辨率：

```java
private int resolution(long span) {
    if (span <= Duration.ofHours(24).toMillis()) return 1;
    if (span <= Duration.ofDays(7).toMillis()) return 5;
    return 30;
}
```

### Step 7: 实现快照组装

流程：

1. `dataPointService.list(...)` 一次读取建筑下 `ONLINE` 测点。
2. 按 `pointCode` 升序排序；同码时以 `pointId` 作为稳定次排序。
3. 批量 `equipmentService.listByIds(...)` 建立 `equipId -> equipCode`。
4. 一次 `minuteRepository.findLatestByPointIds(...)` 建立 `pointId -> row`。
5. 有数据映射 `status="NORMAL"`；无数据保留元数据，数值字段 `null`、`sampleCount=0`、`status="NO_DATA"`。

新增测试：

```java
@Test
void snapshotIncludesConfiguredPointWithoutMinuteDataAsNoData() { ... }

@Test
void snapshotSortsByPointCodeAndAddsEquipmentCode() { ... }
```

### Step 8: 实现趋势组装并强制输出顺序

一次调用：

```java
List<HvacMinuteQueryRow> rows =
        minuteRepository.findHistory(pointIds, from, to, resolution);
```

随后按 `pointId` 分组，每组按 `time` 升序；最终 `series` 必须遍历去重后的请求 ID 构建，不能依赖 MySQL 或 TDengine 返回顺序。合法但无数据的点返回 `records=[]`。

新增测试：

```java
@Test
void historyKeepsRequestedSeriesOrderAndSortsRecordsByTime() { ... }

@Test
void historyIncludesEmptySeriesForValidPointWithoutRows() { ... }

@Test
void rejectsMissingDisabledAndCrossBuildingPoints() { ... }
```

### Step 9: 只转换 TDengine 读取异常

围绕两个 repository 读取调用捕获 `DataAccessException`：

```java
try {
    return minuteRepository.findHistory(...);
} catch (DataAccessException exception) {
    throw new BusinessException(503, "HVAC 时序数据暂不可用，请稍后重试");
}
```

快照同样处理。不要用一个覆盖整个 Service 的宽泛 `catch`。

测试：

```java
@Test
void mapsTdengineReadFailureTo503WithoutLeakingSql() {
    when(minuteRepository.findHistory(anyList(), anyLong(), anyLong(), anyInt()))
            .thenThrow(new DataAccessResourceFailureException("jdbc:TAOS sql secret"));

    assertThatThrownBy(() -> service.history(...))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getCode()).isEqualTo(503);
                assertThat(error.getMessage()).doesNotContain("jdbc").doesNotContain("sql");
            });
}
```

### Step 10: 运行 Service 测试

Run:

```powershell
mvn -Dtest=HvacQueryServiceTest test
```

Expected: 全部通过。

### Step 11: 提交 Service 和 DTO

```powershell
& 'F:\Git\cmd\git.exe' add `
  'src/main/java/com/platform/hvac/model/dto/HvacQueryDtos.java' `
  'src/main/java/com/platform/hvac/service/HvacQueryService.java' `
  'src/test/java/com/platform/hvac/service/HvacQueryServiceTest.java'
& 'F:\Git\cmd\git.exe' diff --cached --name-only
& 'F:\Git\cmd\git.exe' commit -m "feat: validate and assemble HVAC queries"
```

## Task 4: 暴露两个受保护的 HTTP API

**Files:**

- Create: `src/main/java/com/platform/hvac/controller/HvacQueryController.java`
- Modify: `src/main/java/com/platform/framework/exception/GlobalExceptionHandler.java`
- Create: `src/test/java/com/platform/HvacQueryControllerFlowTest.java`

### Step 1: 先写 MockMvc 权限与契约测试

使用：

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HvacQueryControllerFlowTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean HvacMinuteRepository minuteRepository;
}
```

使用现有 `/auth/login`、`/auth/register` 和管理员用户创建能力生成：

- `PLATFORM_ADMIN`
- 已授权 BLD001 的 `BUILDING_OWNER`
- 已授权 BLD001 的 `ENERGY_MANAGER`
- 只授权 BLD002 的建筑角色
- `THIRD_PARTY`

至少覆盖：

```java
@Test
void snapshotRequiresAuthentication() { ... }             // 401

@Test
void allowedRolesCanReadAuthorizedBuilding() { ... }      // 200

@Test
void buildingRoleCannotReadOutsideScope() { ... }         // 403

@Test
void thirdPartyCannotUseInternalHvacApi() { ... }          // 403

@Test
void historyReturnsRequestedSeriesAndResolution() { ... }  // JSON 契约

@Test
void historyRejectsBadPointCountAndTimeRange() { ... }     // 400

@Test
void missingBuildingReturns404() { ... }

@Test
void tdengineFailureReturnsSanitized503() { ... }
```

Mock repository 返回确定数据，使测试完全不依赖本机 TDengine。

### Step 2: 运行测试并确认路由不存在

Run:

```powershell
mvn -Dtest=HvacQueryControllerFlowTest test
```

Expected: API 请求返回 404 或上下文因 controller 缺失而断言失败。

### Step 3: 创建控制器

```java
@RestController
@RequestMapping("/api/hvac/buildings")
@RequiredArgsConstructor
public class HvacQueryController {
    private final HvacQueryService queryService;

    @GetMapping("/{buildingId}/snapshot")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacQueryDtos.SnapshotResponse> snapshot(
            @PathVariable String buildingId,
            Authentication authentication) {
        return Result.success(queryService.snapshot(
                buildingId,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    @GetMapping("/{buildingId}/history")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacQueryDtos.HistoryResponse> history(
            @PathVariable String buildingId,
            @RequestParam(required = false) String pointIds,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        return Result.success(queryService.history(
                buildingId, pointIds, from, to,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }
}
```

### Step 4: 将业务码 503 映射为 HTTP 503

修改 `GlobalExceptionHandler` 的 switch：

```java
case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
```

不要改变现有 401/403/404/409/500 映射。

### Step 5: 运行控制器测试

Run:

```powershell
mvn -Dtest=HvacQueryControllerFlowTest test
```

Expected: 全部通过；响应具有 `code/msg/success/data` 包装，且 503 响应不包含 SQL、JDBC URL 或连接信息。

### Step 6: 运行所有 HVAC 定向测试

Run:

```powershell
mvn -Dtest=HvacQueryServiceTest,TdengineHvacMinuteRepositoryTest,HvacQueryControllerFlowTest test
```

Expected: 全部通过。

### Step 7: 提交 HTTP API

```powershell
& 'F:\Git\cmd\git.exe' add `
  'src/main/java/com/platform/hvac/controller/HvacQueryController.java' `
  'src/main/java/com/platform/framework/exception/GlobalExceptionHandler.java' `
  'src/test/java/com/platform/HvacQueryControllerFlowTest.java'
& 'F:\Git\cmd\git.exe' diff --cached --name-only
& 'F:\Git\cmd\git.exe' commit -m "feat: expose protected HVAC query APIs"
```

## Task 5: 完整回归与手工无前端验证

**Files:**

- Modify only if a failing test exposes a real defect in files already listed above.
- Do not modify frontend files.

### Step 1: 运行完整后端测试

Run:

```powershell
mvn test
```

Expected:

- 原有 47 个测试全部通过。
- 新增仓储、Service、Controller 测试全部通过。
- 无测试尝试连接真实 TDengine。

如依赖解析因沙箱网络受限失败，应申请执行同一条 `mvn test`，不要跳过验证。

### Step 2: 检查工作区与提交边界

Run:

```powershell
& 'F:\Git\cmd\git.exe' status --short
& 'F:\Git\cmd\git.exe' log --oneline -5
```

Expected:

- `HvacMinuteAggregationService.java` 的用户原有修改仍然存在且未进入本功能提交。
- 本功能新增/修改文件已提交。
- 没有前端、COP、数据库迁移或无关文件。

### Step 3: 可选的本地 HTTP 冒烟测试

当本机 MySQL/Redis 可用时启动测试环境或开发环境，先登录取得 token，再验证：

```powershell
curl.exe -H "Authorization: Bearer <token>" `
  "http://localhost:8080/api/hvac/buildings/BLD001/snapshot"

curl.exe -H "Authorization: Bearer <token>" `
  "http://localhost:8080/api/hvac/buildings/BLD001/history?pointIds=POINT001,POINT002&from=<from-ms>&to=<to-ms>"
```

Expected:

- 快照包含 BLD001 全部 `ONLINE` 测点；没有分钟数据的测点为 `NO_DATA`。
- 趋势按请求 ID 顺序返回两个 series。
- 查询跨度决定 `resolutionMinutes` 为 1、5 或 30。

本步骤不是自动化测试的替代；如果本机基础设施未启动，记录“未执行手工冒烟测试”，但完整 `mvn test` 仍必须通过。

### Step 4: 最终交付说明

交付时报告：

- 两个 API 的完整路径和参数。
- 权限角色与建筑范围行为。
- 31 天限制和自动分辨率规则。
- 自动化测试数量及 `mvn test` 结果。
- 明确说明 COP/效率计算、原始事件查询和前端仍不在本期范围。
