# HVAC 最新测点快照 TDengine 分区查询修复设计

## 1. 问题

`TdengineHvacMinuteRepository.findLatestByPointIds()` 当前使用：

```sql
SELECT point_id,
       ts AS bucket_time,
       avg_val AS average_value,
       min_val AS minimum_value,
       max_val AS maximum_value,
       sample_count,
       data_quality
FROM iot_telemetry.st_raw_minute
WHERE point_id IN (...)
PARTITION BY point_id
ORDER BY ts DESC
LIMIT 1
```

代码注释把 `LIMIT 1` 理解为“每个分区各取一条”。在项目使用的
TDengine 3.2.3 中，`PARTITION BY` 只负责分区，普通 `LIMIT` 仍限制最终
结果集。批量请求 19 个测点时，Repository 实际只返回一行；Service 再把其余
18 个合法测点组装成 `NO_DATA`，最终使页面只显示 5% 完整率。

真实环境对三个测点执行同形 SQL 已确认：

- `LIMIT 1`：返回 1 行；
- `LIMIT 2`：返回 2 行；
- `LAST_ROW(...) PARTITION BY point_id`：返回 3 行。

这与迟到证据查询的 `PARTITION BY ... INTERVAL(...)` 缺少聚合函数问题不是
同一条 SQL，但都属于 TDengine 3.2.3 查询语义与代码假设不一致。

## 2. 目标

- 一次 TDengine 查询返回所请求每个测点的最新分钟行；
- 保持 Controller、Service、DTO、权限和 MySQL 配置查询不变；
- 保持空测点列表不访问 TDengine；
- 在真实 TDengine 3.2.3 中确认 19 个测点全部返回 `NORMAL`；
- 防止以后重新引入 `ORDER BY ts DESC LIMIT 1`。

## 3. 方案比较

### 方案 A：`LAST_ROW` 聚合与完整测点身份分区

对时间和值字段使用 `LAST_ROW(...)`，按完整测点身份标签分区。一次查询得到
每个身份的一条最新记录。

优点：

- 与已经验证过的最新指标 TDengine 回退查询保持一致；
- 仍是一次批量查询；
- 明确表达“每个测点取最后一行”；
- 可以通过 SQL 契约测试固定兼容写法。

缺点：

- SQL 比当前实现稍长；
- 必须保证选出的列与 `mapQueryRow()` 使用的别名一致。

### 方案 B：每个测点单独查询

对每个 `point_id` 分别执行 `ORDER BY ts DESC LIMIT 1`。

优点是语义直观；缺点是一次快照产生 19 次 TDengine 往返，扩大接口延迟和
数据库压力，不适合实时页面。

### 方案 C：窗口函数或嵌套排序

使用行号窗口或每分区子查询选取最新值。

这种写法复杂，且会增加 TDengine 3.2.3 的兼容性验证范围，没有比
`LAST_ROW` 更直接的收益。

本次采用方案 A。

## 4. SQL 设计

Repository 继续接收 `List<String> pointIds`，空列表直接返回空集合。非空时
生成以下形状的查询：

```sql
SELECT point_id,
       LAST_ROW(ts) AS bucket_time,
       LAST_ROW(avg_val) AS average_value,
       LAST_ROW(min_val) AS minimum_value,
       LAST_ROW(max_val) AS maximum_value,
       LAST_ROW(sample_count) AS sample_count,
       LAST_ROW(data_quality) AS data_quality
FROM iot_telemetry.st_raw_minute
WHERE point_id IN (...)
PARTITION BY point_id, point_code, building_id, system_group_id,
             equip_id, equip_code, family_code, component_code,
             suffix_code, is_for_calc
```

分区使用完整测点身份标签，避免仅依赖一个标签的隐含唯一性，也与项目已经
验证通过的最新指标查询策略一致。Service 最终仍按 `point_id` 合并结果，对
上层接口没有行为或结构变化。

原来声称 `LIMIT 1` 会作用于每个分区的注释必须删除，替换为说明
`LAST_ROW` 和完整身份分区原因的中文注释。

## 5. 测试设计

### Repository SQL 契约

修改 `TdengineHvacMinuteRepositoryTest`，验证：

- SQL 包含六个 `LAST_ROW(...)` 值；
- SQL 包含完整测点身份 `PARTITION BY`；
- SQL 不包含 `ORDER BY ts DESC`；
- SQL 不包含 `LIMIT 1`；
- 查询结果仍由原 `mapQueryRow()` 映射；
- 空 `pointIds` 不调用 TDengine。

### 自动化回归

- 运行 `TdengineHvacMinuteRepositoryTest`；
- 运行 HVAC 查询 Service 和 Controller 定向测试；
- 运行完整 `mvn test`；
- 记录通过数量、失败、错误和跳过数量。

### 真实 TDengine 3.2.3 验证

使用已重新初始化的本地 Docker 环境：

1. 发布 7 轮、每轮 19 个 MQTT 测点；
2. 确认 `st_raw_minute` 当前数据窗口包含 19 个测点；
3. 调用 `/api/hvac/buildings/BLD001/snapshot`；
4. 确认响应包含 19 个测点且 19 个均为 `NORMAL`；
5. 确认四项最新指标均为 `SUCCESS`；
6. 浏览器确认页面测点完整率为 100%，并显示四项指标真实值。

## 6. 改动边界

本次只修改：

- `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`
- 本设计文档和后续实施计划

不修改：

- Controller、Service、DTO 和权限逻辑；
- MySQL、TDengine 表结构；
- 分钟聚合、数据质量填补和公式计算；
- Redis 最新指标逻辑；
- 历史查询；
- 当前独立前端分支。

## 7. Git 与交付

- 后端分支：`fix/hvac-latest-snapshot-partition`
- 分支基线：最新 `origin/main`
- 前端分支 `feature/hvac-v1-frontend-integration` 保持独立；
- 后端完成全部自动化和真实环境验证后再推送；
- 后端 PR 合并并重新完成前端验收后，再处理前端 PR。
