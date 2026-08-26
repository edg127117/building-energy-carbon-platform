# 建筑能碳监测管理平台项目指南

## 1. 使用方式

本文件只描述稳定定位、架构和职责。开始任务时依次读取：

1. [`AGENTS.md`](AGENTS.md)：长期规则和新旧系统边界；
2. 本文件：模块、数据链路和职责；
3. [`PROJECT_STATUS.md`](PROJECT_STATUS.md)：当前已实现、计划中、风险和下一步；
4. 与任务直接相关的代码、测试或专题文档。

历史计划只用于解释演进原因，不能代替当前代码和状态文档。

## 2. 产品定位与范围

本项目目标是建设“建筑能碳监测管理平台”，围绕建筑、空间、系统、设备、测点和计量边界形成可追溯的能源与碳数据链，逐步支持：

- 冷热源系统；
- 空调及通风系统；
- 供配电系统；
- 环境监测系统。

产品推进顺序为：

1. 数据监测与后台基础架构；
2. 可视化实现；
3. 虚拟数据测试及次级应用点完善。

当前范围以监测、预处理、建模、分析、诊断和展示为主。远程控制、命令下发、响应执行、策略生成与策略下发不属于当前版本。负荷预测、能源审计、运行模式管理及完整自动报告暂缓，启用前必须重新确认范围和输入条件。

GB/T 47474—2026 用于需求拆解和验收依据；平台只有在相应条款完成软件实现、专业确认和必要的硬件/现场验证后，才能声明该条款已满足。

## 3. 与旧中央空调系统的关系

| 对象 | 定位 | 使用规则 |
|---|---|---|
| `building-energy-carbon-platform` | 当前产品仓库 | 所有新增开发、状态和交付均在此管理 |
| `iot-platform-demo` | 旧中央空调仓库 | 只通过 `upstream-hvac` 追溯历史，不继续承载新产品开发 |
| `baseline/hvac-before-energy-carbon-20260823` | 二次开发前代码冻结点 | 证明继承起点，不代表建筑能碳版本或现场验收完成 |
| 旧 HVAC 设计、计划和 PR | 历史证据 | 可解释已有实现，不是新平台需求或完成状态 |

新仓库继承了中央空调系统的代码、测试和 Git 历史。继承代码中的 `hvac` 包、19 测点、公式、页面、脚本和制品名称可以继续运行，但应视为待复用或迁移的基础能力；未经新平台范围核对，不得直接推广为通用建筑能碳能力。

## 4. 稳定技术架构

当前继续采用 Spring Boot 单体后端与 Vue 前端：

| 层次 | 当前职责 |
|---|---|
| `web/src` | 页面、状态编排、API Client、契约类型和可视化；不生成虚假业务数据 |
| `com.platform.system`、`security` | 登录、JWT、角色、菜单和建筑权限 |
| `com.platform.hvac`、`hvac.asset` | 继承的建筑、空间、系统、设备、测点档案及 HVAC 查询 |
| `com.platform.iot.ingest`、`identity` | 标准报文接入、设备身份和归属解析 |
| `com.platform.iot.reliability`、`mqtt` | V2 消息级幂等、24 小时热回执、ACK 失败证据与成功监控、TLS 与连接故障分类 |
| `com.platform.iot.quality`、`dataquality` | 运行校验和 Q0/Q1/Q2 预处理 |
| `com.platform.iot.qualityusage` | Q0/Q1/Q2 消费策略治理、运行快照、门禁、纠正与恢复 |
| `com.platform.iot.deviceparameter` | 标准设备参数定义、四类来源候选、冲突、整组双时间版本、审核、生效、查询、迁移与历史重算编排 |
| `com.platform.iot.aggregation`、`formula` | 分钟聚合和继承的 HVAC 指标计算 |
| `com.platform.iot.onboarding` | 产品模板、未知设备有界发现、绑定和启停 |
| `com.platform.iot.temporal` | TDengine 时序访问边界 |
| `com.platform.cache`、`config` | Redis 缓存和基础设施装配 |
| `telemetry-adapter` | 厂商 Topic 到内部 V2 契约的 MQTT 适配与代理 ACK 基础；不等于边缘持久化网关 |

数据源职责保持不变：

- MySQL：用户、权限、建筑、空间、设备、测点、配置、关系和业务状态；
- TDengine：原始时序、聚合时序、质量结果和指标结果；
- Redis：Token、权限范围和最新结果缓存，不作为永久事实库；
- MQTT/HTTP：统一北向接入契约；
- WebSocket：实时展示通知，权威结果仍由持久化和受保护查询接口提供。

MySQL 结构由应用启动时的 Flyway 版本链统一推进，迁移源文件位于
[`src/env/init`](src/env/init)，构建时只将 `V*.sql` 打包到
`classpath:db/migration/mysql`。Docker Compose 只创建空数据库，不再并行执行 SQL。
新库自动执行完整版本链；没有 Flyway 历史表的非空旧库默认拒绝启动，必须先核验
结构与备份，再按 [`ADR-0001`](docs/adr/0001-flyway-mysql-schema-governance.md)
执行一次性受控接管。已成功应用的版本脚本不得原地修改。

未经证据证明存在独立部署、扩缩容或故障隔离收益，不拆微服务。多数据源必须使用明确 Bean 和 `Qualifier`，不得跨数据源执行 SQL。

## 5. 目标数据链路

```text
设备与业务系统
→ 南向协议驱动
→ 边缘网关/协议适配层
→ 字段、单位、时间和身份归一化
→ 统一 MQTT/HTTP
→ 平台接入与归属校验
→ V2 消息幂等、不可覆盖原始持久化与平台应用 ACK
→ 数据预处理和 Q0/Q1/Q2 质量标识
→ MySQL/TDengine 持久化
→ 场景化质量使用策略门禁
→ 标准设备参数版本解析与公式版本证据
→ 物理空间树与语义关系模型
→ 能源/碳计量、评价和诊断
→ API/WebSocket
→ 看板、趋势、下钻、能流和碳排展示
```

当前候选代码覆盖 MQTT 上行、标准报文、身份与测点映射、数据质量、时序存储、标准设备参数平台侧治理、建筑级关系版本治理、部分 HVAC 指标、查询和展示。其中设备参数和建筑关系治理只提供配置框架、安全拒绝路径和软件版本证据，不代表已取得专业参数、关系映射、真实 Excel、现场报文或现场关系验收。Modbus、BACnet、NB-IoT 等南向驱动，以及完整能源/碳模型和跨系统分析，均必须以 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 标记为计划中或部分完成，不能根据目标链路推断已经实现。

V2 可靠链只把“全部原始测点已进入 TDengine 且轻量 MySQL 回执已持久化”称为 `PLATFORM_PERSISTED`。Broker `PUBACK`、适配器标准发布确认、平台入站消费确认和应用 ACK 发布确认语义不同；平台不逐条持久化成功 ACK，成功量进入监控，失败保留异常明细。成功回执默认只作为 24 小时热证据，聚合、质量、公式和页面处理不属于该回执语义。V1 Topic 在迁移期继续独立存在。

## 6. 空间与语义模型

- 物理空间树保留，用于表达建筑、楼层、区域、房间的包含关系。
- 在物理树之上建立可版本化的多对多语义关系，表达 `LOCATED_IN`、`PART_OF`、`SERVES`、`MEASURES`、`CONNECTED_TO`、`SUPPLIES`、`RETURNS` 等关系。
- 语义模型应覆盖空间、系统、设备、测点、计量边界和服务范围，并支持生效时间、来源和审计。
- 计量边界与物理边界不得默认相同；分摊规则和未分配状态必须显式表达。

## 7. 专业职责边界

| 角色 | 必须提供或确认的内容 | 软件开发职责 |
|---|---|---|
| 能源专家 | 指标、公式、折算与碳因子、边界、阈值、基准、归因、模型、展示逻辑和验收规则 | 将已确认规则实现为可配置、可版本化、可追溯的服务和接口 |
| 硬件人员 | 设备能力、南向协议、字段路径、单位、采样周期、边缘缓存/补传和现场条件 | 定义统一北向契约、平台接入、状态查询和联调工具 |
| 软件开发人员 | 架构、数据模型、接口、权限、存储、任务、日志、测试和可视化实现 | 不替能源专家或硬件人员猜测专业输入 |

专业输入未确认时，可以实现安全默认、配置框架和拒绝路径，但不得自行判定哪些指标允许 Q1/Q2、哪些公式有效或哪些设备命令安全。

## 8. 关键入口

| 用途 | 入口 |
|---|---|
| 后端 | [`PlatformApplication.java`](src/main/java/com/platform/PlatformApplication.java)、[`pom.xml`](pom.xml) |
| 前端 | [`web/src`](web/src)、[`web/package.json`](web/package.json) |
| 本地基础设施 | [`src/env/docker-compose.yml`](src/env/docker-compose.yml) |
| MySQL 迁移治理 | [`ADR-0001`](docs/adr/0001-flyway-mysql-schema-governance.md)、[`src/env/init`](src/env/init) |
| MQTT 接入 | [`MqttConfig.java`](src/main/java/com/platform/config/MqttConfig.java)、[`telemetry-adapter`](telemetry-adapter) |
| 资产与设备接入 | [`com.platform.hvac.asset`](src/main/java/com/platform/hvac/asset)、[`com.platform.iot.onboarding`](src/main/java/com/platform/iot/onboarding) |
| 质量使用策略 | [`com.platform.iot.qualityusage`](src/main/java/com/platform/iot/qualityusage)、[`正式候选设计`](docs/designs/2026-08-24-quality-usage-policy-governance-design.md) |
| 建筑关系治理 | [`com.platform.relation`](src/main/java/com/platform/relation)、[`正式候选设计`](docs/designs/2026-08-26-space-semantic-metering-relation-governance-design.md) |
| 当前状态 | [`PROJECT_STATUS.md`](PROJECT_STATUS.md) |
| Git 与验证 | [`repository-guardrails.md`](docs/development/repository-guardrails.md)、[`.agents/skills/iot-change-verification/SKILL.md`](.agents/skills/iot-change-verification/SKILL.md) |
| 旧系统历史 | [`docs/superpowers/README.md`](docs/superpowers/README.md)、[`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) |

## 9. 更新规则

- 稳定定位、模块边界、数据源职责或核心链路变化时更新本文件。
- 阶段、完成项、风险和下一步只写入 `PROJECT_STATUS.md`，避免重复维护。
- 旧代码名称只在实际迁移时修改，禁止为改名进行无证据的大范围重构。
- 当前行为始终以所在 Git 版本的代码和测试为准；本文件不描述某台电脑的瞬时运行状态。
