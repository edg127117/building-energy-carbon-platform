# Repository Guardrail Line Ending Normalization Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时的实施步骤。文中的复选框表示原计划步骤，不代表当前完成状态；
> 执行任何命令前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试并重新核验。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Repository Guardrail 对 LF、CRLF 和单独 CR 的 PR 正文产生一致的章节与字段校验结果。

**Architecture:** 在 `Test-PrSections` 的输入边界统一换行符，然后复用现有章节提取和单行字段正则。测试同时覆盖 LF/CRLF 的有效正文与空检查范围，证明兼容性提升且门禁没有放宽。

**Tech Stack:** PowerShell、GitHub Actions、仓库现有 Guardrail 测试框架。

## Global Constraints

- 不放宽或删除风险级别、检查范围、结论和生产代码匹配校验。
- 不修改 PR 模板、Java/Vue/TypeScript 生产代码、业务测试或 `PROJECT_STATUS.md`。
- 保留旧内嵌 `comment-audit` 兼容规则。
- 本任务只继续现有 `docs/agents-instruction-layering` 分支和 PR #36。

---

### Task 1: 统一 PR 正文换行并增加回归测试

**Files:**
- Modify: `scripts/Test-RepositoryGuardrails.ps1:111-153`
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1:395-445`
- Modify: `docs/superpowers/README.md`

**Interfaces:**
- Consumes: `Test-PrSections -Body <string> -ProductionFileCount <int>` 接收 GitHub PR 正文。
- Produces: LF、CRLF 和单独 CR 输入共享同一 LF 规范化文本，现有错误码和成功标记保持不变。

- [ ] **Step 1: 增加 CRLF 有效正文和空范围回归场景**

在 LF 有效正文断言之后加入：

```powershell
$crlfBody = $body -replace "`r?`n", "`r`n"
$crlfValid = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $crlfBody }
Assert-True ($crlfValid.ExitCode -eq 0) "CRLF production PR should pass: $($crlfValid.Output)"
Assert-Contains $crlfValid.Output 'REPOSITORY_GUARDRAILS_OK' 'CRLF success marker must be emitted'
Complete-Case 'LF and CRLF production PR bodies both pass'
```

在现有空检查范围断言之后加入：

```powershell
$missingScopeCrlfBody = (New-RiskBody -Scope '') -replace "`r?`n", "`r`n"
$missingScopeCrlf = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $missingScopeCrlfBody }
Assert-True ($missingScopeCrlf.ExitCode -ne 0) 'missing CRLF scope must fail'
Assert-Contains $missingScopeCrlf.Output 'COMMENT_SCOPE_MISSING' 'CRLF scope failure must use the stable error'
```

- [ ] **Step 2: 运行契约测试并确认新有效场景失败**

运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
```

预期：CRLF 有效正文场景失败，输出包含 `COMMENT_SCOPE_MISSING`；既有 LF 场景仍通过。

- [ ] **Step 3: 在解析入口统一换行**

将 `Test-PrSections` 中正文预处理改为：

```powershell
$normalizedBody = $Body.Replace("`r`n", "`n").Replace("`r", "`n")
$withoutInstructions = [regex]::Replace($normalizedBody, '(?s)<!--.*?-->', '').Trim()
```

旧内嵌审计兼容检查同样使用 `$normalizedBody`，其余字段规则和错误码不变。

- [ ] **Step 4: 运行定向和完整回归验证**

依次运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1
```

预期：LF、CRLF 有效正文均通过，LF、CRLF 空范围均返回 `COMMENT_SCOPE_MISSING`；完整仓库和本地 Git 场景全部通过。

- [ ] **Step 5: 重放 GitHub 当前 PR 正文**

从 GitHub 公共 API 读取 PR #36 当前正文，设置为 `PR_BODY` 后运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-RepositoryGuardrails.ps1 -Mode PullRequest -BaseRef origin/main -HeadRef HEAD
```

预期：输出 `REPOSITORY_GUARDRAILS_OK`。

- [ ] **Step 6: 更新历史任务索引并完成静态检查**

在 `docs/superpowers/README.md` 登记本设计和计划，然后运行 PowerShell 语法解析、Markdown 链接检查、`git diff --check` 和 Staged Guardrail。

- [ ] **Step 7: 提交并推送原 PR 分支**

```powershell
git add -- scripts/Test-RepositoryGuardrails.ps1 scripts/tests/Invoke-RepositoryGuardrailTests.ps1 docs/superpowers/README.md docs/superpowers/plans/2026-08-09-guardrail-line-ending-normalization.md
git commit -m "fix(guardrail): normalize PR body line endings"
git push origin docs/agents-instruction-layering
```

推送后确认本地 HEAD 与远程跟踪引用一致，并等待 PR #36 的 `Repository guardrails` 新检查完成。
