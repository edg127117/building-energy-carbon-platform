# 标准设备接入优化（第一阶段）正式设计文档

- **文档状态**：一期设计基线。代码发布与验收状态见 [PROJECT_STATUS.md](../../PROJECT_STATUS.md)
- **适用范围**：第一阶段（范围 A：统一操作入口 + 标准设备极简接入 + 审批合单）
- **分工边界**：
  - **前端实施方**：负责导航与路由迁移、待接入页面融合、极简绑定弹窗、产品测点类型表单、审批与重试交互
  - **后端实施方**：负责 Flyway 迁移、菜单授权路径更新、产品模板 `dataType` 契约、复用已有档案自动建点、合单绑定并启用执行编排
- **关联现状**：
  - 导航注册：[catalog.ts](../../web/src/app/navigation/catalog.ts)
  - 待接入页面：[PendingDevicePage.vue](../../web/src/modules/device-onboarding/pages/PendingDevicePage.vue)、[BindingDraftDialog.vue](../../web/src/modules/device-onboarding/components/BindingDraftDialog.vue)
  - 后端领域服务：[DeviceOnboardingService.java](../../src/main/java/com/platform/iot/onboarding/DeviceOnboardingService.java)、[ScopedDeviceOnboardingService.java](../../src/main/java/com/platform/iot/onboarding/ScopedDeviceOnboardingService.java)
  - 审批命令适配：[DeviceOnboardingSensitiveOperationHandlers.java](../../src/main/java/com/platform/audit/system/DeviceOnboardingSensitiveOperationHandlers.java)、[BindTypedPendingDeviceHandler.java](../../src/main/java/com/platform/audit/system/BindTypedPendingDeviceHandler.java)

---

## 1. 设计目标与工程原则

### 1.1 目标
解决当前标准设备接入“入口分散、同型号重复填表、绑定与启用分两单审批、已有台账无法走自动建点、自动建点写死 `ANALOG`”五个已确认问题，使同型号后续设备接入时**通常只需确认安装位置并提交一次申请**。

### 1.2 工程原则（克制与去冗余）
1. **减少保守性代码与防守性需求**：
   - 不保留长期双路径菜单授权，统一收口到唯一新路径 `/configuration/ingestion/pendingDevices`（前端仅保留旧路径一次性路由重定向）。
   - 不新增平行审批操作码，直接在既有 `BIND_PENDING_DEVICE`（及 `BIND_TYPED_PENDING_DEVICE`）命令中扩展 `activateOnApprove` 字段。
   - 不在前端保留 `general` / `daikin` 两套互相代理的状态机，直接重构为统一页面视图与按来源条件渲染。
   - 不在前端复写后端已执行的并发/权限/命名规则校验，前端只做表单必填与候选提示，后端作为唯一权威校验边界。
2. **严格遵守讨论范围**：
   - 仅实现本文档列出的 5 项已确认能力，**不得夹带未讨论的新功能**。

---

## 2. 明确边界与模块保护红线

### 2.1 本期明确不做（Out of Scope）
- **不做**雷达与门磁并入统一后台采集主链路、历史连续查询与 `roomsensor` 旧链路退役（留待第二阶段独立立项）。
- **不做**新装雷达/门磁从统一页面写 `biz_room_sensor_source`（不在范围 A 内）。
- **不做**已在采集设备的跨房间搬迁改绑与历史归属迁移。
- **不做**通用报文脚本编辑器，也不将大金批量补温推广到其他品牌。
- **不做**标准设备办理权限向非平台管理员放宽（未建立可信来源与建筑映射前，标准设备仍限 `PLATFORM_ADMIN`）。
- **不介入**设备综合台账收尾工作（由原对话负责）。

### 2.2 既有模块保护边界（不得破坏）
实施本设计时，以下既有模块与链路必须保持原有行为不变：
1. **标准遥测运行时入库主链路**：`device/telemetry/up`、V2 可靠链、身份校验、测点别名解析与 TDengine 写入逻辑不改动。
2. **房间传感器独立采集链路**：`com.platform.iot.roomsensor`、`ROOM_SENSOR_ENABLED`、队列及 `st_radar_observation` / `st_door_observation` 不改动。
3. **大金业务能力与权限隔离**：大金目录同步、项目↔建筑映射、房间匹配、温度来源与批量补温逻辑不变；非平台管理员仍只能在授权建筑内看到并办理大金待接入设备。
4. **敏感变更治理边界**：保持 `SensitiveChange` 独立审核与职责分离（生产关闭自审，审计留痕完整）。
5. **通用协议适配器**：云端 `telemetry-adapter` 与边缘 `modbus-edge-adapter` 无需改动。

---

## 3. 端到端优化流程

```text
[前置：首次型号配置（配置平台）]
  配置产品模板（含各测点 dataType）与协议规则并发布

[第一阶段优化主流程：同型号设备接入]
  1. 设备上报 → 平台写入 biz_pending_device（状态 DISCOVERED）
  2. 用户进入唯一入口：配置平台 → 数据接入 → 待接入设备（/configuration/ingestion/pendingDevices）
  3. 点击绑定 → 弹窗自动带出唯一匹配的产品、建筑、系统分组及建议设备名
     - 新建设备 或 复用已有未接采集的台账档案
     - 模板可唯一自动建点时默认折叠逐测点映射；有歧义时展开手工处理
     - 默认勾选「审核通过后立即启用采集」（可取消勾选切为「仅绑定」）
  4. 提交一份敏感变更申请 → 独立审核人一次审批并执行
  5. 后端执行：
     - 在同一数据库事务内加锁复核归属、冲突与规则 → 建档/复用档案 + 自动建点与别名 + 创建身份（若 `activateOnApprove=true` 则同事务置为启用）+ 标记审批已执行并提交
     - 事务提交后刷新运行时身份缓存与测点别名缓存
     - 若缓存刷新失败：审批已落库生效，`connection.configEffective` 显示待生效（`false`），前端提供免审批「重试同步」按钮立即刷新复核，也支持平台既有定时刷新兜底
  6. 身份与测点别名缓存生效后，设备直接进入既有标准遥测入库主链路
```

---

## 4. 前后端共享契约（双方对齐基线）

本节是前端实施方与后端实施方的唯一接口与数据约定；任一方不得擅自增删字段或变更语义。

### 4.1 菜单与路由契约
- **唯一规范路径**：`/configuration/ingestion/pendingDevices`
- **Workspace 与分组**：`configuration` → `ingestion`（配置平台 → 数据接入 → 待接入设备）
- **叶子授权**：数据库复用原 `/operations/devices/pendingDevices` 叶子菜单 ID 与已有角色授权记录，仅迁移 `parent_id` 与 `path`；后端同步更新**待接入**与**大金目录同步**两处路径授权校验，切换时使旧菜单缓存失效。

### 4.2 产品测点模板 `dataType` 契约
- **涉及接口**：既有 `/v1/device-products` 列表详情、新增、编辑接口。
- **新增字段**：在 `ProductPointTemplate`（前端）与对应后端 DTO/实体中新增：
  - 字段名：`dataType`
  - 类型：`string`
  - 合法枚举：`ANALOG`（模拟量）、`DIGITAL`（数字/状态量）、`ACCUMULATE`（累计量）
  - 默认与兼容：未传或历史模板为空时默认为 `ANALOG`；大金温度模板继续按 `ANALOG` 校验；`DIGITAL` 在本期仅作为测点类型标识，标准遥测仍沿用既有数值报文契约。

### 4.3 标准绑定、合单审批与旧单兼容契约
- **复用既有审批操作码**：`BIND_PENDING_DEVICE`（标准设备）与 `BIND_TYPED_PENDING_DEVICE`（大金类型化设备）。
- **请求体扩展（`BindRequest` / `TypedBindRequest`）**：
  - 新增字段：`activateOnApprove?: boolean`
  - 语义与旧单摘要兼容：
    - 省略该字段或显式传入 `false` 均按「仅绑定」处理；
    - 后端生成规范化命令 JSON（`canonicalJson`）与计算摘要时，**仅当 `activateOnApprove == true` 时写入该字段**，省略或 `false` 不写入，确保上线时在途旧审批单的摘要校验继续有效；
    - 新前端页面默认勾选「审核通过后立即启用采集」，显式提交 `activateOnApprove: true`；用户取消勾选时提交 `false`（或不传）。
- **自动建点与复用已有档案规则（`BindRequest`）**：
  - 审批入参校验与绑定领域服务两处同步放开限制：允许 `existingEquipmentId != null` 且 `autoCreatePoints == true`（且 `pointBindings` 为空）。
  - 复用前提（后端权威校验）：
    1. 复用时在事务内锁定目标设备行（`FOR UPDATE`），防止两个待接入记录并发绑到同一档案；
    2. 目标设备存在、未删除，且归属的 `buildingId` / `spaceId` / `systemGroupId` 与请求一致；
    3. 目标设备 `typeCode` 与产品 `equipmentTypeCode` 一致，且 `productId` 为空或等于当前产品（为空时回填）；
    4. 目标设备当前未绑定冲突的采集身份；已启用关系治理的建筑仍遵守现有建点规则；
    5. 自动建点按确定性编码匹配目标设备已有测点，复用前核对归属、单位、类型和现有采集绑定；存在冲突或命名规则不唯一时明确拒绝并交由前端展开手工映射。
- **合单执行事务与缓存生效契约**：
  - 当 `activateOnApprove == true` 时，设备建档/复用、测点与别名生成、身份启用与审批完成状态在**同一个数据库事务**内提交；
  - 事务提交后再执行运行时缓存刷新（身份缓存 + 测点别名缓存）；
  - 若事务已提交但缓存刷新失败：审批单仍保持已执行，不回滚事务；`connection` 接口返回的 `configEffective` 为 `false`（后端需同时检查身份缓存与测点别名缓存，不再只查身份缓存）；
  - **免审批轻量刷新复核接口**（用于「重试同步」按钮）：
    - 管理员侧：`POST /v1/device-onboarding/pending/{pendingId}/refresh-effective`
    - 大金授权侧：`POST /v1/operations/device-onboarding/pending/{pendingId}/refresh-effective`
    - 行为：校验菜单/建筑权限与目标已绑定且处于 `ACTIVE` 状态后，幂等刷新并复核运行时身份及测点别名缓存，返回最新 `ConnectionView`（`configEffective: true|false`），不走审批流。

### 4.4 统一列表与详情接口约定
- **管理员视角（`PLATFORM_ADMIN`）**：统一列表直接调用 `/v1/device-onboarding/pending`，不再前端拼凑两套列表接口。后端在 `/v1/device-onboarding/pending` 增加可选参数 `sourceScope`（枚举 `ALL` | `DAIKIN` | `STANDARD`，缺省 `ALL`）或等价 `identityType` 过滤，**在服务端过滤后再分页**，避免大金行重复或分页总数错乱。
- **大金授权人员（非平台管理员）及大金专属操作**：继续调用 `/v1/operations/device-onboarding/*`（列表、目录同步、房间匹配、温度配置与批量补温），保持既有建筑范围隔离不变。

---

## 5. 数据库变更设计（后端实施方负责）

### 5.1 MySQL（新增受控 Flyway 迁移，不修改任何历史迁移文件）
远程主线迁移已推进至 V131，本次迁移版本号按合并时主线最新版本号顺延：

1. **`sys_menu` 路径与父级迁移（数据 UPDATE）**：
   - 定位原 `path = '/operations/devices/pendingDevices'` 的叶子菜单行；
   - 保留原 `menu_id` 及 `sys_role_menu` 关联不变；
   - 更新 `parent_id` 为 `配置平台 → 数据接入` 目录节点 ID，更新 `path = '/configuration/ingestion/pendingDevices'`。
2. **`biz_product_point_template` 增加测点类型（表结构 ALTER）**：
   - 新增列：`data_type VARCHAR(32) NOT NULL DEFAULT 'ANALOG'`；
   - 既有行自动获得默认值 `'ANALOG'`，无需历史回填脚本。
3. **不改结构的表**：
   - `biz_pending_device`、`biz_equipment`、`biz_device_identity`、`biz_data_point`、`biz_point_alias`、`biz_sensitive_change_request` 全部复用既有结构与索引。

### 5.2 TDengine
- **零变更**：第一阶段不新建、不修改任何 TDengine 超级表、子表或保留策略。

---

## 6. 后端实施任务清单（后端）

1. **Flyway 迁移**：通过 V132 落地 `sys_menu` 迁移与 `biz_product_point_template.data_type` 字段。
2. **菜单授权路径与缓存更新**：
   - 同步更新待接入（[ScopedDeviceOnboardingService.java](../../src/main/java/com/platform/iot/onboarding/ScopedDeviceOnboardingService.java)）与**大金目录同步**两处后端路径授权校验为 `/configuration/ingestion/pendingDevices`，切换时使旧菜单缓存失效。
3. **产品模板 `dataType` 支持**：
   - 实体 [BizProductPointTemplate.java](../../src/main/java/com/platform/iot/onboarding/model/entity/BizProductPointTemplate.java)、DTO、校验器与查询映射支持 `ANALOG` / `DIGITAL` / `ACCUMULATE`（缺省 `ANALOG`）；大金温度模板保持按 `ANALOG` 校验。
4. **绑定与复用领域能力增强（[DeviceOnboardingService.java](../../src/main/java/com/platform/iot/onboarding/DeviceOnboardingService.java)）**：
   - 审批入参校验与 `doBind` 同步放开 `autoCreatePoints` 仅限 `newEquipment` 的限制；复用已有档案时锁住目标设备行（`FOR UPDATE`），校验归属、类型、产品、已有采集身份与关系治理建点规则；测点按确定编码匹配并校验归属、单位、类型和现有采集绑定；
   - 自动建点从产品测点模板读取 `dataType`。自动建点预校验发现测点或规则冲突时，返回 `ONBOARDING_AUTO_POINT_MAPPING_REQUIRED`，表单展开手工映射。
5. **合单同事务提交与缓存刷新编排**：
   - 在 `BindPendingDeviceHandler`、`BindTypedPendingDeviceHandler` 及命令规范化逻辑中支持 `activateOnApprove`，仅当 `true` 时写入 `canonicalJson` 以兼容在途旧审批单摘要；
   - `activateOnApprove=true` 时，在审批执行同一数据库事务内完成绑定与身份启用并提交，事务提交后刷新身份与测点别名缓存；
   - 完善 `connection.configEffective` 同时检查身份缓存与测点别名缓存，并提供免审批 `refresh-effective` 接口。
6. **统一列表服务端过滤**：
   - `/v1/device-onboarding/pending` 增加 `sourceScope`（`ALL` / `DAIKIN` / `STANDARD`）服务端过滤后再分页，保证管理员统一视图无重复行、分页总数准确。

---

## 7. 前端实施任务清单（前端）

1. **导航注册与路由迁移**：
   - 更新 [catalog.ts](../../web/src/app/navigation/catalog.ts) 将 `pendingDevices` 从 `operations/devices` 移至 `configuration/ingestion`；
   - 更新路由配置，并将旧路径 `/operations/devices/pendingDevices` 一次性重定向至 `/configuration/ingestion/pendingDevices`（透传 query 参数）；
   - 更新协议页「查看匹配待接入设备」跳转目标为 `/configuration/ingestion/pendingDevices`。
2. **待接入页面融合（[PendingDevicePage.vue](../../web/src/modules/device-onboarding/pages/PendingDevicePage.vue)）**：
   - 移除 `operationsMode` 双 Proxy 状态切换：管理员统一调用 `/v1/device-onboarding/pending` 并传 `sourceScope` 筛选分页，非管理员大金人员继续调用 `/v1/operations/device-onboarding/pending`；
   - 大金专属能力（目录同步、房间匹配、温度配置与批量补温）按来源条件展示并合并重复同步入口；
   - 将报文样例、完整身份值与技术编号收纳进详情抽屉，主表聚焦业务识别信息与状态。
3. **极简绑定弹窗（[BindingDraftDialog.vue](../../web/src/modules/device-onboarding/components/BindingDraftDialog.vue)）**：
   - 智能预填：唯一匹配的产品、唯一可选建筑、唯一系统分组自动带出；新建模式自动生成建议设备名；仅多候选时要求人工选择；
   - 复用已有档案模式：允许选择已有未接采集的设备档案并走自动建点；
   - 测点映射折叠：自动建点无歧义时默认折叠逐测点明细；仅在手工映射或后端返回规则/测点冲突时展开；
   - 合单开关：默认勾选「审核通过后立即启用采集」（显式提交 `activateOnApprove: true`），次要位置提供取消勾选切回「仅绑定」（提交 `false`）。
4. **产品模板页 `dataType` 维护**：
   - 在产品测点模板编辑表格/弹窗增加 `dataType` 选择（`ANALOG` / `DIGITAL` / `ACCUMULATE`，默认 `ANALOG`），同步更新 [onboarding.ts](../../web/src/modules/device-onboarding/models/onboarding.ts) 类型定义。
5. **审批与缓存待生效交互**：
   - 审批单/提交反馈清晰体现「绑定并启用」或「仅绑定」；
   - 当记录处于 `BOUND` + `ACTIVE` 且 `configEffective == false` 时，明确展示「已启用，待缓存生效」并提供一键「重试同步」按钮，调用免审批 `refresh-effective` 接口复核生效，不引导用户重走绑定或启用审批。

---

## 8. 验收标准

> 说明：当前尚未获取首批真实标准设备型号。第一阶段交付先完成**软件级自动化与隔离测试验收**；**真实设备端到端验收**待后期硬件型号到位后独立补验，不得以测试夹具替代真实设备验收。

### 8.1 功能验收（基于自动化测试与隔离测试夹具）
1. **统一入口与跳转**：
   - 待接入设备仅出现在 `配置平台 → 数据接入 → 待接入设备`（`/configuration/ingestion/pendingDevices`）；
   - 协议页「查看匹配待接入设备」正确跳转至新路径并携带协议筛选；
   - 访问旧路径 `/operations/devices/pendingDevices` 自动跳转至新路径；
   - 管理员统一列表按 `ALL` / `DAIKIN` / `STANDARD` 筛选分页无重复行、总数准确。
2. **同型号极简接入**：
   - 用同一标准协议模板构造两台同型号待接入设备：第二台打开绑定弹窗时，唯一产品、建筑、分组与建议设备名自动带出，用户只需确认安装位置即可提交；
   - 构造多产品/多建筑/多分组候选场景时，系统不误猜并要求用户显式选择。
3. **复用已有台账档案 + 自动建点**：
   - 已有台账档案且未接采集的设备，选择「复用已有档案 + 自动建点」提交并审批通过后，不新建重复设备，自动生成或安全复用测点与别名并关联身份；
   - 并发将两条待接入记录绑定到同一已有档案时，仅一条成功、另一条被行锁校验拒绝；目标设备已绑定冲突身份或归属不一致时后端明确拒绝。
4. **测点类型 `dataType` 透传**：
   - 产品模板配置 `DIGITAL` / `ACCUMULATE` 后，自动建点落库的 `biz_data_point.data_type` 与模板一致；旧模板默认仍为 `ANALOG`；大金温度模板继续按 `ANALOG` 校验。
5. **合单审批、旧单兼容与缓存刷新恢复**：
   - 默认勾选「绑定并启用」（`activateOnApprove: true`）提交 1 份申请 → 独立审核人通过执行后，设备状态为 `BOUND` 且身份直接为 `ACTIVE`、`configEffective`（含身份与测点别名缓存）为 `true`；
   - 取消勾选「仅绑定」提交 → 审核通过后身份保持 `INACTIVE`，可通过后续启用流程激活；
   - 上线前已存在、未携带 `activateOnApprove` 字段的在途旧审批单，仍可通过摘要校验并正常审核执行；
   - 模拟事务已提交但缓存刷新失败 → 审批状态为已执行且不回滚，`connection.configEffective` 为 `false`，点击免审批「重试同步」（`refresh-effective`）后恢复为 `true`。

### 8.2 模块保护与非回归验收
1. **大金权限与功能回归**：非平台管理员登录新路径 `/configuration/ingestion/pendingDevices`，仅能看到授权建筑内的大金待接入设备，目录同步、房间匹配、温度配置与批量绑定均正常，无法越权查看或操作标准设备。
2. **标准遥测与房间传感器无回归**：既有标准遥测入库测试与 `roomsensor` 自动化测试全部通过。
3. **职责分离无回归**：生产模式下仍禁止提交人自审审批单，审计日志完整记录绑定与启用动作。

### 8.3 前后端各自交付验证门槛
- **后端实施方**：完成受控 Flyway 迁移校验、相关单元/切片测试（含旧单摘要兼容、并发复用同一档案、缓存刷新失败后恢复与大金路径迁移）及 Maven 测试通过，确保所有接口与本文档第 4 节契约一致。
- **前端实施方**：完成 TypeScript 类型检查、相关单元测试及前端构建验证通过，确保不遗留旧路径断链或双视图状态残留。
- **联调验收**：前后端合并联调时，逐条跑通 8.1 与 8.2 全部用例；待后期首批真实标准型号到位后，补充真实设备接入与遥测入库验收记录。
