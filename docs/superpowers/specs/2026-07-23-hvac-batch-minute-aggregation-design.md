# HVAC 整分钟批量聚合优化设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 目标

将当前“每 10 秒逐测点扫描最近 10 分钟”的分钟聚合方式，调整为：

1. 正常运行时，每个到期分钟只从 TDengine 原始事件超级表查询一次；
2. Java 按 `pointCode` 对这一分钟的全部真实事件分组；
3. 一次批量写入所有已产生数据的测点分钟结果；
4. 分钟保存成功后发布冻结事件，作为后续 COP 公式的直接输入；
5. 最近 10 分钟扫描只用于应用启动恢复和低频补漏。

本次不处理 `equip_id`、`point_code` 和 TDengine 子表的跨建筑全局唯一性。

## 保持不变的规则

- 原始事件先通过质量校验，再写入 `st_raw_event`。
- 设备采集时间 `event_time` 和服务器接收时间 `received_time` 分开保存。
- 分钟窗口严格为 `[minuteStart, minuteStart + 60 秒)`。
- 分钟结束后等待 30 秒再冻结，等待时间不扩大数据窗口。
- 正常聚合只使用 `late_flag = 0` 的事件。
- 冻结后的迟到事件仍保存原始记录，但不修改正式分钟结果。
- 真实数据质量等级固定为 0。
- 不实现插值、`default_value` 自动补值、质量等级 2 或 COP 公式。
- 原始事件保留 90 天，分钟结果长期保留。

## 已比较方案

### 方案一：持续逐点扫描

当前实现每 10 秒对全部在线测点检查最近 10 个到期分钟。可靠但会产生大量重复的存在性查询。

### 方案二：JVM 长期分钟桶

接收数据时持续维护内存状态，正常聚合不查询 TDengine。查询少，但崩溃恢复、多实例一致性和短暂双写不一致更复杂。

### 方案三：整分钟批量查询（采用）

到期后一次查询该分钟全部测点事件，临时在 Java 中分组，批量写入分钟结果。TDengine仍是事实来源，同时避免长期内存状态和逐点轮询。

## 正常运行数据流

```text
MQTT真实数据
  → 质量校验
  → st_raw_event逐条持久化

分钟到期（结束后30秒）
  → 一次查询st_raw_event该分钟全部非迟到事件
  → 过滤当前有效且ONLINE的测点配置
  → Java按pointCode分组
  → 计算avg/min/max/sampleCount/接收时间范围/最差质量
  → 一次批量写入st_raw_minute
  → 发布HvacMinuteBatchFrozenEvent
```

正常任务使用进程内水位 `lastProcessedMinute` 防止同一分钟在每次调度时被重复处理。只有批量保存成功后才推进水位；TDengine故障时保持原水位，下一次调度自动重试。

如果到期分钟没有任何有效事件，也将推进正常水位。因为超过冻结边界后到达的事件会标记为迟到，不能再进入该正式分钟。

## TDengine 查询

原始仓储新增整窗口查询：

```java
List<RawTelemetryEvent> findWindow(
        long startInclusive,
        long endExclusive,
        boolean includeLate);
```

正常聚合对应一条超级表范围查询：

```sql
SELECT ts, received_time, val, data_quality, late_flag,
       point_code, building_id, equip_id, suffix_code, is_for_calc
FROM iot_telemetry.st_raw_event
WHERE ts >= ?
  AND ts < ?
  AND late_flag = 0
ORDER BY point_code, ts;
```

本次仍按现有全局 `pointCode` 分组，不改变跨建筑编码模型。

## 分钟批量写入

分钟仓储新增：

```java
void saveAll(List<RawMinuteAggregate> aggregates);
Set<String> findExistingPointCodes(long minuteStart);
```

`saveAll` 生成一条 TDengine 多子表 `INSERT`，同一批次写入该分钟全部测点结果。子表时间戳天然唯一，因此恢复任务重复计算相同 `pointCode + minuteStart` 时执行幂等覆盖，不产生重复行。

正常任务不再逐点执行 `exists`。`findExistingPointCodes` 仅供恢复任务一次性判断某分钟已完成的测点。

## 启动恢复与低频补漏

恢复任务在以下时机执行：

- Spring Boot 应用完全启动后；
- 按配置的低频周期执行，默认每 10 分钟一次。

每次恢复最近 `catch-up-minutes`，默认 10 分钟。对每一分钟：

1. 一次查询 `st_raw_minute` 已存在的 `pointCode`；
2. 如果全部在线测点都已完成，则跳过；
3. 否则一次查询该分钟全部原始事件；
4. 只生成缺失测点的分钟结果；
5. 批量保存并发布恢复型冻结事件。

恢复任务与正常任务使用同一进程锁串行执行，避免同时处理同一分钟。

## 公式模块出口

新增不可变事件：

```java
public record HvacMinuteBatchFrozenEvent(
        long minuteStart,
        long finalizedAt,
        boolean recovery,
        List<RawMinuteAggregate> aggregates) {
}
```

事件只在分钟结果批量保存成功后发布。后续 COP 模块直接使用事件中的 `aggregates`，正常计算不再重新查询 TDengine。

本次只提供事件契约，不实现 COP 监听器。未来 COP 写入必须按 `indicatorCode + minuteStart` 幂等，以容忍恢复事件重复发布。

## 错误处理

- 原始窗口查询失败：本轮不推进正常水位，下一轮重试。
- 分钟批量保存失败：不发布冻结事件、不推进水位，下一轮重试。
- 冻结事件监听器异常：分钟数据已经成功保存，记录错误；未来公式模块可通过低频补偿或人工重算恢复。
- 单个未配置或已停用测点的原始事件：保留原始证据，但不生成正式分钟结果。
- 没有数据的在线测点：不生成伪造分钟行。

## 配置

保留：

```yaml
aggregation:
  finalization-delay-seconds: 30
  catch-up-minutes: 10
  scan-delay-ms: 10000
```

新增：

```yaml
aggregation:
  recovery-enabled: true
  recovery-delay-ms: 600000
```

`scan-delay-ms` 只负责发现新到期分钟；不会再触发最近 10 分钟逐点扫描。

## 验收标准

- 正常处理一个到期分钟时，原始仓储只调用一次整窗口查询。
- 19 个测点的数据在 Java 中正确分组并生成各自统计值。
- 一个批次只调用一次分钟仓储 `saveAll`。
- 保存成功后发布一次冻结事件，事件携带实际写入的全部分钟结果。
- 30 秒前不处理该分钟。
- 同一正常分钟不会在每 10 秒调度中重复处理。
- 保存失败后不推进水位，下一次调度会重试。
- 启动/低频恢复只补齐缺失测点，不覆盖已存在测点。
- 完整项目测试和 Spring Boot 打包通过。
