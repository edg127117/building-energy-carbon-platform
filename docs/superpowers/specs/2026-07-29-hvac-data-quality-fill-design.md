# HVAC 质量 1/2 生成、追溯与修正设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 1. 文档目的

本设计在现有 19 测点真实采集、事件时间分钟冻结和 HVAC 核心公式引擎之上，
补齐质量等级 `1/2` 的生成、典型值审批、来源追溯、失败恢复和历史修正能力。

本期目标不是建立通用数据治理平台，而是在 V1 单体架构内完成一条可运行、
可审计、不会阻塞每分钟指标的 HVAC 数据质量闭环。

## 2. 已有基础

当前代码已经实现：

- MQTT 真实数据校验，通过后由平台统一标记 `data_quality=0`；
- 真实原始事件写入 TDengine `st_raw_event`；
- 按事件时间生成 `st_raw_minute` 正式分钟聚合；
- 分钟质量向公式输入和指标结果透传；
- 指标成功、异常、Redis 最新值和 WebSocket 推送；
- 指标缺失恢复与补算；
- MySQL 四角色权限和建筑数据范围；
- MySQL 审批状态机、事务和行锁的现有实现范例。

当前未实现：

- 质量 `1` 的短缺线性插值；
- 质量 `2` 的典型值补全；
- 典型值来源、版本、有效期和审批；
- 补全批次证据和跨库应用状态；
- 质量 `2→1→0` 的历史修正；
- 管理端数据质量 API；
- 人工作废后的定向重算。

## 3. 核心业务定义

### 3.1 质量等级

| 等级 | 含义 | 来源 |
|---:|---|---|
| `0` | 真实采集 | 通过后端校验的设备原始事件 |
| `1` | 线性插值 | 缺失区间两侧的质量 `0` 真实分钟 |
| `2` | 典型值 | 已批准且处于有效期内的 MySQL 典型值配置 |

固定可信度优先级：

```text
质量0 > 质量1 > 质量2
```

低质量数据不得覆盖高质量数据。补全数据不得写入 `st_raw_event`，也不得伪装为
设备上报。

### 3.2 每分钟及时计算

系统不等待未来 5 分钟才生成首次结果。一个自然分钟越过现有冻结边界后立即选择
当前最可信数据：

```text
有真实分钟数据
→ 使用质量0

缺少真实分钟，但存在已批准且有效的典型值
→ 立即生成质量2

既无真实分钟，也无合法典型值
→ 记录具体缺失项，跳过依赖该输入的公式
```

“每分钟有结果”以所有公式必需输入均有真实值、合法替代输入或已批准典型值为
前提。系统不得为了凑齐结果补零或使用未审批默认值。

### 3.3 五分钟插值范围

五分钟不是等待时间，而是允许质量 `2` 或缺失历史分钟升级为质量 `1` 的最大
回看范围。

当质量 `0` 的右端分钟到达后，系统查找同测点最近的质量 `0` 左端分钟。设左右
端点之间缺失分钟数为：

```text
missingCount = (rightMinute - leftMinute) / 60_000 - 1
```

只有 `1 <= missingCount <= 5` 时才允许插值，因此为支持 5 个缺失分钟，查询范围
必须包含右端点之前最多 6 个分钟间隔，不能出现少查一分钟的边界错误。

目标分钟 `t` 的线性插值为：

```text
value(t) = leftValue
         + (rightValue - leftValue)
         * (t - leftMinute)
         / (rightMinute - leftMinute)
```

插值还必须同时满足：

- 左右端点均为质量 `0`；
- 测点状态为 `ONLINE`；
- V1 只处理 `is_for_calc=1` 的连续 `ANALOG` 测点；
- 不允许质量 `1/2` 作为插值端点；
- 插值值通过测点上下限校验；
- 不能覆盖已有质量 `0/1`；
- 同一连续缺口一次批量生成，禁止链式插值。

五分钟是 V1 默认工程参数，不是行业强制常量，配置为：

```yaml
data-quality:
  interpolation:
    max-gap-minutes: 5
```

### 3.4 迟到真实数据

真实原始事件仍按现有规则保存。若某事件属于已经冻结的历史分钟，且该分钟当前为
质量 `1/2`，在自动修正窗口内重新聚合该分钟的全部质量 `0` 原始事件：

```text
质量1/2分钟
→ 质量0真实分钟
→ 原补全任务增加replaced_count
→ 定向重算受影响指标
```

自动修正窗口默认 24 小时，并允许通过配置调整：

```yaml
data-quality:
  late-real-correction-hours: 24
```

超过窗口的真实事件继续作为原始证据保存，但不自动修改正式历史结果，只能由平台
管理员通过指定范围重算 API 处理。

## 4. 总体架构

### 4.1 首次分钟计算

```text
MQTT真实数据
→ TelemetryQualityValidator（只产生质量0）
→ st_raw_event
→ HvacMinuteAggregationService（只聚合真实事件）
→ st_raw_minute质量0行
→ HvacMinuteBatchFrozenEvent
→ 分钟质量补全层
   ├─ 输入完整：直接形成质量完成快照
   ├─ 输入缺失且有已批准典型值：生成并写入质量2
   └─ 无合法来源：保留具体缺失
→ HvacMinuteQualityReadyEvent
→ HvacFormulaEngine
→ st_indicator_minute / 公式异常
→ Redis / WebSocket
```

公式引擎改为消费“分钟质量处理完成”事件，避免同一分钟先因缺失失败、补入质量
`2` 后又无意义地重复计算。

### 4.2 历史质量升级

```text
新的质量0右端分钟
→ 批量查询最近插值范围
→ 生成一个连续缺口质量1任务
→ 质量2或缺失升级为质量1
→ 发布历史分钟修正事件
→ 逐个受影响分钟定向重算

迟到的目标分钟质量0原始事件
→ 重新聚合目标分钟
→ 质量1/2升级为质量0
→ 发布迟到真实修正事件
→ 定向重算
```

### 4.3 事件来源

分钟事件使用明确来源，避免补全监听器消费自己发布的事件而递归生成：

```text
NORMAL_FREEZE
TYPICAL_FILL
INTERPOLATION_CORRECTION
LATE_REAL_CORRECTION
MANUAL_RECALCULATION
```

质量补全服务只处理允许触发补全的来源；公式引擎可以处理所有已经完成质量处理的
事件。

当 `data-quality.enabled=false` 时，由条件化的旁路监听器把真实分钟冻结事件原样
转换成质量处理完成事件。这样测试或应急关闭质量补全后，现有质量 `0` 公式链路
仍然工作，不会因为公式触发点迁移而被一并关闭。

## 5. 模块职责

### 5.1 真实采集边界

`TelemetryQualityValidator`、`HvacIngestionService` 和 `st_raw_event` 继续只负责
质量 `0`。设备载荷不得声明质量 `1/2`。

### 5.2 真实分钟聚合

`HvacMinuteAggregationService` 继续只把真实事件聚合成质量 `0` 分钟，不承担
典型值审批、插值和人工修正。

### 5.3 质量补全模块

新增的质量补全模块负责：

- 比较活动计算测点和当前分钟已有真实聚合；
- 选择已批准且有效的典型值；
- 生成质量 `2` 批次任务并立即应用当前分钟；
- 查找质量 `0` 左右端点并生成质量 `1`；
- 执行 `0 > 1 > 2` 写入保护；
- 维护批次任务、失败分钟、替换计数和作废信息；
- 发布质量处理完成或历史修正事件；
- 执行技术失败重试和小时批次收口。

该模块不得包含公式数学逻辑。

### 5.4 典型值配置模块

典型值配置模块负责 MySQL 草稿、提交、审批、拒绝、停用、版本和有效期。运行时
使用不可变内存快照，不允许每个分钟逐条查询 MySQL。

### 5.5 公式模块

现有四项公式继续只读取同分钟标准输入，并取实际参与输入的最差质量。数学公式
主体不因质量生成机制改变。

历史修正后若公式仍成功，则以相同指标和时间戳幂等覆盖旧结果；若修正或作废后
公式变为缺失，必须删除该分钟旧成功结果并写入公式异常，不能让已经失效的成功值
继续留在历史趋势中。

## 6. 存储设计

### 6.1 MySQL：典型值配置

新增 `biz_point_typical_value_config`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `config_id` | `VARCHAR(32)` | 主键 |
| `point_id` | `VARCHAR(32)` | 标准测点 ID |
| `building_id` | `VARCHAR(32)` | 建筑范围冗余键 |
| `typical_value` | `DECIMAL(12,4)` | 典型值 |
| `unit` | `VARCHAR(20)` | 审批时冻结的单位 |
| `source_description` | `VARCHAR(500)` | 铭牌、设计资料或专家依据 |
| `reason` | `VARCHAR(500)` | 使用原因 |
| `valid_from` | `DATETIME(3)` | 有效期起点，包含 |
| `valid_to` | `DATETIME(3)` | 有效期终点，不包含，可空 |
| `status` | `VARCHAR(20)` | `DRAFT/PENDING/APPROVED/REJECTED/DISABLED` |
| `version` | `INT` | 同测点版本号 |
| `created_by` | `BIGINT` | 创建人 |
| `submitted_at` | `DATETIME(3)` | 提交时间 |
| `reviewer_id` | `BIGINT` | 审核人 |
| `review_comment` | `VARCHAR(500)` | 审核意见 |
| `reviewed_at` | `DATETIME(3)` | 审核时间 |
| `disabled_by` | `BIGINT` | 停用人 |
| `disabled_reason` | `VARCHAR(500)` | 停用原因 |
| `disabled_at` | `DATETIME(3)` | 停用时间 |
| `create_time` | `DATETIME(3)` | 创建时间 |
| `update_time` | `DATETIME(3)` | 更新时间 |

约束与索引：

- 主键 `config_id`；
- 唯一键 `(point_id, version)`；
- 查询索引 `(building_id, status)`；
- 有效配置索引 `(point_id, status, valid_from, valid_to)`；
- 审批时使用 MySQL 事务和测点范围行锁检查有效期重叠；
- 已批准行禁止直接修改，变更必须创建新版本；
- 当前 `biz_data_point.default_value` 保留兼容，但不进入运行链路，也不自动迁移为
  `APPROVED`。

### 6.2 MySQL：补全批次任务

新增 `biz_data_quality_fill_task`。该表是数据库计划书中 `data_quality_log` 概念
在当前项目里的批次化实现，但它是系统内部写入、重试和审计任务，不是等待管理员
逐条审批的业务队列。

| 字段 | 类型 | 说明 |
|---|---|---|
| `task_id` | `VARCHAR(32)` | 主键，同时写入 TDengine |
| `idempotency_key` | `VARCHAR(160)` | 确定性幂等键 |
| `building_id` | `VARCHAR(32)` | 建筑范围 |
| `point_id` | `VARCHAR(32)` | 标准测点 ID |
| `start_minute` | `DATETIME(3)` | 批次起点，包含 |
| `end_minute` | `DATETIME(3)` | 批次终点，不包含 |
| `minute_count` | `INT` | 批次分钟数 |
| `data_quality` | `TINYINT` | 只允许 `1/2` |
| `source_type` | `VARCHAR(30)` | `INTERPOLATION/TYPICAL_VALUE` |
| `algorithm_version` | `VARCHAR(32)` | 插值或典型值应用算法版本 |
| `evidence_json` | `JSON` | 左右端点或典型值来源证据 |
| `typical_config_id` | `VARCHAR(32)` | 质量 `2` 配置 ID |
| `typical_config_version` | `INT` | 质量 `2` 配置版本 |
| `apply_status` | `VARCHAR(20)` | 技术应用状态 |
| `applied_count` | `INT` | 已成功写入分钟数 |
| `failed_count` | `INT` | 当前失败分钟数 |
| `replaced_count` | `INT` | 已被更高质量替换数 |
| `voided_count` | `INT` | 已人工作废数 |
| `failed_minutes_json` | `JSON` | 最多一个小时内的失败分钟和错误 |
| `retry_count` | `INT` | 重试次数 |
| `last_error` | `VARCHAR(1000)` | 最近错误 |
| `generated_at` | `DATETIME(3)` | 任务生成时间 |
| `closed_at` | `DATETIME(3)` | 小时批次或缺口收口时间 |
| `void_by` | `BIGINT` | 异常作废操作人 |
| `void_reason` | `VARCHAR(500)` | 强制必填的作废原因 |
| `void_at` | `DATETIME(3)` | 作废时间 |
| `supersedes_task_id` | `VARCHAR(32)` | 明确重新生成时关联旧任务 |
| `create_time` | `DATETIME(3)` | 创建时间 |
| `update_time` | `DATETIME(3)` | 更新时间 |

技术状态只保留：

```text
WAITING
APPLIED
FAILED
REPLACED
VOIDED
```

不设置逐条 `review_status`、审核人和审核意见。管理员日常不批准或拒绝补全任务；
少量异常通过“作废并重算”处理。

批次中只有部分分钟被替换或作废时，任务仍保持 `APPLIED`，由
`replaced_count/voided_count` 表达进度。全部被高质量替换后变为 `REPLACED`；
明确整批作废后变为 `VOIDED`。

约束与索引：

- 主键 `task_id`；
- 唯一键 `idempotency_key`；
- 范围查询索引 `(building_id, start_minute, end_minute)`；
- 点位追溯索引 `(point_id, start_minute, end_minute)`；
- 异常队列索引 `(apply_status, update_time)`。

### 6.3 批次颗粒

质量 `1`：

```text
一个连续缺失区间 = 一个任务
```

同一任务保存左右质量 `0` 端点、值、时间和算法版本，一次批量写入最多 5 个插值
分钟。

质量 `2`：

```text
同一测点 + 同一典型值配置版本 + 同一自然小时 = 一个任务
```

每个分钟仍立即写入 TDengine 并触发公式，不等待小时结束。成功分钟不逐分钟更新
MySQL 计数；小时结束后通过 `quality_task_id` 一次核对并收口任务。只有写入失败时
立即记录失败分钟。

小时任务第一次成功写入时由 `WAITING` 变为 `APPLIED`，后续成功分钟不再更新
MySQL；收口时一次性重建实际分钟段、`minute_count` 和各类计数。一个小时内同一
配置发生多段离线时仍复用同一任务，实际分钟段压缩保存在 `evidence_json`，不能
把范围内未补全的分钟计入 `minute_count`。

一栋楼 19 个测点全部离线的最坏新增行数由每分钟 27,360 行/天降低为最多
456 个质量 `2` 小时批次/天。

### 6.4 TDengine

不新增时序表。现有 `st_raw_minute` 只增加：

```sql
quality_task_id NCHAR(32)
```

语义：

```text
质量0：quality_task_id=NULL
质量1/2：quality_task_id=对应MySQL补全批次task_id
```

普通历史查询和公式计算不需要读取该字段；来源详情和跨库恢复才使用它。

补全分钟字段约定：

- `avg_val/min_val/max_val/val` 均为生成值；
- `sample_count=0`，明确表示没有真实样本；
- `first_received_time/last_received_time=NULL`，不得伪造设备接收时间；
- `finalized_at` 为该版本生成时间；
- `quality_task_id` 为来源批次。

现有 5/30 分钟降采样不能继续仅用 `sample_count` 作为权重，否则补全分钟会被除外。
平均值改为：

```text
effectiveWeight = sample_count > 0 ? sample_count : 1
weightedAverage = SUM(avg_val * effectiveWeight) / SUM(effectiveWeight)
```

API 返回的 `sample_count` 仍为真实样本数之和，不把补全分钟伪装为真实样本。

## 7. 典型值审批

状态流转：

```text
DRAFT
→ PENDING
→ APPROVED / REJECTED
→ DISABLED
```

业务规则：

- 能效管理员只能维护已授权建筑；
- 平台管理员批准、拒绝和停用；
- 创建人不能批准自己创建的配置；
- 典型值必须通过测点上下限校验；
- 同一测点不得存在有效期重叠的已批准配置；
- 有效期按目标 `minute_start` 判断，采用 `[valid_from, valid_to)`；
- 停用只影响后续分钟，不自动删除历史结果；
- 若历史配置错误，管理员显式选择影响范围执行“作废并重算”；
- 运行缓存刷新失败继续使用上一份完整快照；
- 应用启动后从未成功加载配置时禁止生成质量 `2`。

## 8. 写入优先级与幂等

写入前读取目标分钟当前 `data_quality` 和 `quality_task_id`：

```text
目标不存在
→ 允许写入

当前质量2，新质量1
→ 允许覆盖

当前质量1/2，新质量0
→ 允许覆盖

当前质量0，新质量1/2
→ 拒绝覆盖

相同task_id
→ 幂等成功

同级不同task_id
→ 默认拒绝，除非新任务supersedes旧任务
```

V1 单实例使用“测点 ID + 分钟”细粒度进程锁保护比较与写入。不得引入全局大锁。
多实例部署后才考虑 Redis 分布式锁。

## 9. MySQL 与 TDengine 一致性

不引入分布式事务，采用状态机、确定性幂等键和补偿：

```text
MySQL创建或复用任务
→ WAITING
→ 写TDengine
→ 发布质量完成事件
→ 小时/缺口收口时核对计数
```

故障处理：

| 故障 | 处理 |
|---|---|
| MySQL 成功，TDengine 失败 | 记录失败分钟，状态 `FAILED`，定时重试 |
| TDengine 成功，MySQL 未收口 | 重试发现相同 `quality_task_id`，补记成功，不重复写 |
| 分钟成功，公式失败 | 分钟不回滚，复用现有公式恢复任务 |
| 配置缓存刷新失败 | 使用上一份完整快照 |
| 任务重试时已有更高质量 | 标记对应分钟已替换，不降级覆盖 |

作废时必须先比较 TDengine 当前 `quality_task_id`。只有仍引用目标任务的分钟才允许
撤销；已被质量 `0/1` 或新任务替换的分钟只更新计数和审计信息。

## 10. API 与权限

### 10.1 典型值配置

```text
GET  /iot/data-quality/typical-values
GET  /iot/data-quality/typical-values/{configId}
POST /iot/data-quality/typical-values
PUT  /iot/data-quality/typical-values/{configId}
POST /iot/data-quality/typical-values/{configId}/submit
POST /iot/data-quality/typical-values/{configId}/approve
POST /iot/data-quality/typical-values/{configId}/reject
POST /iot/data-quality/typical-values/{configId}/disable
```

### 10.2 补全任务与修正

```text
GET  /iot/data-quality/fill-tasks
GET  /iot/data-quality/fill-tasks/{taskId}
POST /iot/data-quality/fill-tasks/{taskId}/retry
POST /iot/data-quality/fill-tasks/{taskId}/void-and-recalculate
POST /iot/data-quality/recalculate
```

所有列表接口必须提供分页，并允许按建筑、测点、来源、质量、技术状态和时间范围
筛选。时间范围统一使用半开区间。

### 10.3 权限

| 操作 | 建筑业主 | 能效管理员 | 平台管理员 |
|---|:---:|:---:|:---:|
| 查看本建筑典型值 | 只读 | 允许 | 允许 |
| 查看本建筑补全记录 | 只读 | 允许 | 允许 |
| 创建、修改草稿 | 禁止 | 允许 | 允许 |
| 提交典型值审批 | 禁止 | 允许 | 允许 |
| 批准、拒绝、停用 | 禁止 | 禁止 | 允许 |
| 重试失败任务 | 禁止 | 禁止 | 允许 |
| 作废并重算 | 禁止 | 禁止 | 允许 |
| 指定范围重算 | 禁止 | 禁止 | 允许 |

第三方开发角色不开放生产数据修正权限。能效管理员和建筑业主的读取与写入均需要
`BuildingScopeService` 校验建筑范围。

错误状态：

- 请求字段、时间范围或作废原因非法：`400`；
- 无建筑权限：`403`；
- 配置、任务或测点不存在：`404`；
- 非法状态流转、自审或有效期冲突：`409`；
- TDengine 不可用导致即时操作无法完成：`503`；
- 后台自动任务失败不静默吞掉，写入任务错误并记录日志。

## 11. 性能设计

### 11.1 正常质量 0 热路径

- 不增加逐测点 MySQL 查询；
- 活动测点和典型值使用原子替换的不可变内存快照；
- 输入完整时不创建补全任务；
- 公式只在质量处理完成后计算一次。

### 11.2 质量 1

- 按建筑和时间范围一次批量读取候选分钟；
- 禁止逐测点、逐分钟 N+1 查询；
- 一个连续缺口一次批量写入；
- 每个被修正分钟只触发一次公式重算。

### 11.3 质量 2

- 同一小时复用同一个任务；
- 每个分钟立即写 TDengine，不等待小时收口；
- 成功计数按小时核对，不逐分钟更新 MySQL；
- 失败分钟立即记录，支持小范围重试。

### 11.4 查询隔离

历史趋势和公式查询继续只访问 TDengine。只有来源详情、典型值管理、补全任务管理
和恢复扫描访问 MySQL，不执行 MySQL 与 TDengine 的数据库联表。

## 12. 自动化测试

### 12.1 质量 1

- 缺失 1 分钟正确插值；
- 连续缺失 5 分钟正确插值；
- 5 个缺失分钟的查询边界无少查一分钟问题；
- 超过 5 分钟拒绝插值；
- 左右端点不是质量 `0` 时拒绝；
- 非连续模拟量和非计算测点禁止；
- 结果超量程拒绝；
- 不覆盖质量 `0/1`；
- 连续缺口只创建一个任务；
- 多分钟一次批量写入并逐分钟定向重算。

### 12.2 质量 2

- 已批准且有效配置立即补值；
- 草稿、待审、拒绝、停用和过期配置不能补值；
- 有效期采用半开区间；
- 有效期重叠审批失败；
- 创建人不能自审；
- 同一小时复用任务，新小时创建新任务；
- 配置版本变化创建新任务；
- `sample_count=0` 且接收时间为空；
- 无合法典型值时严格记录缺失并跳过公式。

### 12.3 质量升级

- `2→1`；
- `2→0`；
- `1→0`；
- 旧任务不删除并正确累计替换数；
- 低质量不能覆盖高质量；
- 只重算受影响分钟和指标；
- 修正后公式缺失会删除旧成功指标并保存异常。

### 12.4 跨库失败和恢复

- MySQL 成功、TDengine 失败可重试；
- TDengine 成功、MySQL 未收口可恢复；
- 相同任务重复执行保持幂等；
- 作废旧任务不能误删新质量数据；
- 任务失败不触发公式；
- 公式失败复用既有恢复机制；
- 小时收口可以从 TDengine `quality_task_id` 重建计数。

### 12.5 查询与权限

- 三类业务角色的读取和修改边界；
- 第三方开发角色拒绝；
- 建筑范围越权返回 `403`；
- 非法状态流转返回 `409`；
- 作废和重算缺少原因返回 `400`；
- 列表分页和半开时间范围；
- 5/30 分钟降采样包含质量 `1/2`，但不伪增真实样本数。

### 12.6 回归

- 运行质量模块定向测试；
- 运行分钟聚合、公式、查询、权限定向测试；
- 运行完整 `mvn test`；
- 普通自动化测试使用 H2、Mock 或 Fake，不连接真实 MySQL、TDengine、MQTT 和 Redis；
- Docker 环境可用后执行 MQTT→分钟冻结→质量补全→公式→TDengine/Redis/API/
  WebSocket 真实冒烟；环境不可用时如实记录，不把未执行写成通过。

## 13. 配置项

```yaml
data-quality:
  # false时不创建质量补全调度器和管理API；真实质量0链路继续运行。
  enabled: true
  interpolation:
    # 允许线性插值的最大连续缺失分钟数，不是首次结果等待时间。
    max-gap-minutes: 5
  # 迟到真实数据自动替换质量1/2的最大历史范围。
  late-real-correction-hours: 24
  # 已批准典型值配置的内存快照刷新间隔。
  typical-config-refresh-ms: 60000
  # 技术失败任务的低频重试间隔。
  retry-delay-ms: 600000
  # 小时批次收口和计数核对开关；测试环境可以关闭。
  reconciliation-enabled: true
```

启动初始化、后台调度和外部资源访问均需要测试配置可关闭。

## 14. 本期范围

本期实现：

- 两张 MySQL 表和迁移；
- `st_raw_minute.quality_task_id` 增量迁移；
- 质量 `1/2` 生成、批次追溯、幂等应用和恢复；
- 质量 `2→1→0` 修正；
- 典型值配置审批 API；
- 补全任务查询、失败重试、作废并重算和指定范围重算 API；
- 角色、建筑范围和自动化测试；
- 对现有分钟、公式和查询链路的必要适配。

本期不实现：

- Vue 管理页面；
- 通用规则 DSL；
- 任意数据类型插值；
- 未审批默认值自动迁移；
- 多实例分布式锁；
- 跨库分布式事务；
- 系统级 COP、控制和联合调适；
- 计费、结算或监管数据认证。

## 15. 验收标准

1. 分钟越过冻结边界后立即生成当前最佳结果，不等待未来 5 分钟。
2. 缺少真实输入且存在合法典型值时生成质量 `2`。
3. 未来最多 5 个连续缺失分钟形成质量 `0` 左右端点后升级为质量 `1`。
4. 24 小时内迟到的目标分钟真实数据可以升级为质量 `0`。
5. `0 > 1 > 2` 在正常、重试、作废和并发路径始终成立。
6. 原始事件只保存质量 `0`，补全数据不污染原始证据。
7. 质量 `1/2` 均可通过 `quality_task_id` 追溯 MySQL 批次证据。
8. 典型值未经批准、已过期或已停用时不能生成质量 `2`。
9. 质量 `1` 按连续缺口建任务，质量 `2` 按测点、配置版本和小时建任务。
10. 正常成功分钟不逐分钟更新 MySQL 批次计数。
11. MySQL/TDengine 任一阶段失败均可幂等恢复，不产生低质量覆盖。
12. 历史修正只影响目标分钟和依赖指标。
13. 作废后失效的旧成功指标不会残留。
14. 普通历史查询、公式计算和质量 `0` 热路径没有新增 MySQL 查询。
15. 后端 API 权限、建筑范围、状态码和必填原因符合本设计。
16. 完整自动化测试通过，未执行的真实基础设施测试如实记录。
