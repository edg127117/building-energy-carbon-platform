# Persistent Chinese Comment Audit Documents Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时的实施步骤。文中的复选框表示原计划步骤，不代表当前完成状态；
> 执行任何命令前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试并重新核验。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move full Chinese production-code comment audits from pull-request descriptions into permanent repository Markdown documents while keeping the existing per-file and per-symbol validation strength.

**Architecture:** Extend the report generator with a safe repository-relative output mode, then let the pull-request guardrail resolve exactly one linked audit document from the new `注释审计` section. Validate path containment, ordinary-file type, non-empty UTF-8 content, and `Added` status relative to the PR base before reusing the existing audit contract checks. Preserve old inline `comment-audit` blocks only as a compatibility fallback.

**Tech Stack:** PowerShell 5.1/pwsh, Git, Markdown, GitHub Actions contract tests.

## Global Constraints

- Work only on `chore/comment-audit-documents`; do not switch this Codex worktree to `main` and do not create another worktree.
- Follow `docs/superpowers/specs/2026-08-06-comment-audit-documents-design.md` without reopening the confirmed scope.
- Do not modify Java, Vue, TypeScript production code, application configuration, Docker files, databases, or unrelated workflows.
- Keep `New-CommentAuditReport.ps1` stdout Markdown and JSON behavior compatible when `-OutputPath` is absent.
- Accept audit documents only at `docs/reviews/comment-audits/<year>/<date>-<lowercase-hyphen-task>.md` and never follow external, absolute, parent-relative, directory, symbolic-link, or reparse-point targets.
- Require each linked audit document to be newly added relative to the pull-request base; merged historical reports are immutable evidence and cannot be reused.
- Keep the current audit metadata, freshness, symbol, decision, reason, and stale-comment checks unchanged after audit content is loaded.
- Require only `变更内容`, `测试`, and `注释审计` in new PR descriptions; retain six-section inline reports as legacy-compatible input.
- Use test-first changes and exact-path staging. Never use `git add .`, `--no-verify`, force push, or direct updates to remote `main`.

---

## File Map

- Modify `scripts/New-CommentAuditReport.ps1` — add safe `-OutputPath` Markdown generation.
- Modify `scripts/Test-RepositoryGuardrails.ps1` — resolve and validate linked audit documents with legacy fallback.
- Modify `scripts/tests/Invoke-RepositoryGuardrailTests.ps1` — cover output compatibility, document safety, PR contract, and migration behavior.
- Modify `.github/pull_request_template.md` — expose only the three required PR sections and linked-document workflow.
- Modify `AGENTS.md` — make permanent audit documents the required production-code PR evidence.
- Modify `docs/development/repository-guardrails.md` — document generation, completion, linking, CI validation, and historical immutability.
- Create `docs/reviews/comment-audits/README.md` — provide the permanent archive contract and naming rules.

---

### Task 0: Revalidate the Prepared Branch

**Files:** None.

**Interfaces:**
- Consumes: confirmed design commit on `chore/comment-audit-documents`.
- Produces: clean task branch with repository guardrails ready.

- [ ] **Step 1: Verify branch and baseline**

```powershell
git status --short --branch
git branch --show-current
git log --oneline --decorate -3
```

Expected: branch is `chore/comment-audit-documents`, it contains the confirmed design commit, and there are no unrelated changes.

- [ ] **Step 2: Run repository task preflight**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

Expected: `TASK_PREFLIGHT_OK`.

---

### Task 1: Generate a Permanent Audit Document Safely

**Files:**
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`
- Modify: `scripts/New-CommentAuditReport.ps1`

**Interfaces:**
- Existing: `New-CommentAuditReport.ps1 -BaseRef <ref> -HeadRef <ref> [-Format Markdown|Json]` keeps stdout behavior.
- New: `New-CommentAuditReport.ps1 -BaseRef <ref> -HeadRef <ref> -OutputPath <repository-relative-path>` writes one UTF-8 Markdown template.
- Rejects: `-Format Json -OutputPath ...`, invalid paths, and paths already present in the base ref.

- [ ] **Step 1: Add failing generator contract cases**

Extend `Invoke-CommentScannerTests` with assertions for:

```powershell
$outputPath = 'docs/reviews/comment-audits/2026/2026-08-06-device-contract.md'
$written = Invoke-PowerShellScript $scannerScript @(
    '-BaseRef', 'main', '-HeadRef', 'HEAD', '-OutputPath', $outputPath
) $root $null
Assert-True ($written.ExitCode -eq 0) 'document output should succeed'
Assert-True (Test-Path (Join-Path $root $outputPath)) 'document must be created'
Assert-Contains (Get-Content -Raw -Encoding UTF8 (Join-Path $root $outputPath)) '审计日期：2026-08-06' 'metadata must be generated'
```

Also assert that the file contains the stable audit markers and pending human decisions, that UTF-8 Chinese text round-trips, and that these cases fail: JSON plus output path, an external/absolute/`..`/wrong-root/invalid-name path, and a path present in `main`. Re-run the existing stdout Markdown and JSON assertions unchanged.

- [ ] **Step 2: Run the generator group and confirm the new tests fail**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CommentScanner
```

Expected: failure because `-OutputPath` is not yet defined.

- [ ] **Step 3: Implement validated output mode**

Add the parameter and deterministic helpers:

```powershell
[string]$OutputPath

$script:AuditDocumentPathPattern = '^docs/reviews/comment-audits/\d{4}/\d{4}-\d{2}-\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*\.md$'

function Resolve-AuditOutputPath {
    param([string]$RepositoryRoot, [string]$RelativePath, [string]$Base)
    # Normalize separators, enforce the exact contract, prove repository containment,
    # and reject a path already stored in the base tree.
}
```

When `-OutputPath` is supplied, render a document header from the validated filename/date plus the existing Markdown report body, create only the required year directory, and write with `System.Text.UTF8Encoding($false)`. Keep stdout empty apart from a concise success marker. Do not change JSON or stdout Markdown rendering without `-OutputPath`.

- [ ] **Step 4: Re-run generator tests**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CommentScanner
```

Expected: all scanner/output cases pass.

- [ ] **Step 5: Commit the generator slice**

```powershell
git add -- scripts/New-CommentAuditReport.ps1 scripts/tests/Invoke-RepositoryGuardrailTests.ps1
git diff --cached --check
git commit -m "feat(process): generate persistent comment audits"
```

---

### Task 2: Validate Linked Audit Documents in Pull Requests

**Files:**
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`
- Modify: `scripts/Test-RepositoryGuardrails.ps1`

**Interfaces:**
- PR required sections: `变更内容`, `测试`, `注释审计`.
- Production-code PR evidence: exactly one Markdown link in `注释审计`, or a legacy inline `comment-audit:file=` block when no link is present.
- New failures: `AUDIT_DOCUMENT_LINK_MISSING`, `AUDIT_DOCUMENT_LINK_MULTIPLE`, `AUDIT_DOCUMENT_PATH_INVALID`, `AUDIT_DOCUMENT_NOT_FOUND`, `AUDIT_DOCUMENT_NOT_ADDED`, `AUDIT_DOCUMENT_UNSAFE_TYPE`, `AUDIT_DOCUMENT_EMPTY`.

- [ ] **Step 1: Refactor test fixtures around a linked document**

Make the completed audit helper return only the completed report content. Add a helper that writes and commits it at:

```text
docs/reviews/comment-audits/2026/2026-08-06-contract.md
```

Use the new concise body:

```markdown
## 变更内容
更新设备档案查询实现。

## 测试
定向合同测试通过，无跳过项。

## 注释审计
[查看完整中文注释审计](docs/reviews/comment-audits/2026/2026-08-06-contract.md)
```

- [ ] **Step 2: Add failing resolver and compatibility cases**

Cover all of the following with isolated temporary repositories or isolated commits:

- valid newly added linked audit passes;
- old six-section body with complete inline blocks still passes;
- each of the three required headings missing or empty fails with `PR_SECTION_MISSING`;
- production change with neither link nor inline block fails with `AUDIT_DOCUMENT_LINK_MISSING`;
- two Markdown links in `注释审计` fail with `AUDIT_DOCUMENT_LINK_MULTIPLE`;
- external URL, absolute path, `..`, wrong root, wrong year, or invalid filename fail with `AUDIT_DOCUMENT_PATH_INVALID`;
- valid-looking missing file fails with `AUDIT_DOCUMENT_NOT_FOUND`;
- base-existing historical file fails with `AUDIT_DOCUMENT_NOT_ADDED`;
- empty file fails with `AUDIT_DOCUMENT_EMPTY`;
- directory or reparse/symbolic link, when supported by the host, fails with `AUDIT_DOCUMENT_UNSAFE_TYPE`;
- missing private method row still fails with `AUDIT_SYMBOL_MISSING`;
- invalid decision/reason and incomplete metadata still reuse their existing errors;
- documentation-only PR with `不涉及生产代码` passes without a document.

- [ ] **Step 3: Run repository contract tests and confirm failure**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
```

Expected: new linked-document case fails against the old inline-only validator.

- [ ] **Step 4: Implement section and document resolution**

Change `Test-PrSections` to the three headings. Add helpers with these responsibilities:

```powershell
function Get-AuditDocumentLinkTargets { param([string]$AuditSection) }
function Resolve-AuditDocumentContent {
    param(
        [string]$RepositoryRoot,
        [string]$PullRequestBody,
        [object[]]$ChangedFiles,
        [string]$Base,
        [string]$Head
    )
}
```

Resolution order:

1. Parse the raw `注释审计` section and collect Markdown link targets.
2. If more than one exists, fail without guessing.
3. If exactly one exists, validate the exact path pattern, canonical containment, existence, ordinary-file type, non-empty UTF-8 content, and `Added` status in `base...head`.
4. If no link exists but the PR body contains `<!-- comment-audit:file=`, return the full body as legacy content.
5. Otherwise emit `AUDIT_DOCUMENT_LINK_MISSING`.

Pass the selected content to the existing `Test-CommentAuditContract`; do not weaken its current scanner, metadata, freshness, row, decision, reason, or stale-comment checks.

- [ ] **Step 5: Re-run repository contract tests**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
```

Expected: all linked-document, safety, legacy, and docs-only cases pass.

- [ ] **Step 6: Commit the validator slice**

```powershell
git add -- scripts/Test-RepositoryGuardrails.ps1 scripts/tests/Invoke-RepositoryGuardrailTests.ps1
git diff --cached --check
git commit -m "feat(process): validate linked comment audits"
```

---

### Task 3: Migrate the Repository Contract and Permanent Archive Guide

**Files:**
- Modify: `.github/pull_request_template.md`
- Modify: `AGENTS.md`
- Modify: `docs/development/repository-guardrails.md`
- Create: `docs/reviews/comment-audits/README.md`
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`

**Interfaces:**
- Human workflow: generate document, complete every placeholder, commit document, link it from `注释审计`.
- Archive policy: one newly added document per production-code PR; historical reports are immutable and their line numbers describe the merged snapshot only.

- [ ] **Step 1: Add failing documentation contract assertions**

Update `Invoke-CiContractTests` to require exactly the three new headings and reject the three obsolete mandatory headings from the template. Require the template, `AGENTS.md`, development guide, and archive README to contain:

```text
docs/reviews/comment-audits/<year>/<YYYY-MM-DD>-<task>.md
```

and the `-OutputPath` generation flow. Require the template to use `注释审计`, not `注释检查`.

- [ ] **Step 2: Run CI contract tests and confirm failure**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CiContract
```

Expected: failure until template and documentation are migrated.

- [ ] **Step 3: Update the PR template**

Keep only:

```markdown
## 变更内容
## 测试
## 注释审计
```

Explain in short HTML comments that production changes link one newly added permanent document and non-production changes write `不涉及生产代码`. Do not place an inline full report in the template.

- [ ] **Step 4: Update AGENTS.md and the development guide**

Replace the requirement to paste a full report into the PR with the confirmed permanent-document workflow. Preserve all requirements for complete file/symbol enumeration, human decisions, concrete reasons, test disclosure, exact staging, PR materials, and user-owned PR creation/merge.

- [ ] **Step 5: Add the archive README**

Document directory purpose, naming pattern, generation command, manual completion, one-document-per-task rule, no reuse of base-existing reports, post-merge immutability, and historical line-number semantics.

- [ ] **Step 6: Re-run CI contract tests and commit**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CiContract
git add -- .github/pull_request_template.md AGENTS.md docs/development/repository-guardrails.md docs/reviews/comment-audits/README.md scripts/tests/Invoke-RepositoryGuardrailTests.ps1
git diff --cached --check
git commit -m "docs(process): adopt permanent comment audit records"
```

Expected: CI contract cases pass and the commit contains only repository-process files.

---

### Task 4: Full Verification, Audit, Push, and PR Handoff

**Files:** All files changed by Tasks 0–3.

**Interfaces:**
- Produces: tested remote branch and user-ready PR creation materials.
- Does not create or merge the PR.

- [ ] **Step 1: Run complete repository guardrail regression**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group All
```

Expected: all repository, scanner, CI, and local Git guardrail cases pass with no skipped required case. A host without symbolic-link privileges may skip only the link-creation setup while directory/reparse rejection remains covered where supported.

- [ ] **Step 2: Run the guardrail itself against this non-production task**

```powershell
$body = @'
## 变更内容
将完整中文注释审计迁移为仓库内永久文档。

## 测试
仓库门禁完整回归通过。

## 注释审计
不涉及生产代码。
'@
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-RepositoryGuardrails.ps1 -Mode PullRequest -BaseRef origin/main -HeadRef HEAD -PullRequestBody $body
```

Expected: `REPOSITORY_GUARDRAILS_OK`.

- [ ] **Step 3: Review scope and whitespace**

```powershell
git status --short --branch
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD
git log --oneline origin/main..HEAD
```

Expected: only the confirmed design, plan, scripts, tests, template, `AGENTS.md`, and process documentation are present; no production source or unrelated files.

- [ ] **Step 4: Push the task branch**

```powershell
git push -u origin chore/comment-audit-documents
```

Expected: remote branch is updated without bypassing pre-push checks.

- [ ] **Step 5: Deliver PR materials**

Provide:

- Compare link: `https://github.com/edg127117/iot-platform-demo/compare/main...chore/comment-audit-documents?expand=1`
- Base `main`, compare `chore/comment-audit-documents`.
- Suggested title: `feat(process): 持久化中文注释审计报告`.
- A copyable three-section PR body with exact test evidence and `注释审计：不涉及生产代码`.
- Scope exclusions, conflict status, and unrelated-file check.
- State: waiting for the user to create and merge the PR.
