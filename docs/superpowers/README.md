# 历史任务设计与实施计划目录

本目录中的 `specs` 和 `plans` 保存任务发生时的设计依据与实施步骤，不是当前项目状态入口。
判断当前行为时，依次核对当前代码与测试、[固定工作规则](../../AGENTS.md)、
[项目指南](../../PROJECT_GUIDE.md) 和 [项目状态](../../PROJECT_STATUS.md)。

计划中的复选框只记录原计划步骤，不代表当前完成状态；执行旧命令前必须重新核验。
设计与计划会合理复用范围、约束和验收标准，这类职责重叠不等于重复文件。
历史正文还可能保留任务当时的本机绝对路径、容器名和测试示例凭据；这些内容只用于审计，不能复制为当前环境配置或生产凭据。

## 状态含义

- `成对历史记录`：同一任务同时保存设计与计划，文件名可以直接对应。
- `名称不一致`：设计与计划属于同一任务，但文件名不同。
- `仅实施计划`：当前只找到计划，没有独立设计稿。
- `被后续任务替代`：文档中的关键现状、路径或前提已由后续任务改变，正文仅作历史证据。
- `待核验`：仅凭当前证据还不能确认关系或替代情况。

## 任务目录

| 任务主题 | 设计记录 | 实施计划 | 关系状态 | 当前使用说明 |
| --- | --- | --- | --- | --- |
| 后端代码生成器 V1 | [设计](specs/2026-07-22-backend-code-generator-v1-design.md) | [计划](plans/2026-07-22-backend-code-generator-v1.md) | 成对历史记录 | 生成器当前是否可用须核对代码和项目状态。 |
| 四角色 RBAC 与建筑授权 | [设计](specs/2026-07-22-four-role-rbac-design.md) | [计划](plans/2026-07-22-four-role-rbac-implementation.md) | 成对历史记录 | 当前权限边界以代码、测试和项目指南为准。 |
| RBAC 代码注释 | — | [计划](plans/2026-07-22-rbac-code-comments-plan.md) | 仅实施计划 | 没有独立设计稿；注释现状须直接查看当前代码。 |
| 19 测点接入与质量基础 | [设计](specs/2026-07-23-19-point-ingestion-quality-design.md) | [计划](plans/2026-07-23-19-point-ingestion-quality-implementation.md) | 被后续任务替代 | 接入设计仍有参考价值；计划中“保留旧电表链”的前提已被旧电表下线任务替代。 |
| 多建筑资产与测点身份 | [设计](specs/2026-07-23-building-scoped-equipment-point-identity-design.md) | [计划](plans/2026-07-23-industrial-asset-point-identity-implementation.md) | 名称不一致 | 同一任务的设计与计划命名不同；旧电表现状和 COP 未实现状态已被后续任务替代。 |
| HVAC 整分钟批量聚合 | [设计](specs/2026-07-23-hvac-batch-minute-aggregation-design.md) | [计划](plans/2026-07-23-hvac-batch-minute-aggregation-implementation.md) | 成对历史记录 | 当前聚合行为须核对实现与测试。 |
| HVAC 冻结分钟查询 API | [设计](specs/2026-07-24-hvac-query-api-design.md) | [计划](plans/2026-07-24-hvac-query-api-implementation.md) | 成对历史记录 | 当前接口契约以 Controller、DTO 和测试为准。 |
| 最小双数据源与测试隔离 | [设计](specs/2026-07-24-minimal-dual-datasource-test-isolation-design.md) | [计划](plans/2026-07-24-minimal-dual-datasource-test-isolation.md) | 成对历史记录 | 当前 MySQL/TDengine 边界以项目指南和配置为准。 |
| HVAC API 路径修正 | [设计](specs/2026-07-27-hvac-api-path-design.md) | [计划](plans/2026-07-27-hvac-api-path.md) | 成对历史记录 | 当前 HTTP 路径以代码和 MQTT 对接说明为准。 |
| HVAC 核心公式引擎 | [设计](specs/2026-07-27-hvac-core-formula-engine-design.md) | [计划](plans/2026-07-27-hvac-core-formula-engine.md) | 成对历史记录 | 已替代早期文档中的“COP 尚未实现”状态。 |
| Java 21 开发与后端 CI | [设计](specs/2026-07-27-java21-development-ci-design.md) | [计划](plans/2026-07-27-java21-development-ci.md) | 成对历史记录 | 当前环境用法以 `docs/development/java21.md`、Wrapper 和 CI 为准。 |
| 将 AGENTS.md 纳入 Git | [设计](specs/2026-07-27-track-project-agents-design.md) | [计划](plans/2026-07-27-track-project-agents.md) | 成对历史记录 | 当前固定规则直接查看仓库根目录 `AGENTS.md`。 |
| 本地基础设施端口可配置 | [设计](specs/2026-07-28-configurable-local-ports-design.md) | [计划](plans/2026-07-28-configurable-local-ports.md) | 成对历史记录 | 当前默认值和覆盖方式以 Compose、脚本和示例环境文件为准。 |
| HVAC 最新指标回退查询 | [设计](specs/2026-07-28-hvac-latest-indicator-query-design.md) | [计划](plans/2026-07-28-hvac-latest-indicator-query.md) | 被后续任务替代 | 查询修复仍有参考价值；计划中的 `/ws/dashboard` 已被旧电表下线任务替换为 `/ws/hvac`。 |
| HVAC 数据质量补全 | [设计](specs/2026-07-29-hvac-data-quality-fill-design.md) | [计划](plans/2026-07-29-hvac-data-quality-fill.md) | 成对历史记录 | 当前补全和质量语义以实现、配置和测试为准。 |
| HVAC 人工重算批次 | [设计](specs/2026-07-29-hvac-data-quality-recalculation-design.md) | [计划](plans/2026-07-29-hvac-data-quality-recalculation.md) | 成对历史记录 | 当前重算入口和状态语义以实现与项目状态为准。 |
| HVAC 计算测点单位契约 | [设计](specs/2026-07-30-hvac-calculation-point-unit-contract-design.md) | [计划](plans/2026-07-30-hvac-calculation-point-unit-contract.md) | 被后续任务替代 | 单位契约仍有效；计划中的 `/ws/dashboard` 已被旧电表下线任务替换为 `/ws/hvac`。 |
| 旧电表 Demo 下线 | [设计](specs/2026-07-30-legacy-meter-demo-decommission-design.md) | [计划](plans/2026-07-30-legacy-meter-demo-decommission.md) | 成对历史记录 | 该任务是旧电表并行链和 `/ws/dashboard` 的主要替代依据。 |
| MQTT 测试隔离与干净 Docker 冒烟 | [设计](specs/2026-07-30-mqtt-test-isolation-clean-docker-smoke-design.md) | [计划](plans/2026-07-30-mqtt-test-isolation-clean-docker-smoke.md) | 成对历史记录 | 删除旧容器和匿名卷的命令只适用于任务当时环境，禁止直接复用。 |
| 测点运行时单位校验 | [设计](specs/2026-07-30-runtime-point-unit-validation-design.md) | [计划](plans/2026-07-30-runtime-point-unit-validation.md) | 成对历史记录 | 当前校验边界以运行时代码和测试为准。 |
| 一键启动 Compose 路径兼容 | [设计](specs/2026-07-30-start-server-compose-path-design.md) | [计划](plans/2026-07-30-start-server-compose-path.md) | 成对历史记录 | 当前启动路径以仓库脚本和 Compose 文件为准。 |
| HVAC 最新测点快照分区查询 | [设计](specs/2026-07-31-hvac-latest-snapshot-partition-design.md) | [计划](plans/2026-07-31-hvac-latest-snapshot-partition.md) | 成对历史记录 | 当前查询 SQL 以 Repository 和测试为准。 |
| MySQL 初始化 UTF-8 | [设计](specs/2026-07-31-mysql-init-utf8-design.md) | [计划](plans/2026-07-31-mysql-init-utf8.md) | 成对历史记录 | 当前字符集以初始化 SQL、Compose 和运行数据库核验为准。 |
| MySQL 与 Redis 稳定卷 | [设计](specs/2026-07-31-mysql-redis-stable-volumes-design.md) | [计划](plans/2026-07-31-mysql-redis-stable-volumes.md) | 成对历史记录 | 文中的临时容器是迁移步骤；当前卷名和数据状态须核对 Compose 与本机环境。 |
| TDengine 稳定 hostname 与数据卷 | [设计](specs/2026-07-31-tdengine-stable-hostname-design.md) | [计划](plans/2026-07-31-tdengine-stable-hostname-plan.md) | 成对历史记录 | 当前 hostname 和卷名以 Compose 与本机环境为准。 |
| 项目信息分层与维护机制 | [设计](specs/2026-08-02-project-knowledge-layering-design.md) | [计划](plans/2026-08-02-project-knowledge-layering.md) | 成对历史记录 | 形成 `PROJECT_GUIDE.md` 和 `PROJECT_STATUS.md`；当前治理规则以这些入口为准。 |
| 当前文档健康治理 | [设计](specs/2026-08-02-current-document-health-design.md) | [计划](plans/2026-08-02-current-document-health.md) | 成对历史记录 | 说明本目录、历史文件警告和六份当前文档核验的治理依据。 |
| 仓库开发流程与注释质量防回退 | [设计](specs/2026-08-03-repository-workflow-quality-guardrails-design.md) | [计划](plans/2026-08-03-repository-workflow-quality-guardrails.md) | 成对历史记录 | 当前入口以 `AGENTS.md` 和 `docs/development/repository-guardrails.md` 为准；GitHub 分支保护仍需仓库页面现场验证。 |
| HVAC 公式计算详情前端接入 | [设计](specs/2026-08-04-hvac-calculation-detail-frontend-design.md) | [计划](plans/2026-08-04-hvac-calculation-detail-frontend.md) | 成对历史记录 | 当前详情入口、状态与展示以 API、Composable、抽屉组件和页面测试为准。 |
| 前端 ESLint 基线与 CI 门禁 | [设计](specs/2026-08-04-frontend-eslint-baseline-design.md) | [计划](plans/2026-08-04-frontend-eslint-baseline.md) | 成对历史记录 | 当前规则和 CI 入口以 `web/.eslintrc.cjs`、`web/package.json` 与前端工作流为准。 |
| HVAC 统一真实历史趋势 | [设计](specs/2026-08-05-hvac-unified-history-trends-design.md) | [计划](plans/2026-08-06-hvac-unified-history-trends.md) | 成对历史记录 | 当前接口、分辨率和页面行为以 Controller、Service、Repository、前端历史模块和测试为准。 |
| AGENTS 指令分层整改 | [设计](specs/2026-08-08-agents-instruction-layering-design.md) | [计划](plans/2026-08-08-agents-instruction-layering.md) | 成对历史记录 | 根规则保留核心约束与按需路由，详细注释和验证规范以 `docs/development` 中当前文件为准。 |
| Agent Skills 分层与注释风险分级 | [设计](specs/2026-08-09-agent-skills-layering-design.md) | [计划](plans/2026-08-09-agent-skills-layering.md) | 成对历史记录 | 通用注释和 Git 交付迁入个人 Skill，IoT 验证迁入仓库 Skill；普通 PR 不再强制新增永久注释审计报告。 |
| Repository Guardrail 换行兼容修复 | [设计](specs/2026-08-09-guardrail-line-ending-normalization-design.md) | [计划](plans/2026-08-09-guardrail-line-ending-normalization.md) | 成对历史记录 | PR 正文进入章节解析前统一 LF、CRLF 和 CR，避免跨平台换行导致字段误报。 |
| HVAC WebSocket 实时韧性 | [设计](specs/2026-08-10-hvac-realtime-websocket-resilience-design.md) | [计划](plans/2026-08-10-hvac-realtime-websocket-resilience.md) | 成对历史记录 | 当前实现是认证后按建筑发布四指标增量，并以 HTTP 作为完整状态权威来源；不包含 19 测点 WebSocket 快照或多实例总线。 |

## 已确认的替代关系

- 早期文档中的旧电表并行保留前提，由上表“旧电表 Demo 下线”任务替代。
- 历史命令中的 `/ws/dashboard`，由旧电表下线任务移除；当前 HVAC 指标端点是 `/ws/hvac`。
- 早期的“COP 尚未实现”状态，由上表“HVAC 核心公式引擎”任务及其实现替代。
- 临时或不稳定的本地容器存储和 TDengine hostname，分别由上表“稳定卷”与“稳定 hostname”任务治理；当前本机数据是否存在仍须现场核验。
- 早期前端假数据或临时演示状态，不再从历史任务文档判断；当前完成情况统一查看[项目状态](../../PROJECT_STATUS.md)。

历史文档即使有“已完成”“待实施”或未勾选复选框，也只表达任务当时的记录。若目录说明与当前代码或测试冲突，应在新的专题任务中核验并更新当前入口，不能静默改写历史正文。
