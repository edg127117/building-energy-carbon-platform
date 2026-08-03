# 仓库开发流程与注释质量防回退机制实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为当前仓库建立可执行的 Git/PR 流程、本地 Hook、前后端 CI、PR 模板和“变化生产文件完整方法清单”注释防回退机制。

**Architecture:** 本地层由 Git shell Hook 调用 PowerShell 脚本，阻止 `main` 提交、远程 `main` 推送并执行任务预检；PR 层由 PowerShell 扫描器根据 base/head 生成变化文件、方法和历史注释清单，再由 GitHub Actions 校验 PR 正文。脚本只负责结构和高置信度文本规则，业务语义仍由主代理依据 PR 审查表逐项判断。

**Tech Stack:** Git hooks、PowerShell 5.1+/PowerShell 7、GitHub Actions、Markdown、Java 21/Maven Wrapper、Vue 3/TypeScript/Vitest/npm。

## Global Constraints

- 不修改业务逻辑、API、数据库、Docker、MQTT、TDengine、Redis 或页面行为。
- 本地 Hook 只配置当前仓库的 `core.hooksPath`，不修改用户全局 Git 配置。
- 清理脚本默认只读；只有显式 `-Apply` 且全部安全检查通过时才允许快进、移除 worktree 和删除已合并分支。
- 所有变化的生产 Java、Vue、TypeScript 文件都必须进入注释审查，且扫描完整当前文件而不是只扫描新增行。
- 所有可识别方法/函数都必须逐项分类；关键方法说明业务上下文，简单方法填写具体免注释原因。
- 变化文件内已有注释与新增注释采用同一标准；任务历史、过期未来承诺和无信息复述必须删除或改写。
- CI 通过只证明结构和证据完整，不能代替主代理对业务语义的复核。
- GitHub `main` 分支保护只能在仓库页面启用并现场核验，仓库代码不得声称它已自动生效。
- 子代理不得提交、推送、创建分支或管理 worktree；最终差异、测试、提交和 Git 流程由主代理负责。

---

## 文件结构

### 本地 Git 流程

- `.githooks/pre-commit`：拒绝在 `main` 提交，并调用暂存差异检查。
- `.githooks/pre-push`：读取 Git 标准输入中的远程引用，拒绝任何 `refs/heads/main` 更新。
- `scripts/Install-GitGuardrails.ps1`：安装并回读当前仓库 `core.hooksPath`。
- `scripts/Invoke-TaskPreflight.ps1`：验证规则入口、任务分支、干净工作区、基线和 Hook。
- `scripts/Invoke-PostMergeCleanup.ps1`：预览或安全执行合并后同步与清理。
- `scripts/tests/Invoke-LocalGitGuardrailTests.ps1`：验证 Hook、安装、预检和清理的独立临时仓库用例。

### 注释与 PR 防回退

- `scripts/New-CommentAuditReport.ps1`：输出 Markdown 或 JSON 格式的变化文件、符号和注释风险清单。
- `scripts/Test-RepositoryGuardrails.ps1`：校验暂存差异或 PR 差异、PR 必填章节和注释审查表。
- `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`：在独立临时仓库中执行成功与失败用例。
- `.github/pull_request_template.md`：固定范围、测试和注释审查证据格式。
- `.github/workflows/repository-guardrails.yml`：在 PR 事件中执行仓库防回退检查。

### CI 与使用文档

- `.github/workflows/frontend-ci.yml`：执行前端测试、类型检查和构建。
- `docs/development/repository-guardrails.md`：记录安装、预检、报告生成、清理和分支保护设置。
- `AGENTS.md`、`PROJECT_GUIDE.md`、`PROJECT_STATUS.md`、`docs/superpowers/README.md`：只增加稳定入口和准确状态。
- `.gitattributes`：为无扩展名 Hook 和 PowerShell 文件固定文本换行规则。

---

### Task 1: 本地 Git Hook、安装器与任务预检

**Files:**
- Create: `.githooks/pre-commit`
- Create: `.githooks/pre-push`
- Create: `scripts/Install-GitGuardrails.ps1`
- Create: `scripts/Invoke-TaskPreflight.ps1`
- Create: `scripts/tests/Invoke-LocalGitGuardrailTests.ps1`
- Modify: `.gitattributes`

**Interfaces:**
- Consumes: Git 当前分支、暂存差异、pre-push 标准输入、`origin/main`、`core.hooksPath`。
- Produces: `GIT_GUARDRAILS_INSTALLED`、`TASK_PREFLIGHT_OK`；失败时返回非零退出码和具体规则名称。

- [ ] **Step 1: 写入会失败的 Hook、安装和预检测试**

测试脚本使用唯一临时目录并提供以下断言辅助函数：

```powershell
function Invoke-GuardrailCase {
    param([string]$Name, [scriptblock]$Action, [int]$ExpectedExitCode, [string]$ExpectedText)
    $output = & $Action 2>&1 | Out-String
    if ($LASTEXITCODE -ne $ExpectedExitCode -or $output -notmatch [regex]::Escape($ExpectedText)) {
        throw "CASE_FAILED: $Name`n$output"
    }
    Write-Output "CASE_PASSED: $Name"
}
```

用例必须覆盖：`main` 上 pre-commit 失败、任务分支通过、`HEAD:main` 推送失败、任务分支推送通过、安装器仅写本仓库配置、预检分别拒绝 `main`、脏工作区、缺少三个入口文件、未安装 Hook 和 `origin/main` 不是祖先。

- [ ] **Step 2: 运行测试并确认缺少实现**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-LocalGitGuardrailTests.ps1 -Group LocalWorkflow
```

Expected: FAIL，错误明确指出 `.githooks/pre-commit` 或安装脚本不存在。

- [ ] **Step 3: 实现两个 Hook**

`pre-commit` 先拒绝 `main`，再选择可用 PowerShell 并执行：

```sh
#!/bin/sh
branch="$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
if [ "$branch" = "main" ]; then
  echo "GIT_GUARDRAIL_BLOCKED: commits on main are forbidden" >&2
  exit 1
fi

if command -v pwsh >/dev/null 2>&1; then
  shell=pwsh
elif command -v powershell.exe >/dev/null 2>&1; then
  shell=powershell.exe
else
  echo "GIT_GUARDRAIL_BLOCKED: PowerShell is required" >&2
  exit 1
fi

"$shell" -NoProfile -ExecutionPolicy Bypass -File scripts/Test-RepositoryGuardrails.ps1 -Mode Staged
```

`pre-push` 必须按远程目标引用判断，不依赖当前分支：

```sh
#!/bin/sh
while read local_ref local_sha remote_ref remote_sha; do
  if [ "$remote_ref" = "refs/heads/main" ]; then
    echo "GIT_GUARDRAIL_BLOCKED: direct push to remote main is forbidden" >&2
    exit 1
  fi
done
exit 0
```

- [ ] **Step 4: 实现安装器和预检**

安装器只执行本仓库配置并回读：

```powershell
$root = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw 'NOT_A_GIT_WORKTREE' }
foreach ($hook in 'pre-commit', 'pre-push') {
    if (-not (Test-Path (Join-Path $root ".githooks/$hook"))) { throw "MISSING_HOOK: $hook" }
}
& git config --local core.hooksPath .githooks
if ((& git config --local --get core.hooksPath).Trim() -ne '.githooks') { throw 'HOOK_INSTALL_FAILED' }
Write-Output 'GIT_GUARDRAILS_INSTALLED'
```

预检集中收集错误，全部通过后才输出成功标记：

```powershell
$errors = [System.Collections.Generic.List[string]]::new()
foreach ($entry in 'AGENTS.md', 'PROJECT_GUIDE.md', 'PROJECT_STATUS.md') {
    if (-not (Test-Path (Join-Path $root $entry))) { $errors.Add("MISSING_RULE_ENTRY: $entry") }
}
if ($branch -eq 'main') { $errors.Add('INVALID_TASK_BRANCH: main') }
if ($branch -notmatch '^(feature|fix|perf|refactor|docs|test|chore)/[a-z0-9][a-z0-9-]*$') {
    $errors.Add("INVALID_TASK_BRANCH_NAME: $branch")
}
if (& git status --porcelain) { $errors.Add('WORKTREE_NOT_CLEAN') }
& git merge-base --is-ancestor origin/main HEAD
if ($LASTEXITCODE -ne 0) { $errors.Add('OUTDATED_OR_DIVERGED_BASELINE') }
if ((& git config --local --get core.hooksPath) -ne '.githooks') { $errors.Add('GIT_HOOKS_NOT_INSTALLED') }
if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Error $_ }; exit 1 }
Write-Output 'TASK_PREFLIGHT_OK'
```

本地 Hook 测试仓库放置可控的 `scripts/Test-RepositoryGuardrails.ps1` stub，验证 task 分支的 pre-commit 确实调用 `-Mode Staged`；正式检查器由 Task 4 实现，并在最终安装 Hook 前完成集成。

- [ ] **Step 5: 固定换行并运行定向测试**

在 `.gitattributes` 增加：

```gitattributes
.githooks/* text eol=lf
*.ps1 text eol=crlf
```

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-LocalGitGuardrailTests.ps1 -Group LocalWorkflow
```

Expected: 所有 `LocalWorkflow` 用例输出 `CASE_PASSED`，结尾输出 `GUARDRAIL_TESTS_PASSED`。

- [ ] **Step 6: 提交本地流程防护**

```powershell
git add -- .githooks/pre-commit .githooks/pre-push .gitattributes scripts/Install-GitGuardrails.ps1 scripts/Invoke-TaskPreflight.ps1 scripts/tests/Invoke-LocalGitGuardrailTests.ps1
git diff --cached --check
git commit -m "chore(workflow): add local git guardrails"
```

### Task 2: 默认只读的合并后安全清理

**Files:**
- Create: `scripts/Invoke-PostMergeCleanup.ps1`
- Modify: `scripts/tests/Invoke-LocalGitGuardrailTests.ps1`

**Interfaces:**
- Consumes: 字符串参数 `-TaskBranch`、可选绝对路径参数 `-WorktreePath`、开关 `-Apply`。
- Produces: 默认输出 `CLEANUP_PREVIEW_OK`；执行成功输出 `POST_MERGE_CLEANUP_OK`；任何检查失败前不改变仓库状态。

- [ ] **Step 1: 增加清理失败边界测试**

测试在临时仓库创建 `main`、裸远程、已合并与未合并分支以及独立 worktree，验证：默认模式不改变引用和目录；未合并分支失败；脏 worktree 失败；未注册路径失败；当前主工作区无法快进时失败；合法 `-Apply` 只删除精确任务 worktree 和已合并分支。

- [ ] **Step 2: 运行清理测试并确认失败**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-LocalGitGuardrailTests.ps1 -Group Cleanup
```

Expected: FAIL with `Invoke-PostMergeCleanup.ps1` missing.

- [ ] **Step 3: 实现预检与预览**

脚本必须先完成全部只读检查，再构造动作列表：

```powershell
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][ValidatePattern('^(feature|fix|perf|refactor|docs|test|chore)/')][string]$TaskBranch,
    [string]$WorktreePath,
    [switch]$Apply
)

$root = (& git rev-parse --show-toplevel).Trim()
if ((& git branch --show-current).Trim() -ne 'main') { throw 'CLEANUP_REQUIRES_MAIN_WORKTREE' }
if (& git status --porcelain) { throw 'MAIN_WORKTREE_NOT_CLEAN' }
& git fetch --prune origin
if ($LASTEXITCODE -ne 0) { throw 'REMOTE_REFRESH_FAILED' }
& git merge-base --is-ancestor $TaskBranch origin/main
if ($LASTEXITCODE -ne 0) { throw 'TASK_BRANCH_NOT_MERGED' }
& git merge-base --is-ancestor main origin/main
if ($LASTEXITCODE -ne 0) { throw 'MAIN_CANNOT_FAST_FORWARD' }
```

当提供 worktree 路径时，通过 `git worktree list --porcelain` 获得已注册绝对路径，使用 `[IO.Path]::GetFullPath()` 精确比较，并在目标目录执行 `git status --porcelain`。不接受通配符、`~`、仓库根目录或未注册路径。

- [ ] **Step 4: 实现显式执行**

```powershell
if (-not $Apply) {
    $actions | ForEach-Object { Write-Output "WOULD_RUN: $_" }
    Write-Output 'CLEANUP_PREVIEW_OK'
    exit 0
}
& git merge --ff-only origin/main
if ($LASTEXITCODE -ne 0) { throw 'MAIN_FAST_FORWARD_FAILED' }
if ($resolvedWorktree) { & git worktree remove -- $resolvedWorktree }
if ($LASTEXITCODE -ne 0) { throw 'WORKTREE_REMOVE_FAILED' }
& git branch -d -- $TaskBranch
if ($LASTEXITCODE -ne 0) { throw 'BRANCH_DELETE_FAILED' }
Write-Output 'POST_MERGE_CLEANUP_OK'
```

- [ ] **Step 5: 运行清理组和全套脚本测试**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-LocalGitGuardrailTests.ps1 -Group Cleanup
pwsh -NoProfile -File scripts/tests/Invoke-LocalGitGuardrailTests.ps1 -Group All
```

Expected: 两次均输出 `GUARDRAIL_TESTS_PASSED`，且测试仅删除自己创建的系统临时目录。

- [ ] **Step 6: 提交安全清理脚本**

```powershell
git add -- scripts/Invoke-PostMergeCleanup.ps1 scripts/tests/Invoke-LocalGitGuardrailTests.ps1
git diff --cached --check
git commit -m "chore(workflow): add safe post-merge cleanup"
```

### Task 3: 完整方法清单与历史注释扫描器

**Files:**
- Create: `scripts/New-CommentAuditReport.ps1`
- Create: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`

**Interfaces:**
- Consumes: Git 引用参数 `-BaseRef`、`-HeadRef`、格式参数 `-Format Markdown|Json`。
- Produces: JSON 对象数组 `{ path, responsibilityFields, symbols, commentFindings }`；Markdown 使用可被 CI 稳定解析的 file marker 和表格。
- Symbol identity: 同一文件内使用 `name#occurrence`，另带 `line` 和 `kind`，避免重载方法只保留一条。

- [ ] **Step 1: 增加扫描器契约测试**

在临时 Git 仓库提交基线，再修改 Java、Vue 和 TypeScript fixture。断言 Java 构造器、接口方法、`public/protected/private` 方法，以及前端函数声明、具名箭头函数、`computed`、`watch`、生命周期入口和 Store/Composable 动作都出现在 JSON。断言未变化生产文件不出现，变化文件读取完整内容，重载方法生成 `query#1`、`query#2`。

历史注释用例必须包含：未修改行中的“后续支持导出”失败；“当前任务”“获取数据”失败；“后续节点”“当前温度”不产生 finding。

- [ ] **Step 2: 运行扫描器测试并确认失败**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CommentScanner
```

Expected: FAIL with `New-CommentAuditReport.ps1` missing.

- [ ] **Step 3: 实现变化文件发现和符号模型**

```powershell
[CmdletBinding()]
param(
    [string]$BaseRef = 'origin/main',
    [string]$HeadRef = 'HEAD',
    [ValidateSet('Markdown', 'Json')][string]$Format = 'Markdown'
)

$rows = & git diff --name-status --diff-filter=ACMR "$BaseRef...$HeadRef"
$files = foreach ($row in $rows) {
    $parts = $row -split "`t"
    $path = $parts[-1].Replace('\', '/')
    if ($path -match '^src/main/java/.+\.java$' -or $path -match '^web/src/.+\.(vue|ts)$') {
        [pscustomobject]@{ status = $parts[0]; path = $path }
    }
}
```

Java 与前端扫描结果统一为：

```powershell
[pscustomobject]@{
    id = "$name#$occurrence"
    name = $name
    kind = $kind
    line = $line
}
```

扫描控制关键字 `if/for/while/switch/catch/new/return` 必须排除；同名符号按源文件顺序增加 occurrence。

- [ ] **Step 4: 实现完整现有注释风险扫描**

从完整文件提取 `//`、`/* ... */`、`/** ... */` 和 Vue `<!-- ... -->`。使用高置信度规则：

```powershell
$taskHistory = '(当前任务|本次修改|本\s*PR|本次修复)'
$futurePromise = '(以后|后续|未来).{0,12}(支持|实现|优化|补充|新增|完成)'
$emptyRestatement = '^\s*(获取数据|进行判断|循环处理|返回结果)[。.]?\s*$'
```

每个 finding 输出 `path`、`line`、`rule`、`text`。未来承诺必须同时命中时间词和动作词；“后续节点”“当前温度”不得命中。

- [ ] **Step 5: 实现可填写 Markdown 报告**

每个文件输出固定结构：

```markdown
<!-- comment-audit:file=src/main/java/com/example/ExampleService.java -->
- 职责：待填写
- 上游：待填写
- 下游：待填写
- 数据源：待填写
- 结果消费者：待填写
- 现有注释时效：待核验
- 人工补充符号：无

| 符号 ID | 位置 | 判定与处理 | 业务说明或免注释原因 |
| --- | --- | --- | --- |
| `load#1` | `L25` | `待填写` | 待填写 |
<!-- comment-audit:end-file -->
```

若发现风险注释，在文件段落追加 `注释风险` 列表；`Json` 使用 `ConvertTo-Json -Depth 8`，不得混入日志文本。

- [ ] **Step 6: 运行扫描器测试**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CommentScanner
```

Expected: 所有符号、重载、旧注释风险和业务术语免误报用例通过。

- [ ] **Step 7: 提交注释报告生成器**

```powershell
git add -- scripts/New-CommentAuditReport.ps1 scripts/tests/Invoke-RepositoryGuardrailTests.ps1
git diff --cached --check
git commit -m "feat(workflow): generate complete comment audit reports"
```

### Task 4: 暂存差异和 PR 合同校验器

**Files:**
- Create: `scripts/Test-RepositoryGuardrails.ps1`
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`

**Interfaces:**
- Consumes: `-Mode Staged|PullRequest`、PR 模式下的 `-BaseRef`、`-HeadRef` 和 `$env:PR_BODY`。
- Produces: 成功输出 `REPOSITORY_GUARDRAILS_OK`；失败逐项输出 `GUARDRAIL_ERROR: rule: path-or-detail` 并返回 1。

- [ ] **Step 1: 增加暂存与 PR 合同失败测试**

暂存模式覆盖：生成目录、`server.env`、`.env`、Token/密钥文件、新 Java 业务类无类级 Javadoc、新核心前端文件完全没有业务说明。PR 模式覆盖：缺少必填章节、漏掉变化文件、漏掉私有方法、非法判定、简单方法无具体原因、文件元数据仍为“待填写”、历史风险注释仍存在。

通过用例覆盖文档-only PR，以及生产代码 PR 完整填写以下状态之一：`关键-已新增说明`、`关键-已更新说明`、`关键-现有说明已核验`、`简单-无需说明`。

- [ ] **Step 2: 运行合同测试并确认失败**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
```

Expected: FAIL with `Test-RepositoryGuardrails.ps1` missing.

- [ ] **Step 3: 实现统一错误收集与文件规则**

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Staged', 'PullRequest')][string]$Mode,
    [string]$BaseRef = 'origin/main',
    [string]$HeadRef = 'HEAD',
    [string]$PullRequestBody = $env:PR_BODY
)
$errors = [System.Collections.Generic.List[string]]::new()
function Add-GuardrailError([string]$Rule, [string]$Detail) {
    $errors.Add("GUARDRAIL_ERROR: ${Rule}: $Detail")
}
```

禁止路径至少包括 `target/`、`web/dist/`、`node_modules/`、`.tools/`、`.npm-cache/`、本地数据目录、`server.env`、`web/.env`、`token.txt`、私钥和常见凭据文件；示例文件如 `server.env.example`、`web/.env.example` 保持允许。

- [ ] **Step 4: 实现新增业务文件最低说明检查**

新增 Java 生产类要求类声明前存在 `/** ... */`；新增 `web/src/pages|components|composables|stores|api|router|types` 下的 `.vue/.ts` 文件要求至少存在一段非空业务注释。该检查只负责“完全缺失”，不替代 PR 的逐项语义审查。

- [ ] **Step 5: 实现 PR 必填章节和完整审查表校验**

必填二级标题固定为：

```powershell
$requiredSections = @('解决的问题', '变更内容', '不包含范围', '文件范围检查', '测试', '注释检查')
$allowedDecisions = @('关键-已新增说明', '关键-已更新说明', '关键-现有说明已核验', '简单-无需说明')
```

先剔除 HTML 注释，再要求每节有实际正文。生产文件发生变化时调用扫描器 JSON 输出，逐文件检查 marker、六个文件字段、每个 symbol id 和判定。`简单-无需说明` 的原因不能留空、不能是“无需注释”；关键方法也必须有业务说明。扫描器返回任何 comment finding 时 PR 直接失败，要求先修改代码注释。

- [ ] **Step 6: 运行合同组和全部脚本测试**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group All
```

Expected: 所有用例通过，结尾输出 `GUARDRAIL_TESTS_PASSED`。

- [ ] **Step 7: 提交仓库合同校验器**

```powershell
git add -- scripts/Test-RepositoryGuardrails.ps1 scripts/tests/Invoke-RepositoryGuardrailTests.ps1
git diff --cached --check
git commit -m "feat(workflow): enforce repository and comment contracts"
```

### Task 5: PR 模板与 GitHub Actions

**Files:**
- Create: `.github/pull_request_template.md`
- Create: `.github/workflows/frontend-ci.yml`
- Create: `.github/workflows/repository-guardrails.yml`
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`

**Interfaces:**
- Consumes: GitHub `pull_request` base/head SHA 和 PR body；`web/package-lock.json`。
- Produces: 必需检查名称 `Frontend verify`、`Repository guardrails`；既有后端检查保持 `Java 21 verify`。

- [ ] **Step 1: 增加模板与 YAML 静态测试**

断言 PR 模板包含六个固定标题、注释报告生成命令和完整表格说明；前端工作流依次包含 `npm ci`、`npm run test:run`、`npm run check`、`npm run build`；仓库工作流使用 `fetch-depth: 0`、`pwsh`、base/head SHA 和 `PR_BODY`，job 名称精确为 `Repository guardrails`。

- [ ] **Step 2: 运行 CI 契约测试并确认失败**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CiContract
```

Expected: FAIL，指出 PR 模板或工作流不存在。

- [ ] **Step 3: 创建 PR 模板**

模板正文使用以下固定入口，不预填虚假结果：

```markdown
## 解决的问题
<!-- 说明问题及用户影响。 -->
## 变更内容
<!-- 列出已经完成的修改。 -->
## 不包含范围
<!-- 明确本 PR 没有处理的内容。 -->
## 文件范围检查
<!-- 说明差异中没有生成文件、凭据、运行数据或无关修改。 -->
## 测试
<!-- 逐项填写命令、结果、通过数量、跳过项和未验证内容。 -->
## 注释检查
<!-- 生产代码 PR：运行 scripts/New-CommentAuditReport.ps1 后粘贴并填写报告。文档-only PR 写“不涉及生产代码”。 -->
```

- [ ] **Step 4: 创建前端 CI**

工作流触发 `main` 的 PR/push 和手动运行；使用 `actions/checkout@v6`、`actions/setup-node@v6`、`node-version: 22`、`cache: npm`、`cache-dependency-path: web/package-lock.json`，所有 npm 命令设置 `working-directory: web`，job 名称为 `Frontend verify`。

- [ ] **Step 5: 创建仓库防回退 CI**

```yaml
name: Repository Guardrails
on:
  pull_request:
    branches: [main]
    types: [opened, edited, synchronize, reopened]
permissions:
  contents: read
jobs:
  guardrails:
    name: Repository guardrails
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0
      - name: Validate repository and PR contract
        shell: pwsh
        env:
          PR_BODY: ${{ github.event.pull_request.body }}
        run: ./scripts/Test-RepositoryGuardrails.ps1 -Mode PullRequest -BaseRef '${{ github.event.pull_request.base.sha }}' -HeadRef '${{ github.event.pull_request.head.sha }}'
```

- [ ] **Step 6: 运行 CI 契约测试**

Run:

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CiContract
```

Expected: 输出 `GUARDRAIL_TESTS_PASSED`。

- [ ] **Step 7: 提交 PR 和 CI 防线**

```powershell
git add -- .github/pull_request_template.md .github/workflows/frontend-ci.yml .github/workflows/repository-guardrails.yml scripts/tests/Invoke-RepositoryGuardrailTests.ps1
git diff --cached --check
git commit -m "ci(workflow): enforce frontend and PR quality checks"
```

### Task 6: 规则入口与使用文档

**Files:**
- Create: `docs/development/repository-guardrails.md`
- Modify: `AGENTS.md`
- Modify: `PROJECT_GUIDE.md`
- Modify: `PROJECT_STATUS.md`
- Modify: `docs/superpowers/README.md`

**Interfaces:**
- Consumes: 前五个任务形成的准确脚本路径、成功标记和 CI job 名称。
- Produces: 新任务可从三个当前入口定位安装、预检、PR 审查和清理命令；明确 GitHub 分支保护仍待仓库所有者启用和现场验证。

- [ ] **Step 1: 编写仓库防护使用指南**

文档必须给出 Windows 命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Install-GitGuardrails.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 -BaseRef origin/main -HeadRef HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-PostMergeCleanup.ps1 -TaskBranch chore/example -WorktreePath C:\absolute\registered\worktree
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-PostMergeCleanup.ps1 -TaskBranch chore/example -WorktreePath C:\absolute\registered\worktree -Apply
```

说明本地 Hook 可被 `--no-verify` 绕过，因此最终保护依赖 GitHub：要求 PR、必需检查 `Java 21 verify`/`Frontend verify`/`Repository guardrails`、合并前更新、解决对话、禁用 force push 和 `main` 删除；另一名审批者和签名提交当前不强制。

- [ ] **Step 2: 更新固定规则入口**

在 `AGENTS.md` 的 Git 开始任务、提交、PR、注释和合并后清理位置引用对应脚本。明确：工具失败必须停止；CI 只防遗漏，主代理必须复核每个变化文件的完整方法清单和历史注释时效；不能把扫描器输出中的“待填写”直接提交到 PR。

- [ ] **Step 3: 更新项目指南、状态和历史目录**

`PROJECT_GUIDE.md` 仅增加“仓库工程防护”入口；`PROJECT_STATUS.md` 记录仓库内已具备本地安装脚本、前后端 CI 和 PR 检查，同时把服务器端分支保护标记为“待启用/待现场验证”；`docs/superpowers/README.md` 增加本设计与本计划的成对记录。

- [ ] **Step 4: 检查文档没有错误承诺或临时状态**

Run:

```powershell
rg -n "已启用分支保护|已强制服务器保护|待填写" AGENTS.md PROJECT_GUIDE.md PROJECT_STATUS.md docs/development/repository-guardrails.md docs/superpowers/README.md
git diff --check
```

Expected: 不出现“GitHub 分支保护已经启用”的无证据结论；“待填写”只允许出现在 PR 报告使用说明中；`git diff --check` 无输出。

- [ ] **Step 5: 提交规则与文档入口**

```powershell
git add -- AGENTS.md PROJECT_GUIDE.md PROJECT_STATUS.md docs/development/repository-guardrails.md docs/superpowers/README.md
git diff --cached --check
git commit -m "docs(workflow): document repository guardrails"
```

### Task 7: 全量验证、安装当前 worktree Hook 并交付 PR

**Files:**
- Verify only: all files changed by Tasks 1-6

**Interfaces:**
- Consumes: 完整实现与当前任务分支。
- Produces: 可复核的测试结果、干净工作区、远程任务分支和 PR 材料；不代替用户创建或合并 PR。

- [ ] **Step 1: 运行脚本与 Hook 全套测试**

```powershell
pwsh -NoProfile -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group All
pwsh -NoProfile -File scripts/Install-GitGuardrails.ps1
```

Expected: `GUARDRAIL_TESTS_PASSED` 和 `GIT_GUARDRAILS_INSTALLED`。

- [ ] **Step 2: 验证当前分支预检的可解释边界**

当前分支已有任务提交，因此在干净工作区、`origin/main` 为祖先且 Hook 已安装时执行：

```powershell
pwsh -NoProfile -File scripts/Invoke-TaskPreflight.ps1
```

Expected: 输出三个规则入口和 `TASK_PREFLIGHT_OK`。

- [ ] **Step 3: 执行后端回归**

```powershell
.\mvnw.cmd test
```

Expected: Maven `BUILD SUCCESS`，如实记录 Tests run、Failures、Errors、Skipped。

- [ ] **Step 4: 执行前端回归**

```powershell
Set-Location web
npm run test:run -- --maxWorkers=1 --minWorkers=1 --pool=threads
npm run check
npm run build
```

Expected: Vitest 全部通过，`vue-tsc` 返回 0，Vite build 成功。

- [ ] **Step 5: 执行最终差异和 YAML 检查**

```powershell
git diff origin/main...HEAD --check
git diff origin/main...HEAD --name-only
git status --short --branch
```

确认差异只包含设计、计划、Hook、脚本、工作流、模板和入口文档，不包含业务代码、运行数据、凭据或生成文件。使用 PowerShell YAML 解析器或 Ruby/Python 已安装 YAML 库解析两个新增工作流，并核对 job 名称。

- [ ] **Step 6: 处理最终验证发现的问题**

如果验证失败，回到拥有该文件的 Task，使用该 Task 已列明的精确文件列表完成修正、定向测试和提交，然后重新从 Step 1 执行全量验证。验证全部通过时不创建空提交或笼统的收尾提交。

- [ ] **Step 7: 推送任务分支并准备 PR 材料**

```powershell
git push -u origin chore/workflow-quality-guardrails
```

提供 Compare 链接、Base/Compare、中文 PR 标题、范围、不包含范围、逐项测试结果、跳过与未验证内容、冲突状态和无关文件检查。状态明确为“等待用户创建并合并 PR”；GitHub 分支保护继续标记为合并后待用户启用和现场验证。

---

## 自检结论

- 规格中的本地 `main` 提交拦截、远程 `main` 推送拦截、安装、预检和默认只读清理分别由 Task 1-2 覆盖。
- 全部变化生产文件、完整方法清单、历史注释时效、PR 必填证据和简单方法排除原因由 Task 3-4 覆盖。
- 前端 CI、仓库防回退 CI、固定 job 名称和 PR 模板由 Task 5 覆盖；既有后端 CI 保持不变。
- GitHub 分支保护只记录设置步骤，不声称仓库文件能够自动启用，由 Task 6-7 保留现场验证边界。
- Task 7 同时覆盖 PowerShell、Hook、后端、前端、YAML、差异范围和 Git/PR 交付。
