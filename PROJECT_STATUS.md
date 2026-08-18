# iot-platform-demo 项目状态

## 1. 状态基准

- 状态日期：2026-08-18。
- 代码基线：以本文件所在 Git 版本及其已合并代码、测试为准，不在文档中固定易过期的提交号。
- 本文件只描述其所在 Git 版本能够通过代码、测试、已合并提交或有效文档核验的状态。
- Docker 容器、端口、数据库数据、前后端进程等本地运行状态不进入本文件，必须在需要时现场检查。

## 2. 当前阶段

项目处于 V1 功能整合与稳定化阶段。当前优先保证中央空调 19 测点链路、权限、数据质量、指标计算、查询 API 和前端展示完整可用。

V1 继续保持 Spring Boot 单体后端和 Vue 前端，不进行微服务拆分，也不恢复已经下线的旧电表 Demo。

### 2.1 版本身份

- 当前权威基线：`main` 中最近已合并并具有代码、测试或有效文档证据的状态；它不自动等同于已部署、现场已验收或全部规划完成。
- 活动候选设计：已形成[可配置设备接入与业务绑定设计](docs/designs/2026-08-17-configurable-device-onboarding-design.md)，计划在不重写现有云端 JSON 适配器和正式时序链的前提下，补充产品型号、待绑定设备、接入向导以及建筑/设备/测点管理页面。
- 候选身份：当前 Git 版本已完成工作包 A 的领域发现后端、工作包 B 的绑定事务与版本化管理 API、工作包 C 的资产管理页面，以及工作包 D 的产品模板和设备接入页面；A、B 新增结构与 C、D 菜单迁移已通过隔离 MySQL 8.0 验证，但目标业务库执行和现场验收仍未完成，且候选尚未批准为新的正式版本。当前权威基线仍是既有已合并 V1 能力。
- 约束处理：候选保留“云端只转换、本地管归属、未知设备不写正式时序”的边界，仅把“首次上报前必须全部预注册”替换为“可先发现，正式入库前必须绑定并启用”。

## 3. 当前版本已完成

### 3.1 权限与业务档案

- 已建立四类正式角色、JWT 登录、动态菜单和建筑范围授权后端能力。
- 已形成仅供 `PLATFORM_ADMIN` 使用的最小后台管理界面，覆盖用户生命周期、固定角色权限、完整菜单树和建筑授权审批；`THIRD_PARTY` 对外显示为“接口调用方”，只能管理账号、启停和建筑范围，且禁止分配内部菜单，不提供角色 CRUD 或建筑 CRUD。
- 后台导航只接受受控注册表中的八条显式路由，数据库菜单不驱动前端组件动态加载；未上线菜单保留但隐藏并停用。
- Token 带唯一 `jti`，禁用、密码重置或角色变更撤销旧会话后，同一秒重新登录也不会复用黑名单 Token。
- 已建立建筑、系统、设备、测点、别名、命名规则和指标档案。
- 后端 Controller、Service 和 Mapper 已按建筑权限执行主要查询和管理流程。

证据入口：[`com.platform.system`](src/main/java/com/platform/system)、[`com.platform.security`](src/main/java/com/platform/security)、[`com.platform.hvac`](src/main/java/com/platform/hvac)、[`com.platform.integration`](src/main/java/com/platform/integration)。

### 3.2 HVAC 数据链路

- 已新增可独立部署到云服务器的 `telemetry-adapter`：按云端 MySQL 协议模板解析 JSON 多字段报文，以 QoS 1 发布一包标准多指标消息；云端规则首次不可用或标准发布失败时保留原始消息重投。
- 本地平台已主动订阅 `device/telemetry/up`，按预注册 `外部身份 → 设备 → 建筑 → 期望协议` 解析可信归属，并在任何正式写入前整包校验指标别名、设备/建筑归属、状态、单位、数值范围和重复代码。
- 标准多字段报文复用现有单测点 TDengine 幂等入库链；任一点存储失败时整包不 ACK，重投后已成功点按重复事件处理。真正未知设备会写入本地 MySQL 有界待绑定样例并记录低基数指标，但不自动创建设备、不猜建筑、不进入正式存储；已登记但停用的身份不会被误建为待绑定。
- 迁移期旧 `device/data/up` 单测点链仍保留；本地 MQTT 已实现旧 Topic 与标准 Topic 的精确路由、手动 ACK、固定会话、自动重连和首次连接失败后台重试。
- TDengine 已形成原始事件、整分钟数据、指标结果和公式异常的数据访问能力。
- 已实现分钟聚合、迟到数据处理、Q0/Q1/Q2 数据质量补全、恢复、任务重试和人工重算后端能力。
- 业务定时扫描、人工重算工作和迟到 Q0 修正已使用独立有界执行资源；人工任务以 MySQL `WAITING` 作为持久化队列，工作线程满时条件回退，TDengine 连接与读取也具备有限超时，单个慢任务不会占住后续重算扫描。迟到修正仅在当前分钟 Q0 读写期间持有外层锁，READY、公式和插值在释放锁后执行，避免与下游批量分钟锁形成循环等待。
- 已实现 `WCR_COP`、`TOWER_EFF`、`PUMP_EFF` 和 `AHU_POW_EFF` 四类指标计算、持久化、Redis 最新值缓存，以及认证后按建筑定向的 WebSocket 最佳努力发布。

证据入口：[`iot/ingest`](src/main/java/com/platform/iot/ingest)、[`iot/aggregation`](src/main/java/com/platform/iot/aggregation)、[`iot/dataquality`](src/main/java/com/platform/iot/dataquality)、[`iot/formula`](src/main/java/com/platform/iot/formula)、[`iot/temporal`](src/main/java/com/platform/iot/temporal)。

### 3.3 查询与前端真实数据基线

- 后端提供建筑最新 19 测点快照、历史窗口、最新指标、指标历史和计算详情查询能力。
- Redis 最新指标失效时，后端具备从 TDengine 查询最新结果的回退能力。
- `HvacDemoPage.vue` 已接入可访问建筑、最新测点快照和四项最新指标，不再生成随机业务数据或虚假历史、公式结果。
- 前端已接入 `/ws/hvac` 单连接：首帧 JWT/建筑订阅、心跳、四指标即时合并、500 毫秒 HTTP 权威对账、1/2/5/10/30 秒有界重连、稳定 60 秒退避复位和 30 秒 HTTP 降级均已闭环。
- 连接异常、协议、登录态和建筑拒绝分别处理 `4400`、`4401`、`4403`、`4408`、`1011`；切换建筑时清理旧状态并隔离迟到 HTTP/WebSocket 回调。
- 最新测点快照已区分分钟数据质量与查询时新鲜度；超过容忍边界的最后记录返回 `STALE`，页面主值显示 `--`、辅助展示最后值和来源分钟，并从当前有效测点完整率中排除。
- 四项实时指标卡已接入后端真实计算详情抽屉；成功状态展示输入、质量、公式版本和分步过程，失败卡片只展示业务摘要和缺失数量，原因码与缺失语义键集中在详情抽屉中。
- 详情请求在无指标或无分钟时保持本地空状态，快速切换、关闭和切换建筑时会隔离迟到响应，不在浏览器重算指标或填充默认值。
- 四项能效指标已通过建筑级批量趋势接口展示真实 TDengine 历史，服务端按查询跨度返回 1、5 或 30 分钟分辨率；原有单指标精确历史接口保持不变，用于公式版本和失败记录审计。
- 统一历史模块支持能效指标与原始测点切换；指标模式默认查询最近 24 小时四项指标，测点模式允许从冻结 19 测点中选择 1 至 8 个，并按建筑记忆有效选择。
- 前端按单位拆分图表、保留时间缺口和 Q0/Q1/Q2 质量语义，不计算指标、不使用假数据，也不自行降采样；查询失败时保留上次成功结果并明确提示过期状态。
- 相对历史范围在页面可见时每 30 秒通过现有历史 API 自动重查；页签进入后台时停止历史定时器，返回可见状态后立即补查并从返回时刻重启周期。自定义固定区间、空选择和在途请求不自动访问 TDengine，同一展示条件的移动窗口刷新失败继续保留上一份曲线。

证据入口：[`HvacQueryController.java`](src/main/java/com/platform/hvac/controller/HvacQueryController.java)、[`HvacIndicatorController.java`](src/main/java/com/platform/hvac/controller/HvacIndicatorController.java)、[`HvacIndicatorQueryService.java`](src/main/java/com/platform/hvac/service/HvacIndicatorQueryService.java)、[`web/src/api/hvac.ts`](web/src/api/hvac.ts)、[`useHvacDashboard.ts`](web/src/composables/useHvacDashboard.ts)、[`useHvacCalculationDetail.ts`](web/src/composables/useHvacCalculationDetail.ts)、[`useHvacHistoryTrends.ts`](web/src/composables/useHvacHistoryTrends.ts)、[`HvacCalculationDetailDrawer.vue`](web/src/components/hvac/HvacCalculationDetailDrawer.vue)、[`HvacHistoryPanel.vue`](web/src/components/hvac/HvacHistoryPanel.vue)、[`HvacDemoPage.vue`](web/src/pages/HvacDemoPage.vue)。

### 3.4 工程与运行基础

- 普通后端测试已与真实 MySQL、TDengine、MQTT 和 Redis 隔离。
- 云端适配器拥有独立 Maven 构建、H2/Mock 自动化测试、环境变量示例和 MySQL schema，并通过 HTTP 暴露 Actuator 健康检查与指标端点；后端 CI 同时验证本地平台与云端适配器。
- 后端开发和 CI 已统一到 Java 21。
- 前端已建立 Vue 3 + TypeScript ESLint 实用基线，`npm run lint` 会扫描当前 `.ts`/`.vue` 源码，并已进入 GitHub 前端 CI 合并检查。
- 仓库已提供本地 Git Hook 安装、任务预检、合并后安全清理、PR 模板、前端 CI 和 PR 注释防回退检查。
- MySQL、Redis 和 TDengine 的本地稳定卷、可配置端口、MySQL UTF-8 初始化和启动脚本 Compose 路径已形成仓库配置。
- 旧电表前后端、控制、模拟器、数据库结构和运行入口已经从当前有效项目中下线。
- 隔离 Docker 冒烟已验证首帧认证、受限用户 `4403`、跨建筑隔离、四指标推送、HTTP 对账和重连；浏览器已验证实时更新、后端断开后的 HTTP 保障、无新登录的自动恢复，以及持续 WebSocket 故障时 HTTP 继续刷新。
- 隔离 Docker 后台冒烟已验证管理员完整菜单、临时用户创建与禁用、旧会话失效、密码重置、隐藏菜单 CRUD、建筑申请审批、固定角色菜单替换及回滚。

证据入口：[`application-test.yml`](src/test/resources/application-test.yml)、[`java21.md`](docs/development/java21.md)、[`docker-compose.yml`](src/env/docker-compose.yml)、[`server.env.example`](server.env.example)。

### 3.5 可配置设备接入工作包 A

- 已新增产品型号、产品测点模板和待绑定设备的 MySQL 迁移、领域实体、Mapper 与 H2 等价测试表。
- 同一未知身份通过数据库唯一键原子 upsert：并发计数不丢失，首次/末次时间有序，乱序上报不覆盖较新的协议和样例，`IGNORED`、`BOUND` 不会被自动恢复。
- 待绑定样例只保存指标代码、数值、单位与事件/接收时间语义；字段数、字符串和 UTF-8 JSON 总大小均有配置上限，不保存原始厂商字段路径或未知字段。
- 待绑定 MySQL 写入使用有限重试，耗尽后仍确认未知消息并记录失败指标；未知设备不会调用 `HvacIngestionService` 或写 TDengine。
- 清理任务只删除过期 `DISCOVERED`、`IGNORED`，并同时受批量、批次数和执行超时限制；普通自动化测试关闭后台调度且不连接真实 MySQL、TDengine、Redis 或 Broker。

证据入口：[`com.platform.iot.onboarding`](src/main/java/com/platform/iot/onboarding)、[`StandardTelemetryIngestionService.java`](src/main/java/com/platform/iot/ingest/standard/StandardTelemetryIngestionService.java)、[`13-migrate-device-onboarding-discovery.sql`](src/env/init/13-migrate-device-onboarding-discovery.sql)。

### 3.6 可配置设备接入工作包 B

- 已提供 `/api/v1/device-products` 产品草稿、更新、复制、启停和稳定分页契约；启用产品不能原地破坏性修改，测点指标、单位、范围、身份类型和协议均由后端校验。
- 已提供 `/api/v1/device-onboarding` 待绑定分页、脱敏列表、授权详情、忽略/恢复、绑定和身份启停；Controller 与 Service 都限定 `PLATFORM_ADMIN`，响应不暴露 Entity、Mapper 或 MyBatis 分页对象。
- 绑定事务锁定 `DISCOVERED` 记录，校验建筑、空间、系统、设备、产品、身份、协议、测点和别名一致性；任一步失败回滚整套设备、身份、测点、别名和审计变化。
- 新建身份先保持停用；提交后刷新身份与测点完整快照，绑定响应明确配置是否已生效，身份启用只有在身份与设备专属别名均确认进入缓存后才返回成功。
- `biz_equipment.product_id` 对既有设备保持可空且不批量回填；新增接入审计只保存操作者、对象、状态和归属摘要，不保存完整样例、密码或 Token。
- 新版本 API 使用独立 DTO、Unix 毫秒时间、稳定 `PageResponse` 和机器错误码，并通过 OpenAPI、权限、事务、回滚和缓存可见性后端测试约束。

证据入口：[`DeviceProductService.java`](src/main/java/com/platform/iot/onboarding/DeviceProductService.java)、[`DeviceOnboardingService.java`](src/main/java/com/platform/iot/onboarding/DeviceOnboardingService.java)、[`iot/onboarding/api`](src/main/java/com/platform/iot/onboarding/api)、[`14-migrate-device-onboarding-binding.sql`](src/env/init/14-migrate-device-onboarding-binding.sql)。

### 3.7 可配置设备接入工作包 C

- 已提供 `/system/buildings` 建筑、空间树和系统分组页面，以及 `/system/devices` 设备、身份、协议、测点完整度和测点维护页面；两条入口都来自受控路由注册表和 MySQL 菜单，只向 `PLATFORM_ADMIN` 开放。
- 已提供 `/v1/assets` 建筑、空间、系统分组、设备和设备测点版本化接口；Controller 与 Service 双层校验管理员角色，响应使用独立 DTO、稳定分页、Unix 毫秒时间和机器错误码，不暴露 Entity 或 MyBatis 分页对象。
- 建筑删除会检查空间、系统、设备、测点和用户授权引用；空间、系统、设备和测点删除分别检查子空间、设备、身份、测点或别名引用，冲突返回稳定 409。空间更新额外阻断任意深度父子循环。
- 前端按 Page、Composable、API Client 和 Contract Types 分层，覆盖骨架加载、空数据、请求失败、权限拒绝、未知状态、迟到响应和同对象重复提交；页面不复制后端状态机，不生成假数据。
- OpenAPI、后端 DTO、前端契约样例、路由和菜单由自动化测试约束；旧 `/building`、`/equipment`、`/datapoint` 等实体型接口保持兼容，尚未进入清理阶段。

证据入口：[`com.platform.hvac.asset`](src/main/java/com/platform/hvac/asset)、[`assetManagement.ts`](web/src/api/assetManagement.ts)、[`useAssetManagement.ts`](web/src/composables/useAssetManagement.ts)、[`15-migrate-mysql-asset-management-menu.sql`](src/env/init/15-migrate-mysql-asset-management-menu.sql)。

### 3.8 可配置设备接入工作包 D

- 已提供 `/system/device-products` 产品与测点模板页面，覆盖筛选、详情、新建草稿、编辑草稿、复制和产品启停；写操作只接受详情中的 `allowedActions`，未知状态保持只读。
- 已提供 `/system/device-onboarding` 待绑定列表、授权详情、样例核对、忽略/恢复和分步接入向导；列表使用脱敏身份，只有授权详情展示完整身份。
- 接入向导复用资产管理查询选择建筑、空间、系统、现有设备和测点；新建设备时要求管理员明确提供后端契约所需的命名规则字段，不在浏览器硬编码或猜测规则。
- 绑定结果与身份启用分离展示；绑定成功后明确提示身份仍停用，只有管理员再次确认且后端返回配置生效后才显示启用成功。
- 前端按 Contract Types、API Client、Composable、Page/Component 分层，覆盖权限失败、空数据、迟到响应、同对象重复命令和精确路由；两条菜单只向 `PLATFORM_ADMIN` 分配。

证据入口：[`deviceOnboarding.ts`](web/src/api/deviceOnboarding.ts)、[`useDeviceOnboardingManagement.ts`](web/src/composables/useDeviceOnboardingManagement.ts)、[`DeviceProductManagementPage.vue`](web/src/pages/admin/DeviceProductManagementPage.vue)、[`DeviceOnboardingPage.vue`](web/src/pages/admin/DeviceOnboardingPage.vue)、[`16-migrate-mysql-device-onboarding-menu.sql`](src/env/init/16-migrate-mysql-device-onboarding-menu.sql)。

### 3.9 工作包 A、B、C、D 的隔离 MySQL 迁移验证

- `01`、`03`、`13`、`14`、`15`、`16` SQL 已在空白 MySQL 8.0 容器按真实顺序执行；A、B、C、D 四份增量迁移均通过重复执行验证。
- 绑定迁移会逐项核对设备产品列、索引和外键：中断后可补齐缺失结构，同名结构不符合预期时明确失败，不继续写入。
- 资产与设备接入菜单迁移会在写入前同时核对固定菜单 ID 与路径；冲突时拒绝覆盖既有菜单，正常执行后只向 `PLATFORM_ADMIN` 分配对应入口。
- 真实 MySQL 已验证新增状态检查、外键、UTF-8 菜单、既有设备保留和临时迁移过程清理；该证据不等于已在目标业务库执行。

## 4. 尚未完成或待继续

- 真实现场设备、网络抖动、长时间运行和现场数据质量仍需独立验收，不能由单元测试或本地冒烟替代。
- 云端 EMQX 账号、Topic ACL、TLS、云端 MySQL 和适配器进程仍需在目标云服务器部署并验证；仓库代码不会自动创建这些外部安全配置。
- 工作包 A、B 的新增结构和工作包 C、D 的菜单迁移虽已在隔离 MySQL 8.0 验证，也尚未在目标业务库执行。正式执行前仍必须备份并核对现有身份、产品编码、模板指标、别名、菜单 ID、菜单路径和外键冲突。
- 当前示例电表字段的实际单位、累计/周期语义、发送周期、时间戳和序号仍需硬件方确认；未确认前不能把示例单位当作正式数据契约。

## 5. 当前阻塞与风险

- 当前没有从 Git 代码确认的开发阻塞。
- GitHub `main` 分支保护不能由仓库文件自动启用，仍需仓库所有者在页面配置必需检查并通过实际 PR 现场验证；本地 Hook 也需要在每个 clone 中单独安装。
- MySQL、TDengine、Redis、EMQX 和现场设备都属于外部资源；某台开发机当前能否启动必须现场验证，本文件不作保证。
- 标准多字段链采用逐点幂等写入而非跨 TDengine 行事务；整包中途存储失败会依靠 MQTT 重投和事件幂等补齐，不承诺数据库层整包原子提交。
- 历史任务文档已增加文件级状态警告和中央目录；其正文仍保存当时状态，判断当前范围时不能跳过当前项目入口。
- WebSocket 会话仍是单实例内存注册表；当前未实现多实例会话共享或跨实例消息总线，部署扩容前必须另行设计。
- 仓库防回退 CI 只能证明文件、PR 证据和方法清单结构完整，不能单独证明业务注释语义正确。

## 6. 已确认技术债

- `THIRD_PARTY` 当前已具备按授权建筑读取建筑、设备和测点定义的 Open API，以及按建筑校验的 HVAC WebSocket 订阅；冻结书中的 COP、设备运行状态专用 REST API 和 Swagger 文档尚未实现，不能表述为完整第三方接入交付。
- 角色 CRUD、移动端专项适配和动态组件加载仍为最小后台明确不做，不是遗留缺陷；建筑与资产管理已按工作包 C 的受控范围实现。

记录这些事项不代表必须在当前任务立即重构或补齐；每项应在独立范围、设计、测试和 PR 中处理。

## 7. 下一步优先级

1. 按备份与冲突核对流程决定工作包 A 至 D 迁移在目标业务库的执行窗口，不能直接把隔离容器结果等同于生产执行完成。
2. 在目标云服务器部署 EMQX、适配器和适配器元数据 MySQL，按真实设备字段确认单位与协议模板。
3. 通过新的待绑定与接入流程完成真实设备单包核对，再执行 24 小时运行、断线恢复和数据质量验收。
4. 单独设计并补齐第三方 COP、设备运行状态 REST API 与 Swagger 文档。

每个事项开始前仍需根据 `AGENTS.md` 判断是否必须先确认独立设计和实施范围。

## 8. 文档健康

| 文档 | 本版本状态 | 核验结果与剩余边界 |
|---|---|---|
| [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md) | 当前有效 | 已按代码结构核对模块、链路和数据源边界，并增加历史任务中央目录入口。 |
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | 当前有效、持续更新 | 不再固定提交号或记录本机瞬时状态；业务完成项仍需随当前 Git 版本更新。 |
| [`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md) | V1 设计基线 | 第 11 章只保留稳定能力边界和当前状态入口，不再复制动态完成表；第 12 章和附录 C 已明确为历史排期与未来讨论。 |
| [`docs/MQTT-硬件数据对接说明.md`](docs/MQTT-硬件数据对接说明.md) | 当前静态契约已核验 | 已对照云端适配、本地预注册、双 Topic 路由、ACK 和重投实现；真实云服务器、硬件与网络仍需现场验收。 |
| [`docs/designs/2026-08-17-configurable-device-onboarding-design.md`](docs/designs/2026-08-17-configurable-device-onboarding-design.md) | 活动候选设计、工作包 A/B/C 已实现 | 已明确既有约束的保留与替代、云端适配边界、本地发现与绑定、页面范围和分段验收；D、E 尚未实现，候选也未批准为正式基线。 |
| [`docs/HVAC控制能力设计备忘.md`](docs/HVAC控制能力设计备忘.md) | 未来约束、当前未实现 | 当前代码只有 HVAC 上行采集和指标发布，没有控制入口、下行主题、状态机或协议 Adapter。 |
| [`docs/development/java21.md`](docs/development/java21.md) | 当前开发指南 | 已对照 `pom.xml`、Maven Wrapper、检查脚本和 CI，删除个人机器路径及管理员级 PATH 修改。 |

历史 specs/plans 的配对、缺失和替代关系统一查看 [`docs/superpowers/README.md`](docs/superpowers/README.md)。

## 9. 证据入口

- 固定工作规则：[`AGENTS.md`](AGENTS.md)。
- 稳定项目地图和完整链路：[`PROJECT_GUIDE.md`](PROJECT_GUIDE.md)。
- V1 业务和技术设计基线：[`docs/设计冻结书-V1.0-19测点.md`](docs/设计冻结书-V1.0-19测点.md)。
- MQTT 硬件契约：[`docs/MQTT-硬件数据对接说明.md`](docs/MQTT-硬件数据对接说明.md)。
- 活动候选设备接入设计：[`docs/designs/2026-08-17-configurable-device-onboarding-design.md`](docs/designs/2026-08-17-configurable-device-onboarding-design.md)。
- 任务设计和实施历史：[`docs/superpowers/README.md`](docs/superpowers/README.md)。
- 当前行为：其所在 Git 版本的代码、自动化测试和已合并提交。

## 10. 更新规则

- 本文件描述其所在 Git 版本，不描述某台电脑的瞬时运行状态。
- 只有具有代码、测试、已合并提交或有效文档证据的事项才能标记为完成。
- 计划、推测、本地未提交结果和只完成后端或前端一侧的能力必须明确标记为未完成或部分完成。
- 项目阶段、完成项、未完成项、阻塞、风险、技术债或下一步变化时，在对应任务分支同步更新本文件。
- 稳定架构、模块边界、数据源职责、核心链路或文档入口变化时更新 `PROJECT_GUIDE.md`，不得在两个文件重复维护同一状态。
