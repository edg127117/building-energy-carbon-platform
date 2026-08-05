# HVAC Snapshot Freshness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让最新测点快照由后端统一标记 `NORMAL`、`STALE`、`NO_DATA`，并让前端停止把过期历史值当作当前值或计入完整率。

**Architecture:** 在 `com.platform.hvac` 内新增单一职责的新鲜度策略，复用分钟聚合的 30 秒冻结等待配置，并允许落后最新应冻结分钟 1 分钟。`HvacQueryService` 以同一个 `generatedAt` 组装整份快照；前端领域层把 `STALE` 拆为主值 `--` 和可追溯的最后值，页面只展示事实而不推断故障原因。

**Tech Stack:** Java 21、Spring Boot 3.2、JUnit 5、Mockito、Vue 3、TypeScript、Vitest、Docker Compose、MySQL、TDengine、EMQX、Redis。

## Global Constraints

- 分钟窗口保持 1 分钟，继续使用 `aggregation.finalization-delay-seconds: 30`。
- 新增 `hvac.snapshot.freshness-tolerance-minutes: 1`，落后正好 1 分钟仍为 `NORMAL`，超过 1 分钟才为 `STALE`。
- `STALE` 继续返回最后值、质量等级和来源分钟；前端主实时值显示 `--`，辅助显示最后值和分钟。
- 当前有效测点完整率只统计 `NORMAL` 且有值的 19 个冻结槽位。
- 不修改 MQTT 接入、分钟聚合、数据质量、公式、TDengine 表结构或四项指标接口。
- `STALE` 只说明数据过期，不推断网络、设备、MQTT 或服务故障。
- 所有生产 Java、Vue、TypeScript 变更必须完成仓库注释审计；不得绕过 Hook 或 CI。

---

### Task 1: 建立后端快照新鲜度策略

**Files:**
- Create: `src/main/java/com/platform/hvac/service/HvacSnapshotFreshnessPolicy.java`
- Create: `src/test/java/com/platform/hvac/service/HvacSnapshotFreshnessPolicyTest.java`
- Modify: `src/main/resources/application.yml:80-91`

**Interfaces:**
- Consumes: `aggregation.finalization-delay-seconds` 和 `hvac.snapshot.freshness-tolerance-minutes`。
- Produces: `String HvacSnapshotFreshnessPolicy.status(long minuteStart, long generatedAt)`，只返回 `NORMAL` 或 `STALE`。

- [ ] **Step 1: 写入时间边界失败测试**

```java
package com.platform.hvac.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HvacSnapshotFreshnessPolicyTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private final HvacSnapshotFreshnessPolicy policy =
            new HvacSnapshotFreshnessPolicy(30, 1);

    @Test
    void keepsOneDueMinuteOfTolerance() {
        assertThat(policy.status(MINUTE, MINUTE + 150_000L))
                .isEqualTo("NORMAL");
    }

    @Test
    void marksPointStaleWhenItFallsTwoDueMinutesBehind() {
        assertThat(policy.status(MINUTE, MINUTE + 210_000L))
                .isEqualTo("STALE");
    }

    @Test
    void advancesOnlyAfterTheThirtySecondFreezeBoundary() {
        assertThat(policy.status(MINUTE, MINUTE + 209_999L))
                .isEqualTo("NORMAL");
        assertThat(policy.status(MINUTE, MINUTE + 210_000L))
                .isEqualTo("STALE");
    }
}
```

- [ ] **Step 2: 运行测试并确认因类不存在而失败**

Run:

```powershell
.\mvnw.cmd -Dtest=HvacSnapshotFreshnessPolicyTest test
```

Expected: FAIL，编译器报告 `HvacSnapshotFreshnessPolicy` 不存在。

- [ ] **Step 3: 实现最小策略与配置**

新增配置：

```yaml
# HVAC 最新快照新鲜度只判断记录是否仍可作为实时值，不推断设备或网络故障原因。
hvac:
  snapshot:
    # 允许短时网络抖动落后一个已冻结分钟；超过后返回 STALE。
    freshness-tolerance-minutes: 1
```

新增策略：

```java
package com.platform.hvac.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按服务端统一时间和分钟冻结边界判定最新测点记录是否仍可作为实时快照。 */
@Component
public class HvacSnapshotFreshnessPolicy {

    private static final long MINUTE_MILLIS = 60_000L;

    private final long finalizationDelayMillis;
    private final long toleranceMillis;

    public HvacSnapshotFreshnessPolicy(
            @Value("${aggregation.finalization-delay-seconds:30}")
            long finalizationDelaySeconds,
            @Value("${hvac.snapshot.freshness-tolerance-minutes:1}")
            long freshnessToleranceMinutes) {
        if (finalizationDelaySeconds < 0 || freshnessToleranceMinutes < 0) {
            throw new IllegalArgumentException("快照新鲜度时间配置不能为负数");
        }
        this.finalizationDelayMillis =
                Math.multiplyExact(finalizationDelaySeconds, 1_000L);
        this.toleranceMillis =
                Math.multiplyExact(freshnessToleranceMinutes, MINUTE_MILLIS);
    }

    /**
     * 允许记录落后最新应冻结分钟一个配置窗口，避免短时网络抖动被提前标记为过期。
     */
    public String status(long minuteStart, long generatedAt) {
        long effectiveNow = generatedAt - finalizationDelayMillis;
        long currentEffectiveMinute =
                effectiveNow - Math.floorMod(effectiveNow, MINUTE_MILLIS);
        long latestDueMinute = currentEffectiveMinute - MINUTE_MILLIS;
        long staleBefore = latestDueMinute - toleranceMillis;
        return minuteStart < staleBefore ? "STALE" : "NORMAL";
    }
}
```

- [ ] **Step 4: 运行定向测试并确认通过**

Run: `.\mvnw.cmd -Dtest=HvacSnapshotFreshnessPolicyTest test`

Expected: 3 tests，0 failures，0 errors，0 skipped。

- [ ] **Step 5: 提交独立策略**

```powershell
git add -- src/main/java/com/platform/hvac/service/HvacSnapshotFreshnessPolicy.java src/test/java/com/platform/hvac/service/HvacSnapshotFreshnessPolicyTest.java src/main/resources/application.yml
git diff --cached --check
git commit -m "feat(hvac): define snapshot freshness policy"
```

---

### Task 2: 将新鲜度状态接入快照 API

**Files:**
- Modify: `src/main/java/com/platform/hvac/service/HvacQueryService.java:30-105,302-339`
- Modify: `src/main/java/com/platform/hvac/model/dto/HvacQueryDtos.java:15-62`
- Modify: `src/main/java/com/platform/hvac/controller/HvacQueryController.java:35-55`
- Modify: `src/test/java/com/platform/hvac/service/HvacQueryServiceTest.java:20-225`

**Interfaces:**
- Consumes: `HvacSnapshotFreshnessPolicy.status(long minuteStart, long generatedAt)`。
- Produces: `SnapshotPoint.status` 的稳定值 `NORMAL | STALE | NO_DATA`；`SnapshotResponse.generatedAt` 与整份响应判定使用的时间一致。

- [ ] **Step 1: 给查询服务补充失败测试**

在测试类增加 Mock，并传入构造器：

```java
@Mock private HvacSnapshotFreshnessPolicy freshnessPolicy;

service = new HvacQueryService(
        buildingService,
        buildingScopeService,
        dataPointService,
        equipmentService,
        minuteRepository,
        freshnessPolicy);
```

在现有正常快照测试中补充：

```java
when(freshnessPolicy.status(eq(FROM), anyLong())).thenReturn("NORMAL");
verify(freshnessPolicy).status(FROM, response.generatedAt());
```

新增过期测试：

```java
@Test
@SuppressWarnings({"unchecked", "rawtypes"})
void snapshotKeepsStaleEvidenceButMarksItUnavailableForRealtimeUse() {
    allowBuilding("BLD001");
    BizDataPoint point = point("POINT001", "A", "BLD001", "ONLINE", null);
    when(dataPointService.list(any(Wrapper.class))).thenReturn(List.of(point));
    when(minuteRepository.findLatestByPointIds(List.of("POINT001")))
            .thenReturn(List.of(row("POINT001", FROM, 12.3)));
    when(freshnessPolicy.status(eq(FROM), anyLong())).thenReturn("STALE");

    HvacQueryDtos.SnapshotResponse response =
            service.snapshot("BLD001", USER_ID, ADMIN);

    HvacQueryDtos.SnapshotPoint stale = response.points().getFirst();
    assertThat(stale.status()).isEqualTo("STALE");
    assertThat(stale.minute()).isEqualTo(FROM);
    assertThat(stale.average()).isEqualTo(12.3);
    assertThat(stale.dataQuality()).isZero();
    verify(freshnessPolicy).status(FROM, response.generatedAt());
}
```

- [ ] **Step 2: 运行查询服务测试并确认失败**

Run: `.\mvnw.cmd -Dtest=HvacQueryServiceTest test`

Expected: FAIL，因为 `HvacQueryService` 尚未接收策略，且历史行仍固定返回 `NORMAL`。

- [ ] **Step 3: 使用单一 generatedAt 组装快照**

在 `HvacQueryService` 增加依赖：

```java
private final HvacSnapshotFreshnessPolicy freshnessPolicy;
```

有测点数据的分支在 TDengine 查询完成后获取一次时间，并传入每个测点：

```java
long generatedAt = System.currentTimeMillis();
List<HvacQueryDtos.SnapshotPoint> responsePoints = points.stream()
        .map(point -> snapshotPoint(
                point,
                equipmentCodes.get(point.getEquipId()),
                latestByPoint.get(point.getPointId()),
                generatedAt))
        .toList();
return new HvacQueryDtos.SnapshotResponse(
        buildingId, generatedAt, responsePoints);
```

修改 DTO 组装方法：

```java
private HvacQueryDtos.SnapshotPoint snapshotPoint(
        BizDataPoint point,
        String equipmentCode,
        HvacMinuteQueryRow row,
        long generatedAt) {
    if (row == null) {
        return new HvacQueryDtos.SnapshotPoint(
                point.getPointId(),
                point.getPointCode(),
                point.getPointName(),
                point.getEquipId(),
                equipmentCode,
                point.getUnit(),
                null,
                null,
                null,
                null,
                0L,
                null,
                "NO_DATA");
    }
    return new HvacQueryDtos.SnapshotPoint(
            point.getPointId(),
            point.getPointCode(),
            point.getPointName(),
            point.getEquipId(),
            equipmentCode,
            point.getUnit(),
            row.time(),
            row.average(),
            row.minimum(),
            row.maximum(),
            row.sampleCount(),
            row.dataQuality(),
            freshnessPolicy.status(row.time(), generatedAt));
}
```

保留现有 `row == null` 的完整 `NO_DATA` DTO 内容，不改变空值和样本数语义。

- [ ] **Step 4: 更新 DTO 与 Controller 注释**

将 `SnapshotPoint.status` 注释改为：

```java
@param status {@code NORMAL} 表示记录仍可作为实时值，{@code STALE} 表示保留的最后记录已过期，
              {@code NO_DATA} 表示仅有测点配置且从未查询到分钟记录
```

将 Controller 快照说明补充为：

```java
 * <p>各测点不要求来自同一分钟；最后记录超过服务端新鲜度边界时标记为
 * {@code STALE}，没有任何分钟记录时标记为 {@code NO_DATA}。</p>
```

- [ ] **Step 5: 运行两组后端定向测试**

Run:

```powershell
.\mvnw.cmd -Dtest=HvacSnapshotFreshnessPolicyTest,HvacQueryServiceTest test
```

Expected: 两个测试类全部通过，0 failures，0 errors，0 skipped。

- [ ] **Step 6: 提交 API 接入**

```powershell
git add -- src/main/java/com/platform/hvac/service/HvacQueryService.java src/main/java/com/platform/hvac/model/dto/HvacQueryDtos.java src/main/java/com/platform/hvac/controller/HvacQueryController.java src/test/java/com/platform/hvac/service/HvacQueryServiceTest.java
git diff --cached --check
git commit -m "fix(hvac): mark stale snapshot points"
```

---

### Task 3: 前端领域层区分实时值与最后值

**Files:**
- Modify: `web/src/types/hvac.ts:20-48`
- Modify: `web/src/domain/hvacDashboard.ts:50-160,207-214`
- Modify: `web/src/domain/hvacDashboard.test.ts:1-135`

**Interfaces:**
- Consumes: `SnapshotPoint.status: 'NORMAL' | 'STALE' | 'NO_DATA' | string`。
- Produces: `DashboardPointView.statusLabel: string` 和 `DashboardPointView.lastDisplayValue: string | null`；`value/displayValue` 只承载当前可用值。

- [ ] **Step 1: 写入 STALE 映射失败测试**

```ts
it('hides stale realtime value but keeps the last value evidence', () => {
  const views = buildPointViews(
    snapshot([point('WCR1_TWin', 7.2, 'STALE')]),
  )

  expect(views.WCR1_TWin.value).toBeNull()
  expect(views.WCR1_TWin.displayValue).toBe('--')
  expect(views.WCR1_TWin.status).toBe('STALE')
  expect(views.WCR1_TWin.statusLabel).toBe('数据过期')
  expect(views.WCR1_TWin.lastDisplayValue).toBe('7.2')
  expect(views.WCR1_TWin.minute).toBe(1785420000000)
  expect(calculatePointCoverage(views)).toBe(0)
})
```

同时补充 `NO_DATA` 断言：

```ts
expect(views.WCR1_TWin.statusLabel).toBe('暂无数据')
expect(views.WCR1_TWin.lastDisplayValue).toBeNull()
```

- [ ] **Step 2: 运行领域测试并确认失败**

Run:

```powershell
npm run test:run -- src/domain/hvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Working directory: `web`

Expected: FAIL，因为视图模型尚无 `statusLabel` 和 `lastDisplayValue`。

- [ ] **Step 3: 扩展接口类型和领域模型**

`SnapshotPoint.status` 改为：

```ts
status: 'NORMAL' | 'STALE' | 'NO_DATA' | string
```

并在 DTO 注释中明确：`STALE` 仍携带最后值、质量和分钟。

`DashboardPointView` 增加：

```ts
lastDisplayValue: string | null
statusLabel: string
```

新增状态文案映射：

```ts
/** 将快照新鲜度状态转换为业务文案，不根据 STALE 推断设备或网络故障。 */
function pointStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    NORMAL: '数据正常',
    STALE: '数据过期',
    NO_DATA: '暂无数据',
  }
  return labels[status] ?? status
}
```

在 `buildPointViews` 中分别计算当前值和最后值：

```ts
const status = source?.status ?? 'NO_DATA'
const hasSourceValue = source?.average !== null && source?.average !== undefined
const available = status === 'NORMAL' && hasSourceValue
const stale = status === 'STALE' && hasSourceValue

value: available ? source!.average : null,
displayValue: available
  ? source!.average!.toFixed(definition.precision)
  : '--',
lastDisplayValue: stale
  ? source!.average!.toFixed(definition.precision)
  : null,
status,
statusLabel: pointStatusLabel(status),
```

保持 `calculatePointCoverage` 的 `NORMAL && value !== null` 判定，并把注释改为“当前有效测点完整率”。

- [ ] **Step 4: 运行前端领域测试并确认通过**

Run: `npm run test:run -- src/domain/hvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

Working directory: `web`

Expected: 该测试文件全部通过，0 skipped。

- [ ] **Step 5: 提交前端领域语义**

```powershell
git add -- web/src/types/hvac.ts web/src/domain/hvacDashboard.ts web/src/domain/hvacDashboard.test.ts
git diff --cached --check
git commit -m "fix(web): separate stale and realtime point values"
```

---

### Task 4: 页面展示数据过期与最后值证据

**Files:**
- Modify: `web/src/pages/HvacDemoPage.vue:70-215,247-272,300-465,560-735`
- Modify: `web/src/pages/HvacDemoPage.contract.test.ts:25-48`

**Interfaces:**
- Consumes: `DashboardPointView.statusLabel`、`lastDisplayValue`、`minute`、`unit`。
- Produces: 主值 `--`、状态“数据过期”、辅助文案“最后值 … · HH:mm”和“当前有效测点完整率”。

- [ ] **Step 1: 写入页面契约失败测试**

在“真实状态”测试中增加：

```ts
expect(source).toContain('当前有效测点完整率')
expect(source).toContain('stalePointDetail')
expect(source).toContain('item.statusLabel')
expect(source).toContain('item.lastDisplayValue')
expect(source).not.toContain('测点数据完整率')
```

- [ ] **Step 2: 运行页面契约测试并确认失败**

Run:

```powershell
npm run test:run -- src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Working directory: `web`

Expected: FAIL，因为页面仍展示原始状态码和旧完整率标题。

- [ ] **Step 3: 增加最后值格式化函数**

补充类型导入：

```ts
type DashboardPointView,
```

新增函数：

```ts
/** 格式化后端最后分钟；只作为过期证据，不把它重新解释为当前数据。 */
function formatPointMinute(minute: number | null): string {
  if (minute === null) return '--'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(minute))
}

/** 过期测点保留最后值和来源分钟，主实时值仍保持 --。 */
function stalePointDetail(point: DashboardPointView): string | null {
  if (point.status !== 'STALE' || point.lastDisplayValue === null) return null
  const unit = point.unit ? ` ${point.unit}` : ''
  return `最后值 ${point.lastDisplayValue}${unit} · ${formatPointMinute(point.minute)}`
}
```

让 `pointStatus` 返回 `statusLabel`：

```ts
function pointStatus(code: FrozenPointCode): string {
  return pointViews.value[code].statusLabel
}
```

- [ ] **Step 4: 修改模板并保留清晰信息层级**

两处完整率标题统一改为：

```vue
<span>当前有效测点完整率</span>
```

质量说明改为：

```vue
<span><i class="q-real" />仅统计 NORMAL 当前值</span>
<span><i class="q-default" />过期或缺失保持 --</span>
```

设备状态在代表测点过期时增加辅助证据，四张设备卡分别传入原有代表测点编码：

```vue
<span class="device-state" :class="{ 'is-stale': pointViews.WCR1_TWout.status === 'STALE' }">
  <span><i />{{ pointStatus('WCR1_TWout') }}</span>
  <small v-if="stalePointDetail(pointViews.WCR1_TWout)">
    {{ stalePointDetail(pointViews.WCR1_TWout) }}
  </small>
</span>
```

全部测点列表改为：

```vue
<div
  v-for="item in allPoints"
  :key="item.displayCode"
  class="point-row"
  :class="{ 'is-stale': item.status === 'STALE', 'is-empty': item.status === 'NO_DATA' }"
>
  <span class="point-status" />
  <span class="point-name">
    <b>{{ item.label }}</b>
    <small>{{ item.displayCode }}</small>
  </span>
  <span class="point-reading">
    <span class="point-value">{{ item.displayValue }} <small>{{ item.unit }}</small></span>
    <small v-if="item.lastDisplayValue !== null" class="point-last-value">
      {{ stalePointDetail(item) }}
    </small>
  </span>
  <span class="point-quality">{{ item.qualityLabel }} · {{ item.statusLabel }}</span>
</div>
```

- [ ] **Step 5: 增加过期状态样式并验证响应式不溢出**

```css
.device-state { flex-direction: column; align-items: flex-start; }
.device-state > span { display: flex; align-items: center; gap: 6px; }
.device-state small { color: #a98345; font-size: 8px; }
.device-state.is-stale { color: #e3ad55; }
.device-state.is-stale i { background: #e3ad55; box-shadow: 0 0 8px rgba(227, 173, 85, .7); }
.point-reading { display: flex; flex-direction: column; align-items: flex-end; min-width: 0; }
.point-last-value { color: #a98345; font-size: 7px; white-space: normal; text-align: right; }
.point-row.is-stale .point-status { background: #e3ad55; box-shadow: 0 0 7px rgba(227, 173, 85, .65); }
.point-row.is-stale .point-quality { color: #d3a052; }
.point-row.is-empty .point-status { background: #52677b; box-shadow: none; }
```

同时加入 390px 移动端规则，固定让状态换到下一行，避免辅助文案挤压主值：

```css
@media (max-width: 560px) {
  .point-row { grid-template-columns: 12px minmax(0, 1fr) minmax(86px, auto); }
  .point-quality { grid-column: 2 / 4; }
}
```

- [ ] **Step 6: 运行前端定向测试、类型检查和 ESLint**

Run:

```powershell
npm run test:run -- src/domain/hvacDashboard.test.ts src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run lint
```

Working directory: `web`

Expected: 定向测试全部通过，TypeScript/Vue 检查通过，ESLint 0 errors。

- [ ] **Step 7: 提交页面展示**

```powershell
git add -- web/src/pages/HvacDemoPage.vue web/src/pages/HvacDemoPage.contract.test.ts
git diff --cached --check
git commit -m "fix(web): show stale point evidence"
```

---

### Task 5: 更新当前项目文档并完成全量验收

**Files:**
- Modify: `PROJECT_GUIDE.md:81-89`
- Modify: `PROJECT_STATUS.md:35-88`
- Verify: `docs/superpowers/specs/2026-08-05-hvac-snapshot-freshness-design.md`
- Verify: `docs/superpowers/plans/2026-08-05-hvac-snapshot-freshness.md`

**Interfaces:**
- Consumes: 已实现的 `STALE` API 和页面行为。
- Produces: 当前有效项目地图、状态证据、完整注释审计、自动化测试和真实 Docker 验收记录。

- [ ] **Step 1: 更新稳定项目地图**

在 `PROJECT_GUIDE.md` 的前端刷新链路后补充：

```markdown
快照由后端按统一服务端时间区分 `NORMAL`、`STALE` 和 `NO_DATA`：过期记录仍保留最后值与来源分钟用于追溯，但前端不把它作为当前值或计入当前有效测点完整率。
```

- [ ] **Step 2: 更新当前状态和优先级**

在 `PROJECT_STATUS.md` 的 HVAC 展示能力中记录：

```markdown
- 最新测点快照已区分分钟数据质量与查询时新鲜度；超过容忍边界的最后记录返回 `STALE`，页面主值显示 `--`、辅助展示最后值和来源分钟，并从当前有效测点完整率中排除。
```

删除“历史最后值可能继续显示 `NORMAL` 和 100%”风险；将下一步优先级 1 调整为真实历史数据展示，后续序号顺延。真实现场设备和长时间网络抖动仍保留为未验证边界。

- [ ] **Step 3: 运行完整自动化回归**

Backend:

```powershell
.\mvnw.cmd test
```

Frontend（working directory `web`）:

```powershell
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run lint
npm run build
```

Expected: Maven 0 failures/errors/skipped；Vitest 0 failures/skipped；检查、ESLint 和构建成功。若 Vite 仅报告既有大包警告，如实记录但不扩大到性能重构。

- [ ] **Step 4: 生成并人工完成生产代码注释审计**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 -BaseRef origin/main -HeadRef HEAD
```

逐个覆盖以下生产文件及扫描出的全部函数：

- `src/main/java/com/platform/hvac/service/HvacSnapshotFreshnessPolicy.java`
- `src/main/java/com/platform/hvac/service/HvacQueryService.java`
- `src/main/java/com/platform/hvac/model/dto/HvacQueryDtos.java`
- `src/main/java/com/platform/hvac/controller/HvacQueryController.java`
- `web/src/types/hvac.ts`
- `web/src/domain/hvacDashboard.ts`
- `web/src/pages/HvacDemoPage.vue`

PR 报告中所有判定必须是反引号包裹的允许值：`关键-已新增说明`、`关键-已更新说明`、`关键-现有说明已核验` 或 `简单-无需说明`；文件级“现有注释时效”必须精确填写“已核验”。

- [ ] **Step 5: 执行真实 Docker 停止与恢复验收**

1. 使用仓库现有 Compose 启动 MySQL、TDengine、Redis、EMQX，并启动后端与 Vite。
2. 通过现有 MQTT 数据模拟器持续输入，记录页面 `NORMAL`、当前值、完整率和来源分钟。
3. 只停止模拟器进程，不停止数据库或应用服务。
4. 等待最新应冻结分钟超过最后记录 1 分钟，刷新快照 API，确认返回 `STALE` 且仍携带旧值和分钟。
5. 浏览器确认主值为 `--`、辅助显示最后值和分钟、当前有效测点完整率下降；桌面 1280px 和移动 390px 均无横向溢出，控制台 0 errors。
6. 恢复模拟器，等待形成足够新的冻结分钟，确认 API 和页面自动恢复 `NORMAL`。
7. 不修改或提交容器卷、运行日志、本机 `.env`、凭据和临时文件。

- [ ] **Step 6: 提交文档状态**

```powershell
git add -- PROJECT_GUIDE.md PROJECT_STATUS.md
git diff --cached --check
git commit -m "docs(hvac): record snapshot freshness behavior"
```

- [ ] **Step 7: 最终差异和仓库守卫检查**

```powershell
git status --short --branch
git diff --check origin/main...HEAD
git log --oneline origin/main..HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-RepositoryGuardrails.ps1
```

Expected: 工作区干净；只包含本任务设计、计划、后端快照新鲜度、前端展示、测试和当前文档；仓库守卫通过。

- [ ] **Step 8: 推送分支并由 AI 创建 PR**

```powershell
git push -u origin fix/hvac-snapshot-freshness
```

PR 标题：

```text
fix(hvac): 修复过期测点仍显示为当前值
```

PR 必须包含：问题、变更范围、不包含范围、所有测试数量、Docker 停止/恢复证据、完整注释审计、无关文件检查和冲突状态。AI 创建 PR 后不合并，等待用户审核并合并。
