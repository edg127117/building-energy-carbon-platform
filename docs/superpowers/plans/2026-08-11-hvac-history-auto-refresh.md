# HVAC History Auto Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让相对时间范围内的 HVAC 真实历史趋势在页面可见时每 30 秒自动重查，页面从后台返回时立即补查一次，并在移动窗口刷新失败时保留上次成功曲线。

**Architecture:** `HvacHistoryPanel.vue` 只负责浏览器可见性和定时器生命周期，继续通过 composable 的 `refresh()` 触发查询；`useHvacHistoryTrends.ts` 把稳定展示条件与每次移动的 `[from,to)` 分离，保持现有请求版本隔离和 TDengine 权威结果。WebSocket、实时卡片、后端 API 与图表纵轴逻辑不变。

**Tech Stack:** Vue 3 Composition API、TypeScript、Vitest、Vue Test Utils、现有 HVAC 历史 REST API。

## Global Constraints

- 只修改历史趋势自动刷新、直接测试和对应项目文档，不修改后端、TDengine、WebSocket 或实时卡片刷新。
- `1h`、`6h`、`24h`、`7d` 在页面可见时每 30 秒重查；页面转为不可见时停止定时器，重新可见时立即补查并从该时刻重启 30 秒周期；`custom` 不自动刷新。
- 自动周期遇到空上下文、空测点选择或已有请求时跳过，不排队。
- 前端不拼接实时值、不计算指标、不生成假历史，历史接口返回仍是唯一曲线事实。
- 同一展示条件刷新失败保留旧曲线；建筑、模式、预设或选择变化继续立即清空旧条件。
- Vue 生产代码注释说明时间语义、请求副作用和清理边界，不逐行翻译。

---

### Task 1: Stabilize moving-window refresh state

**Files:**
- Modify: `web/src/composables/useHvacHistoryTrends.test.ts`
- Modify: `web/src/composables/useHvacHistoryTrends.ts`

**Interfaces:**
- Consumes: `refresh(): Promise<void>`、`presetRange(...)` 和现有 `requestVersion` 竞态隔离。
- Produces: 相对窗口每次使用新时间戳请求，但同一模式、建筑、ID 集合和预设共享稳定展示条件键。

- [ ] **Step 1: Write the moving-window failure test**

把现有“manual refresh fails”用例改为先成功加载，再把 `now` 推进一分钟并让刷新失败：

```ts
it('keeps relative-window data as stale when its refreshed range advances and fails', async () => {
  const api = dependencies()
  const state = useHvacHistoryTrends(api)
  await state.setBuildingContext({
    buildingId: 'BLD001', indicatorIds: ['I1'], points: snapshotPoints(),
  })
  api.now.mockReturnValue(NOW + 60_000)
  api.getIndicatorTrends.mockRejectedValueOnce({
    response: { status: 503, data: { msg: 'sensitive' } },
  })

  await state.refresh()

  expect(api.getIndicatorTrends).toHaveBeenLastCalledWith(
    'BLD001', ['I1'], NOW - 86_400_000 + 60_000, NOW + 60_000,
  )
  expect(state.groups.value).not.toEqual([])
  expect(state.stale.value).toBe(true)
  expect(state.error.value).toEqual({
    status: 503,
    message: '历史趋势暂不可用，请稍后重试',
  })
})
```

- [ ] **Step 2: Run the focused test and confirm the old exact timestamp key fails**

Run:

```powershell
npm run test:run -- src/composables/useHvacHistoryTrends.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: the new test fails because the moved `[from,to)` no longer equals `activeConditionKey`, so old groups are cleared and `stale` remains false.

- [ ] **Step 3: Add a stable display scope key**

Extend `HistoryRequest` with `scopeKey: string`. In `buildCurrentRequest()` create it from logical conditions, retaining exact timestamps only for a custom range:

```ts
const scopeKey = JSON.stringify({
  mode: mode.value,
  buildingId,
  ids,
  preset: preset.value,
  customFrom: preset.value === 'custom' ? range.from : null,
  customTo: preset.value === 'custom' ? range.to : null,
})
return {
  mode: mode.value,
  buildingId,
  ids: [...ids],
  from: range.from,
  to: range.to,
  scopeKey,
}
```

In `runQuery()` compare and store `request.scopeKey` instead of serializing the complete moving request. Keep `from` and `to` unchanged when calling the API. Update the composable responsibility comment to state that relative timestamps move while the logical display scope remains stable.

- [ ] **Step 4: Run the composable tests**

Run the same focused command. Expected: all `useHvacHistoryTrends` tests pass, including moved-window stale preservation, changed-condition clearing and late-response isolation.

- [ ] **Step 5: Commit the state fix**

```powershell
git add -- web/src/composables/useHvacHistoryTrends.ts web/src/composables/useHvacHistoryTrends.test.ts
git diff --cached --check
git commit -m "fix(web): preserve HVAC trends during refresh"
```

---

### Task 2: Add visibility-aware 30-second auto refresh

**Files:**
- Modify: `web/src/components/hvac/HvacHistoryPanel.test.ts`
- Modify: `web/src/components/hvac/HvacHistoryPanel.vue`

**Interfaces:**
- Consumes: composable refs `mode`、`preset`、`selectedPointIds`、`loading` and method `refresh()`.
- Produces: one browser interval owned and cleaned by `HvacHistoryPanel`; it never calls the API directly.

- [ ] **Step 1: Add fake-timer lifecycle tests**

Import `afterEach` and add cleanup:

```ts
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})
```

Add the successful interval and cleanup case:

```ts
it('auto refreshes a visible relative range every 30 seconds and stops on unmount', async () => {
  vi.useFakeTimers()
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  const wrapper = mountPanel()

  await vi.advanceTimersByTimeAsync(30_000)
  expect(holder.state.refresh).toHaveBeenCalledTimes(1)

  wrapper.unmount()
  await vi.advanceTimersByTimeAsync(30_000)
  expect(holder.state.refresh).toHaveBeenCalledTimes(1)
})
```

Add one skip-matrix case that advances separate 30-second periods for `custom`、`loading`、hidden page and point mode with no selected IDs, then restores a visible indicator state and expects exactly one refresh.

Add a visibility lifecycle case that mounts while hidden, confirms no timed refresh, dispatches `visibilitychange` after restoring `visible`, expects one immediate refresh, then verifies that the next refresh occurs exactly 30 seconds later. Unmount the component and confirm neither the listener nor timer can trigger another refresh.

- [ ] **Step 2: Run the panel tests and confirm no interval exists**

Run:

```powershell
npm run test:run -- src/components/hvac/HvacHistoryPanel.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: new auto-refresh assertions fail because the component only supports manual refresh.

- [ ] **Step 3: Implement the panel-owned timer**

Import `onMounted`, declare the interval and gate the current state. Keep timer ownership and the visibility listener inside the panel:

```ts
const HISTORY_AUTO_REFRESH_MS = 30_000
let historyRefreshTimer: number | null = null

function canAutoRefresh(): boolean {
  if (document.visibilityState !== 'visible') return false
  if (!props.buildingId || preset.value === 'custom' || loading.value) return false
  if (mode.value === 'indicators') return indicatorIds.value.length > 0
  return selectedPointIds.value.length > 0
}

function stopAutoRefreshTimer(): void {
  if (historyRefreshTimer !== null) window.clearInterval(historyRefreshTimer)
  historyRefreshTimer = null
}

function startAutoRefreshTimer(): void {
  stopAutoRefreshTimer()
  if (document.visibilityState !== 'visible') return
  historyRefreshTimer = window.setInterval(() => {
    if (canAutoRefresh()) void refresh()
  }, HISTORY_AUTO_REFRESH_MS)
}

function handleVisibilityChange(): void {
  if (document.visibilityState !== 'visible') {
    stopAutoRefreshTimer()
    return
  }
  if (canAutoRefresh()) void refresh()
  startAutoRefreshTimer()
}

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  startAutoRefreshTimer()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopAutoRefreshTimer()
  dispose()
})
```

Replace the old direct `onBeforeUnmount(dispose)` registration. Add one concise Chinese comment explaining that fixed custom ranges and background tabs do not continuously scan TDengine, and that returning to the page performs one authoritative catch-up query before restarting the cadence.

- [ ] **Step 4: Run both focused suites**

```powershell
npm run test:run -- src/components/hvac/HvacHistoryPanel.test.ts src/composables/useHvacHistoryTrends.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
```

Expected: both files pass; hidden pages own no running interval, returning visible refreshes immediately, the cadence restarts from the return time, and cleanup leaves no listener or pending refresh after unmount.

- [ ] **Step 5: Commit the auto-refresh behavior**

```powershell
git add -- web/src/components/hvac/HvacHistoryPanel.vue web/src/components/hvac/HvacHistoryPanel.test.ts
git diff --cached --check
git commit -m "feat(web): auto refresh HVAC history trends"
```

---

### Task 3: Synchronize current documentation and deliver

**Files:**
- Modify: `PROJECT_GUIDE.md`
- Modify: `PROJECT_STATUS.md`
- Modify: `docs/superpowers/README.md`
- Verify: `docs/superpowers/specs/2026-08-11-hvac-history-auto-refresh-design.md`
- Verify: `docs/superpowers/plans/2026-08-11-hvac-history-auto-refresh.md`

**Interfaces:**
- Consumes: implemented timer and stable scope behavior from Tasks 1–2.
- Produces: current project facts, complete local verification evidence and a reviewable task branch.

- [ ] **Step 1: Update current facts**

In `PROJECT_GUIDE.md` section 4.3, state that history trends use a separate visibility-aware 30-second authoritative query for relative ranges, immediately catch up when the page returns visible, and do not consume WebSocket values. In `PROJECT_STATUS.md` section 3.3, record the same implemented boundary and failure preservation. Change the README row to link this plan and mark it “成对历史记录”.

- [ ] **Step 2: Run focused and complete frontend verification**

```powershell
npm run test:run -- src/components/hvac/HvacHistoryPanel.test.ts src/composables/useHvacHistoryTrends.test.ts src/components/hvac/HvacTrendChart.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads
$env:VITE_API_BASE=' '; $env:VITE_WS_BASE=' '; npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run lint
npm run check
$env:VITE_API_BASE=' '; $env:VITE_WS_BASE=' '; npm run build
```

Expected: all commands exit 0. Record any existing build warning separately; do not describe warnings as failures or suppress them.

- [ ] **Step 3: Perform browser verification**

Start or reuse the current local backend/frontend. With a relative preset and visible page, observe at least one 30-second interval and confirm “最近成功” advances without clicking “刷新趋势”; hide the page and return, then confirm one immediate catch-up query and a fresh 30-second cadence; switch to a custom range and confirm the timestamp does not advance automatically. Confirm the chart still shows backend data, gaps, quality markers and data-driven Y-axis. Do not insert synthetic production data.

- [ ] **Step 4: Review comments and repository scope**

Inspect full changed production files and the history request call chain. Risk level is high for time semantics and request concurrency. Confirm comments describe timer ownership, TDengine scan avoidance, stable logical scope, moving timestamps, failure preservation and cleanup; remove stale wording that says history never joins any 30-second refresh. Run:

```powershell
git diff --check
git diff --name-status origin/main...HEAD
git status --short --branch
```

Expected: only this design/plan, two production frontend files, two tests and the three routed documentation files appear.

- [ ] **Step 5: Commit documentation and push the task branch**

```powershell
git add -- PROJECT_GUIDE.md PROJECT_STATUS.md docs/superpowers/README.md docs/superpowers/plans/2026-08-11-hvac-history-auto-refresh.md
git diff --cached --check
git commit -m "docs: record HVAC history auto refresh"
git push -u origin feature/hvac-history-auto-refresh
```

Prepare a PR to `main` with actual commands and results, explicit non-goals, the initial/final browser state, and a high-risk comment check covering time semantics and request concurrency.

## Plan Self-Review

- Spec coverage: moving relative ranges, 30-second visible refresh, hidden stop, visible catch-up and cadence restart, custom/empty/loading skips, cleanup, stale preservation, unchanged WebSocket/backend/axis boundaries, tests, docs, browser and Git delivery all map to explicit steps.
- Placeholder scan: every step contains an exact file, command, expected result and implementation boundary.
- Type consistency: both tasks use the existing `refresh(): Promise<void>` and refs returned by `useHvacHistoryTrends`; the new `scopeKey` remains internal to `HistoryRequest`.
