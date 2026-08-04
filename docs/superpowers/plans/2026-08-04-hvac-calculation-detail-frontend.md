# HVAC 公式计算详情前端接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从四项 HVAC 实时指标卡打开对应分钟的真实计算详情抽屉。

**Architecture:** 前端按 DTO、API、独立 Composable、纯展示抽屉和页面入口分层。Composable 用请求版本隔离竞态，页面切换建筑和卸载时主动关闭详情。

**Tech Stack:** Vue 3、TypeScript、Ant Design Vue、Axios、Vitest、Vue Test Utils、ESLint、Vite。

## Global Constraints

- API 路径必须以 `/hvac` 开头，不能重复 `/api`。
- 前端不得执行公式、填充默认业务值或伪造详情。
- 不修改后端 Java、数据库、全局 HTTP 客户端、历史曲线或 WebSocket。
- 所有生产文件必须完成全文件中文业务注释审查。

---

### Task 1: 计算详情 DTO 与 API 客户端

**Files:**

- Modify: `web/src/types/hvac.ts`
- Modify: `web/src/api/hvac.ts`
- Test: `web/src/api/hvac.test.ts`

**Interfaces:**

- Produces: `HvacCalculationInput`、`HvacCalculationStep`、`HvacCalculationDetail`
- Produces: `getIndicatorCalculationDetail(indicatorId: string, minuteStart: number): Promise<HvacCalculationDetail>`

- [ ] **Step 1: 写 API 失败测试**

  增加包含斜杠和空格的指标 ID，断言请求为 `/hvac/indicators/WCR%2FCOP%2001/calculations/{minuteStart}`，并断言返回响应 `data`。

- [ ] **Step 2: 运行测试确认函数缺失**

  Run: `npm run test:run -- src/api/hvac.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

  Expected: 因 `getIndicatorCalculationDetail` 未导出而失败。

- [ ] **Step 3: 增加 DTO 和客户端**

  DTO 字段逐项匹配后端 `HvacIndicatorDtos.CalculationDetail`。客户端调用：

  ```ts
  `/hvac/indicators/${encodeURIComponent(indicatorId)}/calculations/${minuteStart}`
  ```

- [ ] **Step 4: 运行 API 定向测试**

  Expected: `web/src/api/hvac.test.ts` 全部通过。

### Task 2: 计算详情请求状态层

**Files:**

- Create: `web/src/composables/useHvacCalculationDetail.ts`
- Create: `web/src/composables/useHvacCalculationDetail.test.ts`

**Interfaces:**

- Consumes: `getIndicatorCalculationDetail`
- Produces: `HvacCalculationDetailTarget`、`HvacCalculationDetailError`
- Produces: `visible`、`selected`、`detail`、`loading`、`error`、`localNoData`、`open`、`retry`、`close`

- [ ] **Step 1: 写七类状态测试**

  覆盖成功、无 ID/分钟、HTTP 错误、重试、关闭、A/B 竞态、关闭后的迟到响应。竞态使用可控 Promise，不使用固定延迟。

- [ ] **Step 2: 运行测试确认模块缺失**

  Run: `npm run test:run -- src/composables/useHvacCalculationDetail.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

  Expected: 因 Composable 不存在而失败。

- [ ] **Step 3: 实现请求版本与错误映射**

  `open` 先递增请求版本并清空旧详情；`close` 再次递增版本使在途结果失效。错误对象保留 `status`，文案只读取安全的 `response.data.msg` 或普通 `Error.message`，其余回退通用文案。

- [ ] **Step 4: 运行状态层定向测试**

  Expected: 七类状态全部通过。

### Task 3: 纯展示详情抽屉

**Files:**

- Create: `web/src/components/hvac/HvacCalculationDetailDrawer.vue`
- Create: `web/src/components/hvac/HvacCalculationDetailDrawer.test.ts`

**Interfaces:**

- Consumes: `open`、`target`、`detail`、`loading`、`error`、`localNoData`
- Produces events: `close`、`retry`

- [ ] **Step 1: 写组件状态矩阵测试**

  使用稳定 stub 挂载，覆盖 `SUCCESS`、`MISSING_INPUT`、`INVALID_INPUT`、`ENGINE_ERROR`、`NO_DATA`、本地无目标、`409`、`503`、网络错误、事件和空单位。

- [ ] **Step 2: 运行测试确认组件缺失**

  Run: `npm run test:run -- src/components/hvac/HvacCalculationDetailDrawer.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

  Expected: 因组件不存在而失败。

- [ ] **Step 3: 实现抽屉**

  使用 `a-drawer` 和语义化区块展示标题、摘要、输入、步骤、审计及错误；时间用 `Intl.DateTimeFormat`，数值只格式化不计算；长文本使用 `overflow-wrap: anywhere`。

- [ ] **Step 4: 运行组件定向测试**

  Expected: 状态矩阵全部通过且无 `null`、`undefined` 文案。

### Task 4: 页面接入与契约更新

**Files:**

- Modify: `web/src/pages/HvacDemoPage.vue`
- Test: `web/src/pages/HvacDemoPage.contract.test.ts`

**Interfaces:**

- Consumes: `DashboardIndicatorView`、`useHvacCalculationDetail`、`HvacCalculationDetailDrawer`

- [ ] **Step 1: 更新页面契约为失败测试**

  断言 `useHvacCalculationDetail`、`HvacCalculationDetailDrawer`、`openCalculationDetail`、`@click` 存在；旧详情占位和 `disabled` 不存在；历史曲线占位仍存在；仍无 `formulaMap`、随机数或本地详情常量。

- [ ] **Step 2: 运行页面契约确认失败**

  Run: `npm run test:run -- src/pages/HvacDemoPage.contract.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

- [ ] **Step 3: 接入入口和生命周期**

  指标卡传入 `indicatorId`、`indicatorCode`、`label`、`equipId`、`minuteStart`；建筑选择先关闭详情再调用原 `selectBuilding`；页面卸载关闭详情。卡片增加可见提示与 `focus-visible`，不重做页面。

- [ ] **Step 4: 运行页面和相关回归测试**

  Run: `npm run test:run -- src/pages/HvacDemoPage.contract.test.ts src/composables/useHvacDashboard.test.ts src/domain/hvacDashboard.test.ts --maxWorkers=1 --minWorkers=1 --pool=threads`

### Task 5: 项目状态与注释证据

**Files:**

- Modify: `PROJECT_STATUS.md`

- [ ] **Step 1: 更新当前完成与未完成边界**

  只把真实计算详情前端闭环标为完成；历史曲线、WebSocket、后台管理和现场验收继续保留为未完成。

- [ ] **Step 2: 生成注释审查报告**

  Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 -BaseRef origin/main -HeadRef HEAD`

  Expected: 每个变化生产文件及全部函数进入清单，人工完成全部业务判断。

### Task 6: 完整验证与 Git 交付

**Files:**

- Verify only; no unrelated modifications.

- [ ] **Step 1: 前端全量验证**

  Run in `web`: `npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads`

  Run in `web`: `npm run check`

  Run in `web`: `npm run lint`

  Run in `web`: `npm run build`

- [ ] **Step 2: 后端回归**

  Run: `mvn test`

- [ ] **Step 3: 真实环境验收**

  复用仓库现有 Docker 环境，验证成功详情、无记录、快速切换、建筑切换、请求失败与重试。真实环境不具备时明确记录未验证原因。

- [ ] **Step 4: 范围与差异检查**

  Run: `git status --short`

  Run: `git diff --check`

  Run: `git diff --name-only origin/main...HEAD`

- [ ] **Step 5: 按明确文件暂存、提交和推送**

  不使用 `git add .`。推送 `feature/hvac-calculation-detail`，交付 Compare 链接、PR 标题、完整说明、实际测试数量、注释报告和未验证项。
