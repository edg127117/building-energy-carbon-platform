# HVAC 人工重算批次设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 1. 背景

系统已经具备以下自动数据质量能力：

- 真实分钟数据校验为质量 0；
- 五分钟以内的短缺口线性插值为质量 1；
- 长期缺失使用已批准典型值生成质量 2；
- 迟到真实数据按照 `Q0 > Q1 > Q2` 升级历史分钟；
- 公式结果随分钟质量升级定向失效并重新计算；
- MySQL 补全任务记录单一 Q1 或 Q2 批次的来源证据、应用状态和技术重试信息。

管理员仍需要处理少量异常情况，例如典型值配置错误、测点绑定错误、传感器
Q0 后来被确认无效、算法缺陷或跨库状态长期不一致。这类操作需要作废既有结果
或重算一段历史数据。

一次人工重算可能同时得到 Q0、Q1、Q2 和仍然缺失的分钟。现有
`biz_data_quality_fill_task` 一行只能表达一种生成来源和一种质量，不能真实表达
整个混合重算操作，也没有地方保存范围重算原因、执行进度和失败游标。因此人工
操作需要独立的低频批次模型。

## 2. 目标

本设计实现以下能力：

1. 平台管理员可以对一个补全任务执行“作废并重算”。
2. 平台管理员可以指定建筑、测点和半开时间范围执行批量重算。
3. 重算按 60 分钟分块在后台异步执行，服务重启或外部资源失败后可以续跑。
4. 同一范围内分别保留 Q0、Q1、Q2 和缺失结果，不伪造混合来源证据。
5. 每次人工操作都能追溯操作人、原因、范围、进度、结果统计和失败原因。
6. 仍然缺失的分钟也会触发公式权威修正，删除已经失效的旧成功结果。
7. 新能力不进入 MQTT、分钟冻结或普通 TDengine 查询热路径。

## 3. 非目标

- 不增加管理员逐条批准或拒绝补全分钟的流程。
- 不在 MySQL 中保存每分钟重算明细。
- 不改变 TDengine 正式分钟表结构。
- 不允许人工重算降低已有分钟质量。
- 不把自动迟到数据修正改造成通用历史批处理框架。
- 不在 V1 引入分布式任务平台或微服务。

## 4. 方案选择

### 4.1 采用方案：独立重算批次表

新增一张低频 MySQL 表记录人工操作，每次管理员请求最多新增一行。重算中产生
的 Q1/Q2 仍写入现有补全任务表，并通过可空外键语义字段关联批次。Q0 直接写入
TDengine，缺失不创建虚假补全任务，二者数量记录在批次汇总字段中。

该方案能够表达混合结果、保存原因和游标，并保持补全任务证据单一、真实。

### 4.2 未采用：把整次重算写成一条 REGEN 补全任务

一条补全任务只能持有 Q1 插值证据或 Q2 典型值证据，无法同时表达 Q0、Q1、Q2
和缺失。增加一个没有真实来源证据的 REGEN 类型会破坏现有审计与恢复不变量，
因此不采用。

### 4.3 未采用：不保存批次，只同步执行 API

长范围处理可能超过 HTTP 超时，服务重启后无法续跑，范围重算原因和处理结果也
无法审计，因此不采用。

## 5. 数据模型

### 5.1 新表 `biz_data_quality_recalc_job`

| 字段 | 类型建议 | 约束与用途 |
|---|---|---|
| `job_id` | `VARCHAR(32)` | 主键 |
| `idempotency_key` | `VARCHAR(160)` | 唯一；吸收完全相同的重复请求 |
| `job_type` | `VARCHAR(30)` | `VOID_AND_RECALCULATE` 或 `RANGE_RECALCULATE` |
| `building_id` | `VARCHAR(32)` | 重算建筑 |
| `point_ids_json` | `JSON` | 规范化、去重并排序后的测点 ID |
| `from_minute` | `DATETIME(3)` | 半开区间起点 |
| `to_minute` | `DATETIME(3)` | 半开区间终点 |
| `supersedes_task_id` | `VARCHAR(32)` | 被作废的旧任务；范围重算为空 |
| `reason` | `VARCHAR(500)` | 管理员操作原因 |
| `operator_id` | `BIGINT` | 操作人 |
| `status` | `VARCHAR(20)` | `WAITING/RUNNING/SUCCEEDED/FAILED` |
| `phase` | `VARCHAR(20)` | `VOIDING` 或 `RECALCULATING` |
| `cursor_minute` | `DATETIME(3)` | 下一块起点；成功分块不会重复执行 |
| `void_target_minutes_json` | `JSON` | 删除前冻结的旧任务所属分钟，最多一个自然小时 |
| `q0_count` | `INT` | 本批次确认或生成的 Q0 分钟数 |
| `q1_count` | `INT` | 本批次生成或保留的 Q1 分钟数 |
| `q2_count` | `INT` | 本批次生成或保留的 Q2 分钟数 |
| `missing_count` | `INT` | 最终仍缺失的分钟数 |
| `voided_count` | `INT` | 实际删除的旧任务分钟数 |
| `replaced_count` | `INT` | 已被其他合法数据替换、未删除的分钟数 |
| `last_error` | `VARCHAR(1000)` | 面向运维的失败摘要，不保存内部堆栈 |
| `started_at` | `DATETIME(3)` | 首次开始时间 |
| `finished_at` | `DATETIME(3)` | 成功时间 |
| `create_time` | `DATETIME(3)` | 创建时间 |
| `update_time` | `DATETIME(3)` | 更新时间 |

索引：

- 唯一索引 `uk_recalc_idempotency(idempotency_key)`；
- 执行扫描索引 `idx_recalc_status_cursor(status, update_time, job_id)`；
- 重叠检查索引
  `idx_recalc_building_range(building_id, status, from_minute, to_minute)`；
- 旧任务查询索引 `idx_recalc_supersedes(supersedes_task_id, create_time)`。

计数字段以“测点分钟”为单位。例如两个测点各处理十分钟，最多累计二十个结果。

### 5.2 扩展 `biz_data_quality_fill_task`

增加可空字段：

```text
recalc_job_id VARCHAR(32)
```

并增加普通索引 `idx_fill_recalc_job(recalc_job_id, task_id)`。

该字段只关联人工重算产生的 Q1/Q2 子任务。自动冻结、自动插值和自动典型值任务
保持为空，不增加热路径查询。

`supersedes_task_id` 继续表示新的补全任务替代哪个旧任务；它不承担整次人工操作
的进度和汇总职责。

### 5.3 不修改 TDengine 表

TDengine 继续只保存正式分钟的当前值、质量等级和当前 `quality_task_id`。人工
操作批次属于 MySQL 管理数据，不写入每分钟 TDengine 行。

## 6. 幂等和并发规则

### 6.1 请求幂等

`idempotency_key` 由规范化请求生成：

- 作废并重算：`VOID_RECALC:` 加旧任务 ID；
- 范围重算：对建筑、排序后的测点集合、起止分钟、trim 后原因和操作人组成的
  规范化 JSON 计算 SHA-256，格式为 `RANGE_RECALC:{hash}`。

完全相同请求复用已有批次：

- `WAITING/RUNNING`：直接返回当前批次；
- `FAILED`：将同一批次重新置为 `WAITING`，保留 `cursor_minute` 续跑；
- `SUCCEEDED`：返回已完成结果，不重复破坏历史数据。

管理员需要再次执行相同范围时必须提供不同的操作原因，使其成为新的可审计请求。

### 6.2 重叠范围互斥

创建或恢复任务前，在 MySQL 事务内查询同一建筑的 `WAITING/RUNNING` 批次。
只要测点集合有交集且时间满足：

```text
existing.to_minute > request.from_minute
AND existing.from_minute < request.to_minute
```

即视为重叠。完全相同的请求复用原批次；其他重叠请求返回 `409`。

V1 使用 MySQL 行锁和状态条件更新保护单实例/多实例竞争，不使用 JVM 本地锁作为
唯一正确性保证。

## 7. 状态机

```text
WAITING → RUNNING → SUCCEEDED
   ↑         |
   └─ FAILED ←
```

- `WAITING`：已受理，等待后台任务领取。
- `RUNNING`：正在处理 `cursor_minute` 指向的分块。
- `FAILED`：当前分块失败，保存错误和未推进的游标。
- `SUCCEEDED`：所有分块完成，写入 `finished_at`。

领取任务必须使用条件更新，只允许一个执行者把 `WAITING` 改为 `RUNNING`。进程
在 `RUNNING` 状态退出后，恢复调度器根据超时水位把任务重新置为 `WAITING`。

成功完成一个分块后，在 MySQL 事务中原子累计计数并推进 `cursor_minute`。当前
分块任何 TDengine、公式事件或 MySQL 收口失败时都不推进游标。

若进程在 TDengine 已写入但游标推进前退出，下一轮会重做同一分块。Q0 质量优先
写、Q1/Q2 确定性任务键和 READY 权威修正必须保持幂等，因此重复分块不会降级
分钟、创建重复任务或永久保留旧公式成功结果。

## 8. 作废旧任务

“作废并重算”创建批次前读取旧补全任务，并执行以下校验：

- 任务存在；
- 当前用户是平台管理员；
- 任务不是另一个运行中作废批次的目标；
- 任务点位、建筑和时间范围完整；
- 已经成功完成的相同请求直接返回原批次。

执行首个分块前按旧任务的点位和 `[start,end)` 读取当前正式分钟：

1. 当前 `quality_task_id == oldTaskId`：先把分钟集合写入批次的
   `void_target_minutes_json`，再调用带锁的条件删除；
2. 当前为 Q0、Q1 或其他任务：保留并计入 `replaced_count`；
3. 当前不存在：不删除，计入旧任务的作废范围；
4. 删除接口必须返回是否实际删除，防止预读和删除之间的并发升级造成误计数。

批次在删除阶段使用 `phase=VOIDING`。若进程在 TDengine 删除成功、MySQL 收口前
退出，下一轮根据冻结目标再次核对：目标分钟不存在表示已经删除，出现其他任务
或更高质量表示已经替换，仍属于旧任务则继续条件删除。这样不需要新增逐分钟表，
也不会因跨库中断丢失作废范围。

旧任务最终以一次 MySQL 原子更新写入：

- `apply_status=VOIDED`；
- `applied_count=0`；
- `voided_count` 为冻结目标中最终被删除的分钟数；
- `replaced_count` 为当前由 Q0、Q1 或其他任务持有的分钟数；
- `failed_count` 保留旧任务已记录但从未成功写入的失败分钟数；
- `minute_count=voided_count+replaced_count+failed_count`；
- `void_by/void_reason/void_at/closed_at`。

已经被更高质量或其他合法任务持有的分钟绝不删除。

旧任务完成精确作废后，批次把 `phase` 从 `VOIDING` 改为
`RECALCULATING`，随后才处理 60 分钟重算分块。

## 9. 60 分钟分块重算

后台执行器使用：

```text
chunkStart = cursor_minute
chunkEnd = min(chunkStart + 60分钟, to_minute)
```

每块只加载块内数据，完成后再推进游标。61 分钟产生两个不重叠分块，120 分钟
也只产生两个分块。

每个分块按以下顺序执行：

为避免漏掉跨 60 分钟边界的短缺口，每块允许读取有界上下文：

```text
contextFrom = max(job.from, chunkStart - (maxGapMinutes + 1)分钟)
contextTo   = min(job.to, chunkEnd + (maxGapMinutes + 1)分钟)
```

原始事件和正式分钟仍各自批量查询一次上下文窗口，不执行逐点逐分钟查询。右侧
上下文中聚合出的 Q0 可以提前按质量优先级幂等写入，为目标块提供真实端点；Q1、
Q2、READY 和批次计数只处理目标 `[chunkStart,chunkEnd)`，因此相邻块不会重复
计算业务结果。默认最大缺口为五分钟，所以每侧最多额外读取六分钟，不会退化为
全历史加载。

### 9.1 重新聚合 Q0

- 在上述有界上下文内批量读取相关测点的原始事件；
- 复用现有聚合、数据校验、单位和量程规则；
- 人工入口不受自动迟到修正的 24 小时窗口限制；
- 仍使用点位与分钟锁，避免与自动冻结、迟到修正并发覆盖；
- 只写入证据完整、校验通过的真实 Q0。

### 9.2 生成 Q1

- 只处理没有 Q0 的分钟；
- 左右端点都必须是当前 Q0；
- 缺口不超过配置的五分钟；
- 测点允许插值；
- 结果通过量程校验；
- 每个连续缺口创建一个正常 Q1 补全任务；
- 任务填写 `recalc_job_id`，若来自作废任务则填写
  `supersedes_task_id`；
- 质量优先写只允许合法升级或明确替代旧任务。

### 9.3 生成 Q2

- 只处理仍然缺失的分钟；
- 使用该分钟当前有效且已经批准的典型值版本；
- 同点、同配置版本、同自然小时维持一个 Q2 任务；
- 人工任务使用新的幂等键，不复用已经 VOIDED 的标准任务；
- 任务填写 `recalc_job_id` 和可选 `supersedes_task_id`；
- 已有 Q0/Q1 时不覆盖。

### 9.4 保持缺失并修正公式

每个受影响的“测点分钟”无论最终是 Q0、Q1、Q2 还是缺失，都发布一次来源为
`MANUAL_RECALCULATION` 的 READY 事件。事件包含建筑和受影响点位，公式引擎
重新读取该建筑完整分钟并只计算依赖这些点位的指标。

如果输入仍缺失，公式引擎必须删除对应旧成功结果并保存明确的公式异常，避免
管理界面继续显示已经失效的历史指标。

## 10. 后台调度

新增低频恢复调度器，仅在 `data-quality.enabled=true` 时注册：

- 每轮领取固定数量的 `WAITING` 批次；
- 恢复超时的 `RUNNING` 批次；
- 每个批次一次只处理一个 60 分钟块；
- 单批次失败不阻断其他批次；
- 测试配置可以关闭调度；
- 不依赖真实 MySQL、TDengine、MQTT 或 Redis 才能启动普通单元测试。

调度器不参与正常分钟冻结热路径。

## 11. API

### 11.1 补全任务 API

```text
GET  /iot/data-quality/fill-tasks
GET  /iot/data-quality/fill-tasks/{taskId}
POST /iot/data-quality/fill-tasks/{taskId}/retry
POST /iot/data-quality/fill-tasks/{taskId}/void-and-recalculate
POST /iot/data-quality/recalculate
```

- GET：建筑业主、能效管理员和平台管理员可调用；Service 再校验建筑范围。
- retry、void-and-recalculate、recalculate：只允许平台管理员。
- 两个重算 POST 完成参数和并发校验后立即返回批次，不等待后台完成。

### 11.2 重算批次 API

```text
GET /iot/data-quality/recalculation-jobs
GET /iot/data-quality/recalculation-jobs/{jobId}
```

V1 只允许平台管理员访问。列表在 MySQL 侧分页，支持：

- `pageNum/pageSize`；
- `buildingId`；
- `jobType`；
- `status`；
- `fromInclusive/toExclusive`。

详情返回：

- 批次范围、原因、操作人和状态；
- 当前游标和开始/完成时间；
- Q0/Q1/Q2/缺失、作废和替换计数；
- 面向运维的失败摘要；
- 通过 `recalc_job_id` 查询的关联 Q1/Q2 补全任务。

## 12. 参数与错误语义

- 分页越界、空原因、空测点、时间未按分钟对齐、`from>=to`：`400`；
- 测点去空白、去重并排序后最多 100 个；`to` 不得晚于当前已结束分钟；
- 建筑越权：`403`；
- 任务、批次或测点不存在：`404`；
- 非 FAILED 任务重试、不同请求与运行中范围重叠：`409`；
- POST 已成功受理后发生的 TDengine 或公式执行失败：批次转 `FAILED`，
  不通过已经结束的 HTTP 请求返回 `503`；
- 同步创建批次时 MySQL 不可用：由统一异常处理返回服务错误。

接口不返回 JDBC、SQL、堆栈或内部表名。

## 13. 权限

- 补全任务读取：`BUILDING_OWNER/ENERGY_MANAGER/PLATFORM_ADMIN`；
- 补全任务写操作：`PLATFORM_ADMIN`；
- 重算批次读取：`PLATFORM_ADMIN`；
- `THIRD_PARTY` 全部拒绝。

Controller 使用 `@PreAuthorize` 控制入口，Service 仍使用正式角色集合和
`BuildingScopeService` 执行二次校验，防止内部调用绕过权限。

普通角色的补全任务列表必须在 MySQL 分页前按建筑范围过滤。空建筑集合表示没有
权限，SQL 必须返回空结果；只有平台管理员使用“不限制建筑”的查询语义。

## 14. 测试

### 14.1 数据模型和仓储

- 批次幂等创建与重复请求复用；
- FAILED 批次保留游标恢复；
- 重叠测点和时间范围返回冲突；
- 不同测点或不重叠范围允许并行；
- 领取、超时恢复、计数和游标推进使用条件更新；
- 补全任务可按 `recalc_job_id` 查询；
- 分页和时间范围过滤在 MySQL 中完成。

### 14.2 作废安全

- 只删除仍由旧 taskId 持有的分钟；
- 预读后发生质量升级时条件删除返回未删除；
- Q0/Q1/其他任务不删除；
- 精确重建旧任务的作废和替换计数；
- 重复作废请求返回同一批次；
- VOIDED 标准任务和 Q2 本地缓存不会被重新复活。

### 14.3 分块和质量选择

- 61 分钟和 120 分钟边界不重复；
- 中间块失败时不执行后续块；
- 重试从失败块继续；
- 人工旧历史 Q0 不受自动 24 小时窗口限制；
- 同一范围混合产生 Q0、Q1、Q2 和缺失；
- 质量优先级始终为 `Q0 > Q1 > Q2`；
- Q1/Q2 子任务保留真实来源证据和批次关联。

### 14.4 公式和 API

- 缺失分钟也发布 `MANUAL_RECALCULATION`；
- 旧公式成功结果被删除并写入异常；
- 四角色权限和建筑越权；
- 两个 POST 立即返回批次；
- 批次列表、详情、进度和关联补全任务；
- 普通测试关闭后台任务并使用 H2、Fake 或 Mock TDengine。

## 15. 性能和容量

- 重算批次表每次人工操作一行，不按分钟增长；
- 每个分块最多加载 60 分钟范围；
- 原始事件和正式分钟都按块批量查询，不执行测点乘分钟的 N+1 查询；
- 普通历史查询、公式查询和 MQTT 写入不访问重算批次表；
- `recalc_job_id` 只在管理查询和人工执行链路使用；
- 大范围任务通过后台分块处理，不占用长时间 HTTP 连接。

## 16. 迁移与兼容

- 新增 MySQL 手工迁移脚本，不挂入已有持久卷的自动初始化重放；
- 测试 H2 schema 同步增加表和字段；
- 新安装环境的基础 schema 同步更新；
- 旧补全任务的 `recalc_job_id` 为空，现有自动恢复和小时收口行为不变；
- 数据质量功能关闭时，批次 Service、执行器和调度器均不注册。

## 17. 已确认决策

1. 创建低频人工重算批次表。
2. TDengine 不增加字段或逐分钟审计表。
3. 同建筑、同测点、时间范围重叠的运行中任务禁止并发。
4. 完全相同请求复用原批次。
5. 作废和范围重算都采用后台异步分块执行。
6. 增加重算批次列表和详情接口，V1 仅平台管理员访问。
