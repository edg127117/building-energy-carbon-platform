# iot-platform-demo 项目指南

## 1. 使用方式

处理本项目任务时按以下顺序读取信息：

1. 读取 [`AGENTS.md`](AGENTS.md)，确认核心规则和当前任务必须继续读取的开发规范。
2. 读取本文件，定位业务模块、核心链路、数据源和详细文档。
3. 读取 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)，确认当前 Git 版本已经完成、尚未完成和需要核验的事项。
4. 只在任务需要时读取 `docs/superpowers/specs`、`docs/superpowers/plans` 和对应代码。

本文件是稳定项目地图，不记录单次排查过程、临时运行结果和短期任务进度。

## 2. 项目定位与 V1 边界

本项目是中央空调经常性调适平台 Demo。V1 围绕建筑、设备、19 个 HVAC 测点、分钟数据、数据质量、性能指标和查询展示形成完整链路。

当前长期边界：

- V1 保持 Spring Boot 单体后端和 Vue 前端，不拆微服务。
- MySQL 保存用户权限、建筑、设备、测点和任务等结构化业务数据。
- TDengine 保存 HVAC 原始事件、分钟数据和指标结果。
- 云端 EMQX 与独立 `telemetry-adapter` 负责设备接入和配置驱动格式转换；本地平台主动订阅标准 MQTT 上行，Redis 负责 Token、权限范围和最新指标等缓存。
- 旧电表 Demo 已退出当前产品范围，只保留 Git 历史，不得恢复为并行业务链。
- V1 只提供采集、分析和展示能力；HVAC 控制必须经过独立安全设计和验收。

详细业务范围以 [`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) 为设计基线；其中带有时间语义的“现状”和“实施计划”章节可能是历史快照，当前完成状态以 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 和代码为准。

## 3. 系统结构与模块职责

| 位置 | 主要职责 | 不承担的职责 |
|---|---|---|
| `web/src` | 登录、显式路由、HVAC 页面、最小后台管理、API 调用和前端状态编排 | 不在浏览器伪造后端指标和历史数据，不按数据库组件字符串动态加载页面 |
| `com.platform.system`、`com.platform.security` | 登录、JWT、四角色 RBAC、菜单和建筑授权 | 不直接处理 HVAC 时序计算 |
| `com.platform.hvac` | 建筑、设备、测点档案和 HVAC 查询 API | 不直接消费 MQTT 报文 |
| `com.platform.integration` | 面向 `THIRD_PARTY` 的按建筑授权只读 Open API | 不提供内部后台、写入、控制或 Token 签发 |
| `com.platform.iot.ingest`、`com.platform.iot.quality` | MQTT 载荷接入、测点身份解析和运行时质量校验 | 不承担前端展示组装 |
| `com.platform.iot.identity` | 从本地 MySQL 解析外部设备身份到设备、建筑和期望协议 | 不从报文猜测归属，不自动创建设备 |
| `com.platform.iot.onboarding` | 管理产品模板、未知身份有界样例、绑定事务、身份启停、缓存生效与脱敏审计 | 不编辑云端协议模板，不查询或写入 TDengine 正式链 |
| `com.platform.iot.aggregation` | 将原始事件聚合为整分钟数据 | 不保存用户权限数据 |
| `com.platform.iot.dataquality` | Q0/Q1/Q2 数据质量补全、恢复、重算和任务状态 | 不改变 MySQL 与 TDengine 的职责边界 |
| `com.platform.iot.formula` | 组装公式输入、计算四类指标、保存并发布结果 | 不直接处理 HTTP 权限入口 |
| `com.platform.iot.temporal` | 通过专用 Repository 访问 TDengine | 不执行 MySQL SQL |
| `com.platform.cache` | Token、菜单、建筑范围和最新指标缓存 | 不作为时序事实的永久存储 |
| `com.platform.generator` | 后端代码生成器 | 不承载 HVAC 业务规则 |
| `com.platform.config` | 数据源、MQTT、Redis、WebSocket 和开关装配 | 不承载业务流程 |
| `telemetry-adapter` | 云端独立 Spring Boot 接入程序；按云端 MySQL 模板把原始 JSON 转为标准多指标 MQTT 报文 | 不保存正式遥测、建筑、设备归属、用户和指标 |

## 4. 核心业务数据链路

### 4.1 HVAC 采集、计算和展示

设备原始 MQTT 报文
→ 云端 [`telemetry-adapter`](telemetry-adapter)
→ `device/telemetry/up` 标准多指标报文
→ 本地 [`MqttConfig`](src/main/java/com/platform/config/MqttConfig.java)
→ [`StandardTelemetryMqttMessageHandler`](src/main/java/com/platform/iot/ingest/standard/StandardTelemetryMqttMessageHandler.java)
→ 本地设备预注册与整包测点预检
→ [`HvacIngestionService`](src/main/java/com/platform/iot/ingest/HvacIngestionService.java) 逐指标复用单点链
→ 测点配置与 [`TelemetryQualityValidator`](src/main/java/com/platform/iot/quality/TelemetryQualityValidator.java)
→ [`TdengineHvacRawEventRepository`](src/main/java/com/platform/iot/temporal/impl/TdengineHvacRawEventRepository.java)
→ [`HvacMinuteAggregationService`](src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java)
→ 数据质量处理
→ [`HvacFormulaEngine`](src/main/java/com/platform/iot/formula/HvacFormulaEngine.java)
→ TDengine 指标结果与 Redis 最新指标
→ [`HvacQueryController`](src/main/java/com/platform/hvac/controller/HvacQueryController.java) / [`HvacIndicatorController`](src/main/java/com/platform/hvac/controller/HvacIndicatorController.java)
→ [`web/src/api/hvac.ts`](web/src/api/hvac.ts)
→ [`useHvacDashboard`](web/src/composables/useHvacDashboard.ts)
→ [`HvacDemoPage.vue`](web/src/pages/HvacDemoPage.vue)。

迁移期旧 `device/data/up` 单测点报文仍由 `MqttConfig`
→ [`HvacMqttMessageHandler`](src/main/java/com/platform/iot/ingest/HvacMqttMessageHandler.java)
→ [`HvacIngestionService`](src/main/java/com/platform/iot/ingest/HvacIngestionService.java)
→ 测点配置与 [`TelemetryQualityValidator`](src/main/java/com/platform/iot/quality/TelemetryQualityValidator.java)
进入相同的本地时序链；该路径不用于新增多字段设备。

公式结果在 TDengine 持久化成功且 Redis 接受最新分钟后，还会通过
[`IndicatorRealtimePublisher`](src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java)、
[`HvacRealtimeSessionRegistry`](src/main/java/com/platform/iot/websocket/HvacRealtimeSessionRegistry.java)
和 [`WebSocketServer`](src/main/java/com/platform/iot/websocket/WebSocketServer.java)
向 `/ws/hvac` 发布当前建筑的四项指标增量。WebSocket 是最佳努力通知，发送失败不回滚指标结果；
19 测点完整快照和全部最新指标仍以受保护 HTTP 查询为权威来源。

标准入口身份未命中时，先区分“已登记但停用”和“业务库真正未知”。停用身份直接拒绝；
真正未知身份只把规范化、大小受限的最近指标样例写入本地 MySQL `biz_pending_device`，
写入短暂失败经过有限重试后仍确认 MQTT 消息，并继续拒绝正式 TDengine、聚合、质量和公式链。
同一身份的并发发现由数据库唯一键原子累计，过期 `DISCOVERED`、`IGNORED` 记录由独立有界任务清理。
平台管理员通过 `/api/v1/device-products` 和 `/api/v1/device-onboarding` 管理产品草稿、待绑定状态、
设备/测点/别名绑定和身份启停；绑定在一个 MySQL 事务内锁定待绑定记录，提交后刷新并核验身份、测点快照。
绑定完成前身份保持停用，缓存未确认可见时启用接口不会返回成功，既有正式时序和历史数据不被改写。

### 4.2 登录、权限和建筑范围

[`LoginPage.vue`](web/src/pages/LoginPage.vue)
→ [`web/src/api/auth.ts`](web/src/api/auth.ts)
→ [`AuthController`](src/main/java/com/platform/system/controller/AuthController.java)
→ `SysUserService`、`SysRoleService` 和 `BuildingScopeService`
→ MySQL 用户、角色、菜单和建筑授权表
→ Redis Token、菜单和建筑范围缓存
→ JWT 过滤器恢复当前用户
→ Controller 和 Service 继续执行角色与建筑范围校验。

前端路由限制只负责用户体验，后端权限校验才是最终安全边界。

平台管理员从 HVAC 大屏进入 `/system` 后台壳层，前端只把后端菜单树映射到受控注册表中的
`/system/users`、`/system/roles`、`/system/menus` 和 `/system/building-access` 四条显式路由。
用户生命周期、固定四角色菜单、完整菜单树和建筑授权审批分别调用 `com.platform.system`
管理接口；数据库中的 `component` 字符串不参与前端组件解析，普通角色也不能通过直输 URL
绕过后端 `PLATFORM_ADMIN` 鉴权。

### 4.3 前端真实数据刷新

`HvacDemoPage.vue` 初始化
→ `useHvacDashboard`
→ 查询当前用户可访问建筑
→ 请求 `/hvac/buildings/{buildingId}/snapshot` 和 `/hvac/buildings/{buildingId}/indicators/latest`
→ 建立 `/ws/hvac` 单连接并在首帧提交 JWT 与目标建筑
→ 服务端复核 Redis 活动登录态和 MySQL 建筑范围，心跳时继续复核
→ 四项指标增量立即合并，500 毫秒尾随触发一次非重叠 HTTP 权威对账。

实时连接异常时，前端立即启用 30 秒 HTTP 保障，并按 1、2、5、10、30 秒有界退避重连；
连接稳定 60 秒后重置退避，重连成功且 HTTP 对账完成后才恢复实时正常状态。
WebSocket 基地址由 `VITE_WS_BASE` 配置，JWT 不进入 URL。

历史趋势不直接消费 WebSocket 最新值，也不与实时卡片共用调度器。`1h`、`6h`、`24h`
和 `7d` 相对范围在页面可见时每 30 秒通过历史 API 权威重查；页签转入后台时停止历史
定时器，重新可见时立即补查并从返回时刻重启周期。自定义固定区间、空选择和已有请求
不会自动查询。同一展示条件刷新失败时保留上次成功曲线并标记过期，建筑、模式、范围或
测点选择变化仍立即清除不匹配结果。

请求失败时保留已有数据并展示错误；无数据使用明确空值，不生成随机业务数据。
快照由后端按统一服务端时间区分 `NORMAL`、`STALE` 和 `NO_DATA`：过期记录仍保留
最后值与来源分钟用于追溯，但前端不把它作为当前值或计入当前有效测点完整率。

## 5. 数据源与外部资源边界

| 资源 | 保存或传递的内容 | 主要访问位置 | 测试边界 |
|---|---|---|---|
| 本地 MySQL | 用户、角色、菜单、建筑、设备、产品模板、待绑定设备、外部身份绑定、测点、接入审计、典型值和任务状态 | `system`、`hvac`、`iot.onboarding` Mapper，设备身份与数据质量 MySQL Provider | 普通测试使用 H2 或 Mock，不依赖本机 MySQL |
| 云端适配器 MySQL | 协议模板、版本、JSON 字段路径、单位和换算规则 | `telemetry-adapter` 的 JDBC 配置快照 | 适配器普通测试使用独立 H2，不保存业务数据 |
| TDengine | 原始事件、分钟数据、指标结果和公式异常 | `iot.temporal` 专用 Repository | 普通测试关闭真实 TDengine，真实链路使用专用集成验证 |
| Redis | Token、菜单、建筑范围和最新指标缓存 | `com.platform.cache` | 允许 Fake/Mock；缓存失败是否降级由业务服务明确决定 |
| MQTT / EMQX | 设备原始报文、`device/telemetry/up` 标准多指标报文和迁移期 `device/data/up` 单点报文 | 云端适配器、`MqttConfig` 与两个接入 Handler | 普通测试必须关闭真实 MQTT 连接 |
| WebSocket | 按建筑发布四项 HVAC 指标增量，不承载 19 测点完整状态 | `IndicatorRealtimePublisher`、`HvacRealtimeSessionRegistry`、`WebSocketServer`、`web/src/realtime` | 普通测试使用 Mock/Fake；真实路由与恢复使用隔离 Docker 和浏览器验证 |

多数据源代码必须使用明确 Bean 名称和 `Qualifier`；MySQL SQL 与 TDengine SQL 不得跨数据源执行。

## 6. 主要代码与配置入口

| 用途 | 入口 |
|---|---|
| 后端启动 | [`PlatformApplication.java`](src/main/java/com/platform/PlatformApplication.java) |
| 后端配置 | [`application.yml`](src/main/resources/application.yml)、[`server.env.example`](server.env.example) |
| 本地基础设施 | [`src/env/docker-compose.yml`](src/env/docker-compose.yml) |
| 后端启动脚本 | [`start-server.sh`](start-server.sh) |
| 后端构建与测试 | [`pom.xml`](pom.xml)、[`application-test.yml`](src/test/resources/application-test.yml) |
| 云端适配器 | [`telemetry-adapter/README.md`](telemetry-adapter/README.md)、[`telemetry-adapter/pom.xml`](telemetry-adapter/pom.xml) |
| 前端构建与测试 | [`web/package.json`](web/package.json) |
| 前端 HTTP / WebSocket 基址示例 | [`web/.env.example`](web/.env.example) |
| MQTT 接入配置 | 本地 [`MqttConfig.java`](src/main/java/com/platform/config/MqttConfig.java)、云端 [`telemetry-adapter/application.yml`](telemetry-adapter/src/main/resources/application.yml) |
| HVAC 查询入口 | [`HvacQueryController.java`](src/main/java/com/platform/hvac/controller/HvacQueryController.java)、[`HvacIndicatorController.java`](src/main/java/com/platform/hvac/controller/HvacIndicatorController.java) |
| 设备产品与接入 API | [`DeviceProductController.java`](src/main/java/com/platform/iot/onboarding/api/DeviceProductController.java)、[`DeviceOnboardingController.java`](src/main/java/com/platform/iot/onboarding/api/DeviceOnboardingController.java) |
| 前端 HVAC 页面 | [`HvacDemoPage.vue`](web/src/pages/HvacDemoPage.vue) |
| 最小后台管理 | [`ManagementLayout.vue`](web/src/layouts/ManagementLayout.vue)、[`systemAdmin.ts`](web/src/api/systemAdmin.ts)、[`adminNavigation.ts`](web/src/domain/adminNavigation.ts) |

本地 `server.env`、`web/.env` 等被忽略配置只服务当前机器，不是项目事实，也不得提交密码或 Token。

## 7. 文档导航和生命周期

### 7.1 当前项目入口

| 入口 | 状态 | 用途 | 判断当前事实时的来源 |
|---|---|---|---|
| 当前代码与自动化测试 | 当前证据 | 判断当前实现、接口和行为 | 当前 Git 版本中的实现、配置和测试结果 |
| [`AGENTS.md`](AGENTS.md) | 当前有效 | 核心工作规则和按任务读取的规范路由 | 文件正文；只有规则或路由变更时更新 |
| [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md) | 当前有效 | 稳定架构、模块职责、数据链路和文档导航 | 代码结构与长期设计边界 |
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | 动态更新 | 已完成、未完成、风险、技术债和下一步 | 本文件所在 Git 版本的代码、测试和已合并决策 |
| `docs/adr` | 按需创建 | 需要长期追溯的重大技术决策 | 普通任务步骤、当前进度和临时判断 |
| `docs/designs` | 按需创建 | 正式版本、重大架构或权威需求变化的正式设计 | 普通功能修改和执行清单 |

### 7.2 当前专题说明

| 文档 | 状态 | 用途 | 不用于判断 |
|---|---|---|---|
| [`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) | V1 设计基线 | 冻结范围、测点、公式和安全边界 | 当前完成进度和仍待实施事项 |
| [`docs/MQTT-硬件数据对接说明.md`](docs/MQTT-硬件数据对接说明.md) | 当前硬件契约 | 云端适配、本地预注册、多字段与旧单点上行、确认语义 | 现场设备已经完成验收 |
| [`docs/designs/2026-08-17-configurable-device-onboarding-design.md`](docs/designs/2026-08-17-configurable-device-onboarding-design.md) | 活动候选设计 | 可配置设备接入、待绑定设备、产品模板和建筑/设备/测点管理的候选边界 | 当前已经实现这些候选能力或已经批准为正式基线 |
| [`docs/HVAC控制能力设计备忘.md`](docs/HVAC控制能力设计备忘.md) | 未来安全约束 | 未来控制能力必须满足的安全和审计边界 | 当前已经存在控制功能 |
| [`docs/development/java21.md`](docs/development/java21.md) | 当前开发指南 | Java 21、Maven Wrapper 和 CI 验证 | 某台电脑已经正确配置环境 |
| [`docs/development/code-comments.md`](docs/development/code-comments.md) | 当前开发规范 | IoT 生产代码注释边界与风险分级 | 通用语言注释规则和不涉及生产代码的任务 |
| [`.agents/skills/iot-change-verification/SKILL.md`](.agents/skills/iot-change-verification/SKILL.md) | 当前 Codex Skill | 后端、前端、跨端、外部资源和流程变化的可执行验证矩阵 | 某次任务已经实际执行了所有命令 |
| [`docs/development/verification.md`](docs/development/verification.md) | 当前开发说明 | 面向开发者的验证原则和结果记录要求 | 完整命令矩阵和某次任务的执行结果 |

### 7.3 历史任务记录

- [`docs/superpowers/README.md`](docs/superpowers/README.md) 是既有历史任务中央目录，记录 spec/plan 配对、命名差异和已知替代关系。
- `docs/superpowers/specs` 与 `docs/superpowers/plans` 保持冻结，只保存迁移前任务当时的设计、步骤和验证方案，不再作为新任务默认落点。
- 新任务不要求设计与计划成对建档；普通实施步骤留在当前 Codex 任务、Issue 或 PR，需要长期保存时分别进入 `PROJECT_STATUS.md`、`docs/adr` 或 `docs/designs`。
- 每份历史文件顶部都有状态警告；早期正文允许包含当时存在、后来已经删除或替代的能力。
- 需要判断当前行为时，先查代码、测试和 `PROJECT_STATUS.md`；需要理解演进原因时，再查相关历史任务文档和 Git 历史。

## 8. 仓库工程防护

仓库通过本地 Git Hook、任务预检、PR 模板和 GitHub Actions 防止开发流程与注释质量回退：

- 安装、预检、PR 检查、专项注释审计和合并后清理的命令查看 [`repository-guardrails.md`](docs/development/repository-guardrails.md)；
- 生产代码 PR 在正文记录风险级别、检查范围和结论；普通任务不再强制新增永久审计报告，历史报告保持不可变；
- 生产代码的 IoT 注释边界与风险分级查看 [`code-comments.md`](docs/development/code-comments.md)；
- Codex 验证入口是 [`.agents/skills/iot-change-verification/SKILL.md`](.agents/skills/iot-change-verification/SKILL.md)，开发者原则查看 [`verification.md`](docs/development/verification.md)；
- 后端 CI 入口是 [`.github/workflows/backend-ci.yml`](.github/workflows/backend-ci.yml)；
- 前端 CI 入口是 [`.github/workflows/frontend-ci.yml`](.github/workflows/frontend-ci.yml)；
- PR 范围和注释检查入口是 [`.github/workflows/repository-guardrails.yml`](.github/workflows/repository-guardrails.yml)。

本地与 CI 检查不能代替业务语义复核。GitHub `main` 分支保护属于仓库外部设置，必须在 GitHub 页面单独启用并现场验证。

## 9. 维护规则

- 项目定位、模块职责、数据源边界、核心链路、长期运行入口或文档入口变化时，更新本文件。
- 项目阶段、完成项、阻塞、技术债或下一步变化时，只更新 `PROJECT_STATUS.md`。
- 普通任务不新建永久计划文档；大型跨会话模块仅在确有交接风险时，把轻量阶段说明写入 `PROJECT_STATUS.md`。
- 重大且需要长期追溯的技术决策写入 `docs/adr`；正式版本、重大架构或权威需求变化写入 `docs/designs`，并在本文件补充导航。
- 单次命令、错误日志、端口占用、容器状态和本地备份路径保留在当前会话，不沉淀为项目指南。
- 发现文档与代码冲突时先核验代码和测试，再修正文档；证据不足时在 `PROJECT_STATUS.md` 标记为待核验。
