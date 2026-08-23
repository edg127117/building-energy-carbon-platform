# building-energy-carbon-platform 项目规则

本仓库用于开发“建筑能碳监测管理平台”。用户当前指令优先；本文件只保存长期硬约束和文档路由，不记录临时任务过程。

## 1. 任务上下文

每个任务先读本文件，再按需读取：

- 架构、模块、数据链路、运行入口：[`PROJECT_GUIDE.md`](PROJECT_GUIDE.md)；
- 当前能力、阶段、风险和下一步：[`PROJECT_STATUS.md`](PROJECT_STATUS.md)；
- Git、Hook、PR、CI：[`repository-guardrails.md`](docs/development/repository-guardrails.md)；
- 生产代码注释：[`code-comments.md`](docs/development/code-comments.md)；
- Java 环境：[`java21.md`](docs/development/java21.md)。

代码、测试与文档冲突时，以当前代码、自动化测试和已批准决策为准。证据不足必须写“部分完成”“未验证”或“计划中”。历史设计和计划只用于追溯，不能代表当前状态。

仓库内部分开发规范、Skill、脚本和代码仍含 `iot-platform-demo` 或 `HVAC` 名称；其工程约束和现有行为继续有效，但其中的旧产品定位不能覆盖本文件、`PROJECT_GUIDE.md` 和 `PROJECT_STATUS.md`。

## 2. 新旧系统边界

- 本仓库由旧中央空调仓库 `iot-platform-demo` 的冻结基线复制而来，保留其 Git 历史和已实现代码。
- `origin` 是本项目仓库；`upstream-hvac` 只用于追溯旧仓库，不是本项目的交付目标，也不自动同步规则和业务状态。
- 现有 HVAC 19 测点、公式、页面和脚本属于“继承能力”，不等于建筑能碳平台已经完成。
- 新平台目标范围逐步扩展到冷热源、空调及通风、供配电和环境监测；范围外系统必须另行确认。
- GB/T 47474—2026 是需求依据，不得在逐条实现和验收前宣称平台整体符合标准。
- 当前只建设监测、分析和展示能力。远程控制、指令下发、响应执行、策略生成/下发均不在当前范围，除非完成独立安全设计并获明确批准。

产品阶段顺序固定为：

1. 数据监测与后台基础架构；
2. 可视化实现；
3. 虚拟数据测试及次级应用点完善。

阶段名称描述产品推进顺序，不等于阶段内全部功能已实现；完成状态只看 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 和验证证据。

## 3. 架构与实现硬边界

- 当前保持 Spring Boot 单体后端和 Vue 前端；没有可验证收益时不提前拆微服务或拆仓库。
- Controller 负责入口，Service 负责业务与事务，Repository/Mapper 负责数据访问，Adapter/Client 负责外部系统，Config 只负责装配。
- MySQL 保存结构化业务数据，TDengine 保存时序与指标数据，Redis 只作缓存，MQTT 负责设备上行，WebSocket 负责实时发布。
- 南向设备协议在边缘网关/协议适配层转换，平台通过统一 MQTT/HTTP 契约接入。当前已实现 MQTT 基础链；Modbus、BACnet、NB-IoT 等不得写成已实现。
- 保留物理空间树表达包含关系；完整语义关系模型在其上表达空间、系统、设备、测点、计量边界和服务关系，不能把两者混为一棵树。
- Q0/Q1/Q2 质量策略必须按场景可配置、可追溯，禁止把某一等级硬编码为所有指标的统一规则。
- 能源指标、边界、公式、阈值、归因、模型和策略由能源专家确认；协议字段、设备能力和边缘行为由硬件方确认；软件不得自行猜测专业规则。
- 前端不得生成随机业务数据、虚假历史或在浏览器重算后端指标。后端鉴权和建筑范围是安全边界。
- 普通自动化测试不得连接现场设备或真实外部资源；集成、硬件和现场验收必须独立标记。

## 4. 范围、设计与 Git

- 一个任务、分支和 PR 只处理一个明确问题，禁止混入无关格式化、重构、配置或生成文件。
- 新模块、API、数据库结构、权限、并发、数据流或跨系统变化，先确认设计、专业输入、外部依赖和验收边界。
- PR 标题使用 `type(scope): 中文动宾短语`；类型与实际差异一致。
- 修改前确认分支、远程和工作区，并在干净任务分支运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

成功标记必须是 `TASK_PREFLIGHT_OK`。禁止绕过 Hook、强推、直接在 `main` 开发或覆盖用户改动；暂存必须明确列出文件。只有用户确认已合并后才能清理分支或 worktree。

## 5. Skill 与验证

- 仓库发生文件变化时使用 `iot-change-verification` Skill 选择验证范围。
- 修改生产代码及注释时使用 `code-comment-quality` Skill，并遵守 [`code-comments.md`](docs/development/code-comments.md)。
- 涉及写入、提交、推送、PR 或 worktree 时使用 `safe-pr-delivery` Skill。
- 纯文档只检查链接、术语、状态边界和 `git diff --check`；生产 Java 或 Vue/TypeScript 按仓库验证矩阵执行测试、检查和构建。
- 不得用历史测试结果、局部验证或合成数据代替本次验证、真实设备验收或现场验收。

## 6. 文档治理

- `AGENTS.md`：长期规则与路由；
- `PROJECT_GUIDE.md`：稳定定位、架构、链路和职责；
- `PROJECT_STATUS.md`：当前基线、阶段、完成项、风险和下一步；
- `docs/adr`：需长期追溯的重大技术决策；
- `docs/designs`：正式版本、重大架构或权威需求变化；
- `docs/superpowers`：旧系统历史任务记录，只读追溯。

当前权威基线与活动候选必须分开描述。旧约束只能明确标为“保留、迁移、替代或未解决”，不能因改名或删除旧文档而被视为自动失效。不得写入密码、Token、私钥、未脱敏数据、个人临时路径或无证据的完成声明。
