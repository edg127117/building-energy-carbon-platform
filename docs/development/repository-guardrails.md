# 仓库开发流程与质量防护

## 1. 能力边界

仓库使用项目规则、本地 Git Hook、任务预检、GitHub Actions 和 GitHub 分支保护共同约束开发流程：

- Hook 阻止在 `main` 提交以及直接更新远程 `main`；
- 任务预检验证规则入口、任务分支、干净工作区、基线和 Hook；
- PR 模板记录范围、测试和风险分级注释检查；
- Repository Guardrails 检查禁止路径、PR 必填结构、新增生产文件的关键说明和高置信度低价值注释；
- 后端与前端 CI 分别验证 Java 和 Vue/TypeScript；
- GitHub 分支保护提供最终服务器端合并边界。

Hook 可被人为删除或绕过，因此不能代替分支保护。自动化只能判断结构和高置信度文本风险，不能代替业务语义复核。

## 2. 安装 Hook

每个本地 clone 或独立 Git 配置环境执行一次：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Install-GitGuardrails.ps1
```

成功标记为 `GIT_GUARDRAILS_INSTALLED`。安装器只设置当前仓库的 `core.hooksPath=.githooks`，可用以下命令回读：

```powershell
git config --local --get core.hooksPath
```

## 3. 新任务预检

同步 `main`、创建任务分支且工作区仍干净时执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

成功标记为 `TASK_PREFLIGHT_OK`。脚本验证规则入口、任务分支、工作区、`origin/main` 祖先关系和 Hook，但不会获取远程状态，也不能证明执行者已经理解规则。

## 4. PR 与 CI

所有 PR 必须填写：

- `变更内容`：只写本 PR 已完成内容和明确不包含的范围；
- `测试`：记录实际命令、结果、跳过和未验证项；
- `注释检查`：填写风险级别、检查范围和结论。

允许的风险级别为“高”“普通”“低”“不涉及生产代码”。生产代码变化按 [`code-comments.md`](code-comments.md) 选择范围；没有生产代码变化时使用“不涉及生产代码”。

面向 `main` 的 PR 会运行：

- `Java 21 verify`：Maven `verify`；
- `Frontend verify`：依赖安装、Vitest、类型检查和生产构建；
- `Repository guardrails`：范围、PR 结构、注释检查字段和高置信度文本风险。

普通生产代码 PR 不再要求创建或链接永久注释审计文档。历史 PR 中已有的内嵌审计或归档链接不作为失败条件，但新 PR 应使用当前“注释检查”结构。

检查失败只阻止合并，不自动修改代码或 PR 正文。修复后继续推送原任务分支；编辑 PR 正文也会重新触发 Guardrail。

## 5. 专项注释审计

[`New-CommentAuditReport.ps1`](../../scripts/New-CommentAuditReport.ps1) 保留用于人工专项全量审计和读取历史格式，不是普通 PR 必需步骤。需要生成报告时必须显式指定安全的仓库相对 `-OutputPath`；`-Format Json` 供自动文本风险扫描使用，两者不能同时使用。

专项报告仍放在 `docs/reviews/comment-audits/<year>/`，一旦合并即视为历史快照，不回写旧报告。使用方法和归档边界见 [`docs/reviews/comment-audits/README.md`](../reviews/comment-audits/README.md)。

## 6. 合并后安全清理

只有用户明确通知 PR 已合并后才能清理。先预览：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-PostMergeCleanup.ps1 -TaskBranch chore/example -WorktreePath C:\absolute\registered\worktree
```

确认路径和动作后显式执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-PostMergeCleanup.ps1 -TaskBranch chore/example -WorktreePath C:\absolute\registered\worktree -Apply
```

脚本会刷新远程引用，并验证主工作区干净、`main` 仅快进、任务分支已进入 `origin/main`、任务 worktree 已登记且干净；任一条件失败都会停止。

## 7. GitHub `main` 分支保护

仓库文件不能自动启用 GitHub 分支保护。仓库所有者需在 GitHub Rulesets 或 Branch protection rules 中配置并现场验证：必须通过 PR、合并前保持最新、解决全部对话、通过三个必需状态检查、禁止 force push 和删除 `main`。当前不要求另一名审核者批准或签名提交。

只有页面保存并用实际 PR 验证后，才能声称服务器端保护已生效。

## 8. 常见失败

- `GIT_HOOKS_NOT_INSTALLED`：运行安装脚本并回读 `core.hooksPath`。
- `OUTDATED_OR_DIVERGED_BASELINE`：停止修改并安全处理基线，禁止强制覆盖。
- `PR_SECTION_MISSING`：补齐“变更内容”“测试”或“注释检查”。
- `COMMENT_RISK_LEVEL_MISSING`：填写允许的风险级别。
- `COMMENT_SCOPE_MISSING`：说明实际检查的文件、调用链、方法或变化附近。
- `COMMENT_RESULT_MISSING`：说明已修改内容或无需修改的理由。
- `STALE_OR_LOW_VALUE_COMMENT`：删除任务历史、过期承诺或无信息复述，改写为当前真实行为。
- `FORBIDDEN_PATH`：移除本地配置、凭据、生成文件或运行数据，并评估是否需要轮换已暴露凭据。
