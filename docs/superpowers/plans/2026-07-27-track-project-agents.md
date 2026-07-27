# Track Project AGENTS.md Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有项目级 `AGENTS.md` 原样纳入 Git 跟踪，让所有分支、worktree、克隆目录和新 Codex 会话自动获得项目规则。

**Architecture:** 在独立分支 `chore/track-project-agents` 中，从原仓库根目录复制规则文件到当前任务worktree根目录。通过内容哈希、Git索引和提交树三层验证文件完全一致且已被跟踪，不修改规则正文或计算引擎代码。

**Tech Stack:** Git、PowerShell、Markdown

## Global Constraints

- 原样使用 `D:\word\iot-platform-demo\AGENTS.md`，不得修改三组规则正文。
- 不修改或提交 `.git/info/exclude`，它是机器本地配置。
- 不修改计算引擎代码、配置或测试。
- 不提交 `outputs/` 或其他无关文件。
- 仅推送任务分支，禁止直接推送远程 `main`。

---

### Task 1: Track the Project Rules File

**Files:**
- Create: `AGENTS.md`

**Interfaces:**
- Consumes: `D:\word\iot-platform-demo\AGENTS.md`
- Produces: Git-tracked repository-root `AGENTS.md`

- [ ] **Step 1: Verify the file is not tracked yet**

Run:

```powershell
git ls-files --error-unmatch AGENTS.md
```

Expected: command fails because `AGENTS.md` is not present in the current commit.

- [ ] **Step 2: Copy the approved rule file without changing its contents**

Run:

```powershell
Copy-Item -LiteralPath 'D:\word\iot-platform-demo\AGENTS.md' `
  -Destination '.\AGENTS.md'
```

Expected: the task worktree root contains `AGENTS.md`.

- [ ] **Step 3: Verify source and destination are byte-identical**

Run:

```powershell
$sourceHash = (Get-FileHash -Algorithm SHA256 `
  -LiteralPath 'D:\word\iot-platform-demo\AGENTS.md').Hash
$targetHash = (Get-FileHash -Algorithm SHA256 `
  -LiteralPath '.\AGENTS.md').Hash
if ($sourceHash -ne $targetHash) {
  throw "AGENTS.md copy differs from the approved source"
}
```

Expected: command completes without error and both SHA-256 values are identical.

- [ ] **Step 4: Force-add the locally excluded file and verify the staged scope**

Run:

```powershell
git add -f -- AGENTS.md
git diff --cached --name-only
git diff --cached --check
git ls-files --error-unmatch AGENTS.md
```

Expected:

```text
AGENTS.md
```

No whitespace errors and no unrelated files are staged.

- [ ] **Step 5: Commit the tracked project rules**

Run:

```powershell
git commit -m "chore(project): 跟踪项目级Codex规则"
```

Expected: one commit containing only `AGENTS.md`.

- [ ] **Step 6: Verify the committed file and branch state**

Run:

```powershell
git show --name-only --format= HEAD
git ls-tree --name-only HEAD AGENTS.md
git status --short --branch
```

Expected:

- `git show` lists only `AGENTS.md`;
- `git ls-tree` returns `AGENTS.md`;
- the worktree is clean and the task branch is ahead of `origin/main`.

- [ ] **Step 7: Push the task branch and prepare PR delivery**

Run:

```powershell
git push -u origin chore/track-project-agents
```

Expected: the branch is published to the existing
`edg127117/iot-platform-demo` GitHub repository. Provide the user with:

```text
https://github.com/edg127117/iot-platform-demo/compare/main...chore/track-project-agents?expand=1
```

The user creates and merges the PR; do not update remote `main` directly.
