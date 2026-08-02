# iot-platform-demo 项目状态

## 1. 状态基准

- 状态日期：2026-08-02。
- 代码基线：`main` 提交 `e1fc110`。
- 本文件只描述其所在 Git 版本能够通过代码、测试、已合并提交或有效文档核验的状态。
- Docker 容器、端口、数据库数据、前后端进程等本地运行状态不进入本文件，必须在需要时现场检查。

## 2. 当前阶段

项目处于 V1 功能整合与稳定化阶段。当前优先保证中央空调 19 测点链路、权限、数据质量、指标计算、查询 API 和前端展示完整可用。

V1 继续保持 Spring Boot 单体后端和 Vue 前端，不进行微服务拆分，也不恢复已经下线的旧电表 Demo。

## 3. 已完成并进入 `main`

### 3.1 权限与业务档案

- 已建立四类正式角色、JWT 登录、动态菜单和建筑范围授权后端能力。
- 已建立建筑、系统、设备、测点、别名、命名规则和指标档案。
- 后端 Controller、Service 和 Mapper 已按建筑权限执行主要查询和管理流程。

证据入口：[`com.platform.system`](src/main/java/com/platform/system)、[`com.platform.security`](src/main/java/com/platform/security)、[`com.platform.hvac`](src/main/java/com/platform/hvac)。

### 3.2 HVAC 数据链路

- MQTT 只接收 `device/data/up` 的 HVAC 上行载荷，并按 19 测点配置完成身份解析和运行时质量校验。
- TDengine 已形成原始事件、整分钟数据、指标结果和公式异常的数据访问能力。
- 已实现分钟聚合、迟到数据处理、Q0/Q1/Q2 数据质量补全、恢复、任务重试和人工重算后端能力。
- 已实现 `WCR_COP`、`TOWER_EFF`、`PUMP_EFF` 和 `AHU_POW_EFF` 四类指标计算、持久化、Redis 最新值缓存和后端 WebSocket 发布。

证据入口：[`iot/ingest`](src/main/java/com/platform/iot/ingest)、[`iot/aggregation`](src/main/java/com/platform/iot/aggregation)、[`iot/dataquality`](src/main/java/com/platform/iot/dataquality)、[`iot/formula`](src/main/java/com/platform/iot/formula)、[`iot/temporal`](src/main/java/com/platform/iot/temporal)。

### 3.3 查询与前端真实数据基线

- 后端提供建筑最新 19 测点快照、历史窗口、最新指标、指标历史和计算详情查询能力。
- Redis 最新指标失效时，后端具备从 TDengine 查询最新结果的回退能力。
- `HvacDemoPage.vue` 已接入可访问建筑、最新测点快照和四项最新指标，不再生成随机业务数据或虚假历史、公式结果。
- 前端使用非重叠 30 秒轮询，切换建筑时清理旧状态，请求失败时保留已有数据并展示错误。

证据入口：[`HvacQueryController.java`](src/main/java/com/platform/hvac/controller/HvacQueryController.java)、[`HvacIndicatorController.java`](src/main/java/com/platform/hvac/controller/HvacIndicatorController.java)、[`web/src/api/hvac.ts`](web/src/api/hvac.ts)、[`useHvacDashboard.ts`](web/src/composables/useHvacDashboard.ts)、[`HvacDemoPage.vue`](web/src/pages/HvacDemoPage.vue)。

### 3.4 工程与运行基础

- 普通后端测试已与真实 MySQL、TDengine、MQTT 和 Redis 隔离。
- 后端开发和 CI 已统一到 Java 21。
- MySQL、Redis 和 TDengine 的本地稳定卷、可配置端口、MySQL UTF-8 初始化和启动脚本 Compose 路径已形成仓库配置。
- 旧电表前后端、控制、模拟器、数据库结构和运行入口已经从当前有效项目中下线。

证据入口：[`application-test.yml`](src/test/resources/application-test.yml)、[`java21.md`](docs/development/java21.md)、[`docker-compose.yml`](src/env/docker-compose.yml)、[`server.env.example`](server.env.example)。

## 4. 尚未完成或待继续

- 前端尚未消费后端 `/ws/hvac` 实时消息，目前 HVAC 页面仍使用 30 秒轮询。
- HVAC 页面公式卡片尚未接入后端真实计算详情。
- 后端已经提供历史查询，但前端尚未形成真实历史曲线和历史查询交互。
- 用户、角色、菜单和建筑授权已有后端能力，但当前前端尚未形成对应的最小后台管理页面。
- 真实现场设备、网络抖动、长时间运行和现场数据质量仍需独立验收，不能由单元测试或本地冒烟替代。

## 5. 当前阻塞与风险

- 当前没有从 Git 代码确认的开发阻塞。
- MySQL、TDengine、Redis、EMQX 和现场设备都属于外部资源；某台开发机当前能否启动必须现场验证，本文件不作保证。
- 设计冻结书和部分早期任务文档包含当时的阶段状态，直接阅读可能误判当前完成范围。
- 后端已经具备 WebSocket、历史和计算详情能力，但前端未完成对应消费，不能把后端存在接口描述成完整用户功能。

## 6. 已确认技术债

- [`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) 第 11、12 章仍保留早期“需新增”和一个月实施计划，未反映当前代码状态，需要后续专题核验。
- `docs/superpowers/specs` 和 `docs/superpowers/plans` 中存在已经被后续任务替代的历史描述，目前缺少逐份状态标记；它们应视为历史任务记录。
- 前端 30 秒轮询与后端 WebSocket 能力尚未闭环。
- 前端公式详情、历史查询和最小后台页面尚未闭环。

记录这些事项不代表必须在当前任务立即重构或补齐；每项应在独立范围、设计、测试和 PR 中处理。

## 7. 下一步优先级

1. 接入真实公式计算详情和历史数据展示，形成可验证的前后端闭环。
2. 接入 `/ws/hvac`，并保留轮询作为明确的重连或降级策略。
3. 建立用户、角色、菜单和建筑授权的最小后台前端入口。
4. 开展当前专题文档健康核验，优先修正设计冻结书中的阶段状态，并为历史任务文档明确使用边界。
5. 使用真实现场设备和数据完成长时间运行、异常恢复和数据质量验收。

每个事项开始前仍需根据 `AGENTS.md` 判断是否必须先确认独立设计和实施范围。

## 8. 证据入口

- 固定工作规则：[`AGENTS.md`](AGENTS.md)。
- 稳定项目地图和完整链路：[`PROJECT_GUIDE.md`](PROJECT_GUIDE.md)。
- V1 业务和技术设计基线：[`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md)。
- 19 测点硬件契约：[`docs/MQTT-硬件数据对接说明.md`](docs/MQTT-硬件数据对接说明.md)。
- 任务设计和实施历史：`docs/superpowers/specs`、`docs/superpowers/plans`。
- 当前行为：其所在 Git 版本的代码、自动化测试和已合并提交。

## 9. 更新规则

- 本文件描述其所在 Git 版本，不描述某台电脑的瞬时运行状态。
- 只有具有代码、测试、已合并提交或有效文档证据的事项才能标记为完成。
- 计划、推测、本地未提交结果和只完成后端或前端一侧的能力必须明确标记为未完成或部分完成。
- 项目阶段、完成项、未完成项、阻塞、风险、技术债或下一步变化时，在对应任务分支同步更新本文件。
- 稳定架构、模块边界、数据源职责、核心链路或文档入口变化时更新 `PROJECT_GUIDE.md`，不得在两个文件重复维护同一状态。
