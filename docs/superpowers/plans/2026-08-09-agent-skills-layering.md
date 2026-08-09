# Agent Skills Layering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建两个个人全局 Skill 和一个 IoT 项目级验证 Skill，并将本仓库常驻规则与强制永久注释审计改为短硬约束和风险分级流程。

**Architecture:** 通用注释质量与安全 PR 交付放入个人全局 Skill；IoT 专项测试矩阵放入仓库级 Skill。仓库 `AGENTS.md` 保留不可突破的项目边界和触发条件，CI 继续检查可自动判断的质量风险，但不再要求每个生产代码 PR 新增全符号审计文档。

**Tech Stack:** Markdown、YAML、PowerShell、Git、Codex Skill Creator。

## Global Constraints

- 不修改 Java、Vue、TypeScript 业务代码和 `PROJECT_STATUS.md` 业务状态。
- 不删除历史注释审计文档；只停止对新 PR 的强制创建要求。
- 生产代码测试门槛保持不变。
- 个人全局 Skill 不写入仓库，不包含任何项目路径、状态或业务事实。
- 所有 Git 写操作继续服从当前仓库 Hook、预检、分支和 PR 规则。

---

### Task 1: 创建个人全局注释质量 Skill

**Files:**
- Create: `C:/Users/yang/.codex/skills/code-comment-quality/SKILL.md`
- Create: `C:/Users/yang/.codex/skills/code-comment-quality/agents/openai.yaml`
- Create: `C:/Users/yang/.codex/skills/code-comment-quality/references/java-spring.md`
- Create: `C:/Users/yang/.codex/skills/code-comment-quality/references/vue-typescript.md`
- Modify: `C:/Users/yang/.codex/AGENTS.md`

**Interfaces:**
- Consumes: 创建、修改或审查生产代码的任务描述，以及当前仓库更具体的注释规则。
- Produces: 变更及受影响范围的注释判断、必要修改和精简检查结论。

- [ ] 使用 Skill Creator 的 `init_skill.py` 初始化 `code-comment-quality`，只创建 `references` 和 `agents/openai.yaml`。
- [ ] 在 frontmatter `description` 中明确触发 Java、Spring Boot、Vue、TypeScript 及其他生产代码的创建、修改和审查；排除纯文档、纯数据和不涉及源码的任务。
- [ ] 在 `SKILL.md` 定义“识别变化范围 → 判断关键职责 → 检查已有注释 → 最小补充或删除噪声 → 输出结果”的流程。
- [ ] 将 Java/Spring 与 Vue/TypeScript 的语言差异分别放入两个 reference，避免在主文件重复。
- [ ] 在个人 `AGENTS.md` 增加一条硬触发规则，不复制完整 Skill 正文。
- [ ] 运行 `quick_validate.py`，预期 Skill 校验通过且不存在临时占位文本。

### Task 2: 创建个人全局安全 PR 交付 Skill

**Files:**
- Create: `C:/Users/yang/.codex/skills/safe-pr-delivery/SKILL.md`
- Create: `C:/Users/yang/.codex/skills/safe-pr-delivery/agents/openai.yaml`
- Modify: `C:/Users/yang/.codex/AGENTS.md`

**Interfaces:**
- Consumes: 当前仓库 `AGENTS.md`、Git 状态、远程状态及仓库提供的预检/清理脚本。
- Produces: 安全任务分支、明确暂存、验证记录、提交、推送和 PR 材料；合并后仅在用户确认后清理。

- [ ] 使用 `init_skill.py` 初始化 `safe-pr-delivery`，不增加脚本或项目特定资源。
- [ ] 在 `SKILL.md` 定义只读任务不触发写流程，仓库写入任务按“核对规则 → 基线 → 分支 → 预检 → 明确暂存 → 验证 → 提交 → 推送 → PR → 合并后清理”执行。
- [ ] 保留禁止强推、禁止绕过 Hook、禁止覆盖用户改动和不自动清理未合并分支等恢复边界。
- [ ] 明确仓库本地规则优先，Skill 不假设所有仓库都有相同脚本、CI 或默认分支。
- [ ] 在个人 `AGENTS.md` 增加仓库写入和 Git 交付的触发规则。
- [ ] 运行 `quick_validate.py`，预期 Skill 校验通过且不存在项目路径或状态事实。

### Task 3: 创建 IoT 项目级验证 Skill

**Files:**
- Create: `.agents/skills/iot-change-verification/SKILL.md`
- Create: `.agents/skills/iot-change-verification/agents/openai.yaml`
- Modify: `AGENTS.md`
- Modify: `PROJECT_GUIDE.md`
- Modify: `docs/development/verification.md`

**Interfaces:**
- Consumes: 当前 Git diff、`pom.xml`、`web/package.json`、CI 配置和项目硬边界。
- Produces: 与变化范围匹配的后端、前端、跨端、外部资源或文档验证命令及如实结果。

- [ ] 使用 `init_skill.py` 在 `.agents/skills` 初始化 `iot-change-verification`。
- [ ] 将现有 `verification.md` 的可执行流程迁入 Skill，按 Java、Vue/TypeScript、跨端、外部资源、流程文档五类选择验证；原文件缩短为开发者可读的原则和 Skill 入口。
- [ ] 保留完整 Maven 测试、Vitest、lint、类型检查、构建、测试隔离和结果记录要求；纯文档只运行直接相关检查。
- [ ] 更新 `AGENTS.md` 为强制触发句和 IoT 硬边界，删除重复命令和无条件读取 `PROJECT_GUIDE.md`/`PROJECT_STATUS.md` 的要求。
- [ ] 更新 `PROJECT_GUIDE.md` 同时导航到仓库 Skill 和精简后的开发者说明，避免维护两份完整流程。
- [ ] 运行 Skill 校验和 Markdown 链接检查。

### Task 4: 将 IoT 注释审计改为风险分级

**Files:**
- Modify: `AGENTS.md`
- Modify: `PROJECT_GUIDE.md`
- Modify: `docs/development/code-comments.md`
- Modify: `docs/development/repository-guardrails.md`
- Modify: `docs/reviews/comment-audits/README.md`
- Modify: `.github/pull_request_template.md`
- Modify: `scripts/Test-RepositoryGuardrails.ps1`
- Modify: `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`
- Keep: `scripts/New-CommentAuditReport.ps1`

**Interfaces:**
- Consumes: 生产代码变化文件和 PR 正文。
- Produces: PR 中简洁的“注释检查”结论，以及对高置信度过期/低价值注释的自动阻断；不要求新增永久报告。

- [ ] 将通用注释标准移交全局 Skill，`code-comments.md` 只保留 IoT 数据源、权限、状态、时间、单位和跨层链路专项要求。
- [ ] 定义高风险、普通风险、低风险检查范围；仅高风险要求完整变化文件和相关调用链检查。
- [ ] 将 PR 模板章节从“注释审计”改为“注释检查”，要求填写风险级别、检查范围、修改或无需修改的结论。
- [ ] 从 `Test-RepositoryGuardrails.ps1` 移除审计文档链接、路径、新增性、元数据和全符号表格契约；保留禁止路径、新增 Java 类说明、新增前端业务说明和低价值注释扫描。
- [ ] 保留 `New-CommentAuditReport.ps1` 作为历史兼容与人工专项审计工具，不再作为普通 PR 必需入口。
- [ ] 重写 guardrail 场景：生产代码 PR 无审计文档可通过、缺少注释检查章节失败、低价值注释失败、纯文档 PR 通过、历史内嵌审计兼容不阻塞。
- [ ] 更新历史归档 README，明确旧报告不可变但新 PR 不再强制生成。

### Task 5: 更新目录记录并完成验证

**Files:**
- Modify: `docs/superpowers/README.md`
- Verify: 本计划列出的全部个人与仓库文件。

**Interfaces:**
- Consumes: Task 1–4 的完整差异。
- Produces: 可审查、可验证并可继续使用现有 PR 的提交。

- [ ] 在历史任务目录登记本设计和实施计划，不把计划勾选状态当作当前项目状态。
- [ ] 对三个 Skill 分别运行 `quick_validate.py`，检查 `agents/openai.yaml` 与触发描述一致。
- [ ] 运行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-RepositoryGuardrailTests.ps1`，预期全部场景通过。
- [ ] 运行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Invoke-LocalGitGuardrailTests.ps1`，确认 Git 本地保护未回退。
- [ ] 运行 Markdown 链接、PowerShell 解析、关键术语、文件大小和 `git diff --check` 检查。
- [ ] 明确暂存文件，提交并推送现有 `docs/agents-instruction-layering` 分支；更新原 Compare/PR 材料。
