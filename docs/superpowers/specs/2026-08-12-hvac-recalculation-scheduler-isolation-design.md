# HVAC 重算调度与迟到修正隔离设计

> **文档状态：已实现并通过自动化验证，待专用本地端到端验收**
>
> 本设计只处理人工重算长期停留 `WAITING`、迟到 Q0 不完整及其公式下游补偿，
> 不修改 Gaia、MQTT 测点映射、`TOWER_EFF_V1` 公式或其他指标。

## 1. 问题与目标

当前应用存在三个相互放大的运行时风险：

1. Spring 没有显式业务 `taskScheduler`，数据库类 `@Scheduled` 任务实际进入了
   `hvacRealtimeTaskScheduler` 的单线程池；TDengine 调用阻塞时，人工重算扫描无法执行。
2. `DataQualityRecalculationScheduler` 在调度线程内同步执行完整 TDengine 分块；即使完成领取，
   长耗时分块也会阻塞后续扫描和超时任务恢复。
3. 每条迟到原始事件都提交到无界虚拟线程执行器，集中回放会瞬间竞争 TDengine 有限连接，
   造成部分 Q0、READY 事件和公式结果未完成。

修复目标：

- 人工任务在扫描周期内由 `WAITING` 原子进入 `RUNNING`；
- 扫描线程不执行 TDengine 分块，也不受其他定时任务阻塞；
- 迟到修正并发和排队容量可配置、可拒绝、可由既有低频证据扫描恢复；
- TDengine REST 连接和读取都有有界超时；
- 三个冷却塔测点的迟到 Q0 能完整触发 `TOWER_EFF_V1`。

## 2. 调度器边界

新增名称固定为 `taskScheduler` 的业务 `ThreadPoolTaskScheduler`，由 Spring
`@Scheduled` 基础设施明确选择。它使用可配置的有限线程池，线程名前缀为
`iot-business-scheduling-`。

以下现有调度器保持专用，不参与 `@Scheduled` 业务方法：

- `mqttTaskScheduler`：只负责 MQTT 首次连接重试；
- `hvacRealtimeTaskScheduler`：只负责 WebSocket 生命周期超时。

通用业务调度器承载既有 `@Scheduled` 任务；人工重算扫描再使用独立的单线程
`recalculationScanTaskScheduler`，只执行 MySQL 扫描、领取和工作提交。TDengine 人工重算
分块交给独立的有界 `recalculationJobExecutor`。不能仅把 `fixedDelay` 改成 `fixedRate`，
因为这会在慢任务下产生重叠执行。

## 3. 人工重算领取与执行

每轮扫描流程调整为：

```text
查询最多 10 个 WAITING/超时 RUNNING
→ claimAtomic 成功，任务立即变为 RUNNING
→ 提交 jobId 到 recalculationJobExecutor
→ 工作线程重新读取持久化任务并执行一个分块
→ 成功后 SUCCEEDED 或返回 WAITING
→ 异常后条件写入 FAILED
```

执行器并发数可配置且不设置 JVM 等待队列；MySQL `WAITING` 行就是持久化队列。
工作线程满导致提交被拒绝时，调度器必须把刚领取的任务安全退回
`WAITING`；不能留下无人执行的 `RUNNING`。新增回退 SQL 必须同时校验 `job_id`、
`RUNNING` 状态、领取时的 `update_time` 和游标，避免覆盖已经开始执行或被其他实例推进的任务。

多实例边界继续由 MySQL 条件领取保证，不增加 JVM 全局锁。一个分块仍最多处理一小时，
保持现有持久化游标和幂等语义。

## 4. 迟到修正并发

将无界 `virtualThreadExecutor` 替换为专用 `lateRealCorrectionExecutor`：

- 固定并发数，可通过 `data-quality.late-real-correction-concurrency` 配置；
- 有界队列，可通过 `data-quality.late-real-correction-queue-capacity` 配置；
- 拒绝时让事件发布返回异常，由接入服务记录 `late_dispatch_failed`；
- 原始事件已经在 TDengine 持久化，既有 `DataQualityRecoveryService` 继续作为最终补偿来源。

同一测点分钟仍由 `MinuteQualityLockRegistry` 串行和幂等保护。本次不引入新的持久化任务表，
避免与现有迟到证据扫描形成两套事实来源。

## 5. TDengine 超时

REST JDBC URL 增加驱动支持的：

- `httpConnectTimeout`：连接超时；
- `httpSocketTimeout`：读取超时。

两项均通过环境变量覆盖，默认值必须明显短于人工任务陈旧水位，避免连接长期占用调度或工作线程。
Hikari `connectionTimeout` 继续只限制从连接池取连接的等待时间，不冒充网络读取超时。

## 6. 错误与可观测性

- 人工扫描查询异常：记录错误，等待下一轮；
- 条件领取竞争失败：正常跳过；
- 工作线程已满：不进入 JVM 队列，条件退回 `WAITING` 并记录错误；
- 分块执行异常：进入 `FAILED`；
- 迟到修正队列满：记录拒绝计数，保留原始证据等待低频补偿；
- TDengine 超时：快速失败，不允许无限占用线程。

日志只记录任务 ID、测点 ID、分钟和固定公开摘要，不输出凭据或原始证据内容。

## 7. 测试与验收

自动化必须新增或增强：

- Spring 明确选择业务 `taskScheduler`，WebSocket/MQTT 调度器不承载业务任务；
- `WAITING` 条件领取后先进入 `RUNNING`，工作在线程池执行；
- 两次并发领取只有一个成功；
- 工作队列拒绝时任务安全恢复为 `WAITING`；
- 一个阻塞工作任务不妨碍下一轮扫描；
- 迟到执行器并发和队列配置生效；
- 三个迟到冷却塔测点形成 Q0 并产生 `TOWER_EFF_V1`；
- 执行异常进入 `FAILED` 或由持久化证据恢复。

完成定向测试后运行完整 `\.\mvnw.cmd test`。真实 MySQL、TDengine、MQTT 和 Gaia
只在专用本地验收阶段使用。最终脚本必须输出：

```text
CENTRAL_HVAC_TOWER_EFFICIENCY_VERIFIED
```

该标记只代表本地端到端联调，不代表现场验收。
