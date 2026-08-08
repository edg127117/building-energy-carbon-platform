# AGENTS Instruction Layering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将根 `AGENTS.md` 精简为稳定路由，并把详细测试和注释规则迁入按需文档，同时保持现有规则语义。

**Architecture:** 根规则只保存每个任务都必须加载的约束和条件式入口；稳定项目事实留在 `PROJECT_GUIDE.md`，详细开发规范放在 `docs/development`。本次只改文档，不改变代码、Hook、CI 或业务状态。

**Tech Stack:** Markdown、PowerShell、Git、仓库现有 guardrail 脚本。

## Global Constraints

- 任务从仓库根目录启动，不创建子目录 `AGENTS.md`。
- 根 `AGENTS.md` 目标不超过 10 KiB，且与个人全局规则合计低于默认 32 KiB。
- 保留现有 Git、架构、测试、注释和信息维护规则的有效语义。
- 不修改生产代码、构建配置、Hook、CI 或 `PROJECT_STATUS.md` 的业务状态。

---

### Task 1: 建立详细规则文档

**Files:**
- Create: `docs/development/code-comments.md`
- Create: `docs/development/verification.md`

**Interfaces:**
- Consumes: 当前 `AGENTS.md` 的测试和注释章节。
- Produces: 根规则可以按任务条件引用的完整规范。

- [ ] 提取并去重生产代码注释、调用链说明和审计报告要求，写入 `code-comments.md`。
- [ ] 提取后端、前端、外部资源隔离和跨端验证要求，写入 `verification.md`。
- [ ] 使用 `rg` 核对关键术语和必需命令均可定位。

### Task 2: 精简根规则和更新导航

**Files:**
- Modify: `AGENTS.md`
- Modify: `PROJECT_GUIDE.md`

**Interfaces:**
- Consumes: Task 1 的详细规则文件与现有 `repository-guardrails.md`。
- Produces: 小于 10 KiB 的根规则、稳定且无重复的文档导航。

- [ ] 将 `AGENTS.md` 改写为强制读取顺序、条件式路由和不可省略的核心约束。
- [ ] 在 `PROJECT_GUIDE.md` 中登记新的开发规范入口，并保持项目事实不变。
- [ ] 统计文件大小，确认全局与项目根规则合计低于 32 KiB。

### Task 3: 验证、提交和推送

**Files:**
- Verify: 本计划列出的全部变化文件。

**Interfaces:**
- Consumes: Task 1 和 Task 2 的完整差异。
- Produces: 可审查的任务分支和 PR 材料。

- [ ] 运行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1`，预期退出码为 0。
- [ ] 运行链接、关键语义、文件大小、`git diff --check` 和无关文件检查。
- [ ] 明确暂存文件并提交 `docs(process): layer project agent instructions`。
- [ ] 推送 `docs/agents-instruction-layering` 并提供 PR 材料。
