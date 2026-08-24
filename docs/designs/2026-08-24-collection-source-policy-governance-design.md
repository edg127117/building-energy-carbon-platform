# 数据源与采集策略治理闭环设计

> **文档状态：活动候选设计，需求与数据模型已确认，尚未实现、尚未验证、尚未批准为当前正式基线。**
>
> 当前权威基线仍以 [`PROJECT_STATUS.md`](../../PROJECT_STATUS.md)、当前代码和自动化测试为准。
> 本候选只有在完成实现、范围匹配的自动化验证、独立验收并经用户批准后，才能转为正式版本能力。

## 1. 结论

阶段一首个业务闭环锁定为“数据源与测点采集策略的版本化治理闭环”。它回答以下问题：

- 平台通过哪个可信北向来源接收某个标准测点；
- 该测点业务上应多久上报、允许迟到多久；
- 原始事件和分钟数据需要保留多久；
- 当前是否把该测点纳入周期采集计划；
- 配置由谁创建、提交、审核、发布、停用或回滚；
- MySQL 中已经发布的配置是否被当前单体运行实例加载。

所有设备数据继续遵守：

```text
设备协议
→ 边缘网关/协议适配器
→ 统一 MQTT/HTTP
→ 本平台
```

本闭环不建设南向驱动，不保存 Broker 凭据，不向设备下发采样周期，不直接控制 TDengine 清理，
也不实现按测点、指标、公式或场景配置 Q0/Q1/Q2 使用范围。现有 HVAC 质量链只是继承基础，
通用 Q0/Q1/Q2 质量策略治理属于后续独立闭环。

## 2. 背景与当前证据

当前仓库已经具备：

- MySQL 建筑、设备、标准测点和来源别名档案；
- 服务端可信 `source_system` 与外部点码到标准测点的映射；
- MQTT 标准接入、原始时序、分钟聚合和 HVAC 场景下的 Q0/Q1/Q2 基础；
- 登录、正式角色和建筑范围权限；
- 典型值配置的版本、审核和停用实现，可作为状态与审计参考；
- 单体内周期性测点配置刷新基础。

主要当前证据入口：

- [`BizDataPoint.java`](../../src/main/java/com/platform/hvac/model/entity/BizDataPoint.java)：标准测点、数据类型、单位和建筑归属；
- [`BizPointAlias.java`](../../src/main/java/com/platform/hvac/model/entity/BizPointAlias.java)：来源系统、来源点码到标准测点的映射；
- [`HvacIngestionService.java`](../../src/main/java/com/platform/iot/ingest/HvacIngestionService.java)：服务端选择可信来源并写入时序；
- [`TypicalValueConfigController.java`](../../src/main/java/com/platform/iot/dataquality/controller/TypicalValueConfigController.java)：现有版本化配置和角色入口；
- [`PROJECT_STATUS.md`](../../PROJECT_STATUS.md)：继承能力、阶段一状态和未完成边界。

当前缺口是：

- 没有正式的数据源业务档案；
- 来源别名只有启停值，没有草稿生命周期，也没有稳定数据源外键；
- 采样周期、允许迟到、保留要求和采集计划启停没有按来源测点版本化；
- 没有数据源整体首启、别名初启和策略变更的审核流；
- 数据库发布状态与运行时快照是否应用没有分开表达；
- 现有 19 点仍依赖继承配置，尚未纳入本闭环的版本和审计模型。

## 3. 目标与非目标

### 3.1 目标

1. 为统一 MQTT/HTTP 北向来源建立建筑归属明确、编码稳定的数据源档案；
2. 让来源别名支持草稿、启用和停用，并确保草稿永不进入正式运行链；
3. 以“数据源 + 来源别名 + 标准测点”为稳定采集策略身份；
4. 按版本配置采样周期、允许迟到、保留要求和采集计划启停；
5. 支持能源管理员提交、平台管理员审核发布，以及平台管理员直接发布；
6. 支持新数据源整体首启、已启用来源新增测点和现有策略版本升级；
7. 支持停用、重新启用、草稿删除和复制历史版本回滚；
8. 通过建筑范围、状态机、组合约束、事务和显式修订号防止越权、误删和并发覆盖；
9. 区分 MySQL 配置已发布与当前单体实例已经加载；
10. 以可追溯迁移将现有 19 点纳入初始有效版本，不中断继承采集链。

### 3.2 非目标

- 不开发 Vue 管理页面；
- 不接入 Modbus、BACnet、NB-IoT 等南向通信驱动；
- 不保存 MQTT Broker 地址、Topic、用户名、密码、Token 或证书；
- 不向网关或设备下发采样周期、参数或控制命令；
- 不让采集策略直接执行 TDengine 清理；
- 不实现 Q0/Q1/Q2 使用范围、公式输入门禁、插值方法或典型值选择策略；
- 不新增能源、碳排、能效评价或诊断计算；
- 不建立多实例配置广播或节点状态持久化；
- 不把自动化测试描述为真实设备、云环境或长期现场验收；
- 不修改历史设计和计划来制造当前完成状态。

## 4. 既有硬约束处理

| 既有约束 | 本候选处理 | 说明 |
|---|---|---|
| Spring Boot 单体与 Vue 前端 | 保留 | 首闭环只新增单体后端治理能力，不拆服务 |
| MySQL 管业务配置、TDengine 管时序、Redis 只作缓存 | 保留 | 新表全部位于 MySQL，运行快照保存在进程内存 |
| 南向协议由边缘网关/适配器处理 | 保留 | 数据源只登记统一 MQTT/HTTP 北向来源 |
| 服务端决定可信来源命名空间 | 保留并迁移 | 从单一配置字符串逐步迁移为数据源与别名的稳定关联 |
| 现有别名 `1=启用、0=停用` | 兼容扩展 | 新增 `2=草稿`，运行时仍只读取 `1=启用` |
| 现有 19 点已经进入继承运行链 | 保留并纳管 | 不倒退为草稿，以系统迁移建立初始有效策略 |
| 当前无设备控制能力 | 保留 | 启停只控制平台配置是否进入运行快照 |
| Q0/Q1/Q2 需要按场景配置 | 未解决 | 明确放入后续质量策略闭环，不塞入采集策略表 |
| 后端建筑范围是安全边界 | 保留并增强 | 数据源、别名和策略同时验证建筑归属 |
| 已发布事实必须可追溯 | 保留并增强 | 已发布版本不可改删，回滚通过新版本表达 |

## 5. 核心术语与边界

### 5.1 数据源

数据源是平台信任的统一北向来源，不等同于南向设备协议，也不等同于 Spring 的数据库
`DataSource`。它表示一个由服务端选择、归属一个建筑的 MQTT 或 HTTP 接入命名空间。

首版实行“一源一建筑”。归属判断必须集中在领域服务中，内部以建筑范围集合表达，避免权限、
查询和前端各自散落 `buildingId` 判断。未来确需跨建筑时，只替换归属存储和解析实现。

### 5.2 来源别名

来源别名表示：

```text
数据源下的来源点码 → 平台标准测点
```

来源点码保留厂家或网关原始大小写，不自动转换。数据源编码由平台定义，必须使用稳定大写编码。

### 5.3 采集策略

采集策略以一个来源别名为稳定身份。它描述业务期望，不描述南向通信驱动：

- 期望采样周期；
- 允许迟到时间；
- 原始事件和分钟数据保留要求；
- 是否期待该测点周期上报；
- 配置版本、来源、生效时间和变更原因。

标准数据类型和单位继续以标准测点为权威来源。策略发布时保存只读快照，但不提供可编辑的
`dataType` 或 `unit`，防止与测点档案形成双重权威。

### 5.4 配置启停与数据接收

- 别名启用：该来源映射合法，允许数据进入正式接入链；
- 策略 `enabled=true`：业务期待该测点按周期持续上报；
- 策略 `enabled=false`：暂不期待周期上报，但实际到达的数据仍可保存；
- 数据源停用：运行快照排除该来源全部别名，不删除任何历史事实。

策略停用不是设备停机，也不是接入拒绝开关。

## 6. 总体架构与数据流

```text
管理 API
  → 数据源草稿
  → 草稿别名 + 初始策略草稿
  → 能源管理员提交 / 平台管理员直接发布
  → 平台管理员审核
  → MySQL 原子发布
       ├─ 数据源、别名和策略状态
       ├─ 当前/草稿版本指针
       ├─ config_revision / runtime_revision
       ├─ 审核申请
       └─ 脱敏审计
  → 事务提交后发布本地刷新事件
  → 当前单体实例重新加载有效来源快照
  → MQTT/HTTP 接入只读取 ENABLED 数据源 + ENABLED 别名
  → 现有原始时序和后续处理链
```

运行时有效条件固定为：

```text
data_source.status = ENABLED
AND point_alias.status = ENABLED
AND data_point.del_flag = 0
```

活动策略一并加载，供后续周期监测使用；`policy.enabled=false` 不作为原始数据拒收条件。

## 7. 领域模型

本候选新增 5 张 MySQL 表，并扩展既有 `biz_point_alias`。

### 7.1 `biz_data_source`

| 字段 | 类型 | 约束与语义 |
|---|---|---|
| `source_id` | `VARCHAR(32)` | 主键，平台内部稳定 ID |
| `source_code` | `VARCHAR(50)` | 全局唯一，大写稳定技术编码，首次启用后不可修改 |
| `source_name` | `VARCHAR(100)` | 展示名称，可审计修改 |
| `building_id` | `VARCHAR(32)` | 首版唯一归属建筑，非空 |
| `source_category` | `VARCHAR(32)` | 首版只接受 `DEVICE_ACCESS` |
| `transport_type` | `VARCHAR(20)` | `MQTT` 或 `HTTP` |
| `status` | `VARCHAR(20)` | `DRAFT`、`ENABLED`、`DISABLED` |
| `description` | `VARCHAR(500)` | 可空展示说明 |
| `config_revision` | `INT` | 聚合配置修订号，提交审核和并发校验使用 |
| `runtime_revision` | `BIGINT` | 正式运行配置修订号，运行快照比较使用 |
| `create_by`、`update_by` | `BIGINT` | 用户操作人；系统迁移另行标识 |
| `create_time`、`update_time` | `DATETIME(3)` | MySQL 时间事实 |

关键约束：

- `UNIQUE(source_code)`；
- `UNIQUE(source_id, building_id)`，供组合外键使用；
- `source_code` 只允许大写字母、数字、下划线和短横线，服务端不自动转大写；
- 不能从 `ENABLED` 或 `DISABLED` 回到 `DRAFT`；
- 首次启用后冻结 `source_code`、`building_id`、`source_category` 和 `transport_type`；
- 已启用后只有平台管理员可以修改 `source_name` 和 `description`。

### 7.2 `biz_point_alias` 扩展

在既有表上增加：

| 字段或索引 | 语义 |
|---|---|
| `source_id VARCHAR(32)` | 正式关联数据源 |
| `revision INT` | 别名草稿并发控制 |
| `create_by BIGINT` | 草稿归属与审计 |
| `update_by BIGINT` | 最近修改人 |
| `create_time/update_time DATETIME(3)` | 配置时间事实 |
| `UNIQUE(alias_id, building_id)` | 支持策略表组合外键 |

`status` 兼容编码锁定为：

```text
2 = DRAFT
1 = ENABLED
0 = DISABLED
```

API 对外使用 `DRAFT/ENABLED/DISABLED`，不暴露数字。运行时必须同时检查数据源和别名状态，
不能只依赖别名 `status=1`。

关键唯一性继续保留：

```text
UNIQUE(building_id, source_system, source_point_code)
```

新增组合外键必须保证数据源、别名和标准测点属于同一建筑。禁止 `ON DELETE CASCADE`。

### 7.3 `biz_collection_policy`

| 字段 | 类型 | 约束与语义 |
|---|---|---|
| `policy_id` | `VARCHAR(32)` | 主键 |
| `source_id` | `VARCHAR(32)` | 数据源外键 |
| `alias_id` | `VARCHAR(32)` | 来源别名外键，一条别名最多一个策略 |
| `building_id` | `VARCHAR(32)` | 组合外键和权限过滤 |
| `active_version_id` | `VARCHAR(32)` | 当前有效版本，可空 |
| `draft_version_id` | `VARCHAR(32)` | 当前草稿版本，可空 |
| `create_by` | `BIGINT` | 策略创建人 |
| `create_time/update_time` | `DATETIME(3)` | 时间事实 |

关键约束：

- `UNIQUE(alias_id)`；
- `(source_id, building_id)` 指向数据源；
- `(alias_id, building_id)` 指向来源别名；
- 当前有效和当前草稿分别只有一个指针；
- 不设置级联删除。

### 7.4 `biz_collection_policy_version`

| 字段 | 类型 | 约束与语义 |
|---|---|---|
| `version_id` | `VARCHAR(32)` | 主键 |
| `policy_id` | `VARCHAR(32)` | 策略主记录外键 |
| `version_no` | `INT` | 单调递增版本号 |
| `status` | `VARCHAR(20)` | `DRAFT`、`ACTIVE`、`RETIRED` |
| `enabled_flag` | `TINYINT(1)` | 是否期待周期上报 |
| `expected_interval_seconds` | `INT` | 期望采样周期，必须大于 0 |
| `allowed_delay_seconds` | `INT` | 允许迟到，必须大于等于 0 |
| `time_semantics` | `VARCHAR(32)` | 首批锁定 `DEVICE_EVENT_TIME` |
| `raw_retention_mode` | `VARCHAR(20)` | `FIXED_DAYS` 或 `LONG_TERM` |
| `raw_retention_days` | `INT` | 固定天数时必填且大于 0 |
| `minute_retention_mode` | `VARCHAR(20)` | `FIXED_DAYS` 或 `LONG_TERM` |
| `minute_retention_days` | `INT` | 固定天数时必填，长期时为空 |
| `source_code_snapshot` | `VARCHAR(50)` | 发布时只读来源快照 |
| `source_point_code_snapshot` | `VARCHAR(255)` | 发布时只读来源点码快照 |
| `point_id_snapshot` | `VARCHAR(32)` | 发布时只读标准测点身份 |
| `point_code_snapshot` | `VARCHAR(100)` | 发布时只读标准点码 |
| `data_type_snapshot` | `VARCHAR(20)` | 发布时从测点档案复制，不可编辑 |
| `unit_snapshot` | `VARCHAR(20)` | 发布时从测点档案复制，不可编辑 |
| `change_type` | `VARCHAR(20)` | `CREATE`、`UPDATE`、`DISABLE`、`ROLLBACK`、`INITIAL_MIGRATION` |
| `change_source` | `VARCHAR(32)` | `MANUAL` 或 `INITIAL_MIGRATION` |
| `change_reason` | `VARCHAR(500)` | 必填业务原因 |
| `copied_from_version_id` | `VARCHAR(32)` | 复制或回滚来源版本，可空 |
| `revision` | `INT` | 草稿并发控制 |
| `created_by`、`published_by` | `BIGINT` | 系统迁移发布人允许为空 |
| `published_at/effective_from/effective_to/retired_at` | `DATETIME(3)` | 生命周期时间 |
| `create_time/update_time` | `DATETIME(3)` | 记录时间 |

关键约束：

- `UNIQUE(policy_id, version_no)`；
- 已发布配置内容不可修改；
- 发布新版本时只允许系统在同一事务内更新旧版本的状态和结束时间；
- 回滚必须复制历史版本生成更高版本号，不能重新激活旧版本；
- `FIXED_DAYS` 与天数、`LONG_TERM` 与空天数必须满足条件约束；
- 首批 19 点的 `60/30/90/LONG_TERM` 是初始配置数据，不是表默认值。

### 7.5 `biz_collection_review_request`

| 字段 | 类型 | 约束与语义 |
|---|---|---|
| `request_id` | `VARCHAR(32)` | 主键 |
| `building_id` | `VARCHAR(32)` | 权限范围 |
| `target_type` | `VARCHAR(32)` | `SOURCE_ACTIVATION`、`ALIAS_ACTIVATION`、`POLICY_VERSION` |
| `target_id` | `VARCHAR(32)` | 目标数据源、别名或版本 |
| `target_config_revision` | `INT` | 提交时冻结的目标修订号 |
| `status` | `VARCHAR(20)` | `PENDING`、`APPROVED`、`REJECTED`、`WITHDRAWN` |
| `submitted_by/submitted_at` | 用户与时间 | 提交事实 |
| `reviewer_id` | `BIGINT` | 平台管理员，可空 |
| `review_comment` | `VARCHAR(500)` | 拒绝必填，批准也保存说明 |
| `reviewed_at/withdrawn_at` | `DATETIME(3)` | 状态时间 |
| `create_time/update_time` | `DATETIME(3)` | 记录时间 |

审核申请与业务生命周期分开。`PENDING/REJECTED` 不进入数据源、别名或策略状态。
同一目标同一时间只能有一个 `PENDING` 申请。

### 7.6 `biz_collection_config_audit_log`

| 字段 | 类型 | 约束与语义 |
|---|---|---|
| `audit_id` | `VARCHAR(32)` | 主键 |
| `building_id` | `VARCHAR(32)` | 查询范围 |
| `actor_type` | `VARCHAR(20)` | `USER` 或 `SYSTEM_MIGRATION` |
| `operator_id` | `BIGINT` | 用户操作必填，系统迁移为空 |
| `action_type` | `VARCHAR(50)` | 创建、更新、提交、审核、发布、启停、删除、回滚等 |
| `object_type/object_id/version_id` | 对象身份 | 版本可空 |
| `before_summary/after_summary` | `VARCHAR(1000)` | 脱敏前后摘要 |
| `result` | `VARCHAR(20)` | 首版只保存成功业务变更 |
| `operation_time` | `DATETIME(3)` | 操作时间 |

审计表不对被审计对象建立外键，保证草稿物理删除后审计仍存在。普通字段校验失败不写业务审计；
权限攻击和系统异常进入安全日志或应用日志。

### 7.7 运行时应用状态

首版不新建运行状态表。当前单体实例在内存保存：

```text
sourceId
appliedRuntimeRevision
applyStatus
appliedAt
lastErrorCode
lastAttemptAt
```

`applyStatus`：

- `NOT_APPLICABLE`：草稿或停用，无需进入运行快照；
- `PENDING_REFRESH`：MySQL 已提交，当前实例尚未确认加载；
- `APPLIED`：已加载对应 `runtime_revision`；
- `REFRESH_FAILED`：最近加载失败，等待主动或周期重试。

进程重启后从 `PENDING_REFRESH` 开始，成功加载后才能标记 `APPLIED`。如果未来启用多实例，
再独立设计按实例记录和聚合状态，本候选不提前实现。

## 8. 状态机

### 8.1 数据源

```text
DRAFT → ENABLED ⇄ DISABLED
```

禁止 `ENABLED/DISABLED → DRAFT`。启用过的数据源不能物理删除。

### 8.2 来源别名

```text
DRAFT → ENABLED ⇄ DISABLED
```

草稿数据源只能关联草稿别名。已启用或停用的数据源允许增加草稿别名，便于后续扩点；
草稿别名不进入运行快照。

### 8.3 策略版本

```text
DRAFT → ACTIVE → RETIRED
```

一个策略可以同时存在一个 `ACTIVE` 和一个用于下一次变更的 `DRAFT`。发布新版本时旧版本原子退役。

### 8.4 审核申请

```text
PENDING → APPROVED
        → REJECTED
        → WITHDRAWN
```

审核待处理期间目标配置冻结。拒绝或撤回只解除冻结，目标仍保持草稿，不增加 `REJECTED` 业务状态。

## 9. 角色与数据可见性

### 9.1 `BUILDING_OWNER`

在授权建筑内只能查看：

- 已启用数据源和别名；
- 当前有效策略；
- 当前实例运行应用状态。

不能查看草稿、历史版本、审核申请、操作者信息或审计。

### 9.2 `ENERGY_MANAGER`

在授权建筑内可以：

- 查看所有当前有效的正式配置；
- 基于当前有效版本创建自己的草稿；
- 查看、修改、删除自己创建且未提交的草稿；
- 查看自己创建的历史版本；
- 提交、撤回自己的审核申请；
- 查看自己的审核结果、拒绝意见和操作审计。

不能查看或操作他人的草稿、历史版本、审核申请和审计，不能直接发布、批准、拒绝、启用、
停用或物理删除正式配置。

同一策略已经存在他人草稿时，只返回：

```text
409 COLLECTION_CONFIG_DRAFT_CONFLICT
该策略已有待处理草稿，请联系平台管理员
```

不得泄露草稿创建人、内容或审核状态。

### 9.3 `PLATFORM_ADMIN`

可以查看和管理全部建筑的配置、版本、审核和审计；可以批准或拒绝能源管理员提交的配置，
也可以直接发布自己创建的草稿。直接发布审计动作为 `DIRECT_ADMIN_PUBLISH`，直接回滚发布为
`DIRECT_ADMIN_ROLLBACK`。首版不要求另一名平台管理员复核。

### 9.4 `THIRD_PARTY`

首闭环不开放数据源和采集策略治理接口。

## 10. 核心工作流

### 10.1 新数据源整体首启

能源管理员流程：

1. 创建归属自己且状态为 `DRAFT` 的数据源；
2. 逐条创建“草稿别名 + 初始策略草稿”；
3. 提交整个数据源配置包；
4. 提交时记录数据源 `config_revision` 并冻结全部草稿；
5. 等待平台管理员审核。

平台管理员批准时，一个 MySQL 事务完成：

1. 锁定审核申请和数据源；
2. 比较提交修订号与当前 `config_revision`；
3. 重新校验全部草稿别名、策略、测点和建筑归属；
4. 发布全部初始策略；
5. 将全部草稿别名改为 `ENABLED`；
6. 将数据源改为 `ENABLED`；
7. 更新当前版本指针、`config_revision` 和 `runtime_revision`；
8. 完成审核并写入脱敏审计；
9. 提交后发布本地刷新事件。

任一项失败全部回滚，不允许部分启用。

平台管理员自己创建的完整数据源配置包可以直接执行同一发布事务，无需创建审核申请。

### 10.2 已启用来源新增测点

1. 在已启用或停用的数据源下创建草稿别名和初始策略草稿；
2. 能源管理员提交 `ALIAS_ACTIVATION` 审核；
3. 平台管理员批准时原子发布初始策略并启用该别名；
4. 增加数据源 `config_revision/runtime_revision`；
5. 数据源为 `ENABLED` 时触发运行时刷新；为 `DISABLED` 时保留正式配置但运行时仍排除。

新增第 20 个或更多测点不需要修改表结构、枚举或固定点码代码。

### 10.3 修改采集策略

1. 从当前有效版本复制新草稿；
2. 修改采样周期、允许迟到、保留要求、启停和变更原因；
3. 能源管理员提交 `POLICY_VERSION` 审核；
4. 平台管理员批准时旧版本退役、新版本生效；
5. 增加数据源 `config_revision/runtime_revision`；
6. 提交后触发运行时刷新。

### 10.4 审核拒绝与撤回

- `PENDING` 时目标草稿不可修改、删除或重复提交；
- 平台管理员拒绝必须填写原因；
- 拒绝后申请变为 `REJECTED`，草稿解除冻结并可继续修改；
- 提交人可在审核前撤回，申请变为 `WITHDRAWN`；
- 修改后重新提交会产生新的审核申请。

### 10.5 数据源停用与重新启用

停用只允许平台管理员执行且必须提供原因：

- 数据源改为 `DISABLED`；
- 别名状态不批量改变；
- 策略版本不退役；
- `runtime_revision` 递增并触发刷新；
- 刷新后运行时排除该来源全部别名。

重新启用前重新校验数据源、别名、测点和当前有效策略。成功后恢复原先单独启用的别名，
不重新发布策略。重新启用仍只允许平台管理员。

### 10.6 草稿删除

平台管理员可以在一个 MySQL 事务中显式删除：

1. 从未发布的策略草稿版本；
2. 从未发布的策略主记录；
3. 草稿来源别名；
4. 从未启用的草稿数据源；
5. 保留在独立审计表中的删除事实。

必须满足：

- 数据源和全部关联别名仍为 `DRAFT`；
- 所有策略都从未产生 `ACTIVE/RETIRED`；
- 没有正式时序或其他业务引用；
- 没有待处理审核申请。

不使用数据库级联删除。发现草稿数据源异常关联 `ENABLED` 别名时返回状态冲突，不自动降级或删除。

### 10.7 回滚

历史版本不可重新激活。回滚固定为：

```text
复制目标历史版本
→ 创建更高版本号草稿
→ 审核或平台管理员直接发布
→ 当前有效版本退役
→ 新版本生效
```

新版本记录 `copied_from_version_id`、`change_type=ROLLBACK` 和必填原因。能源管理员只能从自己可见的
历史版本创建回滚草稿；其他历史版本由平台管理员处理。

## 11. 修订号、事务与并发

### 11.1 `config_revision`

以下操作递增：

- 修改数据源草稿或元数据；
- 新增、修改、删除草稿别名；
- 修改初始策略草稿；
- 创建策略新版本；
- 启用、停用、发布等状态变化。

审核申请保存提交时修订号。批准前不一致时返回 `409 COLLECTION_CONFIG_VERSION_CONFLICT`，
要求重新提交，不能静默采用新内容。

### 11.2 `runtime_revision`

只有正式运行配置变化时递增：

- 数据源首次启用、停用或重新启用；
- 别名正式启用或停用；
- 策略新版本正式发布或正式停用。

草稿编辑、提交、撤回、拒绝和展示名称修改不递增，也不触发运行刷新。

### 11.3 显式并发更新

草稿更新采用：

```sql
UPDATE ...
SET ..., revision = revision + 1
WHERE id = ?
  AND revision = ?
  AND status = 'DRAFT'
```

更新行数为 0 返回版本冲突。发布、新建版本、启停和删除还必须使用 `SELECT ... FOR UPDATE`
锁定策略主记录或数据源，防止两个管理员并发改变当前指针。

### 11.4 事务边界

创建数据源、创建别名与初始策略、提交审核、批准发布、停用、重新启用、草稿删除和回滚发布分别
使用清晰的 MySQL 事务。业务状态、版本指针、修订号、审核和审计必须同时成功或同时回滚。

事务内禁止：

- 调用 MQTT 或 HTTP 外部资源；
- 发送设备命令；
- 读写 TDengine；
- 执行清理任务；
- 启动 Q1/Q2；
- 把 Redis 刷新作为事务成功条件。

## 12. 运行时应用与恢复

### 12.1 发布后刷新

正确顺序是：

1. 提交 MySQL 业务事务；
2. 发布本地配置变化事件；
3. 当前实例重新加载完整有效快照；
4. 成功后记录 `APPLIED` 和 `appliedRuntimeRevision`；
5. 失败标记 `REFRESH_FAILED`，保留数据库正式配置；
6. 周期刷新继续重试。

不能在数据库提交前更新内存快照，避免提交失败后运行时使用不存在的配置。

### 12.2 收敛时效

- 正常提交后立即尝试刷新当前实例；
- 周期刷新作为失败兜底；
- 当前最迟收敛时间为 60 秒；
- 该 60 秒不是测点采样周期，也不是 30 秒允许迟到；
- 数据源停用不是安全急停，不承诺零延迟阻断。

如果未来需要安全级同步阻断，必须另行设计硬门禁。

### 12.3 手动刷新

平台管理员可以调用手动刷新接口。该操作只重新读取当前 MySQL 正式配置，不创建业务版本，
不改变配置修订号，并保存运行操作结果。服务重启不能被描述为配置回滚。

## 13. API 契约

新接口统一使用 `/v1`、`Result<T>`、稳定 `PageResponse<T>`、独立 DTO、OpenAPI 和机器错误码，
不直接返回 MyBatis 分页对象或持久化 Entity。所有时间对外使用 Unix 毫秒。

### 13.1 数据源

```text
GET    /v1/data-sources
GET    /v1/data-sources/{sourceId}
POST   /v1/data-sources
PUT    /v1/data-sources/{sourceId}
POST   /v1/data-sources/{sourceId}/submit
POST   /v1/data-sources/{sourceId}/enable
POST   /v1/data-sources/{sourceId}/disable
POST   /v1/data-sources/{sourceId}/runtime-refresh
DELETE /v1/data-sources/{sourceId}
```

创建请求示例：

```json
{
  "sourceCode": "MQTT_BLD001_GW01",
  "sourceName": "1号楼能源网关",
  "buildingId": "BLD001",
  "transportType": "MQTT",
  "description": "中央空调及环境测点统一北向来源",
  "changeReason": "新增试点网关"
}
```

服务端固定 `sourceCategory=DEVICE_ACCESS`、`status=DRAFT` 和初始修订号。请求不能包含凭据、
运行状态、创建人或时间。

### 13.2 来源别名与初始策略

```text
GET    /v1/data-sources/{sourceId}/aliases
POST   /v1/data-sources/{sourceId}/aliases
POST   /v1/data-sources/{sourceId}/aliases/{aliasId}/submit
POST   /v1/data-sources/{sourceId}/aliases/{aliasId}/enable
POST   /v1/data-sources/{sourceId}/aliases/{aliasId}/disable
DELETE /v1/data-sources/{sourceId}/aliases/{aliasId}
```

普通接口不允许只创建空别名。创建别名时必须同时提交初始策略：

```json
{
  "sourcePointCode": "WCR1_TWin",
  "pointId": "POINT001",
  "initialPolicy": {
    "expectedIntervalSeconds": 60,
    "allowedDelaySeconds": 30,
    "rawRetentionMode": "FIXED_DAYS",
    "rawRetentionDays": 90,
    "minuteRetentionMode": "LONG_TERM",
    "minuteRetentionDays": null,
    "enabled": true
  },
  "changeReason": "配置冷冻水进水温度采集"
}
```

`buildingId`、`sourceSystem`、数据类型、单位、版本和状态全部由服务端确定。

### 13.3 采集策略与版本

```text
GET    /v1/collection-policies
GET    /v1/collection-policies/{policyId}
GET    /v1/collection-policies/{policyId}/versions
POST   /v1/collection-policies/{policyId}/versions
PUT    /v1/collection-policies/{policyId}/versions/{versionId}
POST   /v1/collection-policies/{policyId}/versions/{versionId}/submit
POST   /v1/collection-policies/{policyId}/versions/{versionId}/publish
POST   /v1/collection-policies/{policyId}/versions/{versionId}/copy
POST   /v1/collection-policies/{policyId}/disable
DELETE /v1/collection-policies/{policyId}/versions/{versionId}
```

### 13.4 审核申请

```text
GET  /v1/collection-review-requests
GET  /v1/collection-review-requests/{requestId}
POST /v1/collection-review-requests/{requestId}/approve
POST /v1/collection-review-requests/{requestId}/reject
POST /v1/collection-review-requests/{requestId}/withdraw
```

能源管理员只能看到自己的申请；平台管理员可查看全部；建筑业主和第三方无权访问。

### 13.5 响应状态

数据源详情返回统计而不内嵌全部别名：

```text
draftAliasCount
enabledAliasCount
disabledAliasCount
configurationComplete
configuredEffective
runtimeApplyStatus
configRevision
runtimeRevision
appliedRuntimeRevision
runtimeAppliedAt
ineffectiveReason
allowedActions
```

`ineffectiveReason` 包括：

- `SOURCE_DRAFT`；
- `SOURCE_DISABLED`；
- `ALIAS_DRAFT`；
- `ALIAS_DISABLED`；
- `POLICY_MISSING`；
- `POLICY_DISABLED`。

前端不得自行推断状态机或建筑权限。

## 14. 校验与错误码

### 14.1 发布前核心校验

1. 数据源、别名、标准测点存在；
2. 数据源、别名和测点建筑完全一致；
3. 当前用户拥有相应角色和建筑范围；
4. 数据源编码全局唯一；
5. 数据源编码符合大写稳定编码规则；
6. 来源点码保留原始大小写且在来源内唯一；
7. 一个来源别名只有一个采集策略；
8. 一个策略最多一个草稿和一个有效版本；
9. 期望采样周期大于 0；
10. 允许迟到大于等于 0；
11. 固定保留必须提供正数天数，长期保留的天数必须为空；
12. 变更原因、提交说明、批准或拒绝意见满足必填规则；
13. 草稿修订号和审核目标修订号未过期；
14. 已发布内容和技术身份字段未被修改；
15. 测点未逻辑删除；测点运行状态临时 `OFFLINE` 不阻止配置发布。

### 14.2 稳定机器错误码

- `COLLECTION_CONFIG_UNAUTHORIZED`；
- `COLLECTION_CONFIG_FORBIDDEN`；
- `COLLECTION_CONFIG_NOT_FOUND`；
- `COLLECTION_CONFIG_VALIDATION_FAILED`；
- `COLLECTION_CONFIG_DUPLICATE`；
- `COLLECTION_CONFIG_STATE_CONFLICT`；
- `COLLECTION_CONFIG_REFERENCE_CONFLICT`；
- `COLLECTION_CONFIG_BUILDING_MISMATCH`；
- `COLLECTION_CONFIG_VERSION_CONFLICT`；
- `COLLECTION_CONFIG_DRAFT_CONFLICT`；
- `COLLECTION_CONFIG_REVIEW_CONFLICT`。

HTTP 状态：

- `400`：字段或业务规则错误；
- `401`：未登录；
- `403`：角色或建筑范围不足；
- `404`：对象不存在；
- `409`：唯一键、引用、状态、草稿、审核或并发冲突。

## 15. 删除与保留

- 所有业务外键默认 `RESTRICT`，禁止 `ON DELETE CASCADE`；
- 草稿整套删除由 Service 显式校验和排序执行；
- `ACTIVE/RETIRED` 版本永不物理删除；
- 启用过的数据源永不物理删除；
- 已发布版本、审核和审计不因停用而删除；
- 数据源停用、策略停用和别名停用都不删除 TDengine 历史数据；
- 首批原始事件保留要求为 90 天、分钟数据为 `LONG_TERM`，只是业务要求配置；
- 本闭环不让这些要求直接控制现有 TDengine 清理任务。

## 16. 现有 19 点迁移与兼容

现有 19 个 `MQTT_FREEZE_V1` 别名已经被继承运行链使用，不能倒退为草稿。迁移以
`SYSTEM_MIGRATION/INITIAL_MIGRATION` 建立当前事实：

1. 创建全局唯一编码为 `MQTT_FREEZE_V1` 的数据源，状态为 `ENABLED`；
2. 将现有 19 个别名关联到该 `source_id`，保持 `status=1`；
3. 分别建立 19 条策略主记录和 `ACTIVE v1`；
4. 每条初始版本保存：
   - `expected_interval_seconds=60`；
   - `allowed_delay_seconds=30`；
   - `raw_retention_mode=FIXED_DAYS`；
   - `raw_retention_days=90`；
   - `minute_retention_mode=LONG_TERM`；
   - `minute_retention_days=NULL`；
   - `time_semantics=DEVICE_EVENT_TIME`；
5. `actor_type=SYSTEM_MIGRATION`、`operator_id/published_by=NULL`；
6. 记录 `change_source=INITIAL_MIGRATION` 和脱敏迁移审计；
7. 迁移前后现有正式接入行为保持可用。

这些值是可修改的初始配置数据，不能写入 Java 常量、枚举、数据库列默认值或“所有新测点必须为
60/30”的校验。自动化测试必须另外使用 15 秒、300 秒等值证明可配置。

迁移前必须检查：

- 是否存在不同建筑重复使用同一 `source_system`；
- 19 个别名和对应标准测点是否完整、唯一且未逻辑删除；
- 是否存在部分数据源、策略或版本记录；
- 已存在结构和数据是否与候选预期一致。

发现冲突必须停止并给出稳定迁移错误，不能自动合并、覆盖、改名或猜测归属。迁移脚本遵守当前
MySQL 8 初始化脚本的可重跑与结构冲突检查约定。

## 17. 验证与验收标准

### 17.1 自动化验证

#### 迁移与数据模型

- 新表、索引、检查约束和外键符合设计；
- 既有 19 点迁移幂等；
- 部分旧结构或冲突数据会安全停止；
- 不存在级联删除；
- 初始 19 点保存 60/30/90/LONG_TERM；
- 非 60/30 策略能够创建、审核和发布。

#### 状态与事务

- 草稿数据源和草稿别名永不进入运行快照；
- 新数据源首启全部成功或全部回滚；
- 新别名初启同时发布初始策略；
- 发布新策略时旧版本原子退役；
- 启用过的数据源不能回到草稿或物理删除；
- 草稿整套可删除且审计保留；
- 回滚创建新版本，不修改旧版本；
- 过期修订号和并发发布返回 409；
- 审计写入失败会回滚对应业务事务。

#### 权限与可见性

- 建筑业主只能读取当前正式配置；
- 能源管理员只能查看和操作自己的草稿、历史、申请和审计；
- 能源管理员可以读取授权建筑当前有效配置并据此创建自己的草稿；
- 他人已有草稿时只返回脱敏冲突，不泄露内容或人员；
- 平台管理员可以审核全部申请并直接发布自己的配置；
- 第三方无治理接口权限；
- 跨建筑查询和修改由后端拒绝。

#### 运行时应用

- 运行快照只包含 `ENABLED` 数据源和 `ENABLED` 别名；
- 数据源停用排除全部别名，重新启用恢复原先有效别名；
- 单独停用别名不影响同来源其他别名；
- 策略停用不拒绝实际到达数据；
- `config_revision` 与 `runtime_revision` 按设计分别变化；
- 刷新失败不撤销数据库配置，也不伪装为 `APPLIED`；
- 周期刷新和平台管理员手动刷新可以恢复；
- 60 秒运行刷新、60 秒初始采样和 30 秒允许迟到互不混用。

#### 测试隔离

普通自动化测试使用测试数据库、Mock、Stub 或仓库允许的隔离设施，不连接真实 MQTT Broker、
TDengine、Redis、现场设备或第三方服务。

### 17.2 业务验收

1. 当前 19 点可以查询到明确的数据源、有效策略和初始版本来源；
2. 新建草稿来源、别名和策略时运行链不可见；
3. 能源管理员提交完整配置包，平台管理员一次审核完成首启；
4. 新增第 20 点不修改表结构和固定点码代码；
5. 修改采样周期会产生新版本和完整前后审计；
6. 停用后历史版本和数据仍可查询；
7. 平台管理员可从历史版本复制回滚并生成更高版本；
8. 数据库已发布与运行实例已应用可以被分别查询；
9. 运行刷新失败能够被识别、重试和手动恢复；
10. 所有不在本候选范围内的控制、清理和质量策略能力均未被误报为完成。

真实设备、云环境、TLS、跨网络和长期稳定性验收不属于本设计文档 PR，也不能由上述自动化替代。

## 18. 实施范围与顺序

本候选后续作为一个单一业务闭环 PR 实现，避免出现“只有表没有状态机”或“运行读取未接入”的
半完成状态。建议在同一任务分支内按以下顺序形成可验证提交：

1. MySQL 迁移、领域模型、Repository 和状态枚举；
2. 数据源、别名、策略版本、审核和审计 Service；
3. `/v1` 管理 DTO/API、建筑范围和稳定错误码；
4. 现有别名运行配置提供者接入数据源状态与运行修订号；
5. 现有 19 点初始迁移与兼容测试；
6. 定向测试、完整后端测试和状态文档同步。

是否拆分代码提交不改变一个任务、一个分支、一个 PR、一个闭环的范围。本候选不包含前端页面。

## 19. 已确认事项与未解决边界

### 19.1 已确认

- 首批 19 点暂定采样周期 60 秒、允许迟到 30 秒；
- 参数必须按版本配置，不得写死；
- 数据源编码全局唯一并使用大写稳定格式；
- 首版一源一建筑，归属判断集中处理；
- 数据源、别名和策略初始发布形成完整首启事务；
- 能源管理员提交、平台管理员审核，平台管理员自建草稿可直接发布；
- 能源管理员不能查看他人的草稿、历史、申请或审计；
- 数据库发布与运行时应用分开表达，最迟 60 秒收敛；
- 运行状态首版只保存在单体实例内存；
- 回滚通过新版本表达；
- 现有 19 点通过系统迁移建立有效 `v1`。

### 19.2 未解决且不阻塞本候选设计

- 通用 Q0/Q1/Q2 使用范围和场景化质量策略；
- 采集策略保留要求与 TDengine 清理任务的安全联动；
- 设备或网关采样周期下发；
- 多实例运行配置广播和实例状态聚合；
- 真实设备、云环境、TLS 和长期运行验收；
- 管理端 Vue 页面。

这些事项必须以后续独立设计、专业输入和验收推进，不能在实现本闭环时顺手加入。

## 20. 完成边界

本设计文档完成只表示需求、数据模型、状态机、接口、权限、事务、迁移、运行应用和验收范围已经
形成可实施候选，不表示任何新表、API、审核流、运行过滤或迁移已经存在。

只有后续实现完成范围匹配的验证，并经独立验收和用户批准后，才能在 `PROJECT_STATUS.md` 中把
相应能力从“计划中/部分完成”改为“已实现”，并评估是否将本候选纳入当前权威基线。
