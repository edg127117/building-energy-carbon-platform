# iot-platform-demo 项目规则

本文件适用于整个仓库。用户当前指令优先；个人全局规则继续生效。这里只保存长期硬约束、Skill 触发条件和文档路由。

## 1. 建立任务上下文

每个任务先读取本文件，再只读取与任务直接相关的代码、测试和文档：

- 涉及架构、模块职责、数据链路、运行入口或项目事实时，读取 [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md)；
- 涉及当前能力、进度、技术债、风险、下一步或计划时，读取 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)；
- Git、Hook、预检、PR、CI 或合并后清理：读取 [`repository-guardrails.md`](docs/development/repository-guardrails.md)；
- Java、Vue 或 TypeScript 生产代码：读取 [`code-comments.md`](docs/development/code-comments.md)；
- Java 版本和环境：读取 [`java21.md`](docs/development/java21.md)；
- 历史决策：从 [`docs/superpowers/README.md`](docs/superpowers/README.md) 定位对应设计和计划，不能把历史状态当作当前事实。

文档与代码冲突时，以当前代码、自动化测试和已合并决策为证据；证据不足必须标记“部分完成”“未验证”或“待核验”。

## 2. Skill 触发

- 本仓库发生任何文件变化时，必须使用仓库级 `iot-change-verification` Skill 选择、执行并如实报告验证；纯只读解释不触发。
- 创建、修改、重构或审查生产代码及注释时，必须使用个人全局 `code-comment-quality` Skill，并叠加本仓库 [`code-comments.md`](docs/development/code-comments.md) 的 IoT 专项要求。
- 涉及文件写入、分支、暂存、提交、推送、PR、worktree 或合并后清理时，必须使用个人全局 `safe-pr-delivery` Skill，并遵守本仓库 Guardrail。
- Skill 不可用不免除本文件中的硬约束；仓库规则比通用 Skill 更具体时，以仓库规则为准。

## 3. 范围、设计与 Git

一个任务、分支和 PR 只处理一个明确问题。禁止顺手提交无关格式化、重构、配置、生成文件或本地状态。

新模块、新 API、数据库结构、权限、缓存、并发、数据流程、跨子系统功能或大范围架构变化，必须先确认设计和实施范围。纯文档、注释、拼写和不改变行为的简单格式调整可以跳过独立设计，但不能跳过范围、验证和 Git 流程。

修改前确认分支、远程、工作区和基线；从最新 `main` 创建短期任务分支，并在干净任务分支运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

成功标记必须是 `TASK_PREFLIGHT_OK`。本地 `main` 分叉、存在未推送提交、工作区不干净或基线不明时停止并报告，不得自动覆盖、重置、变基或带入无关改动。

暂存必须明确列出文件并检查暂存差异；禁止用 `git add .` 随意收集全部内容，禁止绕过 Hook、强推、直接在 `main` 开发或覆盖用户改动。只有用户明确通知“已合并”后，才能同步 `main` 并清理任务分支或 worktree。

## 4. 当前架构硬边界

- V1 保持 Spring Boot 单体后端和 Vue 前端；没有独立部署、扩容、故障隔离、团队发布或已证实瓶颈等收益时，不提前拆仓库或微服务。
- Controller 负责入口，Service 负责业务和事务，Repository/Mapper 负责数据访问，Adapter/Client 负责外部系统，Config 只负责装配。禁止 Controller 直连数据层、跨模块操作其他模块 Repository、循环依赖和无边界大型 Service。
- 公共能力必须已有两个以上真实使用方、含义稳定且不会形成反向依赖；禁止为未来假设提前抽象万能 `common`、`utils`。
- MySQL 保存结构化业务数据，TDengine 保存原始时序、分钟和指标数据，Redis 是缓存，MQTT 负责设备上行，WebSocket 负责实时发布。多数据源必须明确 Bean 和 `Qualifier`，SQL 不得跨数据源执行。
- 外部资源地址、凭据和开关通过配置管理；启动初始化、定时任务、补偿、MQTT 连接和演示数据必须在测试环境可关闭或替换。
- 普通自动化测试不得连接真实数据库、缓存、Broker、现场设备或第三方服务；集成或现场验证必须使用专用环境并明确标记。
- 前端不得生成随机业务数据、虚假历史或默认指标，不在浏览器重算后端指标。前端权限只改善体验，后端鉴权和建筑范围才是安全边界。
- 旧电表 Demo 已退出当前产品范围，不得恢复为并行业务链。当前只有采集、分析和展示能力，控制能力必须另行安全设计和验收。
- 现有边界无法承载需求时，先说明涉及模块、最小改动、正式重构及成本风险；未经确认不得扩大重构。

## 5. 验证与注释

验证矩阵以 [`.agents/skills/iot-change-verification/SKILL.md`](.agents/skills/iot-change-verification/SKILL.md) 为执行入口，以 [`verification.md`](docs/development/verification.md) 为开发者说明。生产 Java 代码通常需要定向测试和完整 Maven 测试；Vue/TypeScript 生产代码通常需要定向 Vitest、完整测试、lint、类型检查和构建。纯文档或流程整改只运行直接相关检查。

测试失败时不得推送为可合并 PR。提交和 PR 必须如实列出实际命令、结果、跳过和未验证原因，不能复用历史数量或把局部验证描述成完整功能。

生产代码注释使用简洁中文，说明职责、上下游、数据源、业务边界、单位、状态、异常、副作用和设计原因；不逐行翻译，不记录任务历史，不把预留能力写成已实现。

注释检查按风险分级：

- 高风险：权限、数据归属、状态、事务、并发、重试/补偿、多数据源、MQTT、WebSocket、外部资源、公式、聚合、时间语义或跨层契约；检查完整变化文件及相关调用链。
- 普通风险：局部业务逻辑、DTO 映射、页面状态或错误处理；检查变化及受影响的方法/组件。
- 低风险：纯注释、拼写、等价重命名或格式；检查变化附近和是否存在过期、误导或低价值注释。

PR 在“注释检查”中记录风险级别、检查范围和结论。普通生产代码 PR 不再强制新增永久审计文档；历史报告保持不可变，专项全量审计可继续使用现有脚本生成。

## 6. 项目信息分层与交付

- `AGENTS.md`：长期硬规则和条件式路由；
- `PROJECT_GUIDE.md`：稳定项目定位、模块、数据链路、运行入口和文档导航；
- `PROJECT_STATUS.md`：当前完成项、未完成项、风险、技术债和下一步；
- `docs/development`：开发者长期规范；
- `.agents/skills`：Codex 可执行的项目专项流程；
- `docs/superpowers/specs`、`plans`：具体任务当时的设计和实施历史；
- 当前会话：命令、错误和临时判断。

稳定架构或文档入口变化时更新 `PROJECT_GUIDE.md`；阶段、完成项、技术债、风险或下一步变化时更新 `PROJECT_STATUS.md`。不得在项目文档中写入密码、Token、私钥、未脱敏数据、个人临时路径或无证据完成声明。

只有新正式版本、重大架构调整或权威需求/设计变化才启用版本治理：在 `PROJECT_STATUS.md` 区分当前权威基线与活动候选，在对应设计或 ADR 中逐项记录旧硬约束是保留、迁移、明确替代还是未解决。普通任务、分支和 PR 不因此增加平行版本或额外审批；重大版本或重要里程碑需经干净 Codex 任务独立验收并由用户最终批准后，才能替换当前权威基线。

交付前确认范围、架构和数据边界、实际验证、注释检查、文档同步、暂存文件、敏感信息、生成文件及 `git diff --check`；完成提交和推送后提供完整 PR 材料，不把“等待用户创建并合并 PR”说成已完成合并。
