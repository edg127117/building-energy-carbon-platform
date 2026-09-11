# 建筑能碳监测管理平台项目指南

## 1. 使用方式

本文件只描述稳定定位、架构、数据链路、职责和入口。开始任务时先读取 [`AGENTS.md`](AGENTS.md)，再按任务需要读取相关章节：

- 当前能力、阶段、风险和下一步：[`PROJECT_STATUS.md`](PROJECT_STATUS.md)；
- 具体实现：直接相关的代码、测试和专题文档；
- Git、Hook、PR 和 CI：[`repository-guardrails.md`](docs/development/repository-guardrails.md)。

普通单点任务不要求完整读取本文件或 `PROJECT_STATUS.md`。历史计划只用于解释演进原因，不能代替当前代码和状态文档。

## 2. 产品定位与范围

本项目建设“建筑能碳监测管理平台”，围绕建筑、空间、系统、设备、测点和计量边界形成可追溯的能源与碳数据链，逐步支持：

- 冷热源系统；
- 空调及通风系统；
- 供配电系统；
- 环境监测系统。

产品阶段依次为数据监测与后台基础架构、可视化实现、虚拟数据测试及次级应用点完善；当前进度只在 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 维护。

平台范围以监测、预处理、建模、分析、诊断和展示为主。远程控制、命令下发、响应执行、策略生成与策略下发不属于当前版本。负荷预测、能源审计、运行模式管理及完整自动报告暂缓，启用前必须重新确认范围和输入条件。

GB/T 47474—2026 用于需求拆解和验收依据；只有相应条款完成软件实现、专业确认和必要的硬件/现场验证后，才能声明该条款已满足。

## 3. 与旧中央空调系统的关系

| 对象 | 定位 | 使用规则 |
|---|---|---|
| `building-energy-carbon-platform` | 当前产品仓库 | 所有新增开发、状态和交付均在此管理 |
| `iot-platform-demo` | 旧中央空调仓库 | 只通过 `upstream-hvac` 追溯历史，不继续承载新产品开发 |
| `baseline/hvac-before-energy-carbon-20260823` | 二次开发前代码冻结点 | 证明继承起点，不代表建筑能碳版本或现场验收完成 |
| 旧 HVAC 设计、计划和 PR | 历史证据 | 可解释已有实现，不是新平台需求或完成状态 |

继承代码中的 `hvac` 包、19 测点、公式、页面、脚本和制品名称可以继续运行，但应视为待复用或迁移的基础能力；未经新平台范围核对，不得推广为通用建筑能碳能力。

## 4. 稳定技术架构

平台采用 Spring Boot 单体后端与 Vue 前端。未经证据证明存在独立部署、扩缩容或故障隔离收益，不拆微服务或仓库。

| 模块 | 稳定职责 |
|---|---|
| `web/src` | 页面、状态编排、API Client、契约类型和可视化；不生成虚假业务数据 |
| `com.platform.system`、`security` | 登录、JWT、角色、菜单和建筑权限 |
| `com.platform.audit` | 后台职责、服务端追踪、安全事件和敏感变更公共闭环 |
| `com.platform.hvac`、`hvac.asset` | 继承的建筑、空间、系统、设备、测点档案及 HVAC 查询 |
| `com.platform.iot.ingest`、`identity` | 标准报文接入、设备身份和归属解析 |
| `com.platform.iot.reliability`、`mqtt` | V2 消息幂等、平台回执、ACK 失败证据与成功监控、TLS 和连接故障分类 |
| `com.platform.iot.quality`、`dataquality`、`qualityusage` | Q0/Q1/Q2 生成、使用策略、运行门禁、纠正和恢复 |
| `com.platform.iot.energymetadata` | 测点能源类型、来源、数据性质、统计周期和专业确认属性 |
| `com.platform.iot.deviceparameter` | 标准设备参数定义、来源、冲突、双时间版本、审核、生效、查询和重算编排 |
| `com.platform.iot.onboarding` | 产品模板、未知设备有界发现、绑定和启停 |
| `com.platform.iot.temporal` | TDengine 时序访问边界 |
| `com.platform.energy.activity` | 多能源原始活动数据的有界读取和质量门禁 |
| `com.platform.energy.catalog`、`conversion` | 能源品种、单位量纲、折标公式和参数版本；研发模拟结果不得冒充正式结算 |
| `com.platform.energy.aggregation`、`period`、`summary` | 活动量聚合、计量事件与修正、周期投影与封账、历史关系和计量边界汇总 |
| `com.platform.energy.efficiency` | 电驱动水冷冷站原生周期、年度 EERp 和研发评价 |
| `com.platform.carbon` | 因子与分母版本、范围一/二计算、追溯、重算和批次审批 |
| `com.platform.relation` | 建筑关系版本、表计层级和方向、计量边界、分层查询及 Excel 草稿导入 |
| `com.platform.cache`、`config` | Redis 缓存和基础设施装配 |
| `telemetry-adapter` | 厂商 Topic 到内部 V2 契约的 MQTT 适配与代理 ACK；不等于边缘持久化网关 |
| `modbus-edge-adapter` | 只读 Modbus TCP/RTU 采集并生成内部 V2 报文；不包含控制或持久化补传 |

### 数据源职责

- MySQL：用户、权限、建筑、空间、设备、测点、配置、关系和业务状态；
- TDengine：原始时序、聚合时序、质量结果和指标结果；
- Redis：Token、权限范围和最新结果缓存，不作为永久事实库；
- MQTT/HTTP：统一北向接入契约；
- WebSocket：实时展示通知，权威结果仍由持久化和受保护查询接口提供。

多数据源必须使用明确 Bean 和 `Qualifier`，不得跨数据源执行 SQL。

### MySQL 迁移

MySQL 结构由应用启动时的 Flyway 版本链统一推进，迁移源文件位于 [`src/env/init`](src/env/init)，构建时只将 `V*.sql` 打包到 `classpath:db/migration/mysql`。Docker Compose 只创建空数据库，不并行执行 SQL。

新库执行完整版本链；没有 Flyway 历史表的非空旧库默认拒绝启动，必须先核验结构与备份，再按 [`ADR-0001`](docs/adr/0001-flyway-mysql-schema-governance.md) 执行一次性受控接管。已成功应用的版本脚本不得原地修改。

### 前端入口与目录边界

前端采用 `index.html → src/app/main.ts` 唯一入口，旧页面、旧全局样式、旧请求客户端及 Ant Design/Tailwind 实现不再保留。进入 `web` 后运行 `npm ci`、`npm run dev`；`npm run build` 构建唯一入口到 `web/dist`。

平台恢复会话时重新向后端核验身份和授权，不信任本地缓存角色。客户端菜单只能使用服务器返回且已在本地注册的叶子路径；目录不自动授予子页面，管理员角色也不自动扩权。迁入管理页面还需保留平台管理员角色检查；前端导航限制不能替代后端接口鉴权和建筑范围校验。

| 目录 | 职责与边界 |
|---|---|
| `web/src/app` | 应用装配、路由、办公/监控外壳和全局服务；不实现业务规则 |
| `web/src/modules/<模块>` | 页面、模块组件、API、编排、模型和模块文案；模块间只通过 `public.ts` |
| `web/src/shared` | 无业务归属的组件、图表、组合函数、模型和工具 |
| `web/src/infrastructure`、`generated` | 通用传输能力和生成契约边界；不存放页面或领域计算 |
| `web/src/locales`、`styles` | 公共文案、设计变量、主题和组件库映射 |

监控端采用 1920×1080 逻辑画布等比居中缩放。大屏注册表统一生成路由和切换导航；场景型布局与普通网格布局分开，信息层不得阻断场景交互。图表统一从 `shared/charts` 获取主题、尺寸监听和释放行为。

目录依赖、文案和样式变量由 `web/AGENTS.md` 与 `npm run check:architecture` 共同约束。前端结构、菜单和交付阶段见[前端实施计划](docs/designs/frontend-visualization-phase-two-implementation-plan.md)，当前迁移和验收状态见 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)。

## 5. 目标数据链路

以下是目标链路，不表示每一段都已正式验收：

```text
设备与业务系统
→ 南向协议驱动或边缘适配
→ 字段、单位、时间和身份归一化
→ 统一 MQTT/HTTP
→ 平台接入、归属校验、消息幂等和原始持久化
→ 数据预处理和 Q0/Q1/Q2 质量标识
→ 场景化质量使用策略门禁
→ 多能源活动数据读取、能源品种和单位解析
→ 活动量聚合、计量事件、修正和周期封账
→ 历史关系与计量边界汇总
→ 折标、能效和碳排确定性计算及版本证据
→ 依赖变化影响分析、重算和正式结果审批
→ API/WebSocket
→ 看板、趋势、下钻、能流和碳排展示
```

V2 可靠链只把“全部原始测点已进入 TDengine 且轻量 MySQL 回执已持久化”称为 `PLATFORM_PERSISTED`。Broker `PUBACK`、适配器发布确认、平台消费确认和应用 ACK 发布确认语义不同；成功回执默认只作为热证据，聚合、质量、公式和页面处理不属于该回执语义。

## 6. 空间与语义模型

- 物理空间树表达建筑、楼层、区域、房间的包含关系；
- 可版本化语义关系表达空间、系统、设备、测点、计量边界和服务范围；
- 计量边界与物理边界不得默认相同，分摊规则和未分配状态必须显式表达；
- 表计层级、方向和覆盖对象分开保存，未知事实保持待专业确认；
- Excel 导入固定为“平台模板下载 → 无写预检 → 幂等原子写入指定草稿”，不得自动提交、审核或生效。

## 7. 专业职责边界

| 角色 | 必须提供或确认的内容 | 软件职责 |
|---|---|---|
| 能源专家 | 指标、公式、折算与碳因子、边界、阈值、基准、归因、模型、展示和验收规则 | 将已确认规则实现为可配置、可版本化、可追溯的服务和接口 |
| 硬件人员 | 设备能力、南向协议、字段、单位、采样周期、边缘缓存/补传和现场条件 | 定义统一北向契约、平台接入、状态查询和联调工具 |
| 软件开发人员 | 架构、数据模型、接口、权限、存储、任务、日志、测试和可视化 | 不替能源专家或硬件人员猜测专业输入 |

专业输入未确认时，可以实现配置框架、安全默认和拒绝路径，但不得自行判定哪些指标允许 Q1/Q2、哪些公式有效或哪些设备命令安全。

## 8. 关键入口

| 用途 | 入口 |
|---|---|
| 后端 | [`PlatformApplication.java`](src/main/java/com/platform/PlatformApplication.java)、[`pom.xml`](pom.xml) |
| 前端 | [`web/src`](web/src)、[`web/package.json`](web/package.json) |
| 本地基础设施 | [`src/env/docker-compose.yml`](src/env/docker-compose.yml) |
| MySQL 迁移 | [`ADR-0001`](docs/adr/0001-flyway-mysql-schema-governance.md)、[`src/env/init`](src/env/init) |
| MQTT 与南向适配 | [`MqttConfig.java`](src/main/java/com/platform/config/MqttConfig.java)、[`telemetry-adapter`](telemetry-adapter)、[`modbus-edge-adapter`](modbus-edge-adapter) |
| 资产与设备接入 | [`com.platform.hvac.asset`](src/main/java/com/platform/hvac/asset)、[`com.platform.iot.onboarding`](src/main/java/com/platform/iot/onboarding) |
| 能源数据与计算 | [`com.platform.energy`](src/main/java/com/platform/energy) |
| 碳管理 | [`com.platform.carbon`](src/main/java/com/platform/carbon)、[`碳管理设计`](docs/designs/2026-09-02-carbon-management-foundation-design.md) |
| 关系治理 | [`com.platform.relation`](src/main/java/com/platform/relation)、[`关系治理设计`](docs/designs/2026-08-26-space-semantic-metering-relation-governance-design.md) |
| 职责与审计 | [`com.platform.audit`](src/main/java/com/platform/audit)、[`职责与审计设计`](docs/designs/2026-08-26-backoffice-duty-audit-governance-design.md) |
| 当前状态 | [`PROJECT_STATUS.md`](PROJECT_STATUS.md) |
| Git 与验证 | [`repository-guardrails.md`](docs/development/repository-guardrails.md)、[`iot-change-verification`](.agents/skills/iot-change-verification/SKILL.md) |
| 旧系统历史 | [`docs/superpowers/README.md`](docs/superpowers/README.md)、[`设计冻结书`](docs/设计冻结书-V1.0-19测点.md) |

## 9. 更新规则

- 稳定定位、模块边界、数据源职责、核心链路或运行入口变化时更新本文件；
- 当前阶段、完成项、验证结果、风险和下一步只写入 `PROJECT_STATUS.md`；
- 具体实现和验收细节写入相应设计或评审文档，本文件只保留入口；
- 当前行为始终以所在 Git 版本的代码和测试为准。
