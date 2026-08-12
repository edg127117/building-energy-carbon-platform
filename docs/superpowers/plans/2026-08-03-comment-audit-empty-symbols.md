# Comment Audit Empty Symbols Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时的实施步骤。文中的复选框表示原计划步骤，不代表当前完成状态；
> 执行任何命令前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试并重新核验。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让注释扫描器把无可识别方法的变化生产文件稳定输出为零长度符号数组，并防止 Repository Guardrails 访问不存在的 `id`。

**Architecture:** 修复放在扫描数据的产生端，不放宽校验器合同。回归测试在临时 Git 仓库加入一个仅含 TypeScript 接口、没有函数的变化生产文件，同时验证 JSON 结构和完整 PR 合同。

**Tech Stack:** PowerShell、Git 临时测试仓库、GitHub Actions Repository Guardrails。

## Global Constraints

- 只修改扫描器、对应回归测试和本计划文档，不修改业务代码。
- 无方法文件仍必须填写文件级职责、上下游、数据源、结果消费者和注释时效。
- 不修改审计表格式、允许的判定值或失败规则。
- 不通过伪造方法记录规避空集合问题。

---

### Task 1: 添加空符号集合回归场景

**Files:**
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`

**Interfaces:**
- Consumes: `New-CommentAuditReport.ps1 -Format Json` 输出的每文件 `symbols` 属性。
- Produces: 一个包含 `web/src/types/ContractTypes.ts` 的合同测试夹具；该文件发生变化但扫描结果必须为零个符号。

- [ ] **Step 1: 在合同测试夹具创建无函数生产文件**

在 `New-ContractFixture` 的基线提交前写入：

```powershell
Set-Utf8File $root 'web/src/types/ContractTypes.ts' @'
export interface ContractRecord {
  id: string
}
'@
```

切换任务分支后，把接口更新为：

```powershell
Set-Utf8File $root 'web/src/types/ContractTypes.ts' @'
export interface ContractRecord {
  id: string
  name?: string
}
'@
```

该文件属于变化生产 TypeScript 文件，但不包含扫描器识别的函数、方法或生命周期入口。

- [ ] **Step 2: 添加 JSON 空数组断言**

在 `Invoke-RepositoryContractTests` 生成 `$body` 后、执行完整合同校验前加入：

```powershell
$scannerResult = Invoke-PowerShellScript $scannerScript @('-BaseRef', 'main', '-HeadRef', 'HEAD', '-Format', 'Json') $root $null
Assert-True ($scannerResult.ExitCode -eq 0) "empty-symbol scanner JSON should succeed: $($scannerResult.Output)"
$reports = $scannerResult.Output | ConvertFrom-Json
$emptyReports = @($reports | Where-Object { $_.path -eq 'web/src/types/ContractTypes.ts' })
Assert-True ($emptyReports.Count -eq 1) 'changed type-only production file must be listed'
$emptySymbolCount = @($emptyReports[0].symbols).Count
$emptySymbolsJson = $emptyReports[0].symbols | ConvertTo-Json -Depth 4 -Compress
Assert-True ($emptySymbolCount -eq 0) "type-only production file symbols must be an empty array, actual: $emptySymbolsJson"
```

- [ ] **Step 3: 运行合同测试并确认先失败**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
```

Expected: FAIL，输出包含 `type-only production file symbols must be an empty array`；若越过该断言，现有实现会在完整合同校验中出现 `property 'id' cannot be found`。

### Task 2: 从生成端稳定输出空数组

**Files:**
- Modify: `scripts/New-CommentAuditReport.ps1`
- Test: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`

**Interfaces:**
- Consumes: `Get-JavaSymbols` 或 `Get-FrontendSymbols` 的零个或多个管道输出。
- Produces: 每个报告对象的 `symbols: object[]`；无符号时 JSON 必须为 `[]`。

- [ ] **Step 1: 用数组子表达式包住完整扫描分支**

把原有赋值替换为：

```powershell
$symbols = @(
    if ($file.path -match '\.java$') {
        Get-JavaSymbols $content
    }
    else {
        Get-FrontendSymbols $content
    }
)
```

数组子表达式捕获 `if` 的全部管道输出；零个结果保持为 `object[]`，不会因 `if` 无输出而变成 `$null`。

- [ ] **Step 2: 运行定向合同测试并确认通过**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
```

Expected: PASS，结尾输出 `GUARDRAIL_TESTS_PASSED`，完整 PR 合同、缺章节、缺私有方法、弱理由和文档-only 用例均保持预期。

- [ ] **Step 3: 运行扫描器与全部 Guardrails 回归**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CommentScanner
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group All
git diff --check
```

Expected: 两组脚本测试均输出 `GUARDRAIL_TESTS_PASSED`，`git diff --check` 无输出且退出码为 0。

- [ ] **Step 4: 提交实现与测试**

```powershell
git add -- scripts/New-CommentAuditReport.ps1 scripts/tests/Invoke-RepositoryGuardrailTests.ps1 docs/superpowers/plans/2026-08-03-comment-audit-empty-symbols.md
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(workflow): handle empty comment audit symbols"
```

Expected: 暂存文件只包含上述三个路径，提交成功且本地 Hook 输出 `REPOSITORY_GUARDRAILS_OK`。

### Task 3: 推送并验证 PR

**Files:**
- No repository file changes.

**Interfaces:**
- Consumes: 当前分支提交和 PR #25 已存在的完整审计正文。
- Produces: 更新后的远程任务分支及通过的 GitHub Repository Guardrails 检查。

- [ ] **Step 1: 推送当前任务分支**

```powershell
git push origin docs/comment-hvac-query-frontend
```

Expected: 远程分支更新成功；不得推送或合并 `main`。

- [ ] **Step 2: 验证 GitHub Checks**

查看 PR #25 最新提交对应的 `Repository Guardrails`、`Backend CI` 和 `Frontend CI`。Expected:

- `Repository Guardrails` 不再出现 `property 'id' cannot be found`，结论为 success。
- 后端、前端检查结果按 GitHub 实际状态如实报告；任何失败都不声称任务可合并。
- PR 无冲突，仍处于等待用户审核并合并状态。
