# iot-platform-demo 项目状态

## 1. 状态基准

- 状态日期：2026-08-04。
- 代码基线：以本文件所在 Git 版本及其已合并代码、测试为准，不在文档中固定易过期的提交号。
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
- 最新测点快照已区分分钟数据质量与查询时新鲜度；超过容忍边界的最后记录返回 `STALE`，页面主值显示 `--`、辅助展示最后值和来源分钟，并从当前有效测点完整率中排除。
- 四项实时指标卡已接入后端真实计算详情抽屉；成功状态展示输入、质量、公式版本和分步过程，失败卡片只展示业务摘要和缺失数量，原因码与缺失语义键集中在详情抽屉中。
- 详情请求在无指标或无分钟时保持本地空状态，快速切换、关闭和切换建筑时会隔离迟到响应，不在浏览器重算指标或填充默认值。

证据入口：[`HvacQueryController.java`](src/main/java/com/platform/hvac/controller/HvacQueryController.java)、[`HvacIndicatorController.java`](src/main/java/com/platform/hvac/controller/HvacIndicatorController.java)、[`web/src/api/hvac.ts`](web/src/api/hvac.ts)、[`useHvacDashboard.ts`](web/src/composables/useHvacDashboard.ts)、[`useHvacCalculationDetail.ts`](web/src/composables/useHvacCalculationDetail.ts)、[`HvacCalculationDetailDrawer.vue`](web/src/components/hvac/HvacCalculationDetailDrawer.vue)、[`HvacDemoPage.vue`](web/src/pages/HvacDemoPage.vue)。

### 3.4 工程与运行基础

- 普通后端测试已与真实 MySQL、TDengine、MQTT 和 Redis 隔离。
- 后端开发和 CI 已统一到 Java 21。
- 前端已建立 Vue 3 + TypeScript ESLint 实用基线，`npm run lint` 会扫描当前 `.ts`/`.vue` 源码，并已进入 GitHub 前端 CI 合并检查。
- 仓库已提供本地 Git Hook 安装、任务预检、合并后安全清理、PR 模板、前端 CI 和 PR 注释防回退检查。
- MySQL、Redis 和 TDengine 的本地稳定卷、可配置端口、MySQL UTF-8 初始化和启动脚本 Compose 路径已形成仓库配置。
- 旧电表前后端、控制、模拟器、数据库结构和运行入口已经从当前有效项目中下线。

证据入口：[`application-test.yml`](src/test/resources/application-test.yml)、[`java21.md`](docs/development/java21.md)、[`docker-compose.yml`](src/env/docker-compose.yml)、[`server.env.example`](server.env.example)。

## 4. 尚未完成或待继续

- 前端尚未消费后端 `/ws/hvac` 实时消息，目前 HVAC 页面仍使用 30 秒轮询。
- 后端已经提供历史查询，但前端尚未形成真实历史曲线和历史查询交互。
- 用户、角色、菜单和建筑授权已有后端能力，但当前前端尚未形成对应的最小后台管理页面。
- 真实现场设备、网络抖动、长时间运行和现场数据质量仍需独立验收，不能由单元测试或本地冒烟替代。

## 5. 当前阻塞与风险

- 当前没有从 Git 代码确认的开发阻塞。
- GitHub `main` 分支保护不能由仓库文件自动启用，仍需仓库所有者在页面配置必需检查并通过实际 PR 现场验证；本地 Hook 也需要在每个 clone 中单独安装。
- MySQL、TDengine、Redis、EMQX 和现场设备都属于外部资源；某台开发机当前能否启动必须现场验证，本文件不作保证。
- 历史任务文档已增加文件级状态警告和中央目录；其正文仍保存当时状态，判断当前范围时不能跳过当前项目入口。
- 后端已经具备 WebSocket 和历史查询能力，但前端未完成对应消费，不能把后端存在接口描述成完整用户功能。
- 仓库防回退 CI 只能证明文件、PR 证据和方法清单结构完整，不能单独证明业务注释语义正确。

## 6. 已确认技术债

- 前端 30 秒轮询与后端 WebSocket 能力尚未闭环。
- 前端历史查询和最小后台页面尚未闭环。

记录这些事项不代表必须在当前任务立即重构或补齐；每项应在独立范围、设计、测试和 PR 中处理。

## 7. 下一步优先级

1. 接入真实历史数据展示和时间范围查询，形成可验证的前后端闭环。
2. 接入 `/ws/hvac`，并保留轮询作为明确的重连或降级策略。
3. 建立用户、角色、菜单和建筑授权的最小后台前端入口。
4. 使用真实现场设备和数据完成长时间运行、异常恢复和数据质量验收。

每个事项开始前仍需根据 `AGENTS.md` 判断是否必须先确认独立设计和实施范围。

## 8. 文档健康

| 文档 | 本版本状态 | 核验结果与剩余边界 |
|---|---|---|
| [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md) | 当前有效 | 已按代码结构核对模块、链路和数据源边界，并增加历史任务中央目录入口。 |
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | 当前有效、持续更新 | 不再固定提交号或记录本机瞬时状态；业务完成项仍需随当前 Git 版本更新。 |
| [`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) | V1 设计基线 | 第 11 章已改为能力概览，第 12 章和附录 C 已明确为历史排期与未来讨论。 |
| [`docs/MQTT-硬件数据对接说明.md`](docs/MQTT-硬件数据对接说明.md) | 当前静态契约已核验 | 已对照 MQTT 配置、接入处理、测试别名和 WebSocket 端点；真实硬件与网络仍需现场验收。 |
| [`docs/HVAC控制能力设计备忘.md`](docs/HVAC控制能力设计备忘.md) | 未来约束、当前未实现 | 当前代码只有 HVAC 上行采集和指标发布，没有控制入口、下行主题、状态机或协议 Adapter。 |
| [`docs/development/java21.md`](docs/development/java21.md) | 当前开发指南 | 已对照 `pom.xml`、Maven Wrapper、检查脚本和 CI，删除个人机器路径及管理员级 PATH 修改。 |

历史 specs/plans 的配对、缺失和替代关系统一查看 [`docs/superpowers/README.md`](docs/superpowers/README.md)。

## 9. 证据入口

- 固定工作规则：[`AGENTS.md`](AGENTS.md)。
- 稳定项目地图和完整链路：[`PROJECT_GUIDE.md`](PROJECT_GUIDE.md)。
- V1 业务和技术设计基线：[`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md)。
- 19 测点硬件契约：[`docs/MQTT-硬件数据对接说明.md`](docs/MQTT-硬件数据对接说明.md)。
- 任务设计和实施历史：[`docs/superpowers/README.md`](docs/superpowers/README.md)。
- 当前行为：其所在 Git 版本的代码、自动化测试和已合并提交。

## 10. 更新规则

- 本文件描述其所在 Git 版本，不描述某台电脑的瞬时运行状态。
- 只有具有代码、测试、已合并提交或有效文档证据的事项才能标记为完成。
- 计划、推测、本地未提交结果和只完成后端或前端一侧的能力必须明确标记为未完成或部分完成。
- 项目阶段、完成项、未完成项、阻塞、风险、技术债或下一步变化时，在对应任务分支同步更新本文件。
- 稳定架构、模块边界、数据源职责、核心链路或文档入口变化时更新 `PROJECT_GUIDE.md`，不得在两个文件重复维护同一状态。
