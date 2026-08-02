# 旧电表 Demo 彻底下线设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 1. 背景

当前项目最初基于电表 Demo 二次开发，随后形成了面向中央空调经常性调适的
HVAC 业务链。现有代码因此同时存在两套设备与时序模型：

- 旧电表链：`iot_device`、`DeviceMessage`、`st_electric_data`、
  `/device/**`、`/telemetry/**`、`/control/**` 和 `/ws/dashboard`；
- HVAC 链：`biz_equipment`、`biz_data_point`、`st_raw_event`、
  `st_raw_minute`、`st_indicator_minute`、HVAC 查询、数据质量和公式引擎。

旧电表链已经不属于中央空调 V1 的产品范围。继续通过功能开关保留整套旧实现，
会让设备模型、接口、数据库、前端路由和测试范围长期混淆。因此本次不采用
“隐藏菜单”“默认关闭”或“移动到 legacy 包”的方式，而是从当前有效项目中
彻底删除旧电表 Demo，只通过 Git 历史保留恢复和参考能力。

## 2. 目标

完成后，当前工作树、构建产物、默认数据库和运行进程只包含：

- 登录、四角色 RBAC、动态菜单和建筑数据权限；
- 建筑、空间、系统分组、HVAC 设备和标准测点档案；
- MQTT 19 测点真实数据接入；
- TDengine 原始事件、分钟数据、指标结果和异常证据；
- 数据质量补全、迟到真实值纠正和人工重算；
- 四个 HVAC 指标公式；
- HVAC 查询、实时消息和第三方开放能力；
- 冻结书范围内的代码生成器及平台基础设施；
- HVAC 19 测点模拟器和暂时保留的 HVAC 视觉 Demo。

旧电表前端、后端、控制、模拟器、数据库表、时序表、测试数据和运行配置不再
存在，也不提供 `legacy-meter.enabled=false` 一类遗留功能开关。

## 3. 非目标

本次不包含：

- 正式 HVAC 前端页面；
- WebSocket JWT 握手和建筑订阅隔离；
- Modbus、BACnet、厂家 API 或完整多协议中心；
- HVAC 控制功能；
- 最终视觉设计、移动端适配或组件库重构；
- 与旧电表下线无关的模块化重构；
- 删除 `docs/superpowers` 中混合记录 RBAC、HVAC 演进的历史设计资料。

历史设计资料不是运行功能。当前有效的冻结书、MQTT 对接说明和启动说明必须
更新；历史文档中的旧电表文字允许作为演进证据保留。

## 4. 核心决策

### 4.1 删除而非隔离

旧电表实现从当前分支直接删除，不移动到新包，不保留禁用 Bean，不保留前端
隐藏路由，不保留默认关闭的数据库初始化脚本。恢复只能通过 Git 历史完成。

### 4.2 保留控制设计经验，不保留旧控制实现

冻结书原 D-007 的目的，是降低后续增加 HVAC 控制能力的成本。现有控制代码
依赖 `iot_device`、整数 `commandType`、无类型 `Map` 参数、硬编码 MQTT 主题和
恒定返回成功的 MPC 占位实现，不满足 HVAC 建筑权限、设备能力、安全联锁、
审批、审计、协议适配和执行回读要求。

本次删除旧控制实现，但在当前有效文档中保留以下未来设计约束：

- 控制功能必须有部署级开关，默认关闭；
- 指令必须有唯一追踪号、审计记录、明确状态机、ACK 关联和超时处理；
- 目标设备必须来自 `biz_equipment`，并校验建筑范围和设备能力；
- 控制参数必须使用有类型 DTO、单位、范围和前置状态校验；
- 协议下发必须通过适配器，不在业务 Service 中写死 MQTT；
- 后续 HVAC 控制必须单独设计和验收，不复用旧电表实现。

冻结书 D-007 相应调整为：“保留控制能力的设计约束和 Git 历史参考，不保留旧
电表控制实现；V1 禁止控制，后续 HVAC 控制独立设计。”

### 4.3 不按 `iot` 目录整体删除

`com.platform.iot` 中同时存在旧电表代码和正式 HVAC 采集代码。删除必须按依赖
边界进行，禁止删除整个 `iot` 包。以下正式能力仍属于 HVAC：

- `iot/ingest`
- `iot/quality`
- `iot/aggregation`
- `iot/dataquality`
- `iot/formula`
- `iot/temporal` 中的 HVAC 仓储、模型和留存服务
- `iot/websocket` 中公式模块依赖的实时消息边界

## 5. 删除范围

### 5.1 后端 Java

删除旧电表专用的以下能力及其对应测试：

- `IotDeviceController`
- `TelemetryController`
- `ControlController`
- `ControlFeature`
- `MpcAlgorithmService`
- `IotMessagePublisher`
- `DeviceMessage`
- `DeviceMessageConsumer`
- `CommandAckConsumer`
- `DeviceHeartbeatService`
- `IotDevice`
- `IotDeviceStatusLog`
- `ControlCommand`
- `IotDeviceMapper`
- `IotDeviceStatusLogMapper`
- `ControlCommandMapper`
- `IotDeviceService`
- `IotDeviceServiceImpl`
- `ControlCommandServiceImpl`
- `MqttPublisher`
- `TimeSeriesRepository`
- `TDengineRepositoryImpl`
- `DeviceStatusCacheService`

删除完成后，不应存在设备状态缓存、旧心跳扫描、旧控制超时扫描、电表批量刷盘、
旧 ACK 消费者或旧 MPC 占位 Bean。

### 5.2 前端

删除以下旧电表页面和业务能力：

- `web/src/pages/DashboardPage.vue`
- `web/src/pages/DevicePage.vue`
- `web/src/api/device.ts`
- `web/src/api/control.ts`
- `web/src/store/device.ts`
- `web/src/store/device.test.ts`
- `web/src/types/ws.ts`
- `web/src/utils/websocket.ts`
- 仅被旧页面使用的 `ChartPanel.vue`、`GlassCard.vue`、`MetricFlipper.vue`、
  `StatusBadge.vue`、`ScreenLayout.vue` 和 `AdminLayout.vue`
- 空且未使用的 `HomePage.vue` 和 `Empty.vue`

删除 `/dashboard` 和 `/device` 路由。过渡阶段 `/` 指向 `/login`，登录成功后
临时进入 `/hvac-demo`；正式 HVAC 页面完成后再改成 `/hvac/overview`。

前端保留：

- `LoginPage.vue`
- `ForbiddenPage.vue`
- `HvacDemoPage.vue`
- `api/auth.ts`
- `store/auth.ts`
- 通用 HTTP、鉴权和主题基础能力

`store/auth.ts` 中旧 `ADMIN` 判断必须同步改为 `PLATFORM_ADMIN`，登录页删除
“电表、设备台账、指令下发、普通 USER”相关文案。

### 5.3 数据库和初始化

MySQL 默认初始化不再创建或写入：

- `iot_device`
- `iot_device_status_log`
- `control_commands`
- `meter-001`
- 旧 `ADMIN`、`USER` 角色
- 1 万台电表测试数据

`sys_user`、`sys_role` 和 `sys_user_role` 仍保留，但全新环境直接初始化四类正式
角色：

- `BUILDING_OWNER`
- `ENERGY_MANAGER`
- `THIRD_PARTY`
- `PLATFORM_ADMIN`

内置 `admin` 直接关联 `PLATFORM_ADMIN`，不再通过旧 `ADMIN` 角色平滑迁移。
注册用户继续默认获得 `BUILDING_OWNER`，且不自动获得任何建筑。

删除默认执行的 `02-init-10000-devices.sql`。从 HVAC Schema 中删除对
`iot_device.building_id` 的兼容升级。不得在应用启动时自动执行 `DROP TABLE`；
现有环境使用明确的测试环境清理操作或重建测试数据库。

TDengine 默认初始化删除：

- `tdengine.stableName`
- `st_electric_data`
- 电压、电流、有功功率和旧电表标签结构
- 对旧电表超级表的创建、日志和启动校验

必须保留并验证：

- `st_raw_event`
- `st_raw_minute`
- `st_indicator_minute`
- `st_formula_calc_exception`

### 5.4 模拟器和辅助脚本

删除：

- `.scripts/simulate-devices.mjs`
- `.scripts/stress-test.mjs`
- `.scripts/generate-ppt.mjs` 中仅服务旧电表汇报的脚本文件
- `mqtt.demo-simulator-enabled`
- MQTT 配置中的控制下行订阅、自动 ACK、模拟开关机和随机
  `voltage_a/current_a/active_power` 上报

保留：

- `.scripts/simulate-hvac-19-points.mjs`
- HVAC 缺测、重复、冲突、迟到和质量补全相关自动化测试
- `.scripts/generate-ppt-v2.mjs` 等非旧电表专用资料脚本

旧 `stress-test.mjs` 删除后，HVAC 专用持续压测和场景生成器作为后续全面测试
任务实施，不在本次删除任务中混入新功能。

## 6. 必须解耦后保留的共享点

### 6.1 MQTT

`MqttConfig` 不能整文件删除。它改造成 HVAC 专用连接配置：

```text
EMQX
  → 上行主题
  → 载荷大小和 JSON 基础校验
  → HvacMqttMessageHandler
  → HvacIngestionService
  → TDengine 原始事件
  → 按存储结果决定手动 ACK
```

必须保持现有 HVAC 可靠性语义：

- 合法消息成功写入后确认；
- 重复或业务无效的毒消息确认并丢弃；
- TDengine 存储失败时不确认，保留 QoS 1 重投机会；
- 来源系统由服务端可信配置确定；
- 不改变当前 19 测点 MQTT 契约和上行主题。

删除旧 EMQX 离线系统主题、控制下行主题、`DeviceMessage` 分流和普通电表属性
分支。上行主题收到缺少 `pointCode` 的旧格式消息时，应记录明确拒绝原因并确认
丢弃，不能进入其他业务链。

### 6.2 WebSocket

现有 `WebSocketServer` 同时被旧电表消费者和 HVAC
`IndicatorRealtimePublisher` 使用，不能直接连同整个包删除。

处理方式：

- 删除旧 `/ws/dashboard` 端点；
- 建立语义明确的 `/ws/hvac` 端点；
- 保留 `RealtimeMessageGateway` 接口；
- 让 `WebSocketRealtimeMessageGateway` 只转发 HVAC 指标消息；
- 删除 `DeviceMessageConsumer` 的设备消息广播；
- 更新安全配置和当前有效文档中的端点说明。

本次只完成业务边界清理。JWT 握手、建筑订阅和广播隔离在正式 HVAC 实时功能
任务中实现。

### 6.3 TDengine

`TdengineConfig` 和 `TdengineProperties` 继续负责独立 TDengine 数据源、初始化
开关、连接池和 HVAC Schema。只删除旧电表超级表分支，禁止删除整个配置类或
数据源 Bean。

启动日志和初始化验证只列出四个 HVAC 超级表。测试必须捕获初始化 SQL，确认
不再包含 `st_electric_data`、`voltage_a`、`current_a` 或 `active_power`。

## 7. 测试迁移

旧测试不能简单全部删除后降低覆盖率，应将仍有价值的安全断言迁移到 HVAC：

- `AuthRbacFlowTest` 不再创建和删除电表，改为验证注册、登录、正式角色以及
  HVAC/系统管理接口的认证和权限；
- `FourRoleBackendFlowTest` 删除旧 `/control/issue` 断言，保留角色、菜单、
  建筑申请和开放接口流程，并增加旧路由不存在的断言；
- H2 `schema-test.sql` 删除三张旧表；
- `data-test.sql` 删除旧角色和 `meter-001`；
- 删除 `device.test.ts`，补充前端认证、正式角色或路由测试，保证前端测试套件
  仍然有有效测试；
- HVAC 接入、聚合、数据质量、公式和查询测试保持原样并全部通过。

使用已认证管理员访问以下旧接口时必须返回 404，而不是因权限判断返回 403：

- `/device/list`
- `/telemetry/history`
- `/control/issue`

`/ws/dashboard` 必须不可连接，`/ws/hvac` 必须仍可承载 HVAC 指标消息。

## 8. 文档调整

当前有效文档同步修改：

- `docs/设计冻结书-V1.0-19测点.md`
  - 修订 D-007；
  - 删除把旧 `DeviceMessageConsumer`、电表 TDengine 缓冲和旧压测脚本描述为
    当前能力的内容；
  - 明确 V1 只有采集与计算，没有控制；
- 将 `docs/MQTT-硬件数据对接说明.md` 整理或重命名为 HVAC 专用对接说明，
  删除 `meter-001`、电压、电流、有功功率、旧接口和旧 WebSocket 内容；
- 增加未来 HVAC 控制设计备忘，只记录约束，不保留可执行控制代码。

历史 `docs/superpowers` 允许保留旧电表文字；它们必须被视为历史审计记录，不是
当前接口和运行说明。静态零残留扫描对此目录和本设计文档建立明确白名单。

## 9. 实施顺序与风险控制

为避免共享依赖突然断裂，实施按以下顺序进行：

1. 运行后端和前端基线测试，记录删除前结果；
2. 先用测试定义旧路由不存在、HVAC Schema 不含电表结构等目标行为；
3. 解耦 MQTT、WebSocket 和 TDengine 三个共享点；
4. 确认 HVAC 定向测试通过后，删除后端旧电表闭环；
5. 清理 MySQL/TDengine 初始化、测试 Schema 和测试数据；
6. 删除前端旧路由、页面、Store、API和只被旧页面使用的组件；
7. 删除旧模拟器、压测脚本和旧文档内容；
8. 执行静态残留扫描、完整回归和干净环境端到端冒烟；
9. 只暂存本任务明确列出的文件，检查没有误包含 `outputs/`。

任何一步出现 HVAC 编译错误或定向测试失败，都应停止继续删除并恢复该步正在
处理的文件，先查清共享依赖；不得通过删除失败测试或放宽断言绕过问题。

## 10. 验收标准

### 10.1 静态零残留

以下有效目录不得再出现旧电表业务引用：

- `src/main`
- `web/src`
- `src/env/init`
- `src/test`
- `.scripts`
- 当前有效运行和接口文档

重点扫描：

- `meter-001`
- `voltage_a`
- `current_a`
- `active_power`
- `iot_device`
- `iot_device_status_log`
- `control_commands`
- `st_electric_data`
- `/device/list`
- `/telemetry/history`
- `/control/issue`
- `/ws/dashboard`
- `demo-simulator-enabled`
- `simulate-devices`

允许命中的位置只有本下线设计、明确的未来控制备忘和历史
`docs/superpowers`；这些位置不得被构建或运行时加载。

### 10.2 数据库

全新 MySQL：

- 不存在三张旧表；
- 不存在 `ADMIN`、`USER`角色；
- 只存在四类正式角色；
- `admin`拥有 `PLATFORM_ADMIN`；
- HVAC资产、19测点、来源别名和四个指标初始化成功。

全新 TDengine：

- 不存在 `st_electric_data`；
- 四个 HVAC 超级表存在且字段契约正确。

### 10.3 自动化测试

必须执行并通过：

```bash
mvn test
cd web
npm test
npm run build
```

测试结果必须记录通过数量、跳过项和未执行项。生产代码发生变化时，任何失败
都不得以“属于旧Demo”为理由忽略；只有确认测试本身唯一覆盖已删除能力时才能
删除或改写。

### 10.4 干净环境冒烟

在可用的专用测试环境中从空数据库启动：

```text
启动 MySQL、TDengine、Redis、EMQX
→ 启动后端
→ 登录平台管理员
→ 运行 HVAC 19 测点模拟器
→ 验证原始事件和分钟数据
→ 验证四个指标
→ 验证快照、历史和计算详情 API
→ 验证 /ws/hvac
→ 验证旧路由、旧表和旧超级表不存在
```

如果当前机器没有 Docker，必须如实标记此项未执行，不能用单元测试替代真实
外部链路验收。

## 11. 完成后的系统边界

完成后产品结构为：

```text
中央空调设备/环境测点
  → MQTT 19 测点
  → 可信来源别名映射
  → TDengine 原始事件
  → 分钟聚合
  → 数据质量补全
  → HVAC 公式
  → Redis / HTTP / WebSocket
  → 正式 HVAC 前端（后续任务）
```

项目中不再存在并行的电表设备模型、旧控制闭环或电表展示入口。未来需要控制
或其他协议时，应围绕 HVAC 的 `biz_equipment`、标准测点、建筑权限和协议适配
边界新增能力，而不是恢复旧电表实现。
