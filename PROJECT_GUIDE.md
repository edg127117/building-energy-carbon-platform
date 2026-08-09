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
- MQTT 负责 19 测点上行，Redis 负责 Token、权限范围和最新指标等缓存。
- 旧电表 Demo 已退出当前产品范围，只保留 Git 历史，不得恢复为并行业务链。
- V1 只提供采集、分析和展示能力；HVAC 控制必须经过独立安全设计和验收。

详细业务范围以 [`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) 为设计基线；其中带有时间语义的“现状”和“实施计划”章节可能是历史快照，当前完成状态以 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 和代码为准。

## 3. 系统结构与模块职责

| 位置 | 主要职责 | 不承担的职责 |
|---|---|---|
| `web/src` | 登录、路由、HVAC 页面、API 调用和前端状态编排 | 不在浏览器伪造后端指标和历史数据 |
| `com.platform.system`、`com.platform.security` | 登录、JWT、四角色 RBAC、菜单和建筑授权 | 不直接处理 HVAC 时序计算 |
| `com.platform.hvac` | 建筑、设备、测点档案和 HVAC 查询 API | 不直接消费 MQTT 报文 |
| `com.platform.iot.ingest`、`com.platform.iot.quality` | MQTT 载荷接入、测点身份解析和运行时质量校验 | 不承担前端展示组装 |
| `com.platform.iot.aggregation` | 将原始事件聚合为整分钟数据 | 不保存用户权限数据 |
| `com.platform.iot.dataquality` | Q0/Q1/Q2 数据质量补全、恢复、重算和任务状态 | 不改变 MySQL 与 TDengine 的职责边界 |
| `com.platform.iot.formula` | 组装公式输入、计算四类指标、保存并发布结果 | 不直接处理 HTTP 权限入口 |
| `com.platform.iot.temporal` | 通过专用 Repository 访问 TDengine | 不执行 MySQL SQL |
| `com.platform.cache` | Token、菜单、建筑范围和最新指标缓存 | 不作为时序事实的永久存储 |
| `com.platform.generator` | 后端代码生成器 | 不承载 HVAC 业务规则 |
| `com.platform.config` | 数据源、MQTT、Redis、WebSocket 和开关装配 | 不承载业务流程 |

## 4. 核心业务数据链路

### 4.1 HVAC 采集、计算和展示

`device/data/up` MQTT 报文
→ [`MqttConfig`](src/main/java/com/platform/config/MqttConfig.java)
→ [`HvacMqttMessageHandler`](src/main/java/com/platform/iot/ingest/HvacMqttMessageHandler.java)
→ [`HvacIngestionService`](src/main/java/com/platform/iot/ingest/HvacIngestionService.java)
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

公式结果还会通过 [`IndicatorRealtimePublisher`](src/main/java/com/platform/iot/formula/IndicatorRealtimePublisher.java) 和 [`WebSocketServer`](src/main/java/com/platform/iot/websocket/WebSocketServer.java) 发布到后端 `/ws/hvac` 端点。前端是否已消费该端点，以项目状态文件为准。

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

### 4.3 前端真实数据刷新

`HvacDemoPage.vue` 初始化
→ `useHvacDashboard`
→ 查询当前用户可访问建筑
→ 请求 `/hvac/buildings/{buildingId}/snapshot` 和 `/hvac/buildings/{buildingId}/indicators/latest`
→ 将后端 DTO 转为页面展示模型
→ 使用非重叠的 30 秒轮询刷新。

请求失败时保留已有数据并展示错误；无数据使用明确空值，不生成随机业务数据。
快照由后端按统一服务端时间区分 `NORMAL`、`STALE` 和 `NO_DATA`：过期记录仍保留
最后值与来源分钟用于追溯，但前端不把它作为当前值或计入当前有效测点完整率。

## 5. 数据源与外部资源边界

| 资源 | 保存或传递的内容 | 主要访问位置 | 测试边界 |
|---|---|---|---|
| MySQL | 用户、角色、菜单、建筑、设备、测点、典型值和任务状态 | `system`、`hvac` Mapper，数据质量 MySQL Repository | 普通测试使用 H2 或 Mock，不依赖本机 MySQL |
| TDengine | 原始事件、分钟数据、指标结果和公式异常 | `iot.temporal` 专用 Repository | 普通测试关闭真实 TDengine，真实链路使用专用集成验证 |
| Redis | Token、菜单、建筑范围和最新指标缓存 | `com.platform.cache` | 允许 Fake/Mock；缓存失败是否降级由业务服务明确决定 |
| MQTT / EMQX | `device/data/up` 的 19 测点上行载荷 | `MqttConfig`、`HvacMqttMessageHandler` | 普通测试必须关闭真实 MQTT 连接 |
| WebSocket | HVAC 指标实时消息 | `IndicatorRealtimePublisher`、`WebSocketServer` | 后端端点存在不代表前端已经完成实时接入 |

多数据源代码必须使用明确 Bean 名称和 `Qualifier`；MySQL SQL 与 TDengine SQL 不得跨数据源执行。

## 6. 主要代码与配置入口

| 用途 | 入口 |
|---|---|
| 后端启动 | [`PlatformApplication.java`](src/main/java/com/platform/PlatformApplication.java) |
| 后端配置 | [`application.yml`](src/main/resources/application.yml)、[`server.env.example`](server.env.example) |
| 本地基础设施 | [`src/env/docker-compose.yml`](src/env/docker-compose.yml) |
| 后端启动脚本 | [`start-server.sh`](start-server.sh) |
| 后端构建与测试 | [`pom.xml`](pom.xml)、[`application-test.yml`](src/test/resources/application-test.yml) |
| 前端构建与测试 | [`web/package.json`](web/package.json) |
| 前端 API 基址示例 | [`web/.env.example`](web/.env.example) |
| HVAC MQTT 配置 | [`MqttConfig.java`](src/main/java/com/platform/config/MqttConfig.java) |
| HVAC 查询入口 | [`HvacQueryController.java`](src/main/java/com/platform/hvac/controller/HvacQueryController.java)、[`HvacIndicatorController.java`](src/main/java/com/platform/hvac/controller/HvacIndicatorController.java) |
| 前端 HVAC 页面 | [`HvacDemoPage.vue`](web/src/pages/HvacDemoPage.vue) |

本地 `server.env`、`web/.env` 等被忽略配置只服务当前机器，不是项目事实，也不得提交密码或 Token。

## 7. 文档导航和生命周期

### 7.1 当前项目入口

| 入口 | 状态 | 用途 | 判断当前事实时的来源 |
|---|---|---|---|
| 当前代码与自动化测试 | 当前证据 | 判断当前实现、接口和行为 | 当前 Git 版本中的实现、配置和测试结果 |
| [`AGENTS.md`](AGENTS.md) | 当前有效 | 核心工作规则和按任务读取的规范路由 | 文件正文；只有规则或路由变更时更新 |
| [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md) | 当前有效 | 稳定架构、模块职责、数据链路和文档导航 | 代码结构与长期设计边界 |
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | 动态更新 | 已完成、未完成、风险、技术债和下一步 | 本文件所在 Git 版本的代码、测试和已合并决策 |

### 7.2 当前专题说明

| 文档 | 状态 | 用途 | 不用于判断 |
|---|---|---|---|
| [`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) | V1 设计基线 | 冻结范围、测点、公式和安全边界 | 当前完成进度和仍待实施事项 |
| [`docs/MQTT-硬件数据对接说明.md`](docs/MQTT-硬件数据对接说明.md) | 当前硬件契约 | 19 测点上行载荷、确认语义和接口边界 | 现场设备已经完成验收 |
| [`docs/HVAC控制能力设计备忘.md`](docs/HVAC控制能力设计备忘.md) | 未来安全约束 | 未来控制能力必须满足的安全和审计边界 | 当前已经存在控制功能 |
| [`docs/development/java21.md`](docs/development/java21.md) | 当前开发指南 | Java 21、Maven Wrapper 和 CI 验证 | 某台电脑已经正确配置环境 |
| [`docs/development/code-comments.md`](docs/development/code-comments.md) | 当前开发规范 | IoT 生产代码注释边界与风险分级 | 通用语言注释规则和不涉及生产代码的任务 |
| [`.agents/skills/iot-change-verification/SKILL.md`](.agents/skills/iot-change-verification/SKILL.md) | 当前 Codex Skill | 后端、前端、跨端、外部资源和流程变化的可执行验证矩阵 | 某次任务已经实际执行了所有命令 |
| [`docs/development/verification.md`](docs/development/verification.md) | 当前开发说明 | 面向开发者的验证原则和结果记录要求 | 完整命令矩阵和某次任务的执行结果 |

### 7.3 历史任务记录

- [`docs/superpowers/README.md`](docs/superpowers/README.md) 是历史任务中央目录，记录 spec/plan 配对、命名差异和已知替代关系。
- `docs/superpowers/specs` 保存任务当时确认的设计和取舍；`docs/superpowers/plans` 保存任务当时的实施步骤和验证方案。
- 设计与计划成对存在不属于重复：设计回答“为什么和做什么”，计划回答“如何实施和验证”。
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
- 具体任务设计和实施步骤继续写入 `docs/superpowers`，不复制到本文件。
- 单次命令、错误日志、端口占用、容器状态和本地备份路径保留在当前会话，不沉淀为项目指南。
- 发现文档与代码冲突时先核验代码和测试，再修正文档；证据不足时在 `PROJECT_STATUS.md` 标记为待核验。
