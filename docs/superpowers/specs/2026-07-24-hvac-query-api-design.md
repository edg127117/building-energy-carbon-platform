# HVAC 冻结分钟查询 API 设计

## 1. 目标

在不实现 COP/效率计算、不依赖前端的前提下，为现有 HVAC `st_raw_minute` 冻结分钟数据提供两个受权限保护的 HTTP 查询接口：

1. 按建筑查询所有已启用测点的最新快照。
2. 按建筑查询用户自主选择的 1～8 个测点的历史趋势。

本期只查询已经冻结的分钟汇总数据，不查询逐条原始事件，不返回或伪造 COP 指标。

## 2. 范围

### 2.1 本期包含

- 建筑最新测点快照。
- 1～8 个测点的批量历史趋势。
- 最大 31 天查询跨度。
- 根据查询跨度自动选择 1、5、30 分钟分辨率。
- 建筑角色和数据范围权限校验。
- 测点归属校验。
- TDengine 查询与统一 DTO 映射。
- 不依赖前端的 Service、Controller、Repository 自动化测试。

### 2.2 本期不包含

- COP、冷却塔效率、水泵效率、AHU 单位风量耗功计算。
- `st_indicator_minute` 指标查询。
- `st_raw_event` 逐条原始事件查询。
- 前端页面或图表接入。
- API、Modbus、BACnet 数据采集适配器。
- 数据导出。
- 用户自定义分辨率。

## 3. 对外接口

所有响应继续使用项目统一的 `Result<T>` 包装。

### 3.1 建筑最新快照

```http
GET /api/hvac/buildings/{buildingId}/snapshot
Authorization: Bearer <token>
```

行为：

- 查询该建筑所有状态为 `ONLINE` 的已配置测点。
- 每个测点返回其各自最新的一条冻结分钟数据。
- 不要求所有测点来自同一分钟。
- 已配置但没有分钟数据的测点仍然返回，状态为 `NO_DATA`。
- 测点按 `pointCode` 升序返回，保证无数据情况下也有稳定顺序。

响应示例：

```json
{
  "code": 200,
  "msg": "操作成功",
  "success": true,
  "data": {
    "buildingId": "BLD001",
    "generatedAt": 1784860200000,
    "points": [
      {
        "pointId": "POINT_WCR_TWIN",
        "pointCode": "WCR1_TWin",
        "pointName": "冷冻水进水温度",
        "equipId": "EQUIP_WCR_B1",
        "equipCode": "WCR1",
        "unit": "℃",
        "minute": 1784858400000,
        "average": 12.3,
        "minimum": 12.1,
        "maximum": 12.5,
        "sampleCount": 6,
        "dataQuality": 0,
        "status": "NORMAL"
      },
      {
        "pointId": "POINT_WCR_FLOW",
        "pointCode": "WCR1_Flow",
        "pointName": "冷冻水流量",
        "equipId": "EQUIP_WCR_B1",
        "equipCode": "WCR1",
        "unit": "m³/h",
        "minute": null,
        "average": null,
        "minimum": null,
        "maximum": null,
        "sampleCount": 0,
        "dataQuality": null,
        "status": "NO_DATA"
      }
    ]
  }
}
```

### 3.2 多测点历史趋势

```http
GET /api/hvac/buildings/{buildingId}/history
    ?pointIds=POINT_001,POINT_002
    &from=1782172800000
    &to=1784851200000
Authorization: Bearer <token>
```

参数约束：

- `pointIds` 必填，使用逗号分隔。
- 去除空白并按首次出现位置去重。
- 去重后必须包含 1～8 个测点。
- 所有 `pointId` 必须属于路径中的 `buildingId`，且测点状态为 `ONLINE`。
- `from`、`to` 为 Unix 毫秒时间戳。
- 必须满足 `from < to`。
- `to - from` 不得超过连续 31 天，即 `2,678,400,000` 毫秒。
- 返回序列顺序与去重后的 `pointIds` 输入顺序一致。
- 某个合法测点没有历史数据时仍返回该序列，但 `records=[]`。

响应示例：

```json
{
  "code": 200,
  "msg": "操作成功",
  "success": true,
  "data": {
    "buildingId": "BLD001",
    "from": 1782172800000,
    "to": 1784851200000,
    "resolutionMinutes": 30,
    "series": [
      {
        "pointId": "POINT_WCR_TWIN",
        "pointCode": "WCR1_TWin",
        "pointName": "冷冻水进水温度",
        "unit": "℃",
        "records": [
          {
            "time": 1782172800000,
            "average": 12.3,
            "minimum": 12.0,
            "maximum": 12.7,
            "sampleCount": 180,
            "dataQuality": 0
          }
        ]
      }
    ]
  }
}
```

## 4. 自动分辨率

服务端根据请求跨度自动选择返回粒度，第一版不开放用户自定义：

| 查询跨度 | `resolutionMinutes` |
|---|---:|
| `0 < span <= 24小时` | 1 |
| `24小时 < span <= 7天` | 5 |
| `7天 < span <= 31天` | 30 |

边界采用闭区间：恰好 24 小时使用 1 分钟，恰好 7 天使用 5 分钟，恰好 31 天使用 30 分钟。

对于 5 分钟和 30 分钟窗口：

- `average`：`sum(avg_val * sample_count) / sum(sample_count)`，按原始采样数加权。
- `minimum`：窗口内所有分钟行 `min_val` 的最小值。
- `maximum`：窗口内所有分钟行 `max_val` 的最大值。
- `sampleCount`：窗口内所有分钟行 `sample_count` 之和。
- `dataQuality`：窗口内最差质量等级，即最大值。

对于 1 分钟分辨率，直接返回已冻结分钟行的 `avg_val`、`min_val`、`max_val`、`sample_count` 和 `data_quality`。

## 5. 权限

- 允许角色：`BUILDING_OWNER`、`ENERGY_MANAGER`、`PLATFORM_ADMIN`。
- `BUILDING_OWNER` 和 `ENERGY_MANAGER` 必须通过 `BuildingScopeService` 拥有目标建筑权限。
- `PLATFORM_ADMIN` 按现有约定拥有全建筑范围。
- `THIRD_PARTY` 本期不开放内部 HVAC 趋势接口，继续使用独立的 `/open-api/**` 边界。
- 权限必须在服务端执行，不能依赖调用方过滤。

## 6. 内部实现结构

本期功能仍然只是两个 HVAC 查询 API。以下类是这两个 API 的内部实现分层，不是新微服务或额外业务范围。

```text
HvacQueryController
        ↓
HvacQueryService
        ↓
HvacMinuteRepository
        ↓
TdengineHvacMinuteRepository
        ↓
st_raw_minute
```

职责：

- `HvacQueryController`：接收 HTTP 参数并返回统一 `Result<T>`。
- `HvacQueryService`：检查建筑存在性、角色数据范围、测点数量与归属、时间跨度，并选择分辨率。
- `HvacMinuteRepository`：扩展现有正式分钟存储边界，增加最新值和历史查询。
- `TdengineHvacMinuteRepository`：执行 TDengine 查询并映射成稳定的查询模型。
- `HvacQueryDtos`：集中定义快照、趋势序列和记录 DTO。
- `GlobalExceptionHandler`：补充业务错误码 `503` 到 HTTP `503 Service Unavailable` 的映射。

现有 `HvacMinuteRepository.saveAll()` 和恢复能力保持不变。本期不增加表、不增加缓存、不增加定时任务。

## 7. 数据流

### 7.1 快照

```text
校验建筑存在
→ 校验当前用户建筑权限
→ 从 MySQL 读取建筑下 ONLINE 测点
→ TDengine 批量读取这些 pointId 的最新分钟行
→ 以 pointId 合并配置和数据
→ 无数据测点生成 NO_DATA
→ 按 pointCode 排序返回
```

### 7.2 历史趋势

```text
校验建筑存在
→ 校验当前用户建筑权限
→ 解析、去重并校验 1～8 个 pointId
→ 从 MySQL 校验全部测点属于该建筑且已启用
→ 校验 from/to 和 31 天上限
→ 选择 1/5/30 分钟分辨率
→ TDengine 一次批量查询所选测点
→ 按 pointId 分组
→ 按用户输入顺序组装 series
→ 无数据测点返回空 records
```

## 8. 错误处理

| 场景 | HTTP 状态 |
|---|---:|
| 建筑不存在 | 404 |
| 当前用户无目标建筑权限 | 403 |
| `pointIds` 缺失或去重后为空 | 400 |
| 去重后超过 8 个测点 | 400 |
| 测点不存在、已停用或不属于目标建筑 | 400 |
| `from`、`to` 缺失或不是有效毫秒值 | 400 |
| `from >= to` | 400 |
| 查询跨度超过 31 天 | 400 |
| TDengine 查询不可用 | 503 |
| 查询合法但没有数据 | 200 |

业务异常继续由现有全局异常处理器转换成统一 JSON 错误结构；本期需补充错误码
`503` 到 HTTP `503 Service Unavailable` 的映射。未知异常仍按现有规则返回 HTTP 500，
不得把 TDengine SQL 或连接信息暴露给调用方。

## 9. 测试

本功能不依赖前端即可完成验证。

### 9.1 Service 单元测试

- 24 小时、7 天、31 天分辨率边界。
- 超过 31 天拒绝。
- `pointIds` 去重并保持首次出现顺序。
- 1 个和 8 个测点接受，9 个测点拒绝。
- 测点跨建筑、已停用或不存在时拒绝。
- 无建筑权限时拒绝。
- 快照包含无数据测点并标记 `NO_DATA`。
- 历史无数据测点返回空 `records`。
- Repository 返回顺序任意时，Service 仍按请求顺序输出。

### 9.2 Controller/MockMvc 测试

- 未登录返回 401。
- `BUILDING_OWNER`、`ENERGY_MANAGER` 在获授权建筑返回 200。
- 两类角色访问未授权建筑返回 403。
- `PLATFORM_ADMIN` 返回 200。
- `THIRD_PARTY` 返回 403。
- 参数错误返回 400。
- 返回 JSON 字段、状态和序列顺序符合契约。

### 9.3 Repository 测试

- 最新快照查询使用 `point_id`，支持同名 `pointCode` 存在于不同建筑。
- 1 分钟查询直接映射冻结分钟行。
- 5/30 分钟查询使用加权平均、最小值、最大值、样本数总和和最差质量。
- 多测点使用一次批量查询，不执行逐测点 N+1 查询。
- TDengine 异常向上转换为查询不可用错误。

### 9.4 回归

- 运行新增定向测试。
- 运行完整 `mvn test`。
- 现有 47 个后端测试不得回归。

## 10. 后续升级

未来增加完整原始事件查询时，新增 `/events` 接口和原始事件查询仓储，不修改本期快照和历史响应契约。原始事件接口必须增加游标分页、最大时间跨度、数量限制，并默认只开放给 `ENERGY_MANAGER` 和 `PLATFORM_ADMIN`。
