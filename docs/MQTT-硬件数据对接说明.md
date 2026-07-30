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

## HVAC 19 测点公式链路冒烟

以下步骤验证 MQTT 接入、分钟冻结、四个公式、TDengine、Redis、
WebSocket 和只读 API 的完整链路。命令在项目根目录执行，要求本机已有
Java 21、Maven、Node.js 20+、Docker Engine 和 Docker Compose 插件。

默认本地配置如下：

| 服务 | 地址/凭据 |
|---|---|
| 后端 API | `http://127.0.0.1:8081/api` |
| MQTT | `tcp://127.0.0.1:1883`，`admin/change-me` |
| MySQL | `127.0.0.1:3306`，`root/change-me` |
| TDengine REST | `127.0.0.1:6041`，`root/taosdata` |
| Redis | `127.0.0.1:6379`，无密码 |

本机已有 MySQL 或 Redis 时，可在启动 Compose 和后端前设置相同的端口变量。
例如 MySQL 使用 `13306`、Redis 使用 `16379`：

```powershell
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
```

Compose 只改变宿主机发布端口；容器内部仍使用标准端口。后端读取同名变量，
因此不需要另外修改 `application.yml`。

`MQTT_BROKER_URL`、`MQTT_USER`、`MQTT_PASSWORD` 等环境变量可以覆盖默认值。
模拟器和后端使用同一套 MQTT 环境变量。

### 1. 启动并发布完整 19 测点

首次检出代码或依赖锁文件变化后先安装 Node.js 依赖：

```powershell
npm ci
```

启动基础设施：

```powershell
docker compose -f src/env/docker-compose.yml up -d
```

全新的 MySQL 数据卷只会自动执行 `01-init-tables.sql`、
`02-init-10000-devices.sql` 和 `03-init-hvac-schema.sql`。Docker 官方镜像仅在
数据目录为空时执行 `/docker-entrypoint-initdb.d`，因此已有数据卷不会因重启
Compose 而重新执行初始化脚本。

持久环境升级数据质量功能时，应先备份，再严格按 `08`（MySQL）→ `09`
（TDengine）→ `10`（MySQL）的顺序，根据各文件头说明手工执行：

- `08-migrate-mysql-data-quality-fill.sql`：补全任务和典型值配置；
- `09-migrate-tdengine-data-quality-fill.sql`：分钟质量与任务所有权；
- `10-migrate-mysql-data-quality-recalculation.sql`：异步人工重算批次。

从更早版本升级的持久环境，还必须先按文件头说明完成 `05`、`06`、`07`，
不能因为本节只验证数据质量链路而跳过既有模型迁移。

`10` 不会自动应用到已有 MySQL 数据卷。`08`、`09`、`10` 都不能挂入 MySQL
初始化目录，其中 `09` 只能交给 TDengine 执行。TDengine 当前表结构也可由后端
`TdengineConfig` 在允许初始化的环境中创建或补齐。

在第二个终端启动后端：

```powershell
mvn spring-boot:run
```

待后端启动完成后，在第三个终端发布数据：

```powershell
node .scripts/simulate-hvac-19-points.mjs
```

脚本默认每 10 秒发布一轮，持续 70 秒，共 7 轮。每轮包含 19 个外部别名，
且同一轮的 19 条报文共享一个设备时间戳。连接失败、发布失败或未知的省略测点
都会使脚本以非零状态退出。

结果在对应的设备采集自然分钟结束后，再经过配置项
`aggregation.finalization-delay-seconds=30` 的冻结等待才出现。模拟器结束后
最多再等待 90 秒，即可覆盖最后一轮所在分钟的关闭和冻结：

```powershell
Start-Sleep -Seconds 90
```

### 2. 核对 TDengine 和 Redis

查询最新四个成功指标：

```powershell
docker exec iot-tdengine taos -s "SELECT indicator_code, ts, val, data_quality, formula_version FROM iot_telemetry.st_indicator_minute ORDER BY ts DESC LIMIT 4;"
```

同一个完整分钟的期望值为：

```text
WCR_COP     ≈ 5.805556   data_quality=0  formula_version=WCR_COP_V1
TOWER_EFF   = 50.000000  data_quality=0  formula_version=TOWER_EFF_V1
PUMP_EFF    ≈ 58.277778  data_quality=0  formula_version=PUMP_EFF_V1
AHU_POW_EFF ≈ 0.462963   data_quality=0  formula_version=AHU_POW_EFF_V1
```

核对四个 Redis 最新状态键：

```powershell
docker exec iot-redis redis-cli --scan --pattern "iot:indicator:latest:*"
```

Redis 最新指标的 TTL 是 120 秒。如果键已经过期，可重新运行模拟器，或直接用
下面的最新指标 API；该 API 会从 TDengine 回退读取。

### 3. 调用三个只读 API

三个接口均需要 JWT。全新本地库内置管理员为 `admin/123456`：

```powershell
$login = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8081/api/auth/login" `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"123456"}'
$headers = @{ Authorization = "Bearer $($login.data.token)" }

$latest = Invoke-RestMethod -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/hvac/buildings/BLD001/indicators/latest"
$latest.data.indicators | Format-Table

$minuteStart = ($latest.data.indicators |
  Where-Object indicatorId -eq "INDICATOR_WCR_COP_B1").minuteStart
$minuteEnd = $minuteStart + 60000

Invoke-RestMethod -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/hvac/indicators/INDICATOR_WCR_COP_B1/history?from=$minuteStart&to=$minuteEnd"

Invoke-RestMethod -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/hvac/indicators/INDICATOR_WCR_COP_B1/calculations/$minuteStart"
```

最新值接口应返回四个 `SUCCESS` 指标；历史接口应返回指定分钟的 COP；计算详情
应包含公式输入、分步计算、质量和公式版本。

### 4. 验证缺少水泵功率的失败分钟

先进入一个没有完整脚本数据的新自然分钟，避免上一轮 `PUMP1_Power` 样本混入
本次分钟，然后通过环境变量省略该外部别名：

```powershell
Start-Sleep -Seconds (61 - (Get-Date).Second)
$env:HVAC_OMIT_POINT = "PUMP1_Power"
try {
  node .scripts/simulate-hvac-19-points.mjs
} finally {
  Remove-Item Env:HVAC_OMIT_POINT -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 90
```

查询最新水泵异常：

```powershell
docker exec iot-tdengine taos -s "SELECT indicator_code, ts, calc_status, reason_code, missing_inputs, formula_version FROM iot_telemetry.st_formula_calc_exception WHERE indicator_code='PUMP_EFF' ORDER BY ts DESC LIMIT 1;"
docker exec iot-redis redis-cli GET "iot:indicator:latest:INDICATOR_PUMP_EFF_B1"
```

验证结果应满足：

1. 异常分钟没有 `PUMP_EFF` 成功行；
2. `st_formula_calc_exception` 有 `calc_status=MISSING_INPUT`，缺失项包含
   `Pc/PPE`；
3. Redis 水泵状态为 `MISSING_INPUT` 且 `value=null`；
4. 预先连接 `ws://127.0.0.1:8081/api/ws/dashboard` 的客户端收到
   `type=HVAC_INDICATOR` 的相同缺失状态；
5. 同一分钟的 `WCR_COP`、`TOWER_EFF`、`AHU_POW_EFF` 仍成功。

可用异常记录的 `ts` 再查询
`iot_telemetry.st_indicator_minute`，确认该时间没有 `PUMP_EFF`，而另外三个指标
均存在。不要用默认值补齐缺失功率；缺失必须保持可见并可审计。

### 5. 验证历史 Q0 补发和异步人工重算

历史补发只发送一个测点一次，不会伪造整批 19 点数据。时间必须位于目标旧分钟
内，数值必须通过该测点的量程校验：

```powershell
$env:HVAC_ONLY_POINT = "PUMP1_Power"
$env:HVAC_EVENT_TIME_MS = "目标旧分钟内的Unix毫秒"
$env:HVAC_VALUE_OVERRIDE = "10.0"
try {
  node .scripts/simulate-hvac-19-points.mjs
} finally {
  Remove-Item Env:HVAC_ONLY_POINT -ErrorAction SilentlyContinue
  Remove-Item Env:HVAC_EVENT_TIME_MS -ErrorAction SilentlyContinue
  Remove-Item Env:HVAC_VALUE_OVERRIDE -ErrorAction SilentlyContinue
}
```

`HVAC_ONLY_POINT` 必须是冻结协议中的外部别名；指定后脚本只发布一条。
`HVAC_EVENT_TIME_MS` 必须是正的安全整数，`HVAC_VALUE_OVERRIDE` 必须是有限数。
后两个变量只用于定点模式，防止误把整批当前数据写入历史分钟。

人工重算的两个 POST 都只创建 MySQL 批次并立即返回 job，不等待 TDengine
作废、Q0/Q1/Q2 选择和公式修正完成：

```text
POST /api/iot/data-quality/recalculate
POST /api/iot/data-quality/fill-tasks/{taskId}/void-and-recalculate
```

范围重算示例中的 `pointIds` 是平台内部测点 ID，不是 MQTT 外部别名：

```powershell
$request = @{
  buildingId = "BLD001"
  pointIds = @("目标平台内部测点ID")
  fromInclusive = 目标起始分钟Unix毫秒
  toExclusive = 目标结束分钟Unix毫秒
  reason = "历史原始数据补发后重新核算"
} | ConvertTo-Json

$accepted = Invoke-RestMethod -Method Post -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/iot/data-quality/recalculate" `
  -ContentType "application/json" -Body $request
$jobId = $accepted.data.jobId
```

作废某个异常 Q1/Q2 任务并按原范围重算：

```powershell
$taskId = "待作废补全任务ID"
$voidRequest = @{ reason = "测点绑定修正后作废并重算" } | ConvertTo-Json
$accepted = Invoke-RestMethod -Method Post -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/iot/data-quality/fill-tasks/$taskId/void-and-recalculate" `
  -ContentType "application/json" -Body $voidRequest
$jobId = $accepted.data.jobId
```

使用详情接口轮询，直到 `SUCCEEDED` 或 `FAILED`。`WAITING` 和 `RUNNING` 都是
正常异步状态：

```powershell
do {
  $detail = Invoke-RestMethod -Headers $headers `
    -Uri "http://127.0.0.1:8081/api/iot/data-quality/recalculation-jobs/$jobId"
  $job = $detail.data.job
  $job | Select-Object jobId,status,phase,cursorMinute,
    q0Count,q1Count,q2Count,missingCount,voidedCount,replacedCount,lastError
  if ($job.status -in @("WAITING", "RUNNING")) {
    Start-Sleep -Seconds 2
  }
} while ($job.status -in @("WAITING", "RUNNING"))
```

如果状态为 `FAILED`，先处理 `lastError` 对应的外部资源或配置问题，再由同一
平台管理员原样重发完全相同的 POST（建筑、排序后的测点集合、时间范围、原因均
保持不变）。系统会把原批次恢复为 `WAITING` 并从原 `cursorMinute` 续跑，不会
新建重复批次；修改原因表示新的审计请求，会创建新批次。

例如范围任务失败后，修复故障并再次执行上面的同一个 `$request`：

```powershell
$accepted = Invoke-RestMethod -Method Post -Headers $headers `
  -Uri "http://127.0.0.1:8081/api/iot/data-quality/recalculate" `
  -ContentType "application/json" -Body $request
$jobId = $accepted.data.jobId
```

完成后按以下规则核验：

1. `q0Count/q1Count/q2Count/missingCount` 之和等于已处理的“测点分钟”数；
2. `detail.data.childTasks` 由批次关联关系查询，只返回本批次生成的 Q1/Q2；
   分别检查 `dataQuality=1/2`、来源类型和证据快照；
3. TDengine 正式分钟中，真实原始证据生成 Q0；两个当前 Q0 端点之间不超过
   `data-quality.interpolation.max-gap-minutes`（默认五分钟）的缺口生成 Q1；
   其余缺口仅在存在当分钟有效且已批准的典型值时生成 Q2；
4. 没有合法来源的分钟保持 missing，不能套用 `default_value`；
5. 每个目标分钟都会触发公式权威修正。输入齐全时指标按当前最高质量重算；输入
   仍缺失时，旧的成功指标必须从 `st_indicator_minute` 删除，并在
   `st_formula_calc_exception` 留下 `MISSING_INPUT`，避免继续展示失效结果；
6. 对应 API、Redis 最新状态和 WebSocket `HVAC_INDICATOR` 消息应与修正后的
   Q0/Q1/Q2 或缺失状态一致。
