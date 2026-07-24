# MQTT 数据对接说明（电表遥测）

## 一、连接参数

| 参数 | 值 |
|------|-----|
| 协议 | MQTT 3.1.1 / 5.0 |
| Broker | `tcp://<SERVER_IP>:1883` |
| 认证 | 用户名和密码由部署方通过环境变量配置 |
| Client ID | 建议用设备编号，如 `meter-001`，需全局唯一 |

> 平台和硬件需位于网络可达的环境中。部署完成后，由部署方提供实际服务器地址。

---

## 二、发布主题

```
device/data/up
```

QoS 建议设为 **1**（至少一次送达），平台侧 Broker 为 EMQX 5.6。

---

## 三、报文格式

**JSON，单条一包。**

### 必填字段

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `deviceId` | string | 设备唯一编号，必须在平台注册过 | `"meter-001"` |

### 遥测字段（选填，传了就存）

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `voltage_a` | number | A 相电压 (V) | `220.1` |
| `voltage_b` | number | B 相电压 (V) | `219.5` |
| `voltage_c` | number | C 相电压 (V) | `220.8` |
| `current_a` | number | A 相电流 (A) | `1.2` |
| `current_b` | number | B 相电流 (A) | `1.1` |
| `current_c` | number | C 相电流 (A) | `1.3` |
| `active_power` | number | 有功功率 (kW) | `0.38` |
| `frequency` | number | 电网频率 (Hz) | `50.01` |
| `timestamp` | number | 硬件采集时间戳（**毫秒**） | `1752998400000` |

### 完整示例

```json
{
  "deviceId": "meter-001",
  "voltage_a": 220.1,
  "voltage_b": 219.5,
  "voltage_c": 220.8,
  "current_a": 1.2,
  "current_b": 1.1,
  "current_c": 1.3,
  "active_power": 0.38,
  "frequency": 50.01,
  "timestamp": 1752998400000
}
```

### 最小报文（只测链路）

```json
{
  "deviceId": "meter-001",
  "voltage_a": 220.1,
  "current_a": 1.2,
  "active_power": 0.38,
  "timestamp": 1752998400000
}
```

---

## 四、约束与限制

1. **`deviceId` 必须在平台先注册**。当前已内置一台测试电表 `meter-001`，可直接用。如需新增设备，联系平台侧通过接口或数据库添加。

2. **`deviceId` 仅允许** 字母、数字、`:`、`_`、`-`，长度 1~64 字符。

3. **单条报文 ≤ 64KB**，超出会被直接丢弃。

4. **上报频率建议** 1~5 秒/条。平台侧数据保留 10 年（TDengine KEEP 3650）。

---

## 五、链路验证方法

硬件发完数据后，按以下顺序确认各层：

| 步骤 | 检查点 | 方法 |
|------|--------|------|
| 1 | EMQX 收到 | EMQX Dashboard → 客户端列表 → 确认设备已连接 |
| 2 | 设备上线 | 前端大屏 → 在线设备数 +1，`meter-001` 状态变绿 |
| 3 | 时序落盘 | TDengine 查询：`SELECT * FROM iot_telemetry.d_meter_001 ORDER BY ts DESC LIMIT 1` |
| 4 | 大屏刷新 | 前端大屏图表每 5 秒自动刷新，应出现电压/电流/功率曲线 |

---

## 六、数据存储说明

硬件时间戳和平台入库时间是**分开存储**的两列：

| TDengine 列 | 含义 | 来源 |
|-------------|------|------|
| `ts` | 平台入库时间 | 服务端 `NOW`，用于查询排序 |
| `collect_ts` | 硬件采集时间 | 报文中 `timestamp` 字段 |

硬件没传 `timestamp` 时 `collect_ts` 为 NULL，不影响写入。

---

# HVAC 19 测点真实数据协议

HVAC 与上面的电表宽表报文共用 `device/data/up`，但采用“单测点、单报文”格式。

## 必填字段

| 字段 | 类型 | 含义 |
|------|------|------|
| `buildingId` | string | 平台建筑 ID。缺失时拒绝，防止多建筑同名设备串点 |
| `deviceId` | string | 建筑内物理设备编码，如 `WCR1`；环境测点可使用 `WEATHER_GATEWAY` |
| `pointCode` | string | 当前接入来源的测点别名，不是平台内部 ID |
| `val` | number | 有限数值 |
| `timestamp` | integer | 设备采集时间，13 位 Unix 毫秒时间戳 |

```json
{
  "buildingId": "BLD001",
  "deviceId": "WCR1",
  "pointCode": "WCR1_TWin",
  "val": 12.6,
  "timestamp": 1784781000000
}
```

来源命名空间由后端配置 `ingestion.source-system=MQTT_FREEZE_V1` 决定，设备不能在
报文中覆盖。平台按 `buildingId + source-system + pointCode` 解析唯一测点，
再转换为内部 `point_id` 和交底文档标准编码。

## 冻结编码到平台标准编码

| MQTT `pointCode` | 平台标准编码 |
|---|---|
| `WCR1_TWin` | `WCR1_TWin` |
| `WCR1_TWout` | `WCR1_TWout` |
| `WCR1_Flow` | `WCR1_GW` |
| `WCR1_PPE` | `WCR1_PPE` |
| `WCR1_Voltage` | `WCR1_Voltage` |
| `WCR1_Current` | `WCR1_Current` |
| `WCR1_PF` | `WCR1_PF` |
| `TOWER1_TCWin` | `WCR1_CT_TWin` |
| `TOWER1_TCWout` | `WCR1_CT_TWout` |
| `TOWER1_TWB` | `WCR1_CT_TWB` |
| `PUMP1_Flow` | `WCR1_Pc_GW` |
| `PUMP1_Pout` | `WCR1_Pc_Pout` |
| `PUMP1_Pin` | `WCR1_Pc_Pin` |
| `PUMP1_Z` | `WCR1_Pc_Z` |
| `PUMP1_Power` | `WCR1_Pc_PPE` |
| `AHU1_TotalPress` | `AHU1_TotalPress` |
| `AHU1_EtaT` | `AHU1_EtaT` |
| `DBO_TDB` | `DBO` |
| `DBO_RH` | `RHO` |

设备只需维护冻结协议中的编码，不应上报 `point_id`。内部 ID 由平台生成，
用于 MySQL 关联、TDengine 子表隔离、分钟聚合和将来的公式输入。

## 接收与存储规则

1. 先校验建筑、别名、设备归属、时间戳、有限数值和已配置量程。
2. 异常数据记录拒绝原因，但不进入 TDengine 正常原始表、分钟聚合或 COP。
3. 合法真实数据统一标记质量等级 `0`；本阶段不生成插值、默认值或质量等级 `2`。
4. `ts` 使用设备采集时间，`received_time` 单独记录服务器接收时间。
5. 同一 `point_id + ts` 重复且数值相同视为幂等；冲突数值以最后收到值为准并记录告警。
6. 每个自然分钟按设备采集时间计算平均值，同时保存最小值、最大值、样本数和最差质量。
