# HVAC 最新指标 TDengine 回退查询修复设计

## 1. 背景

HVAC 最新指标接口优先读取 Redis；缓存未命中或 120 秒 TTL 到期后，批量回查
TDengine 的成功指标与公式异常。真实 Docker 冒烟发现，TDengine 3.2.3 中现有
`PARTITION BY indicator_id ORDER BY ts DESC LIMIT 1` 查询只返回一个指标分区，
导致其余已持久化指标被接口错误组装为 `NO_DATA`。

当前真实数据已经证明：

- `st_indicator_minute` 同时存在四个指标的最近成功行；
- Redis 过期后，最新接口只返回一个 `SUCCESS`；
- 同一条分区排序查询在 CLI 中也只返回一行；
- 按指标身份分区的 `LAST_ROW` 查询在 TDengine 3.2.3 中返回四行。

## 2. 目标

修复 Redis 未命中时的 TDengine 批量最新值查询，使每个请求指标最多返回一条
最新成功记录和一条最新异常记录，并保持现有 Service 合并、权限、DTO、缓存和
公式逻辑不变。

## 3. 方案比较

### 方案 A：使用 `LAST_ROW` 按完整指标身份分区

分别对成功表和异常表执行一次批量查询，以
`indicator_id`、`indicator_code`、`building_id`、`system_group_id`、`equip_id`
分区，对普通数据列使用 `LAST_ROW` 并保留现有列别名。

优点：

- 已在当前 TDengine 3.2.3 容器验证；
- 语义直接表达“每个指标取最后一行”；
- 仍是一次批量查询；
- 不修改现有 JDBC 映射和 Service 合并逻辑；
- 不要求升级数据库。

缺点：

- 成功和异常 SQL 需要分别维护明确的最新行投影。

### 方案 B：升级 TDengine 后保留现有 SQL

优点是生产代码改动少。缺点是扩大为数据库升级任务，需要验证数据卷、JDBC、
DDL 和全部查询兼容性；并且仍需为最新值语义补充真实数据库回归测试。

### 方案 C：逐指标执行 `ORDER BY ts DESC LIMIT 1`

能够绕过当前分区查询问题，但会把一次批量查询退化为 N 次数据库往返，不符合
未来多建筑扩展要求。

采用方案 A。

## 4. 查询设计

### 4.1 最新成功结果

查询保留 `IndicatorMinuteResult` 所需全部列：

```sql
SELECT indicator_id,
       indicator_code,
       building_id,
       system_group_id,
       equip_id,
       LAST_ROW(ts) AS ts,
       LAST_ROW(val) AS val,
       LAST_ROW(data_quality) AS data_quality,
       LAST_ROW(formula_version) AS formula_version,
       LAST_ROW(calculated_at) AS calculated_at
FROM iot_telemetry.st_indicator_minute
WHERE indicator_id IN (...)
PARTITION BY indicator_id,
             indicator_code,
             building_id,
             system_group_id,
             equip_id
```

`LAST_ROW` 按 TDengine 主时间戳选择每个分区的最后一行。完整身份字段同时参与
分区，既满足 TDengine 聚合查询规则，也避免同一内部 ID 出现异常元数据时被
静默合并。Java 层继续按 `indicatorId` 排序，数据库返回顺序不作为契约。

### 4.2 最新异常结果

异常表采用同样的指标身份分区，对异常数据列使用 `LAST_ROW`：

```sql
SELECT indicator_id,
       indicator_code,
       building_id,
       system_group_id,
       equip_id,
       LAST_ROW(ts) AS ts,
       LAST_ROW(calc_status) AS calc_status,
       LAST_ROW(reason_code) AS reason_code,
       LAST_ROW(missing_inputs) AS missing_inputs,
       LAST_ROW(formula_version) AS formula_version,
       LAST_ROW(calculated_at) AS calculated_at
FROM iot_telemetry.st_formula_calc_exception
WHERE indicator_id IN (...)
PARTITION BY indicator_id,
             indicator_code,
             building_id,
             system_group_id,
             equip_id
```

同一指标、同一来源分钟使用相同 TDengine 主时间戳，重试会覆盖该分钟异常行，
因此最新主时间戳对应最终保留的异常记录，不需要再按 `calculated_at` 二次截断。

## 5. 代码边界

修改：

- `TdengineIndicatorMinuteRepository`：新增成功/异常最新行 SELECT 投影和共用身份
  分区片段，替换两条存在问题的排序截断查询。
- `TdengineIndicatorMinuteRepositoryTest`：验证生成 SQL 使用 `LAST_ROW`、完整身份
  分区和稳定列别名，并验证空 ID 列表不访问 TDengine。

不修改：

- 四个公式策略及输入校验；
- TDengine 表结构和历史数据；
- Redis TTL、缓存键和单调更新规则；
- Controller、Service、DTO 与权限逻辑；
- Docker 镜像版本；
- 当前环境端口配置。

## 6. 错误处理与兼容性

- TDengine 查询异常仍由现有 Service 转换为 `503`，不改变错误边界。
- 空指标列表仍直接返回空集合。
- 查询返回顺序仍由 Java 按 `indicatorId` 稳定排序。
- Redis 命中路径不受影响。
- 历史查询和指定分钟计算详情继续使用原有精确时间查询。
- 本修复不迁移、不删除、不覆盖任何历史 TDengine 数据。

## 7. 测试与验收

### 自动化测试

1. 先修改测试，使旧 SQL 因缺少 `LAST_ROW` 和完整身份分区而失败。
2. 实现最小查询修改，使仓储定向测试通过。
3. 执行完整 `mvn test`，要求零失败、零错误、无跳过项。

### Docker 回归

1. 使用现有四项成功指标数据启动修复后的后端。
2. 删除四个已知 Redis 最新指标键，强制走 TDengine 回退。
3. 调用建筑最新指标接口，确认四项均为 `SUCCESS`，值、质量和公式版本正确。
4. 运行省略 `PUMP1_Power` 的独立分钟场景。
5. 确认该分钟只有 `PUMP_EFF` 为 `MISSING_INPUT`，异常表包含 `Pc/PPE`，另外
   三项继续成功。
6. 再次清除 Redis 最新键，确认异常回退路径仍能返回水泵最新失败状态。
7. 连接 WebSocket 后重新发布一轮数据，确认实时消息不受查询修复影响。

## 8. 完成标准

- 成功和异常最新查询均不再使用
  `PARTITION BY ... ORDER BY ... LIMIT 1`；
- Redis 未命中时，四个活动指标均从 TDengine 得到正确最新状态；
- 缺失水泵功率时，成功表、异常表、Redis、API 和 WebSocket 状态一致；
- 自动化测试和 Docker 回归全部通过；
- 提交不包含 `outputs/` 或其他无关文件。
