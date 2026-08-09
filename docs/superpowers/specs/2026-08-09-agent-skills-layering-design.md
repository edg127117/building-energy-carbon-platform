# Agent Skills 分层整改设计

## 状态

已确认。

## 背景

上一轮已经将根 `AGENTS.md` 从大段细则精简为硬约束和文档路由，但通用 Git 交付、通用生产代码注释以及本仓库验证流程仍以常驻规则或普通文档存在。用户希望这些重复流程在相关任务中按需加载，同时保持项目边界、测试质量和 Git 安全性。

## 目标

- 使用个人全局 `code-comment-quality` Skill 统一生产代码注释质量流程。
- 使用个人全局 `safe-pr-delivery` Skill 统一安全分支、提交、推送、PR 和合并后清理流程。
- 在本仓库增加项目级 `iot-change-verification` Skill，负责后端、前端、跨端、外部资源隔离和结果记录。
- 根 `AGENTS.md` 只保留硬边界、Skill 触发条件和项目专项差异。
- 将注释审计从“所有生产代码 PR 全文件、全符号、永久报告”改为风险分级，避免小修改产生不成比例的流程工作量。

## Skill 边界

### 个人全局 Skill

- `code-comment-quality`：创建、修改或审查生产代码时触发；检查变更及受影响范围的职责、上下游、业务边界、单位、异常、副作用和既有注释时效。
- `safe-pr-delivery`：发生仓库写入、提交、推送、PR 或合并后清理时触发；服从当前仓库更具体的 Hook、预检、CI 和分支规则。

个人全局 Skill 不进入本仓库 Git。后续开发者若要获得完全相同的自动流程，需要单独安装；仓库自身仍保留足够的最小硬约束。

### 本仓库 Skill

`iot-change-verification` 存放于 `.agents/skills/iot-change-verification/` 并进入 Git。它根据实际变化选择后端、前端、跨端、流程脚本或文档验证，禁止用历史测试数量代替本次证据。

## 注释审计分级

- 高风险：权限、数据归属、状态变化、事务、并发、重试、补偿、多数据源、MQTT、WebSocket、外部资源、公式、聚合、时间语义和跨层数据契约。检查完整变化文件和相关调用链，并在 PR 中记录结论。
- 普通风险：局部业务逻辑、DTO 映射、页面状态和错误处理。检查变更及受影响方法，不要求枚举文件内全部无关符号。
- 低风险：注释、拼写、纯重命名、格式或不改变行为的机械调整。只核验变化附近注释，不生成永久审计报告。

现有历史审计文档保持不变。新的 PR 不再强制创建 `docs/reviews/comment-audits/<year>/...` 永久报告；CI 继续检查禁止路径、PR 必填信息和高置信度低价值注释风险，但不强制全符号审计文档。

## 文件变化

- 个人全局：创建两个 Skill，并在个人 `AGENTS.md` 增加触发规则。
- 本仓库：创建 `.agents/skills/iot-change-verification/`。
- 精简 `AGENTS.md`、`docs/development/code-comments.md`、`docs/development/verification.md` 和 `docs/development/repository-guardrails.md`。
- 同步调整注释审计生成器、仓库守卫脚本、测试、PR 模板和相关 CI 契约；保留历史兼容读取，不再要求新报告。
- 更新 `PROJECT_GUIDE.md` 和 `docs/superpowers/README.md` 的导航，不改变 `PROJECT_STATUS.md` 的业务状态。

## 验证

- 使用 Skill Creator 校验两个全局 Skill和本仓库 Skill 的结构与触发描述。
- 以 Java、Vue/TypeScript、纯文档和 Git 交付示例核对 Skill 触发边界。
- 运行仓库 guardrail 全部回归测试，覆盖无审计文档的普通生产代码 PR、高风险注释结论、纯文档 PR 和历史兼容场景。
- 检查 Markdown 链接、PowerShell 语法、工作区范围和 `git diff --check`。
- 本次不修改业务代码，因此不机械运行无关 Maven、Vitest 或前端构建。

## 非目标

- 不修改 Java、Vue、TypeScript 业务行为。
- 不降低生产代码测试门槛。
- 不删除历史注释审计文档。
- 不把 IoT 项目事实写入个人全局 Skill。
- 不把个人全局 Skill 误写成已随仓库分发。
