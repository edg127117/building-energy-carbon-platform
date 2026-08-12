# Document State Guardrail Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保存任务当时的实施步骤。复选框只记录原计划，不代表当前完成状态；执行前必须核对
> [历史任务目录](../README.md)、[项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。

**Goal:** 清除当前有效文档中的过期完成状态，并通过 Repository Guardrails 阻止历史文件状态标识和 PR 文档同步证据再次缺失。

**Architecture:** `PROJECT_STATUS.md` 继续作为唯一动态状态入口，设计冻结书只保留稳定边界，历史设计和计划统一使用可独立识别的顶部状态块。现有 PowerShell Guardrail 增加全目录历史文档契约检查和 PR“文档同步”字段检查，由当前测试框架覆盖成功、失败和换行兼容路径。

**Tech Stack:** Markdown、PowerShell 7 / Windows PowerShell 5.1、Git、GitHub Actions、现有 Repository Guardrails 测试框架。

## Global Constraints

- 不修改 Java、Vue、TypeScript 生产代码和业务行为。
- 不改写历史设计或计划正文，不回填历史复选框，只补统一历史状态块。
- `PROJECT_STATUS.md` 是动态完成状态、风险、技术债和下一步的唯一入口。
- Guardrail 只强制可确定的结构事实，不使用关键词自动猜测业务是否完成。
- 普通自动化不连接 MySQL、TDengine、Redis、MQTT、现场设备或第三方服务。
- 暂存必须明确列出文件；提交前运行范围匹配的 PowerShell、链接和 Git 差异检查。

---

### Task 1: Normalize current and historical document boundaries

**Files:**
- Modify: `docs/设计冻结书-V1.0-19测点.md:836`
- Modify: `PROJECT_STATUS.md:106`
- Modify: `docs/superpowers/README.md`
- Modify: every `docs/superpowers/specs/*.md` missing the standard design history header
- Modify: every `docs/superpowers/plans/*.md` missing the standard plan history header

**Interfaces:**
- Consumes: `PROJECT_STATUS.md` as the only dynamic status source.
- Produces: every historical file contains the exact type marker and links consumed by Task 2.

- [ ] **Step 1: Capture the failing baseline**

Run a PowerShell scan that checks every spec for `文档状态：历史任务设计记录`, every plan for
`文档状态：历史任务实施计划`, and both file types for `../README.md` plus
`../../../PROJECT_STATUS.md` in the first ten lines.

Expected: FAIL and list the 33 known files missing the complete contract.

- [ ] **Step 2: Remove dynamic progress from the frozen design**

Replace the current capability table in chapter 11 with stable text equivalent to:

```markdown
## 第十一章 能力边界与当前状态入口

本文冻结 V1 的中央空调采集、分析和展示范围，不在此重复维护完成进度。
已完成、未完成、风险、技术债和下一步统一查看项目状态入口 `../PROJECT_STATUS.md`。

V1 只包含上行采集、数据质量、指标计算、受保护查询和展示；不包含 HVAC 下行控制。
第十二章是历史排期，不是当前待办清单。
```

- [ ] **Step 3: Normalize historical spec headers**

For every non-compliant spec, insert immediately after the title:

```markdown
> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。
```

Do not alter the existing body or its original status paragraph.

- [ ] **Step 4: Normalize historical plan headers**

For every non-compliant plan, insert immediately after the title:

```markdown
> **文档状态：历史任务实施计划**
>
> 本文保留任务当时的实施步骤。文中的复选框表示原计划步骤，不代表当前完成状态；
> 执行任何命令前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试并重新核验。
```

Do not check historical boxes or rewrite commands.

- [ ] **Step 5: Align current document-health statements**

Update `PROJECT_STATUS.md` and `docs/superpowers/README.md` only where necessary so they accurately
state that the frozen design has no dynamic progress table and all historical files carry a standard
warning. Do not change business completion claims.

- [ ] **Step 6: Verify the normalized documents**

Run the baseline scan again and validate Markdown relative links.

Expected: all historical files pass; no broken relative links; frozen design chapter 11 contains no
claims such as “前端尚未消费 WebSocket” or “只接收 device/data/up”.

- [ ] **Step 7: Commit the document normalization**

Stage only the frozen design, current document-health files, central history index, and normalized
historical specs/plans. Review `git diff --cached --stat` and the representative headers before:

```powershell
git commit -m "docs: normalize project status boundaries"
```

Expected: commit succeeds and the existing staged Guardrail emits `REPOSITORY_GUARDRAILS_OK`.

---

### Task 2: Add failing Guardrail contract tests

**Files:**
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1:304`

**Interfaces:**
- Consumes: `New-TestRepository`, `Set-Utf8File`, `Commit-All`, `Invoke-PowerShellScript`, and the
  exact historical header contract from Task 1.
- Produces: failing tests for `Test-HistoricalDocumentContracts` and PR document-sync validation.

- [ ] **Step 1: Add valid historical fixtures to `New-ContractFixture`**

Create one spec and one plan containing the exact headers from Task 1 so the fixture represents a
compliant repository before each negative case.

- [ ] **Step 2: Extend `New-RiskBody` with document-sync fields**

Add parameters and body content equivalent to:

```powershell
[string]$StatusImpact = '有',
[string]$DocumentScope = '核对 PROJECT_STATUS.md、当前代码和测试。',
[string]$DocumentResult = '已同步当前项目状态。'
```

```markdown
## 文档同步
状态影响：$StatusImpact
检查范围：$DocumentScope
结论：$DocumentResult
```

- [ ] **Step 3: Add PR document-sync negative cases**

Verify these stable failures:

- missing section → `PR_SECTION_MISSING: 文档同步`
- empty or invalid status impact → `DOCUMENT_STATUS_IMPACT_MISSING`
- empty scope → `DOCUMENT_SCOPE_MISSING`
- empty result → `DOCUMENT_RESULT_MISSING`

Also require LF, CRLF, and CR valid bodies to continue passing.

- [ ] **Step 4: Add historical document negative cases**

For isolated test repositories, cover:

- wrong or absent type marker → `HISTORICAL_DOC_STATUS_MISSING`
- absent `../README.md` → `HISTORICAL_DOC_INDEX_LINK_MISSING`
- absent `../../../PROJECT_STATUS.md` → `HISTORICAL_DOC_CURRENT_STATUS_LINK_MISSING`

Run both `Staged` and `PullRequest` modes where appropriate so local Hook and CI behavior remain aligned.

- [ ] **Step 5: Update CI contract assertions**

Require the PR template to contain `## 文档同步` and its three fields, and require the Guardrail script
to retain all new stable error codes.

- [ ] **Step 6: Run the focused tests and confirm failure**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
```

Expected: FAIL because the production Guardrail does not yet enforce historical documents and PR
document-sync fields.

---

### Task 3: Implement the Guardrail and workflow documentation

**Files:**
- Modify: `scripts/Test-RepositoryGuardrails.ps1`
- Modify: `.github/pull_request_template.md`
- Modify: `docs/development/repository-guardrails.md`
- Test: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`

**Interfaces:**
- Consumes: exact error codes and fixtures introduced by Task 2.
- Produces: `Test-HistoricalDocumentContracts`, extended `Test-PrSections`, and documented PR contract.

- [ ] **Step 1: Implement historical document enumeration**

Add a helper that uses `git ls-files` for `docs/superpowers/specs/*.md` and
`docs/superpowers/plans/*.md`. In `Staged` mode read staged content for changed paths and working-tree
content for unchanged tracked files; in `PullRequest` mode read the checked-out head tree. Normalize
all paths to `/` before matching.

- [ ] **Step 2: Implement the exact historical header checks**

Inspect the first ten lines of each historical file and add the stable errors from Task 2 when the
type marker, history index link, or current project status link is missing. Run this check in both
Guardrail modes before the final error emission.

- [ ] **Step 3: Extend PR section validation**

Require `文档同步` alongside the existing three headings. Parse the section after line-ending
normalization and enforce:

```text
状态影响：有|无
检查范围：non-empty single line
结论：non-empty single line
```

Emit only the stable error codes expected by Task 2; leave the legacy inline comment-audit compatibility
limited to the comment section and do not let it skip document-sync checks.

- [ ] **Step 4: Update the PR template and developer guide**

Add the required document-sync section to `.github/pull_request_template.md`. Explain in
`docs/development/repository-guardrails.md` that structure is automated, semantic accuracy remains a
human review responsibility, and list the new common failure codes.

- [ ] **Step 5: Run focused tests until green**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CiContract
```

Expected: both groups emit `GUARDRAIL_TESTS_PASSED`.

- [ ] **Step 6: Commit the Guardrail implementation**

Stage the script, tests, template, guide, and this plan explicitly. Review the staged diff, then:

```powershell
git commit -m "chore: prevent stale document status"
```

Expected: commit succeeds with `REPOSITORY_GUARDRAILS_OK`.

---

### Task 4: Complete repository verification and PR delivery

**Files:**
- Verify: all files changed by Tasks 1–3

**Interfaces:**
- Consumes: committed document normalization and Guardrail implementation.
- Produces: a pushed branch and reviewable GitHub pull request with truthful verification evidence.

- [ ] **Step 1: Run the full process test suite**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group All
```

Expected: all Repository Guardrail and local Git Guardrail cases pass.

- [ ] **Step 2: Run the Guardrail against the current branch**

Use a complete PR body containing `变更内容`, `测试`, `注释检查`, and `文档同步`, then run
`scripts/Test-RepositoryGuardrails.ps1 -Mode PullRequest -BaseRef origin/main -HeadRef HEAD`.

Expected: `REPOSITORY_GUARDRAILS_OK`.

- [ ] **Step 3: Run document and Git integrity checks**

Validate all Markdown relative links, scan current effective documents for the confirmed stale phrases,
then run:

```powershell
git diff --check origin/main...HEAD
git status --short --branch
```

Expected: no broken links, no confirmed stale phrases, no uncommitted files, and no whitespace errors.

- [ ] **Step 4: Push the task branch**

```powershell
git push -u origin docs/document-state-guardrail
```

Do not force push or push directly to `main`.

- [ ] **Step 5: Create the pull request**

Create a ready PR whose body records actual commands, results, skipped business tests and why, comment
risk `不涉及生产代码`, and document-sync evidence. Return the real PR URL; a pushed branch alone is not
delivery completion.
