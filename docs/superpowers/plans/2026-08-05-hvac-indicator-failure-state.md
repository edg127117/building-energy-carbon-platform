# HVAC Indicator Failure State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让四项 HVAC 指标在计算失败时先显示清晰业务结论，并把原因码和缺失语义键收敛到计算详情抽屉。

**Architecture:** 保持现有 `latest indicators → buildIndicatorViews → HvacDemoPage → useHvacCalculationDetail → Drawer` 数据流。领域映射层补充稳定的状态摘要，页面负责布局、来源分钟和无障碍名称，抽屉负责完整技术诊断；不修改后端或完整率算法。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Test Utils、Ant Design Vue、Lucide Vue、CSS media queries。

## Global Constraints

- 只实施设计文档中的任务一，不修改数据新鲜度和测点完整率语义。
- 不修改后端 API、DTO、公式引擎、分钟聚合或数据质量流程。
- 不在浏览器重算指标、推测故障原因或生成默认值。
- 原因码与缺失语义键必须保留在详情抽屉，不能丢失诊断事实。
- 桌面横向区四列、中屏两列、手机单列，真实长文案不能横向溢出。

---

### Task 1: 指标状态业务摘要

**Files:**
- Modify: `web/src/domain/hvacDashboard.ts`
- Test: `web/src/domain/hvacDashboard.test.ts`

**Interfaces:**
- Consumes: `LatestIndicator.status`、`LatestIndicator.missingInputs`。
- Produces: `DashboardIndicatorView.summaryText: string` 和 `DashboardIndicatorView.supportingText: string | null`，供页面卡片直接展示。

- [ ] **Step 1: 写失败测试**

在 `hvacDashboard.test.ts` 的指标映射用例中断言：成功状态摘要为“最新分钟计算完成”，`MISSING_INPUT` 摘要为“当前分钟缺少必要输入”，并且只生成“缺少 1 项必要输入”而不把 `PUMP_POWER` 拼入卡片文案。

```ts
expect(views[0].summaryText).toBe('最新分钟计算完成')
expect(views[0].supportingText).toBeNull()
expect(views[2].summaryText).toBe('当前分钟缺少必要输入')
expect(views[2].supportingText).toBe('缺少 1 项必要输入')
expect(views[2].supportingText).not.toContain('PUMP_POWER')
```

- [ ] **Step 2: 运行定向测试并确认失败**

Run: `npm run test:run -- src/domain/hvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

Expected: FAIL，提示 `summaryText` 或 `supportingText` 不存在。

- [ ] **Step 3: 实现状态映射**

在 `DashboardIndicatorView` 增加两个字段，并使用纯函数生成业务文案：

```ts
function indicatorSummary(status: string): string {
  const summaries: Record<string, string> = {
    SUCCESS: '最新分钟计算完成',
    MISSING_INPUT: '当前分钟缺少必要输入',
    INVALID_INPUT: '当前分钟输入数据无效',
    ENGINE_ERROR: '当前分钟计算未完成',
    NO_DATA: '暂无可用计算记录',
  }
  return summaries[status] ?? '当前指标暂不可用'
}

function indicatorSupportingText(
  status: string,
  missingInputs: string[],
): string | null {
  if (status !== 'MISSING_INPUT' || missingInputs.length === 0) return null
  return `缺少 ${missingInputs.length} 项必要输入`
}
```

`buildIndicatorViews` 必须继续原样保留 `reasonCode` 和 `missingInputs`，只把新文案字段附加到展示模型。

- [ ] **Step 4: 运行定向测试并确认通过**

Run: `npm run test:run -- src/domain/hvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

Expected: `hvacDashboard.test.ts` 全部通过。

### Task 2: 重组指标卡与响应式布局

**Files:**
- Modify: `web/src/pages/HvacDemoPage.vue`
- Test: `web/src/pages/HvacDemoPage.contract.test.ts`

**Interfaces:**
- Consumes: `DashboardIndicatorView.summaryText`、`supportingText`、`statusLabel`、`minuteStart`。
- Produces: 不暴露原因码和语义键的指标卡，以及包含状态的无障碍名称。

- [ ] **Step 1: 写失败契约测试**

将原来要求页面直接包含 `missingInputs.join` 的断言改为以下边界：

```ts
expect(source).toContain('card.summaryText')
expect(source).toContain('card.supportingText')
expect(source).toContain('formatIndicatorMinute')
expect(source).toContain('card.statusLabel')
expect(source).not.toContain("card.missingInputs.join('、')")
expect(source).not.toContain('{{ card.reasonCode }}')
```

- [ ] **Step 2: 运行定向测试并确认失败**

Run: `npm run test:run -- src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

Expected: FAIL，页面尚未包含新的摘要和来源分钟结构。

- [ ] **Step 3: 实现模板、时间格式和无障碍名称**

卡片模板采用标题状态、值、业务摘要和页脚四层结构：

```vue
<span class="indicator-card-head">
  <span class="indicator-identity">
    <span class="indicator-icon"><component :is="card.icon" :size="19" /></span>
    <span class="indicator-label">{{ card.label }}</span>
  </span>
  <span class="indicator-status">{{ card.statusLabel }}</span>
</span>
<span class="indicator-number">{{ card.displayValue }}<small>{{ card.unit }}</small></span>
<span class="indicator-message">
  <strong>{{ card.summaryText }}</strong>
  <small v-if="card.supportingText">{{ card.supportingText }}</small>
</span>
<span class="indicator-footer">
  <small>{{ formatIndicatorMinute(card.minuteStart) }}</small>
  <span class="indicator-detail-hint">查看计算详情</span>
</span>
```

按钮的 `aria-label` 使用：

```vue
:aria-label="`${card.label}，${card.statusLabel}，查看计算详情`"
```

来源分钟格式函数只格式化后端时间，不判断数据是否过期：

```ts
function formatIndicatorMinute(minuteStart: number | null): string {
  if (minuteStart === null) return '暂无来源分钟'
  return `来源分钟 ${new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(minuteStart))}`
}
```

- [ ] **Step 4: 调整 CSS**

用纵向内容结构替换当前横向三段挤压布局；指标卡正文最小字号为 11px，值保持清晰层级。`@media (max-width: 1450px)` 保持四列，`@media (max-width: 900px)` 两列，并在 `@media (max-width: 560px)` 明确单列。所有 flex/grid 子项设置 `min-width: 0`，长文案允许换行。

- [ ] **Step 5: 运行页面定向测试**

Run: `npm run test:run -- src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

Expected: 页面契约测试全部通过。

### Task 3: 优化详情抽屉失败结论

**Files:**
- Modify: `web/src/components/hvac/HvacCalculationDetailDrawer.vue`
- Test: `web/src/components/hvac/HvacCalculationDetailDrawer.test.ts`

**Interfaces:**
- Consumes: 详情 API 已有的 `status`、`reasonCode` 和 `missingInputs`。
- Produces: 业务结论优先、技术诊断完整的失败详情。

- [ ] **Step 1: 写失败组件测试**

在缺少输入用例中增加：

```ts
expect(wrapper.text()).toContain('本分钟未产生指标值')
expect(wrapper.text()).toContain('异常详情')
expect(wrapper.text()).toContain('原因码')
expect(wrapper.text()).toContain('缺失语义键')
```

- [ ] **Step 2: 运行定向测试并确认失败**

Run: `npm run test:run -- src/components/hvac/HvacCalculationDetailDrawer.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

Expected: FAIL，旧抽屉仍显示“失败审计”，且没有业务结论。

- [ ] **Step 3: 实现异常详情结构**

将失败区域标题改为“异常详情”，并在诊断字段前加入：

```vue
<p class="audit-summary">
  本分钟未产生指标值，以下信息用于定位数据或公式问题。
</p>
```

保留原因码和缺失语义键原值；为 `dd` 和缺失键标签设置 `overflow-wrap: anywhere`，避免横向滚动。

- [ ] **Step 4: 运行组件测试并确认通过**

Run: `npm run test:run -- src/components/hvac/HvacCalculationDetailDrawer.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

Expected: 抽屉组件测试全部通过。

### Task 4: 状态文档、质量验证与交付

**Files:**
- Modify: `PROJECT_STATUS.md`
- Verify: 本计划涉及的全部生产文件和测试文件。

**Interfaces:**
- Consumes: 前三项任务的最终代码。
- Produces: 可合并分支、测试证据、浏览器截图验收结果和完整注释审计报告。

- [ ] **Step 1: 更新项目状态**

将指标详情完成项更新为：失败卡片只展示业务摘要和缺失数量，原因码及缺失语义键集中到详情抽屉；不得把数据新鲜度语义描述为已完成。

- [ ] **Step 2: 执行前端完整验证**

Run:

```powershell
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run lint
npm run build
```

Expected: 所有命令退出码为 0；如有跳过项，如实记录。

- [ ] **Step 3: 运行设计检测和浏览器验收**

Run: `node C:\Users\yang\.codex\skills\impeccable\scripts\detect.mjs --json D:\word\iot-platform-demo\web\src\pages\HvacDemoPage.vue D:\word\iot-platform-demo\web\src\components\hvac\HvacCalculationDetailDrawer.vue`

在本地页面分别检查桌面与手机尺寸，确认失败卡片无技术键堆叠、无横向溢出，详情抽屉保留完整诊断字段。只进行一次集中修复和一次确认复查。

- [ ] **Step 4: 完成生产文件注释审计**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 -BaseRef origin/main -HeadRef HEAD
```

人工复核 `hvacDashboard.ts`、`HvacDemoPage.vue` 和 `HvacCalculationDetailDrawer.vue` 的完整函数清单、业务用途、输入来源和展示边界，不保留“待填写”。

- [ ] **Step 5: 精确暂存、提交与推送**

只暂存本设计、实施计划、三份生产文件、三份测试文件和 `PROJECT_STATUS.md`，执行 `git diff --cached --check` 后提交：

```powershell
git commit -m "fix(hvac): clarify indicator failure states"
git push -u origin fix/hvac-indicator-failure-state
```

推送后由 AI 创建 PR，并在 PR 中明确任务二未包含。
