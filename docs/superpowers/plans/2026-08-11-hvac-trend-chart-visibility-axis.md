# HVAC Trend Chart Visibility and Axis Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让孤立 Q0 真实点可见，恢复真正的纵轴单位与稳定单值范围，并为全空历史数据提供明确且不伪造数值的空状态。

**Architecture:** 保持 `HvacTrendChart.vue` 作为唯一生产代码修改点：领域适配层继续提供缺口分隔，图表组件根据直接相邻点判断 Q0 是否已有线段可见性。纵轴范围只在全组只有一个不同有效值时增加数据驱动留白；全组无有效值时使用 DOM 空状态，不生成虚假刻度。

**Tech Stack:** Vue 3、TypeScript 5.3、ECharts 5.6、Vitest 2.1、Vue Test Utils、ESLint、Vite。

## Global Constraints

- 只修改 `web/src/components/hvac/HvacTrendChart.vue`、`web/src/components/hvac/HvacTrendChart.test.ts` 和本任务历史计划索引。
- 不修改后端 API、DTO、TDengine 查询、Q0/Q1/Q2 业务语义或领域适配层的缺口插入契约。
- 不连接缺口，不进行前端插值、补点、指标重算或虚假默认值填充。
- 连续 Q0 仍以折线为主，只有前后都没有直接相邻有效值的 Q0 才显示圆点。
- 全空组显示“当前时段无有效数据”，不伪造 0～1 或业务默认纵轴范围。
- 生产代码注释使用简洁中文并准确说明缺失值、相邻点和单位边界。
- 普通自动化测试不得连接真实 MySQL、TDengine、Redis、MQTT 或现场设备。

---

### Task 1: 修复孤立 Q0 点可见性

**Files:**
- Modify: `web/src/components/hvac/HvacTrendChart.test.ts:14-145`
- Modify: `web/src/components/hvac/HvacTrendChart.vue:228-260`

**Interfaces:**
- Consumes: `HvacTrendPoint.average: number | null`、`HvacTrendPoint.dataQuality: number | null`，以及领域适配层插入的直接相邻空分隔点。
- Produces: `isFiniteAverage(point: HvacTrendPoint | undefined): boolean` 和 `qualitySymbol(point: HvacTrendPoint, index: number, points: HvacTrendPoint[]): string`。

- [ ] **Step 1: 写入孤立、连续和缺口 Q0 的失败测试**

在 `HvacTrendChart.test.ts` 中扩展 `RenderedDatum` 并新增测试辅助函数：

```ts
type RenderedDatum = {
  value: [number, number | null]
  symbol: string
}

function renderSymbols(points: HvacTrendGroup['series'][number]['points']): string[] {
  mount(HvacTrendChart, {
    props: {
      group: {
        ...group,
        series: [{ ...group.series[0]!, points }],
      },
      from: 0,
      to: 300_000,
      resolutionMinutes: 1,
    },
  })
  return mocks.setOption.mock.calls.at(-1)![0].series[0].data
    .map((item: RenderedDatum) => item.symbol)
}
```

新增三个断言场景：

```ts
it('shows only Q0 points that have no adjacent line segment', () => {
  const isolated = renderSymbols([
    { time: 60_000, average: 3.2, minimum: 3.2, maximum: 3.2, sampleCount: 1, dataQuality: 0 },
  ])
  expect(isolated).toEqual(['circle'])

  vi.clearAllMocks()
  mocks.init.mockReturnValue({
    setOption: mocks.setOption,
    resize: mocks.resize,
    dispose: mocks.dispose,
  })
  const continuous = renderSymbols([
    { time: 60_000, average: 3.2, minimum: 3.2, maximum: 3.2, sampleCount: 1, dataQuality: 0 },
    { time: 120_000, average: 3.3, minimum: 3.3, maximum: 3.3, sampleCount: 1, dataQuality: 0 },
  ])
  expect(continuous).toEqual(['none', 'none'])

  vi.clearAllMocks()
  mocks.init.mockReturnValue({
    setOption: mocks.setOption,
    resize: mocks.resize,
    dispose: mocks.dispose,
  })
  const separated = renderSymbols([
    { time: 60_000, average: 3.2, minimum: 3.2, maximum: 3.2, sampleCount: 1, dataQuality: 0 },
    { time: 120_000, average: null, minimum: null, maximum: null, sampleCount: 0, dataQuality: null },
    { time: 180_000, average: 3.4, minimum: 3.4, maximum: 3.4, sampleCount: 1, dataQuality: 0 },
  ])
  expect(separated).toEqual(['circle', 'none', 'circle'])
})
```

保留现有 Q1=`circle`、Q2=`diamond`、空值=`none` 断言，并把现有首个 Q0 的期望保持为 `none`，因为它右侧有有效 Q1 并形成线段。

- [ ] **Step 2: 运行测试并确认旧实现失败**

Run:

```powershell
cd web
npm run test:run -- src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: FAIL，孤立和缺口两侧 Q0 的实际符号为 `none`，而期望为 `circle`。

- [ ] **Step 3: 实现最小相邻点判断**

将 `buildSeries` 的数据映射和 `qualitySymbol` 改为：

```ts
function buildSeries(series: HvacTrendSeries) {
  return {
    id: series.id,
    name: series.label,
    type: 'line',
    showSymbol: true,
    connectNulls: false,
    smooth: false,
    symbolSize: 7,
    lineStyle: { width: 1.8 },
    emphasis: { focus: 'series' },
    data: series.points.map((point, index, points) => ({
      value: [point.time, point.average],
      symbol: qualitySymbol(point, index, points),
      symbolSize: point.dataQuality === 2 ? 9 : 7,
      trendPoint: point,
      trendSeries: series,
    })),
  }
}

/** 有限平均值才能形成折线；空分隔点和异常数字均视为不可连接。 */
function isFiniteAverage(point: HvacTrendPoint | undefined): boolean {
  return typeof point?.average === 'number' && Number.isFinite(point.average)
}

/** Q1/Q2 始终标记；Q0 仅在两侧都没有有效线段时显示圆点。 */
function qualitySymbol(
  point: HvacTrendPoint,
  index: number,
  points: HvacTrendPoint[],
): string {
  if (!isFiniteAverage(point)) return 'none'
  if (point.dataQuality === 1) return 'circle'
  if (point.dataQuality === 2) return 'diamond'
  if (point.dataQuality !== 0) return 'none'
  return isFiniteAverage(points[index - 1]) || isFiniteAverage(points[index + 1])
    ? 'none'
    : 'circle'
}
```

同步更新 `buildOption` 上方注释，删除“Q0 不显示普通点”的绝对表述，改为“连续 Q0 依靠线段，孤立 Q0 显示圆点”。

- [ ] **Step 4: 运行定向测试并确认通过**

Run:

```powershell
npm run test:run -- src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: PASS，新增孤立、连续和缺口 Q0 用例以及原有质量符号用例全部通过。

- [ ] **Step 5: 提交 Q0 可见性修复**

```powershell
git add -- web/src/components/hvac/HvacTrendChart.vue web/src/components/hvac/HvacTrendChart.test.ts
git diff --cached --check
git commit -m "fix(web): show isolated HVAC Q0 trend points"
```

Expected: Hook 输出 `REPOSITORY_GUARDRAILS_OK`，提交只包含上述两个文件。

---

### Task 2: 恢复纵轴单位、稳定单值范围并增加全空状态

**Files:**
- Modify: `web/src/components/hvac/HvacTrendChart.test.ts:185-220`
- Modify: `web/src/components/hvac/HvacTrendChart.vue:35-205`
- Modify: `web/src/components/hvac/HvacTrendChart.vue:330-430`

**Interfaces:**
- Consumes: `HvacTrendGroup.unit`、每条系列的 `precision` 与 `points[].average`。
- Produces: `hasFiniteGroupValue: ComputedRef<boolean>` 和 `singleValueAxisExtent(group: HvacTrendGroup): { min?: number; max?: number }`，以及 `.trend-chart__empty` DOM 空状态。

- [ ] **Step 1: 写入纵轴、单值范围与全空状态的失败测试**

把现有纵轴断言改为：

```ts
expect(wrapper.find('.trend-chart__axis-unit').exists()).toBe(false)
expect(mocks.setOption.mock.calls[0][0].yAxis).toMatchObject({
  name: '%',
  type: 'value',
})
expect(mocks.setOption.mock.calls[0][0].grid.containLabel).toBe(true)
```

新增单值范围测试：

```ts
it('uses a bounded data-driven extent for a single finite value', () => {
  mount(HvacTrendChart, {
    props: {
      group: {
        unit: '',
        series: [{
          ...group.series[0]!,
          unit: '',
          precision: 2,
          points: [{
            time: 60_000,
            average: 3.2,
            minimum: 3.2,
            maximum: 3.2,
            sampleCount: 1,
            dataQuality: 0,
          }],
        }],
      },
      from: 0,
      to: 300_000,
      resolutionMinutes: 1,
    },
  })

  const yAxis = mocks.setOption.mock.calls[0][0].yAxis
  expect(yAxis.name).toBe('无量纲')
  expect(yAxis.min).toBeCloseTo(2.88)
  expect(yAxis.max).toBeCloseTo(3.52)
})
```

新增全空状态测试：

```ts
it('shows an accessible empty state without inventing an axis extent', () => {
  const wrapper = mount(HvacTrendChart, {
    props: {
      group: {
        ...group,
        series: [{
          ...group.series[0]!,
          points: [{
            time: 60_000,
            average: null,
            minimum: null,
            maximum: null,
            sampleCount: 0,
            dataQuality: null,
          }],
        }],
      },
      from: 0,
      to: 300_000,
      resolutionMinutes: 1,
    },
  })

  expect(wrapper.find('.trend-chart__empty').text()).toBe('当前时段无有效数据')
  expect(wrapper.find('.trend-chart__empty').attributes('role')).toBe('status')
  const yAxis = mocks.setOption.mock.calls[0][0].yAxis
  expect(yAxis.name).toBe('%')
  expect(yAxis.min).toBeUndefined()
  expect(yAxis.max).toBeUndefined()
})
```

- [ ] **Step 2: 运行测试并确认纵轴旧行为失败**

Run:

```powershell
npm run test:run -- src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: FAIL，旧实现仍渲染 `.trend-chart__axis-unit`，没有 `yAxis.name`、单值范围和 `.trend-chart__empty`。

- [ ] **Step 3: 实现纵轴单位和单值范围**

在 `buildOption` 前计算范围并恢复真正的纵轴名称：

```ts
function buildOption(): EChartsCoreOption {
  const reducedMotion = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const axisExtent = singleValueAxisExtent(props.group)
  return {
    animation: !reducedMotion,
    animationDuration: reducedMotion ? 0 : 260,
    color: SERIES_COLORS,
    grid: { left: 24, right: 24, top: 38, bottom: 72, containLabel: true },
    legend: {
      show: false,
      selected: Object.fromEntries(props.group.series.map((series) => [
        series.label,
        isSeriesVisible(series.id),
      ])),
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      backgroundColor: 'rgba(5, 17, 30, 0.96)',
      borderColor: 'rgba(104, 172, 224, 0.28)',
      textStyle: { color: '#dce9f6', fontSize: 12 },
      formatter: formatTooltip,
    },
    xAxis: {
      type: 'time',
      min: props.from,
      max: props.to,
      boundaryGap: false,
      axisLine: { lineStyle: { color: 'rgba(126, 166, 198, 0.28)' } },
      axisLabel: { color: '#71889f', hideOverlap: true },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      name: props.group.unit || '无量纲',
      nameLocation: 'end',
      nameGap: 12,
      nameTextStyle: { color: '#71889f', fontSize: 11 },
      axisLabel: { color: '#71889f' },
      axisLine: { show: false },
      splitLine: { lineStyle: { color: 'rgba(126, 166, 198, 0.10)' } },
      scale: true,
      ...axisExtent,
    },
    dataZoom: [
      { type: 'inside', filterMode: 'none' },
      {
        type: 'slider',
        bottom: 18,
        height: 18,
        borderColor: 'rgba(126, 166, 198, 0.16)',
        backgroundColor: 'rgba(5, 17, 30, 0.62)',
        fillerColor: 'rgba(66, 165, 255, 0.16)',
        handleStyle: { color: '#55b9ff', borderColor: '#55b9ff' },
        textStyle: { color: '#71889f' },
      },
    ],
    series: props.group.series.map(buildSeries),
  }
}

/** 单一有效值以自身和展示精度生成留白；多值继续交给 ECharts 自动缩放。 */
function singleValueAxisExtent(
  group: HvacTrendGroup,
): { min?: number; max?: number } {
  const values = group.series.flatMap((series) => series.points.flatMap((point) =>
    isFiniteAverage(point) ? [point.average as number] : [],
  ))
  if (values.length === 0) return {}
  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  if (minimum !== maximum) return {}
  const maximumPrecision = Math.max(0, ...group.series.map((series) => series.precision))
  const displayStep = 10 ** -maximumPrecision
  const padding = Math.max(Math.abs(minimum) * 0.1, displayStep * 2)
  return { min: minimum - padding, max: maximum + padding }
}
```

- [ ] **Step 4: 实现 DOM 空状态并移除重复单位行**

删除模板中的 `.trend-chart__axis-unit` 段落及对应 CSS，在 Canvas 外增加定位容器：

```vue
<div class="trend-chart__plot">
  <p v-if="!hasFiniteGroupValue" class="trend-chart__empty" role="status">
    当前时段无有效数据
  </p>
  <div
    ref="chartElement"
    class="trend-chart__canvas"
    role="img"
    :aria-label="`${groupTitle}历史趋势图，${seriesSummary}`"
  />
</div>
```

在 `script setup` 中增加：

```ts
const hasFiniteGroupValue = computed(() => props.group.series.some((series) =>
  series.points.some((point) => isFiniteAverage(point)),
))
```

增加样式：

```css
.trend-chart__plot {
  position: relative;
}

.trend-chart__empty {
  position: absolute;
  z-index: 1;
  inset: 38px 24px 72px;
  display: grid;
  place-items: center;
  margin: 0;
  color: #7890a7;
  font-size: 12px;
  pointer-events: none;
}
```

- [ ] **Step 5: 运行定向测试并确认通过**

Run:

```powershell
npm run test:run -- src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: PASS，纵轴、单值范围、空状态及 Task 1 的 Q0 用例全部通过。

- [ ] **Step 6: 提交纵轴与空状态修复**

```powershell
git add -- web/src/components/hvac/HvacTrendChart.vue web/src/components/hvac/HvacTrendChart.test.ts
git diff --cached --check
git commit -m "fix(web): restore HVAC trend axes and empty state"
```

Expected: Hook 输出 `REPOSITORY_GUARDRAILS_OK`，提交只包含上述两个文件。

---

### Task 3: 完整前端验证、人工核对与交付材料

**Files:**
- Verify: `web/src/components/hvac/HvacTrendChart.vue`
- Verify: `web/src/components/hvac/HvacTrendChart.test.ts`
- Verify: `web/src/domain/hvacHistoryTrends.ts`
- Verify: `docs/superpowers/specs/2026-08-11-hvac-trend-chart-visibility-axis-design.md`
- Verify: `docs/superpowers/plans/2026-08-11-hvac-trend-chart-visibility-axis.md`

**Interfaces:**
- Consumes: Task 1 和 Task 2 已提交的组件行为。
- Produces: 可合并分支的自动化证据、注释检查结论、浏览器核对结果和 PR 材料。

- [ ] **Step 1: 运行完整前端验证矩阵**

在 `web/` 目录依次执行：

```powershell
npm run test:run -- src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
npm run test:run
npm run lint
npm run check
npm run build
```

Expected: 所有命令退出码为 0；报告本次实际测试数量，不复用历史数字。

- [ ] **Step 2: 复核注释、范围和差异**

在仓库根目录执行：

```powershell
git diff origin/main...HEAD --check
git diff --stat origin/main...HEAD
git diff --name-status origin/main...HEAD
git status --short --branch
```

Expected: 只出现本任务设计、计划、目录索引、趋势图组件和对应测试；工作区干净。完整阅读 `HvacTrendChart.vue`、测试和 `trendPoints` 的缺口插入说明，确认没有过期的“Q0 一律无标记”注释。

- [ ] **Step 3: 执行浏览器核对或如实记录环境边界**

若本机已有可登录的隔离后端和历史数据，则在 `web/` 启动前端：

```powershell
npm run dev -- --host 127.0.0.1
```

使用浏览器验证：

- 孤立 COP Q0 显示圆点；
- 连续 Q0 不产生密集圆点；
- `%` 与 `W/(m³·h)` 图显示 y 轴单位和数值刻度；
- 全空组显示“当前时段无有效数据”；
- DataZoom、系列显隐和 Tooltip 正常；
- 控制台没有 Vue、ECharts 或 ResizeObserver 错误。

若没有可登录后端或真实历史数据，不新增演示数据、不连接现场设备，也不把单元测试描述成浏览器验收；在交付中明确写“浏览器真实数据核对未执行：缺少隔离运行环境或历史样本”。

- [ ] **Step 4: 推送分支并准备 PR**

```powershell
git push -u origin fix/hvac-trend-chart
```

Expected: 远程分支创建成功，不直接推送 `main`。

PR 标题：

```text
修复 HVAC 历史趋势图孤立点与纵轴显示
```

PR 正文必须包含：

```markdown
## 变更内容
- 仅让没有相邻有效线段的 Q0 点显示圆点，连续 Q0 仍保持简洁折线。
- 恢复 ECharts 纵轴单位和单值范围，并为全空组增加真实空状态。
- 不修改后端数据、质量语义、缺口断线和其他页面。

## 测试
- 列出本次实际执行的定向 Vitest、完整 Vitest、ESLint、类型检查和构建结果。
- 单独列出浏览器核对结果；未执行时写明环境原因。

## 注释检查
- 风险级别：普通。
- 检查范围：完整 HvacTrendChart.vue、对应测试和历史领域适配层缺口契约。
- 结论：更新孤立 Q0 与纵轴边界注释，未增加逐行复述；无过期或误导说明。
```

Expected: PR 材料只陈述实际实现和实际验证，不声称已合并或完成现场验收。
