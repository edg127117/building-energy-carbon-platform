# MQTT 硬件数据对接说明

> **文档状态：当前代码契约，尚未完成真实云服务器、硬件和 24 小时现场验收。**

## 1. 当前采用的部署边界

```text
设备
  → 云端 EMQX 原始 Topic
  → 云端 telemetry-adapter（MySQL 配置驱动解析）
  → 云端 EMQX 标准 Topic：device/telemetry/up
  → 本地平台主动订阅
  → 本地业务 MySQL 校验设备/建筑/测点归属
  → 本地 TDengine 正式存储
```

云服务器只做接入和格式转换。云端 MySQL 只保存协议模板与字段映射，不保存建筑、用户、
设备归属、正式遥测、历史和指标。本地平台保存全部业务关系和时序数据，不需要暴露公网写入 API。

旧 `device/data/up` 单测点协议仅保留一个迁移窗口；新设备统一接入标准多字段链。

## 2. Topic 与账号

| 链路 | 默认 Topic | 发布方 | 订阅方 | 说明 |
|---|---|---|---|---|
| 设备原始上行 | `device/raw/#` 下的设备专属 Topic | 设备 | 云端适配器 | 实际模板必须配置精确 Topic |
| 标准多字段上行 | `device/telemetry/up` | 云端适配器 | 本地平台 | 一包原始报文对应一包标准报文 |
| 旧单点迁移 | `device/data/up` | 旧设备/模拟器 | 本地平台 | 不再用于接入新的多字段设备 |

三类 MQTT 身份应分开授权：设备账号只发布自己的原始 Topic；适配器账号订阅原始 Topic、
发布标准 Topic；本地平台账号只订阅标准 Topic和迁移期旧 Topic。生产环境应在 EMQX 配置 TLS、
账号密码、Topic ACL、连接限制和载荷限制；代码不会自动创建 Broker 安全规则。

本地平台配置入口为 [`application.yml`](../src/main/resources/application.yml) 和
[`server.env.example`](../server.env.example)。云端配置见
[`telemetry-adapter/README.md`](../telemetry-adapter/README.md)。

## 3. 新设备必须先完成本地预注册

设备连接和正式入库前必须按以下顺序配置：

1. 本地创建建筑、空间、设备台账和标准测点；
2. 本地创建每个指标到标准测点的别名；
3. 本地创建 `外部身份 → 设备 → 建筑 → 期望协议` 绑定；
4. 云端创建 MQTT 账号、Topic ACL、协议模板和字段映射；
5. 确认全部配置后再启用设备和模板。

现有数据库先执行 [`12-migrate-device-identity.sql`](../src/env/init/12-migrate-device-identity.sql)。
当前设备身份和标准多字段别名没有管理页面，需通过受控 SQL/运维流程配置。未来管理页面可以
复用这些表和缓存刷新机制，但尚未实现，不能把“自动发现设备”当成当前能力。

设备身份绑定示例：

```sql
INSERT INTO biz_device_identity
(identity_id, identity_type, identity_value, equip_id, building_id,
 expected_profile_code, status)
VALUES
('IDENTITY_METER001', 'MAC', '123456789012345',
 '实际设备内部ID', 'BLD001', 'ENERGY_METER_V1', 1);
```

每个指标必须有设备专属别名，格式固定为：

```text
身份类型:身份值:标准指标代码
```

例如：

```sql
INSERT INTO biz_point_alias
(alias_id, building_id, source_system, source_point_code, point_id, status)
VALUES
('ALIAS_METER001_ENERGY', 'BLD001', 'MQTT_STANDARD_V1',
 'MAC:123456789012345:CURRENT_ENERGY', '实际能耗测点ID', 1);
```

其余 `CURRENT_CO2`、`CURRENT_CO2_FACTOR`、`LAST_PERIOD_ENERGY`、`LAST_PERIOD_CO2`、
`LAST_PERIOD_CO2_FACTOR` 也必须分别映射。平台不会根据字段名自动猜测建筑、设备、测点或单位。

## 4. 原始多字段报文

以下报文由设备发布到云端原始 Topic，例如 `device/raw/energy/up`：

```json
{
  "MAC": "123456789012345",
  "current_energy": 12.34,
  "current_co2": 7.88,
  "current_co2_factor": 0.639,
  "last_period_energy": 0.25,
  "last_period_co2": 0.16,
  "last_period_co2_factor": 0.639
}
```

字段路径、必填性、单位、换算和标准指标代码由云端 MySQL 模板决定，不写死在 Java 中。
该示例没有 `timestamp` 和 `seq`，适配器会使用云端接收时间作为事件时间，序号为 `null`。
为了断网补传、排序和重复诊断，硬件后续仍强烈建议提供 Unix 毫秒 `timestamp` 和单设备递增 `seq`。

## 5. 云端标准报文

适配器发布一包标准 JSON，不把六个字段拆成六次网络消息：

```json
{
  "standardVersion": "1.0",
  "profileCode": "ENERGY_METER_V1",
  "profileVersion": 1,
  "deviceIdentity": {
    "type": "MAC",
    "value": "123456789012345"
  },
  "eventTime": 1785398400000,
  "receivedTime": 1785398400000,
  "timeSource": "SERVER_RECEIVED",
  "seq": null,
  "metrics": [
    {
      "code": "CURRENT_ENERGY",
      "value": 12.34,
      "unit": "kWh",
      "sourceField": "/current_energy"
    }
  ]
}
```

本地平台不信任报文中的业务归属。它只使用 `deviceIdentity` 查本地预注册关系，再校验：

- 上报协议是否等于设备绑定的期望协议；
- 每个指标是否有该设备专属别名；
- 别名指向的测点是否属于同一设备和建筑；
- 测点是否启用，单位和数值上下限是否匹配；
- 一包内是否存在重复指标代码。

整包预检全部通过后，平台才把指标逐条交给现有 TDengine 幂等入库链。

## 6. 未知设备不是正常接入流程

未预注册设备不会进入正式存储。V1 行为是：

- 记录带设备身份的告警日志；
- 增加低基数监控计数；
- ACK 并拒绝这条无法靠重投修复的业务报文；
- 不自动创建设备、不自动猜建筑、不写 TDengine。

后续可以增加“待绑定设备”管理页面或异常上报，但绑定前仍不得进入正式时序数据。

## 7. ACK、重投与断线恢复

| 场景 | 云端原始消息 | 本地标准消息 |
|---|:---:|:---:|
| JSON/字段格式错误、规则不匹配 | ACK 并拒绝 | ACK 并拒绝 |
| 未知设备、协议不匹配、别名/归属/单位错误 | 不适用 | ACK 并拒绝 |
| 规则/身份/测点快照从未成功加载 | 不 ACK | 不 ACK |
| 云端发布标准 Topic 失败 | 不 ACK | 不适用 |
| TDengine 存储失败 | 不适用 | 不 ACK |
| 成功、重复或已按规则处理的冲突 | ACK | ACK |

两侧均使用 QoS 1、固定 clientId 和 `cleanSession=false`。初次连接失败会后台重试；首次成功后
由 Paho 自动重连并恢复订阅。标准报文某个指标写入失败时整包不 ACK，重投后此前已成功的指标
依靠现有幂等键变为重复数据，不会重复生成事实行。

## 8. 旧 19 测点迁移协议

旧 Topic `device/data/up` 仍接受单测点 JSON：

```json
{
  "buildingId": "BLD001",
  "deviceId": "WCR1",
  "pointCode": "WCR1_TWin",
  "val": 12.3,
  "timestamp": 1785398400000
}
```

其来源命名空间为 `MQTT_FREEZE_V1`，只用于现有 19 测点和迁移验证。缺少 `pointCode` 的旧电表
格式会被明确拒绝，不能借旧 Topic 绕过新设备预注册和标准别名。

现有模拟器仍可用于旧链本地回归：

```powershell
node .scripts/simulate-hvac-19-points.mjs
```

## 9. 现场测试前检查

- 硬件方确认每个字段的单位、累计/周期语义、时间戳、序号和发送周期；
- 云端数据库已执行适配器 schema，模板能唯一命中实际 Topic；
- 本地数据库已执行设备身份迁移，设备、建筑、测点和六条别名全部启用；
- EMQX 三类账号权限隔离，云端适配器和本地平台使用不同固定 clientId；
- 先发一包，核对云端标准 JSON、未知设备计数、本地入库点数和单位；
- 再验证断网重连、重复包、字段缺失、数据库短暂不可用和 TDengine 短暂不可用；
- 24 小时结束后核对设备发送数、云端转换数、本地接收数、正式入库数、拒绝数和重投数。

自动化测试通过只证明代码契约，不等于真实云服务器、现场网络和硬件已验收。

## 10. HTTP 与实时展示边界

- HVAC 快照：`GET /api/hvac/buildings/{buildingId}/snapshot`
- HVAC 历史：`GET /api/hvac/buildings/{buildingId}/history`
- 最新指标：`GET /api/hvac/buildings/{buildingId}/indicators/latest`
- 指标实时消息：`ws://<host>:<port>/api/ws/hvac`

WebSocket 只发送四项指标增量，HTTP 仍是完整当前状态的权威读取入口。当前没有控制下行 Topic、
控制 API 或设备自动回执；未来控制能力必须单独设计和验收。
