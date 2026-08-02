# 将项目 AGENTS.md 纳入 Git 跟踪设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 目标

将原仓库根目录的本地规则文件 `AGENTS.md` 纳入现有
`edg127117/iot-platform-demo` 仓库版本管理，使所有分支、worktree、克隆目录和
新启动的 Codex 会话都能在项目根目录自动发现并遵守同一套项目规则。

## 实施边界

- 使用独立分支 `chore/track-project-agents`。
- 原样纳入现有 `D:\word\iot-platform-demo\AGENTS.md`，不改写三组规则内容。
- 不修改计算引擎代码、配置或测试。
- 不提交原仓库的 `outputs/` 等无关文件。
- `.git/info/exclude` 是机器本地配置，不提交到仓库；文件一旦被 Git 跟踪，
  该本地排除项不会再阻止它出现在分支和worktree中。

## Git交付流程

1. 从已包含计算引擎的最新 `origin/main` 创建或快进任务分支。
2. 将根目录 `AGENTS.md` 原样加入 Git 索引。
3. 验证文件内容、跟踪状态和提交范围。
4. 使用符合项目规范的提交信息提交。
5. 推送远程任务分支并向用户提供PR创建链接、标题和说明。
6. 用户创建并合并PR；收到“已合并”通知后再同步本地 `main` 并清理分支。

## 验收标准

- `git ls-files AGENTS.md` 能返回 `AGENTS.md`。
- 新建worktree时根目录自动出现该文件。
- `git check-ignore AGENTS.md` 不再影响已跟踪文件。
- 提交只包含 `AGENTS.md`、本设计说明及后续实施计划。
- 不包含密码、Token、本地路径配置或其他敏感信息。
