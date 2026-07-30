# HVAC 19 测点 MQTT 硬件数据对接说明

## 1. 适用范围

本文档描述中央空调 V1 的 MQTT 上行契约。当前运行系统只接收 HVAC 19 测点，
不包含旧电表属性上报、设备台账、控制下行、自动 ACK 模拟器或旧大屏消息。

V1 数据链路：

```text
EMQX 上行主题
  → 载荷大小与 JSON 基础校验
  → HvacMqttMessageHandler
  → 测点别名、单位和质量校验
  → TDengine 原始事件
  → 手动 ACK
```

## 2. 连接参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| Broker | `tcp://127.0.0.1:1883` | 由 `MQTT_BROKER_URL` 覆盖 |
| Username | `admin` | 由 `MQTT_USER` 覆盖 |
| Password | `change-me` | 由 `MQTT_PASSWORD` 覆盖，生产必须修改 |
| Topic | `device/data/up` | 服务端 `mqtt.topics.upstream` 配置 |
| QoS | `1` | 至少一次投递，服务端手动确认 |
| Retain | `false` | 避免历史测点值在重连时被当成新事件 |
| 最大载荷 | `64 KiB` | 超限报文确认并拒绝 |

来源系统不是硬件可提交字段。服务端使用可信配置
`ingestion.source-system=MQTT_FREEZE_V1` 选择 `biz_point_alias` 命名空间，
防止设备伪造来源绕过映射。

## 3. 上行 JSON

```json
{
  "buildingId": "BLD001",
  "deviceId": "WCR1",
  "pointCode": "WCR1_TWin",
  "val": 12.3,
  "timestamp": 1785398400000
}
```

| 字段 | 类型 | 必填 | 业务含义 |
|---|---|:---:|---|
| `buildingId` | string | 是 | 建筑 ID，必须与测点别名映射一致 |
| `deviceId` | string | 是 | 来源设备或网关 ID，用于审计 |
| `pointCode` | string | 是 | 该来源系统下的测点别名 |
| `val` | number | 是 | 有限数值，不允许 `NaN` 或无穷大 |
| `timestamp` | integer | 是 | 设备事件时间，Unix 毫秒 |

时间轴以 `timestamp` 为准；服务端另记接收时间用于迟到审计。相同测点、事件时间
和数值属于重复事件；同一测点同一事件时间但数值不同，按现有冲突纠正规则处理。

## 4. 冻结的 19 个外部别名

| # | deviceId | pointCode | 单位 | 含义 |
|---:|---|---|---|---|
| 1 | WCR1 | `WCR1_TWin` | ℃ | 冷水机组冷冻水进水温度 |
| 2 | WCR1 | `WCR1_TWout` | ℃ | 冷水机组冷冻水出水温度 |
| 3 | WCR1 | `WCR1_Flow` | m³/h | 冷冻水流量 |
| 4 | WCR1 | `WCR1_PPE` | kW | 冷水机组瞬时功率 |
| 5 | WCR1 | `WCR1_Voltage` | V | 压缩机电压 |
| 6 | WCR1 | `WCR1_Current` | A | 压缩机电流 |
| 7 | WCR1 | `WCR1_PF` | 1 | 功率因数 |
| 8 | TOWER1 | `TOWER1_TCWin` | ℃ | 冷却塔进水温度 |
| 9 | TOWER1 | `TOWER1_TCWout` | ℃ | 冷却塔出水温度 |
| 10 | TOWER1 | `TOWER1_TWB` | ℃ | 室外湿球温度 |
| 11 | PUMP1 | `PUMP1_Flow` | m³/h | 水泵流量 |
| 12 | PUMP1 | `PUMP1_Pout` | Pa | 水泵出口压力 |
| 13 | PUMP1 | `PUMP1_Pin` | Pa | 水泵入口压力 |
| 14 | PUMP1 | `PUMP1_Z` | m | 压力表高度差 |
| 15 | PUMP1 | `PUMP1_Power` | kW | 水泵输入功率 |
| 16 | AHU1 | `AHU1_TotalPress` | Pa | AHU 风机全压 |
| 17 | AHU1 | `AHU1_EtaT` | % | AHU 风机总效率 |
| 18 | WEATHER_GATEWAY | `DBO_TDB` | ℃ | 室外干球温度 |
| 19 | WEATHER_GATEWAY | `DBO_RH` | % | 室外相对湿度 |

外部别名会映射为内部标准 `biz_data_point.point_code`。硬件只能发送本表别名，
不能直接假定内部主键或标准编码。

## 5. 确认与重投语义

| 处理结果 | 是否 ACK | 原因 |
|---|:---:|---|
| 原始事件成功写入 | 是 | 数据已经持久化 |
| 重复事件 | 是 | 重投不会产生新结果 |
| 冲突事件按规则更新 | 是 | 纠正结果已经持久化 |
| 未知测点、单位错误、越界等业务拒绝 | 是 | 重投不能修复配置或载荷问题 |
| 空包、非法 JSON、超过 64 KiB | 是 | 属于毒消息，避免阻塞队列 |
| 缺少 `pointCode` | 是 | 属于已下线的旧格式，明确拒绝 |
| TDengine 存储失败 | 否 | 保留 QoS 1 重新投递机会 |

设备端必须接受“至少一次”语义，不能通过重复发送不同消息 ID 绕过事件幂等。

## 6. 本地验证

使用仓库保留的 HVAC 模拟器：

```powershell
node .scripts/simulate-hvac-19-points.mjs
```

缺测验证：

```powershell
$env:HVAC_OMIT_POINT='WCR1_TWin'
node .scripts/simulate-hvac-19-points.mjs
Remove-Item Env:HVAC_OMIT_POINT
```

历史单点纠正验证：

```powershell
$env:HVAC_ONLY_POINT='WCR1_TWin'
$env:HVAC_EVENT_TIME_MS='1785398400000'
$env:HVAC_VALUE_OVERRIDE='12.8'
node .scripts/simulate-hvac-19-points.mjs
Remove-Item Env:HVAC_ONLY_POINT
Remove-Item Env:HVAC_EVENT_TIME_MS
Remove-Item Env:HVAC_VALUE_OVERRIDE
```

## 7. HTTP 与实时边界

- HVAC 快照：`GET /api/hvac/buildings/{buildingId}/snapshot`
- HVAC 历史：`GET /api/hvac/buildings/{buildingId}/history`
- 最新指标：`GET /api/hvac/buildings/{buildingId}/indicators/latest`
- 指标实时消息：`ws://<host>:<port>/api/ws/hvac`

实时消息类型为 `HVAC_INDICATOR`。当前端点只完成 HVAC 业务边界清理；JWT
握手、建筑订阅和广播隔离属于后续正式实时功能。

V1 没有控制下行主题、控制 API 或设备自动回执。未来控制能力必须按
`docs/HVAC控制能力设计备忘.md` 单独设计和验收。
