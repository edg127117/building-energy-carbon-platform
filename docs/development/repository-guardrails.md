# 仓库开发流程与质量防护

## 1. 能力边界

仓库通过固定规则、本地 Git Hook、GitHub Actions 和 GitHub 分支保护共同约束开发流程：

- `AGENTS.md` 说明必须遵守的 Git、测试和注释标准；
- 本地 Hook 阻止在 `main` 提交以及直接更新远程 `main`；
- 任务预检验证规则入口、任务分支、干净工作区、基线和 Hook；
- PR 模板和仓库防回退 CI 验证范围、测试和注释审查证据；
- 后端与前端 CI 分别验证 Java 和 Vue/TypeScript 构建；
- GitHub `main` 分支保护提供最终服务器端合并边界。

本地 Hook 可以被人为删除或使用 Git 参数绕过，因此不能替代 GitHub 分支保护。CI 只能检查结构和高置信度文本规则，不能替代主代理判断业务注释是否准确。

## 2. 安装当前仓库 Hook

在每个本地 clone 或独立 Git 配置环境中执行一次：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Install-GitGuardrails.ps1
```

成功标记：

```text
GIT_GUARDRAILS_INSTALLED
```

安装器只写当前仓库的 `core.hooksPath=.githooks`，不会修改全局 Git 配置。可用以下命令回读：

```powershell
git config --local --get core.hooksPath
```

## 3. 新任务预检

完成 `main` 同步并创建任务分支后，在尚未修改文件时执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

成功标记：

```text
TASK_PREFLIGHT_OK
```

预检会验证：

- `AGENTS.md`、`PROJECT_GUIDE.md`、`PROJECT_STATUS.md` 存在；
- 当前分支使用允许的任务前缀且不是 `main`；
- 工作区干净；
- `origin/main` 是任务分支 `HEAD` 的祖先；
- 当前仓库已经安装 `.githooks`。

脚本不会自动获取远程状态，也不能证明执行者已经理解规则。运行前仍需按 `AGENTS.md` 获取远程引用并安全同步 `main`。

## 4. 注释审查报告

生产 Java、Vue 或 TypeScript 文件发生变化时，在任务分支执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 -BaseRef origin/main -HeadRef HEAD
```

将输出粘贴到 PR 的“注释检查”章节，然后逐项填写：

- 文件职责、上游、下游、数据源和结果消费者；
- 现有注释时效是否已经核验；
- 扫描器无法识别、需要人工补充的匿名或动态符号；
- 每个方法、构造器或函数的判定与处理；
- 关键项的业务说明，或简单项无需注释的具体原因。

允许的判定值只有：

- `关键-已新增说明`
- `关键-已更新说明`
- `关键-现有说明已核验`
- `简单-无需说明`

报告不能保留“待填写”或“待核验”。如果报告列出任务历史、未来承诺或无信息复述，必须先删除或改写代码中的注释，再重新生成报告。

扫描器读取每个变化生产文件的完整当前内容，因此旧行中的过期注释也会进入检查。未发生变化的历史文件不阻塞普通 PR，由独立的全系统注释审计任务处理。

## 5. PR 和 CI

面向 `main` 的 PR 会运行：

- `Java 21 verify`：Maven `verify`；
- `Frontend verify`：`npm ci`、Vitest、Vue/TypeScript 类型检查和生产构建；
- `Repository guardrails`：文件范围、PR 必填章节、完整方法清单和历史注释风险。

文档-only PR 仍需填写六个 PR 章节，但“注释检查”可以写“不涉及生产代码”。生产代码 PR 漏掉任一变化文件、可识别方法、判定或具体原因时，`Repository guardrails` 会失败。

检查失败只会阻止合并，不会自动修改代码或 PR 正文。修复后继续推送原任务分支；编辑 PR 正文也会重新触发仓库防回退检查。

## 6. 合并后安全清理

只有用户明确通知 PR 已合并后才能清理。先在主工作目录运行默认预览：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-PostMergeCleanup.ps1 -TaskBranch chore/example -WorktreePath C:\absolute\registered\worktree
```

确认预览动作和路径正确后显式执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-PostMergeCleanup.ps1 -TaskBranch chore/example -WorktreePath C:\absolute\registered\worktree -Apply
```

脚本会先刷新远程引用，并验证主工作区干净、`main` 可以仅快进、任务分支已进入 `origin/main`、任务 worktree 已登记且干净。任一条件失败都会停止，不使用强制重置、强制推送或路径通配符。

## 7. GitHub `main` 分支保护

仓库文件不能自动启用 GitHub 分支保护。合并本机制后，仓库所有者需要在 GitHub 的 Rulesets 或 Branch protection rules 中为 `main` 配置并现场验证：

1. 必须通过 Pull Request 合并；
2. 合并前分支必须保持最新；
3. 必须解决全部 PR 对话；
4. 必需状态检查为 `Java 21 verify`、`Frontend verify`、`Repository guardrails`；
5. 禁止 force push；
6. 禁止删除 `main`；
7. 当前不要求另一名审核者批准；
8. 当前不强制签名提交。

只有在 GitHub 页面保存规则并用实际 PR 验证后，才能报告服务器端保护已经生效。在此之前，项目状态必须写成“待启用/待现场验证”。

## 8. 常见失败

- `GIT_HOOKS_NOT_INSTALLED`：运行安装脚本并回读 `core.hooksPath`。
- `OUTDATED_OR_DIVERGED_BASELINE`：停止修改，获取远程状态并按 `AGENTS.md` 处理基线，禁止强制覆盖。
- `AUDIT_SYMBOL_MISSING`：重新生成报告，补齐遗漏方法，包括私有方法。
- `AUDIT_REASON_INVALID`：填写具体业务说明或免注释原因，不能只写“无需注释”。
- `STALE_OR_LOW_VALUE_COMMENT`：删除任务历史、过期承诺或无信息复述，改写为当前真实行为。
- `FORBIDDEN_PATH`：从提交中移除本地配置、凭据、生成文件或运行数据，并检查是否需要轮换已经暴露的凭据。
