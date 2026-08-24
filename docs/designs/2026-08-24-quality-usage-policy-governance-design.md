# Q0/Q1/Q2 使用策略治理与执行闭环设计

> **文档状态：活动候选设计，需求、边界、数据模型和验收规则已确认，尚未实现、尚未验证、尚未批准为当前正式基线。**
>
> 当前权威基线仍以 [`PROJECT_STATUS.md`](../../PROJECT_STATUS.md)、当前代码和自动化测试为准。
> 本候选只有在完成实现、范围匹配验证、独立验收并经用户批准后，才能转为正式版本能力。

## 1. 结论

阶段一第二个后台业务闭环锁定为“Q0/Q1/Q2 使用策略治理与执行闭环”。它只回答一个问题：

> 已经生成的 Q0、Q1、Q2 分钟事实，在某个标准测点和某个消费场景下是否允许使用。

本闭环不重新发明 Q0/Q1/Q2，也不修改现有插值、典型值、公式或专业阈值。它在现有质量事实和消费入口之间增加一个后端权威门禁：

```text
Q0/Q1/Q2 分钟事实
→ QualityUsagePolicyResolver
→ 场景化 ALLOW / BLOCK
→ 实时查询、历史查询或指标计算
```

首版系统注册三个场景：

- `POINT_REALTIME_VIEW`：测点实时值展示；
- `POINT_HISTORY_VIEW`：测点历史值与降采样；
- `INDICATOR_CALCULATION`：指标公式实际输入。

策略按“标准测点 + 使用场景”独立配置允许等级集合。没有已发布策略时，系统安全默认只允许 Q0；显式空集合表示该测点在该场景下完全禁止使用。门禁只影响消费，不删除、不降级、不替换 Q0/Q1/Q2 事实，也不能反压采集链路。

## 2. 背景与当前证据

当前主线已经具备：

- HVAC 场景的 Q0 实际分钟、Q1 线性插值和 Q2 已批准典型值；
- Q0 > Q1 > Q2 的分钟结果优先级和迟到 Q0 修正；
- 公式输入组装、四项继承指标计算、成功结果和失败结果持久化；
- 测点实时、历史、指标查询以及 WebSocket 实时发布；
- 正式角色、建筑范围、分页 DTO、业务异常和机器错误码；
- 已合并的数据源与采集策略治理候选实现，可提供数据源、别名、采集策略和运行快照基础。

主要当前证据入口：

- [`HvacIngestionService.java`](../../src/main/java/com/platform/iot/ingest/HvacIngestionService.java)：按设备事件时间归属分钟，区分服务器接收时间和迟到；
- [`HvacMinuteAggregationService.java`](../../src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java)：冻结 Q0 分钟结果；
- [`InterpolationFillService.java`](../../src/main/java/com/platform/iot/dataquality/InterpolationFillService.java)：现有 `LINEAR_V1` Q1 实现；
- [`TypicalValueFillService.java`](../../src/main/java/com/platform/iot/dataquality/TypicalValueFillService.java)：现有 `TYPICAL_V1` Q2 实现；
- [`HvacMinuteRepository.java`](../../src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java)：Q0/Q1/Q2 分钟事实读写边界；
- [`FormulaInputAssembler.java`](../../src/main/java/com/platform/iot/formula/FormulaInputAssembler.java)：公式实际输入组装边界；
- [`TdengineIndicatorMinuteRepository.java`](../../src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java)：指标成功和异常时序事实；
- [`HvacIndicatorQueryService.java`](../../src/main/java/com/platform/hvac/service/HvacIndicatorQueryService.java)：指标最新、历史和计算详情查询；
- [`BuildingScopeService.java`](../../src/main/java/com/platform/system/service/BuildingScopeService.java)：建筑范围权限边界；
- [`数据源与采集策略治理闭环设计`](2026-08-24-collection-source-policy-governance-design.md)：前一闭环的来源、采集策略和运行配置边界。

当前缺口是：

- Q1 最大间隔仍是现有 HVAC 全局配置，不是按测点、场景治理；
- 当前在线且参与计算的测点会自动进入质量补全，没有使用场景门禁；
- 实时、历史和公式入口默认接受已有 Q0/Q1/Q2，没有统一允许集合；
- 历史降采样不能区分“没有数据”和“有数据但策略禁止”；
- 指标计算无法表达“原来成功、后来因策略收紧而失效”；
- 没有质量使用策略的版本、审核、修订号、运行快照、恢复和审计模型。

## 3. 目标与非目标

### 3.1 目标

1. 建立系统注册、可停用、可扩展的质量使用场景目录；
2. 以“标准测点 + 使用场景”为唯一策略身份；
3. 使用显式允许等级集合表达 Q0/Q1/Q2 使用范围；
4. 支持安全默认 Q0、显式空集合和 Q1/Q2 独立许可；
5. 建立策略草稿、版本、变更集、审核、直接发布、回滚和审计；
6. 让实时 HTTP、WebSocket、历史查询和指标计算统一执行后端门禁；
7. 区分缺失、质量禁止、场景停用和策略快照不可用；
8. 使用全局配置修订号和单实例不可变内存快照实现低延迟解析；
9. 在快照滞后恢复后纠正实时状态并有界重算指标；
10. 以可追溯迁移为现有 19 点建立初始保守策略，不中断实时和历史展示。

### 3.2 非目标

- 不修改 Q0 生成、Q1 插值算法、Q1 最大间隔、Q2 典型值生成或典型值审核；
- 不把 `LINEAR_V1`、`TYPICAL_V1` 宣称为通用或专业最终算法；
- 不修改公式、专业阈值、物理边界、主备输入规则或能源指标定义；
- 不生成新的 Q0/Q1/Q2，不删除或改写已有质量事实；
- 不控制设备采样周期、边缘网关、MQTT/HTTP 接入或 TDengine 清理；
- 不开发或修改 Vue 页面、组件、样式和前端状态；
- 不建立告警、报告、碳计算、能源审计或其他未注册消费场景；
- 不建立多实例配置广播、分布式锁或节点状态聚合；
- 不建设审核证据自动归档、对象存储或最终物理清理；
- 不把隔离自动化测试描述为真实设备、云环境、TLS 或现场验收。

## 4. 既有硬约束处理

| 既有约束 | 本候选处理 | 说明 |
|---|---|---|
| 采集链路与 Q0/Q1/Q2 事实持续运行 | 保留 | 门禁只在消费入口执行，不改变采集和质量生成 |
| Q0/Q1/Q2 使用范围不得硬编码 | 落实 | 实际策略使用版本表和等级子表，Java 不保存业务允许集合 |
| 能源专家确认专业算法与阈值 | 保留 | 本候选只治理“是否允许使用”，不代替专家确认 |
| 前一闭环不实现质量使用范围 | 迁移 | 该未解决项由本候选独立承接，不反向扩大前一闭环 |
| MySQL 保存业务配置，TDengine 保存时序事实 | 保留 | 策略治理在 MySQL；指标计算事实和状态投影在 TDengine |
| 建筑范围是后端安全边界 | 保留 | 所有治理读写继续调用 `BuildingScopeService` |
| 运行配置首版为单实例内存 | 保留 | `runtime_revision` 只在实例内存，不伪装为集群状态 |
| 当前前端是继承版本 | 保留 | 只提供向后兼容的后端附加字段，不修改 Vue |
| 历史设计保持冻结 | 保留 | 本文不修改旧设计，只明确承接其未解决边界 |

## 5. 术语与质量事实边界

### 5.1 质量等级

- **Q0**：由真实设备事件形成的正式分钟事实；
- **Q1**：由现有短间隔线性插值生成的分钟事实；
- **Q2**：由现有已批准、版本化、有效期内的典型值生成的分钟事实。

本文中的 Q0/Q1/Q2 是现有质量事实等级，不是新的算法定义。策略允许某个等级不代表该等级一定存在，也不证明其专业适用性已经被能源专家确认。

### 5.2 生成与消费

分钟聚合、Q1 补全和 Q2 补全属于“生成”。实时查看、历史查看和指标公式属于“消费”。本闭环不在生成链前置门禁，原因是：

- 同一质量事实可能被一个场景允许、另一个场景禁止；
- 删除或停止生成会损失历史重算和审计依据；
- 使用策略变化时应重新判断消费，不应重建原始事实。

### 5.3 业务阻止与系统堵塞

`QUALITY_BLOCKED` 表示业务禁止使用，不表示线程、连接池或队列被阻塞。门禁必须是内存判断；配置刷新、WebSocket 纠正和指标恢复必须异步、有界，禁止反压采集链路。

## 6. 使用场景目录

### 6.1 初始场景

| `scenario_code` | 业务语义 | `adapter_type` |
|---|---|---|
| `POINT_REALTIME_VIEW` | 测点实时 HTTP 与 WebSocket 消费 | `POINT_REALTIME_GATE` |
| `POINT_HISTORY_VIEW` | 测点历史与 5/30 分钟降采样 | `POINT_HISTORY_GATE` |
| `INDICATOR_CALCULATION` | 指标公式实际输入 | `INDICATOR_INPUT_GATE` |

分钟聚合不是消费场景；指标趋势是指标计算完成后的查询，不另建初始场景。

### 6.2 注册与生命周期

场景目录由系统受控迁移注册，普通治理 API 只读。管理员不能在页面随意创建场景编码。

```text
DRAFT → ENABLED → DISABLED
```

- `scenario_code` 创建后不可修改；
- 从未启用、从未被引用的草稿场景可以物理删除；
- 启用过或被正式策略引用的场景不能物理删除；
- 启用前必须存在对应后端适配器、默认策略说明和门禁集成测试；
- 停用后消费失败关闭，返回 `QUALITY_USAGE_SCENARIO_DISABLED`，不能绕过策略；
- 启用和停用递增全局 `config_revision`，但不删除已有策略和历史证据。

Java 允许注册受支持的 `adapter_type`，但不得把全部业务 `scenario_code` 写成封闭枚举。新增复用既有适配器的场景主要增加迁移、初始策略和测试；新增完全不同的消费链路需要一个新适配器及其语义测试。

## 7. 策略身份、允许集合与默认规则

### 7.1 策略身份

正式策略唯一身份为：

```text
(point_id, usage_scenario)
```

`building_id` 冗余保存用于权限过滤、组合外键和归属校验，不构成第二套策略身份。首版不提供建筑级自动继承，批量创建或跨建筑复制只提高编辑效率，发布后的测点策略仍彼此独立。

### 7.2 显式允许集合

实际允许等级使用关系子表保存，不使用最大等级、JSON、位掩码或 Java 常量。合法结果包括：

| 策略状态 | 语义 |
|---|---|
| 没有当前有效策略 | 系统默认仅允许 `{Q0}` |
| `{Q0}` | 只允许真实分钟 |
| `{Q0,Q1}` | 允许真实分钟和插值 |
| `{Q0,Q2}` | 允许真实分钟和典型值 |
| `{Q0,Q1,Q2}` | 允许全部现有质量等级 |
| 显式空集合 `[]` | 该测点在该场景完全禁止使用 |

任何非空集合必须包含 Q0。Q1 和 Q2 相互独立，因为两者生成依据和专业风险不同。

### 7.3 默认策略证据

没有已发布策略时不得临时创建伪版本，解析结果固定为：

```text
policySource = SYSTEM_DEFAULT_Q0_ONLY
policyVersion = null
configRevision = 当前已加载修订号
reason = NO_ACTIVE_POLICY_DEFAULT
```

默认规则必须由数据库初始配置或运行策略模块的可配置安全默认表达，禁止散落在 Controller、公式、前端或各查询实现中。默认值未来可通过正式设计调整，不得写死为不可替换的业务规则。

## 8. 版本、变更集与审核状态机

### 8.1 策略版本

```text
DRAFT → ACTIVE → RETIRED
```

- `ACTIVE` 和 `RETIRED` 不可修改、不可物理删除；
- 发布新版本时旧 `ACTIVE` 在同一事务内变为 `RETIRED`；
- 回滚通过复制历史版本生成更高版本号，不重新激活旧版本；
- 从未发布的草稿版本可以删除；
- 显式空集合可以作为正式 `ACTIVE` 版本；
- 每个草稿保存 `base_active_version_id`，没有当前版本时为 `NULL`。

### 8.2 变更集

一个变更集只能属于一个建筑，可以同时包含多个测点和多个场景。跨建筑复制必须产生不同变更集。

```text
DRAFT → PENDING → PUBLISHED
   ↑         │
   └─────────┘  拒绝或撤回

DRAFT → CANCELLED
```

- `DRAFT` 可编辑；
- `PENDING` 临时冻结；
- 拒绝或撤回后原变更集回到 `DRAFT`，不复制整套新草稿；
- 曾提交但放弃的变更集进入 `CANCELLED`；
- 从未提交的草稿变更集可以物理删除；
- `PUBLISHED` 和 `CANCELLED` 不可恢复为草稿。

`biz_quality_usage_policy_version.change_set_id` 直接表达变更集项目，首版不新增重复的变更项目表。

### 8.3 审核申请

```text
PENDING → APPROVED / REJECTED / WITHDRAWN
```

每次提交创建新的不可变轻量审核快照，包含策略 ID、版本、允许等级、基础版本、摘要和哈希，不复制无关测点或设备字段。普通草稿编辑不产生快照。

批准时重新计算规范化快照的 SHA-256 摘要，必须与提交摘要一致。普通审核禁止自审；只有 `PLATFORM_ADMIN` 对自己创建的草稿执行显式直接发布时，允许提交人与审核人相同，并记录 `DIRECT_PUBLISH`。

### 8.4 并发与原子发布

- 并行私有草稿允许存在；
- 同一策略身份同时最多一个待审核申请；
- 发布时按 `point_id + scenario_code` 稳定顺序锁定全部策略身份；
- 当前有效版本必须等于各草稿的 `base_active_version_id`；
- 任一版本、状态、测点归属、测点可用性或场景冲突使整个变更集失败；
- 冲突时不发布任何版本，审核请求记录为 `REJECTED`，变更集退回 `DRAFT` 并保留机器原因；
- 发布成功后在同一 MySQL 事务内退役旧版本、激活新版本、更新当前指针、递增一次全局 `config_revision` 并写审核审计。

直接发布也必须执行相同快照、锁定、校验、事务和修订号流程。相关身份已有其他待审核变更集时，不允许直接发布插队。

## 9. 角色、建筑范围与可见性

### 9.1 `BUILDING_OWNER`

- 只能查看授权建筑当前生效策略；
- 不能查看草稿、历史版本、审核申请和审计；
- 不能创建、提交、批准、拒绝或直接发布。

### 9.2 `ENERGY_MANAGER`

- 可以查看授权建筑全部当前生效策略；
- 只能查看、编辑、提交、撤回和取消自己创建的变更集；
- 只能查看自己的草稿、历史版本、审核申请、审核结果和操作审计；
- 不能查看他人的草稿、历史、申请或审计；
- 不能批准、拒绝或直接发布。

他人已有待审核内容时，只返回脱敏冲突，不泄露创建人和草稿内容。

### 9.3 `PLATFORM_ADMIN`

- 可以查看全部建筑、策略、版本、变更集、审核和审计；
- 可以批准或拒绝能源管理员提交；
- 可以对自己创建的草稿执行显式直接发布；
- 普通审核流程仍禁止自己审核自己的普通提交。

### 9.4 `THIRD_PARTY`

不开放质量策略治理 API。现有数据接口是否允许访问仍由其原有角色和建筑范围规则决定，但不能绕过质量使用门禁。

所有 Controller 只负责角色入口和 DTO，Service 必须再次调用 `BuildingScopeService` 校验建筑归属。建筑范围不放入 JWT，也不由前端推断。

## 10. MySQL 数据模型

### 10.1 `biz_quality_usage_scenario`

系统场景目录，核心字段：

- `scenario_id`、`scenario_code`、`scenario_name`；
- `adapter_type`；
- `status`：`DRAFT/ENABLED/DISABLED`；
- `introduced_version`、创建、启用、停用和原因字段。

`scenario_code` 全局唯一且不可修改。

### 10.2 `biz_quality_usage_policy`

稳定策略身份，核心字段：

- `policy_id`、`building_id`、`point_id`、`scenario_id`；
- `current_active_version_id`；
- `pending_review_request_id`；
- 创建时间、更新时间。

唯一键为 `(point_id, scenario_id)`。`building_id` 必须通过组合外键与测点归属一致。待审核指针用于数据库层约束同一身份最多一个待审核申请。

### 10.3 `biz_quality_usage_policy_version`

不可变版本和草稿记录，核心字段：

- `version_id`、`policy_id`、`change_set_id`；
- `version_no`、`status`；
- `base_active_version_id`、`copied_from_version_id`；
- `effective_from_ms`、`effective_to_ms`；
- `initial_baseline`；
- `published_config_revision`；
- 创建、发布、退役、操作人和原因字段。

同一策略版本号唯一。除系统初始化基线外，`effective_from_ms` 必须非空并对齐分钟；区间采用 `[effective_from,effective_to)`。

### 10.4 `biz_quality_usage_policy_level`

正式允许等级子表：

- `version_id`；
- `quality_level`：`Q0/Q1/Q2`。

唯一键为 `(version_id, quality_level)`。显式空集合由“存在正式版本但没有等级子行”表达。Service 和数据库约束共同保证非空集合包含 Q0。

### 10.5 `biz_quality_usage_change_set`

单建筑变更集，核心字段：

- `change_set_id`、`building_id`、`status`；
- `revision`、`submitted_revision`；
- `created_by`、`has_been_submitted`；
- 标题、说明、创建、提交、发布、取消时间。

草稿编辑使用显式乐观修订号；状态改变采用行锁和条件更新。

### 10.6 `biz_quality_usage_review_request`

每次提交的不可变审核申请，核心字段：

- `request_id`、`change_set_id`、`request_no`；
- `status`、`review_mode`：`NORMAL/DIRECT_PUBLISH`；
- `submitted_revision`；
- `snapshot_json`、`snapshot_sha256`；
- 提交、审核、撤回人员、时间和意见；
- 幂等键和请求摘要。

实际允许等级仍以规范化策略表为准；`snapshot_json` 只用于证明提交时审核了什么，不作为运行配置来源。

### 10.7 `biz_quality_usage_audit_log`

只记录治理操作，不逐行记录实时和历史门禁决定。字段包含操作者、动作、对象、建筑、前后摘要、结果、原因、修订号和时间。摘要必须脱敏、有界，不保存 Token、SQL、设备报文样例或无关字段。

### 10.8 `biz_quality_usage_config_revision`

全局单行单调修订号：

- `config_revision`；
- `updated_at`；
- 最近一次发布或场景变更摘要。

发布一个变更集只递增一次。草稿编辑、提交、拒绝和撤回不递增。`runtime_revision` 只在每个应用实例内存中保存，不写入该表。

### 10.9 外键与删除

- 正式外键默认 `RESTRICT`，禁止 `ON DELETE CASCADE`；
- 正式版本、审核和审计不能由级联删除带走；
- 草稿删除由 Service 显式校验和排序执行；
- 所有列表查询先按建筑范围过滤，普通用户空建筑范围必须显式返回空结果。

## 11. 时间语义

系统不使用模糊的“现实时间”字段，明确区分：

- `eventTime`：设备声明的采样时间，决定数据归属分钟；
- `receivedTime`：可信平台入口记录的接收时间，用于迟到和时钟异常判断；
- 平台操作时间：提交、审核、发布、加载和计算实际执行时间；
- 数据库事务时间：发布事务的权威当前时间。

核心规则：

- `minuteStart`、`effective_from_ms`、`effective_to_ms` 和 API 时间使用 Unix 毫秒；
- 分钟对齐固定为 `timestamp - floorMod(timestamp, 60000)`；
- 人工发布以数据库事务时间为准，严格从其后的下一个完整分钟生效；
- 即使在 `10:15:00.000` 完成发布，也从 `10:16:00.000` 生效；
- 策略解析始终使用数据的 `minuteStart`，不使用查询时间或当前服务器时间；
- MySQL 操作审计时间继续遵循项目现有 `DATETIME(3)` 和 `Asia/Shanghai` 约定；
- API 返回 Unix 毫秒，建筑时区只用于展示；
- 日、月、账单周期等未来场景由其适配器明确建筑时区，本候选三个分钟场景不参与该划分。

系统初始化基线是唯一回溯例外：`effective_from_ms=NULL` 表示无下界历史区间，并同时要求 `initial_baseline=true`。普通用户版本禁止使用 `NULL` 回溯生效。

## 12. 运行解析、修订号与快照

### 12.1 统一解析器

`QualityUsagePolicyResolver` 是三个消费入口的唯一权威判断，输入至少包含：

- `pointId`；
- `scenarioCode`；
- 数据 `minuteStart`；
- 实际质量等级。

输出包含：

- `decision`：`ALLOW/BLOCK`；
- `actualQuality`；
- `scenarioCode`；
- `policySource`；
- `policyVersion`；
- `configRevision`；
- `reason`。

场景停用或快照不可用是系统不可用，不伪装为普通 `BLOCK`，由入口映射为稳定错误状态。

### 12.2 修订检查

数据库保存全局 `config_revision`，实例内存保存已经加载的 `runtime_revision`：

```text
查询数据库 config_revision
→ 与内存 runtime_revision 比较
→ 仅不一致时重新加载完整快照
```

- 发布事务在同一事务内递增一次 `config_revision`；
- 提交后 `afterCommit` 立即触发比较和刷新；
- 每 60 秒只查询一次轻量修订号作为兜底，不是每次全表刷新；
- 多个修订累积时直接加载最新完整快照，不逐版回放；
- 完整加载成功后一次性原子替换不可变快照并更新内存 `runtime_revision`；
- 加载失败保留旧快照和旧 `runtime_revision`，下个周期继续重试。

60 秒是当前可配置兜底值，不得与设备 60 秒采样周期或 30 秒允许迟到混用。配置项必须允许后续调整，不能散落写死。

### 12.3 快照内容

全局内存快照只保存：

- 当前策略；
- 配置的自动纠正时间窗内仍可能被实时、迟到修正和自动重算访问的历史版本；
- 场景目录和必要索引。

历史长区间查询和人工重算不得把全部历史策略永久放入全局内存，而应一次批量读取请求范围内重叠区间，在请求内建立不可变区间索引。禁止逐分钟访问 MySQL，也禁止 MySQL 与 TDengine 跨库 JOIN。

### 12.4 快照故障

- 已有旧快照时，新版本加载失败继续按旧策略消费，采集和质量事实持续保存；
- 当前发布结果标记 `REFRESH_FAILED`，不能伪装成已应用；
- 首次快照加载失败时，采集和持久化继续，但实时、历史和指标消费失败关闭；
- HTTP 返回可重试 503；WebSocket 返回明确不可用状态；指标不保存成功结果；
- 历史策略区间无法加载时返回 503，不能退回 Q0 默认。

## 13. 三个消费入口的执行语义

### 13.1 实时 HTTP 与 WebSocket

实时 HTTP 和 WebSocket 必须调用同一个后端门禁服务。旧字段尽量保持兼容，新增：

- `usageStatus`：`AVAILABLE/QUALITY_BLOCKED/POLICY_SNAPSHOT_UNAVAILABLE`；
- `actualQuality`；
- `policySource`；
- `policyVersion`；
- `configRevision`；
- `reason`。

允许时返回正常数值。禁止时保留建筑、测点和时间元数据，`value=null`，返回实际质量和策略证据，任何实时入口都不得泄露原值。

策略恢复后，WebSocket 异步发送当前最新状态纠正事件。同一测点和分钟使用稳定业务键，新状态替代旧状态；刷新线程本身不得逐条推送。

### 13.2 历史查询与降采样

1 分钟原始查询保留被禁止分钟的时间位置，但 `value=null`、状态为 `QUALITY_BLOCKED`。

5/30 分钟降采样必须先按每条分钟的 `minuteStart` 解析策略、过滤禁止值，再对允许值计算平均、最小和最大。每个桶返回：

- `usableCount`；
- `blockedCount`；
- `missingCount`；
- `status`：`AVAILABLE/PARTIAL_POLICY_FILTERED/QUALITY_BLOCKED/NO_DATA`。

只要存在可用值，就返回基于可用值的统计并在有阻止值时标记 `PARTIAL_POLICY_FILTERED`。全部现有值被禁止时返回空值和 `QUALITY_BLOCKED`。没有任何分钟事实时返回 `NO_DATA`。被禁止分钟不参与统计，也不能由相邻值、旧值或 Q0 默认替代。聚合质量取实际参与统计数据中的最差等级。

### 13.3 指标计算

执行顺序固定为：

1. 按现有公式语义组装数据并选择实际主输入、备用输入和可选输入；
2. 缺少必需数据时返回 `MISSING_INPUT`；
3. 输入完整后，只对 `FormulaCalculation.inputs()` 中实际使用的测点执行门禁；
4. 任一实际输入被禁止时返回 `QUALITY_NOT_ALLOWED`；
5. 全部允许时保存成功结果，输出质量取实际输入中的最差等级。

门禁不得导致被禁止的主输入静默改用备用输入，也不得让未使用的备用或可选输入阻止公式。快照不可用时结果为 `POLICY_SNAPSHOT_UNAVAILABLE`，不能按 Q0 默认放行。

同一指标分钟使用稳定幂等业务键。策略恢复或变化重算时：

- 以前被禁止、现在允许的分钟可以生成成功结果；
- 以前成功、现在禁止的分钟保留旧数值事实，但当前状态变为失效；
- 查询只返回该指标分钟当前有效状态。

## 14. TDengine 指标事实与当前状态

### 14.1 当前实现限制

现有 `st_indicator_minute` 保存成功结果，`st_formula_calc_exception` 保存异常；同一分钟成功和异常并存时，查询固定让成功优先。这不能表达“较新的策略拒绝使较早成功失效”。

### 14.2 候选模型

保留现有 `st_indicator_minute` 作为指标成功事实，不因策略收紧删除或覆盖旧数值。新增：

1. **追加式计算异常/策略判定事实** `st_formula_calc_attempt_v2`；
2. **轻量当前状态投影** `st_indicator_minute_state`。

追加事实至少保存：

- `attempt_id`、实际执行时间、指标分钟；
- `calc_status`、`reason_code`；
- 场景编码、公式版本；
- 被阻止的测点、实际质量、策略版本和 `config_revision` 的有界规范化证据；
- 完整指标身份标签。

首版单实例 Repository 必须为同一指标保证追加时间戳单调，并使用 `attempt_id` 实现业务幂等。正式实现前必须以仓库固定的 TDengine 3.2.3 验证 DDL、覆盖语义、排序和查询计划，不依据更高版本能力猜测实现。

当前状态投影按“指标 + 分钟”保存：

- `current_status`；
- `source_fact_id` 或 `attempt_id`；
- `state_updated_at`；
- `config_revision`。

投影允许覆盖，因为它不是审计事实。查询先读取投影，再决定是否返回成功值；投影为禁止时，即使旧成功值仍存在，API也返回空值。

### 14.3 写入与对账

- 成功：先保存成功事实，再把投影更新为 `SUCCESS`；
- 失败：先追加异常事实，再把投影更新为对应失败状态；
- 不为普通首次成功重复保存一份完整成功快照；
- 事实写入成功而投影失败时，记录可恢复任务，由后台按幂等键重建投影；
- 投影失败不能阻塞采集或改写 Q0/Q1/Q2；
- 没有新投影的旧历史分钟继续按旧数据兼容读取，一旦被新策略重算即建立正式投影。

实时和历史策略决定不逐条写 MySQL。配置操作审计在 MySQL；公式失败和状态变化事实在 TDengine；高频实时、历史门禁只记录聚合指标和脱敏日志。

## 15. 策略恢复、防堵塞与性能边界

### 15.1 修订恢复

每次发布记录修订号、生效分钟和受影响的测点/场景。实例从旧修订追到新修订时：

- 实时查询立即使用新快照；
- WebSocket 异步纠正最新状态；
- 指标场景触发受影响指标的有界重算；
- 更严格策略将不合法旧成功的当前状态改为 `QUALITY_NOT_ALLOWED`；
- 更宽松策略重算此前被阻止的结果；
- 原始分钟质量事实保持不变。

自动恢复范围复用可配置的现有纠正时间窗，不写死 24 小时或其他时长。超出范围的数据标记 `QUALITY_POLICY_RECOVERY_REQUIRED`，等待人工重算，禁止无限扫描。

### 15.2 防堵塞硬约束

- 实时门禁只查不可变内存 Map/区间索引，不访问 MySQL；
- 快照在后台完整构建后原子切换，不锁查询线程；
- 发布事务只锁 MySQL 策略身份，不锁采集、TDengine 写入和实时查询；
- WebSocket 纠正和指标恢复使用相互独立的有界任务队列；
- 同一测点的重复纠正事件合并，只保留最新未发送状态；
- 队列满时不阻塞采集，记录积压和丢弃的过期纠正任务；
- 历史策略批量加载，不执行每分钟 SQL；
- 所有队列容量、并发、批次和自动恢复窗口均为配置项，不散落业务硬编码。

## 16. API 与错误契约

Spring Controller 路径统一使用 `/v1/quality-usage`；结合现有应用上下文，对外路径为 `/api/v1/quality-usage`。成功响应继续使用 `Result<T>`，分页使用稳定 `PageResponse<T>`，不暴露 MyBatis 分页或 Entity。

### 16.1 场景与生效策略

```text
GET /v1/quality-usage/scenarios
GET /v1/quality-usage/policies/active
GET /v1/quality-usage/policies/{policyId}
GET /v1/quality-usage/policies/{policyId}/versions
```

### 16.2 变更集

```text
GET    /v1/quality-usage/change-sets
GET    /v1/quality-usage/change-sets/{changeSetId}
POST   /v1/quality-usage/change-sets
PUT    /v1/quality-usage/change-sets/{changeSetId}
POST   /v1/quality-usage/change-sets/{changeSetId}/submit
POST   /v1/quality-usage/change-sets/{changeSetId}/withdraw
POST   /v1/quality-usage/change-sets/{changeSetId}/cancel
POST   /v1/quality-usage/change-sets/{changeSetId}/direct-publish
DELETE /v1/quality-usage/change-sets/{changeSetId}
```

草稿更新携带 `expectedRevision`。状态动作必须携带 `Idempotency-Key`：相同键和相同请求返回原结果；相同键复用不同请求返回 `IDEMPOTENCY_KEY_REUSED`。

### 16.3 审核

```text
GET  /v1/quality-usage/review-requests
GET  /v1/quality-usage/review-requests/{requestId}
POST /v1/quality-usage/review-requests/{requestId}/approve
POST /v1/quality-usage/review-requests/{requestId}/reject
```

撤回属于变更集提交人动作，使用变更集撤回接口；批准和拒绝属于平台管理员动作。

### 16.4 DTO 规则

- 时间统一使用 Unix 毫秒；
- `allowedQualities` 必须显式返回数组，`[]` 表示明确禁止，不能省略或返回 `null`；
- 系统默认返回 `policySource=SYSTEM_DEFAULT_Q0_ONLY`、`policyVersion=null`；
- 批量操作必须返回整个变更集结果，不返回部分成功；
- 实时和历史 DTO 使用第 13 节的附加状态字段；
- 前端不得自行推断允许等级、状态机或建筑权限。

### 16.5 错误响应

质量策略 API 可以在现有错误结构上增加专用可选 `details`：

```json
{
  "code": 409,
  "msg": "策略基础版本已经变化",
  "success": false,
  "errorCode": "QUALITY_POLICY_VERSION_CONFLICT",
  "details": {
    "items": []
  }
}
```

`details` 只包含变更项目 ID、测点、场景和机器原因码，按测点 ID、场景编码稳定排序，不泄露 SQL、内部异常或无权查看的信息。详情数量受可配置变更集容量限制。

稳定错误码至少包括：

- `QUALITY_POLICY_VALIDATION_FAILED`：400；
- `QUALITY_POLICY_UNAUTHORIZED`：401；
- `QUALITY_POLICY_FORBIDDEN`：403；
- `QUALITY_POLICY_NOT_FOUND`：404；
- `QUALITY_POLICY_STATE_CONFLICT`：409；
- `QUALITY_POLICY_VERSION_CONFLICT`：409；
- `QUALITY_POLICY_PENDING_CONFLICT`：409；
- `QUALITY_POLICY_POINT_NOT_ELIGIBLE`：409；
- `QUALITY_USAGE_SCENARIO_DISABLED`：治理操作 409、运行消费 503；
- `QUALITY_POLICY_SNAPSHOT_UNAVAILABLE`：503；
- `QUALITY_POLICY_RECOVERY_REQUIRED`：409；
- `IDEMPOTENCY_KEY_REUSED`：409。

## 17. 现有 19 点与初始场景迁移

迁移首先注册三个初始场景，再为现有 19 个标准测点建立 57 个独立策略身份和 v1：

| 场景 | 初始允许集合 |
|---|---|
| `POINT_REALTIME_VIEW` | `{Q0,Q1,Q2}` |
| `POINT_HISTORY_VIEW` | `{Q0,Q1,Q2}` |
| `INDICATOR_CALCULATION` | `{Q0}` |

初始策略必须是数据库正式记录，不得写入 Java 常量、枚举、前端逻辑或数据库列默认值。每个 v1 保存：

- `actor_type=SYSTEM_MIGRATION`；
- `change_source=INITIAL_CONSERVATIVE_POLICY`；
- `initial_baseline=true`；
- `effective_from_ms=NULL`；
- 对应允许等级子行；
- 发布修订号和脱敏迁移审计。

迁移规则：

- 三个场景、57 个策略身份及其 v1 在一个 MySQL 事务内完成；
- 任一测点缺失、重复、归属异常、逻辑删除或已有冲突策略，整批失败；
- 唯一迁移标识保证可重跑；
- 已存在且内容完全一致视为已完成；
- 只存在部分内容或内容不一致时稳定失败并报告差异；
- 禁止自动覆盖、改名、猜测归属或部分补齐；
- 整次初始化只递增一次全局 `config_revision`；
- 不读取 TDengine，不根据当前最早时序反推生效时间。

该无下界 v1 是唯一历史回溯例外。后续人工版本只允许面向未来完整分钟生效。

## 18. 测点生命周期、删除与证据保留

### 18.1 测点生命周期

- 测点停用不删除、不退役已有质量使用策略；
- 运行入口先判断测点是否可用，再解析策略；
- 停用测点不进入实时和指标消费；
- 重新启用后按当前分钟对应策略恢复；
- 待审核变更集中任一测点在发布时已不符合条件，整个变更集返回 `QUALITY_POLICY_POINT_NOT_ELIGIBLE`，不跳过、不部分发布。

只有从未正式使用、只有从未提交草稿且没有正式时序引用的测点，才允许先删除草稿策略后物理删除。存在审核、发布策略或正式时序事实时，测点只能停用。

### 18.2 删除规则

- 从未提交的草稿变更集和草稿版本可以物理删除；
- 已提交后放弃的变更集进入 `CANCELLED`；
- `ACTIVE/RETIRED` 策略、审核申请和审计不允许普通业务删除；
- 已启用场景及其历史策略不能物理删除；
- 退役策略至少在相关 TDengine 历史仍可查询期间保持在线可解析。

### 18.3 审核快照保留

首版本闭环不建设自动归档、对象存储或物理清理任务：

- 快照只在提交时生成，不在普通编辑时生成；
- 快照只保存必要 ID、版本、允许等级、基础版本、摘要和哈希；
- 当前有效、待审核、已批准和近期审核证据保存在 MySQL；
- 重复提交使用幂等和可配置频率限制；具体时长由部署配置决定，不写死；
- 后续明确热保留期限、归档介质、最终期限、法规保留和校验流程后，再建设“归档—校验—清理”闭环。

暂不自动删除是为了避免误删不可重建证据，不代表永久否定归档和清理。

## 19. 验证与验收标准

### 19.1 自动化验证

#### 策略解析

- 无策略默认 Q0；
- 显式空集合；
- Q0、Q1、Q2 合法组合和非法组合；
- 半开时间区间及分钟边界；
- 初始无下界版本；
- 场景停用和快照不可用。

#### 状态、权限与并发

- 草稿、提交、撤回、拒绝、批准、直接发布和取消；
- 四角色及建筑范围；
- 他人草稿、历史和审计不可见；
- 基础版本冲突和待审核冲突；
- 批量发布全量成功或全量回滚；
- 幂等重试不重复审核、不重复递增修订号；
- 审核摘要不一致拒绝发布。

#### 消费门禁

- 实时 HTTP 与 WebSocket 对同一事实返回一致判断；
- 被禁止实时值始终为空且保留证据；
- 历史先过滤再降采样，正确区分可用、阻止和缺失数量；
- 公式只检查实际输入；
- 被禁止主输入不能改走备用输入；
- 未使用备用或可选输入不能误阻止公式；
- 旧成功可被较新质量拒绝状态判为失效。

#### 运行快照与恢复

- 发布后立即刷新；
- 周期只比较修订号，未变化不全量加载；
- 加载失败保留旧快照；
- 首次失败时消费 503、采集继续；
- 严格策略使旧成功失效；
- 宽松策略恢复此前阻止结果；
- 超出自动窗口进入人工恢复；
- 恢复队列满时不阻塞采集；
- TDengine 事实成功而投影失败可以幂等对账。

#### 初始化迁移

- 精确建立三个场景和 57 个策略身份；
- 初始集合与本设计一致；
- 重复执行结果不变；
- 部分结构、内容冲突或测点异常时整批失败；
- 不存在级联删除和 TDengine 跨库读取。

### 19.2 结构性性能验收

首版不猜测生产 TPS，但必须验证：

- 实时策略判断不访问 MySQL；
- 历史请求按范围批量加载策略，不按分钟查询；
- 一个新修订最多触发一次完整快照切换；
- WebSocket 纠正与指标恢复使用独立有界队列；
- 自动化至少覆盖现有 19 点和 57 个初始策略身份；
- 暴露策略解析耗时、加载修订号、刷新失败、阻止数量和恢复积压等监控指标。

### 19.3 测试隔离与外部验证

普通自动化使用测试数据库、Mock、Stub 或仓库允许的隔离设施，不连接真实 MQTT Broker、现场设备或第三方资源。

仓库固定 TDengine 3.2.3 的新 DDL、时间戳追加、覆盖、排序和范围查询必须在隔离 TDengine 集成环境另行验证。真实 MySQL 8 迁移、云环境、TLS、跨网络和长期稳定性也必须独立标记，不能由 H2 或 Mock 测试替代。

## 20. 上线与回退

上线顺序：

1. 执行增量 MySQL 和 TDengine 结构迁移；
2. 写入并核验三个场景及 19 点初始策略；
3. 启动应用并确认 `runtime_revision=config_revision`；
4. 验证治理 API、实时 HTTP、WebSocket、历史查询和指标计算；
5. 验证采集与 Q0/Q1/Q2 事实写入未被门禁反压；
6. 再开放策略编辑、提交和审核权限。

回退约束：

- 数据库迁移必须是增量迁移，不删除旧表和旧字段；
- 回退应用不删除已经发布的策略、审核和指标证据；
- 正式启用门禁后，不得回退到完全不具备门禁能力的旧版本，否则会重新暴露被禁止数据；
- 故障优先前向修复；紧急回退也必须回到支持质量门禁的兼容版本；
- 大里程碑完成后，在干净任务中基于冻结需求、固定代码和原始验证证据进行独立验收；独立验收通过后仍需用户批准，才能升级为正式基线。

## 21. 实施范围与顺序

本候选后续作为一个独立后台业务闭环实现，不能只建治理表而不接入三个运行入口。建议顺序：

1. MySQL 场景、策略、版本、允许等级、变更集、审核、审计和修订号迁移；
2. 领域模型、Repository、状态机、权限、并发和原子发布；
3. `/v1/quality-usage` DTO、API、幂等和稳定错误码；
4. `QualityUsagePolicyResolver`、修订检查和不可变运行快照；
5. 实时 HTTP、WebSocket 和历史查询门禁；
6. 指标实际输入门禁、TDengine 异常事实和当前状态投影；
7. 修订恢复、WebSocket 纠正、指标有界重算和投影对账；
8. 三个场景与 19 点初始迁移；
9. 定向测试、完整后端验证、隔离 TDengine/MySQL 验证和状态文档同步。

本候选不修改 Vue。是否拆分代码提交不改变一个任务、一个分支、一个 PR、一个完整闭环的范围。

## 22. 已确认事项与延期边界

### 22.1 已确认

- 只治理现有 Q0/Q1/Q2 的使用，不修改生成算法和专业阈值；
- 初始三个场景由系统注册，场景目录动态读取；
- 策略身份为“测点 + 场景”，使用显式允许等级子表；
- 无策略默认 Q0，显式空集合表示完全禁止；
- 实时、历史和公式执行统一后端门禁；
- 历史先过滤再降采样并返回可用、阻止、缺失数量；
- 指标只门禁实际输入，禁止通过备用输入绕过；
- 能源管理员管理自己的草稿，平台管理员审核或显式直接发布；
- 变更集单建筑、批量原子发布、基础版本冲突全量失败；
- 提交时产生轻量不可变审核快照，拒绝或撤回后复用原变更集；
- 数据库使用全局 `config_revision`，实例内存使用 `runtime_revision`；
- 发布后立即刷新，60 秒只作可配置修订检查兜底；
- 旧快照可继续服务，首次无快照时消费失败关闭但采集继续；
- 恢复、纠正和重算异步有界，不能反压采集；
- 现有 19 点建立 57 个初始正式策略，指标计算初始仅允许 Q0；
- 本闭环不修改旧前端。

### 22.2 已延期且不阻塞实现

- 审核快照热存储保留时长；
- 归档介质和归档后保留时长；
- 法规保留、暂停删除和最终清理条件；
- 多实例修订广播和实例状态聚合；
- 新告警、报告、碳计算等消费场景；
- Q1/Q2 算法、专业阈值和典型值生成的通用化；
- 管理端和业务端 Vue 可视化。

延期事项不得在实现本闭环时被擅自补造默认值、存储系统或业务规则。

## 23. 完成边界

本文档完成只表示 Q0/Q1/Q2 使用策略治理的范围、状态机、权限、数据模型、时间语义、运行门禁、故障恢复、迁移和验收规则形成了可实施候选，不表示对应 MySQL 表、TDengine 表、API、运行解析器或消费门禁已经存在。

只有后续代码完成范围匹配的自动化与隔离集成验证，并经过独立验收和用户批准后，才能在 `PROJECT_STATUS.md` 中把该候选从“计划中”调整为“已实现”，并评估是否纳入当前权威基线。
