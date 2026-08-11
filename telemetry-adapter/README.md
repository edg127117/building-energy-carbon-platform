# 云端通用多字段报文适配器

## 1. 结论与边界

该模块部署在云服务器，与 EMQX 同侧运行。它把不同设备的 JSON 多字段报文按 MySQL
协议模板转换为统一批量报文，再发布到 `device/telemetry/up`，由本地平台主动订阅。

```text
云端：设备 → EMQX → telemetry-adapter → device/telemetry/up
本地：平台后端 → 业务 MySQL / TDengine / Redis → Vue
```

云端 MySQL 只保存协议模板和字段映射，不保存建筑、设备归属、用户、正式遥测、历史或指标。
本地平台根据预注册关系确定 `MAC → 设备 → 建筑 → 期望协议`，再写入本地 TDengine。
当前代码与自动化测试已完成；云服务器部署、真实 EMQX、真实设备和 24 小时运行尚需现场验证。

## 2. 启动前准备

1. 在云端 MySQL 执行 [`adapter-schema.sql`](src/main/resources/db/adapter-schema.sql)。
2. 复制 [`adapter.env.example`](adapter.env.example) 的变量到服务器私有环境配置并替换密码。
3. 给适配器 MQTT 账号配置：订阅 `device/raw/#`，发布 `device/telemetry/up`。
4. 给本地平台 MQTT 账号配置：只需订阅 `device/telemetry/up`；旧迁移链另订阅 `device/data/up`。
5. 在云端 MySQL 新增并启用协议模板和字段映射。

构建和测试：

```powershell
.\mvnw.cmd -f telemetry-adapter/pom.xml test
.\mvnw.cmd -f telemetry-adapter/pom.xml -DskipTests package
```

运行时必须使用 Java 21。适配器默认端口为 `8091`，健康检查为 `/actuator/health`。
在服务器已注入上述环境变量后运行：

```powershell
java -jar target/telemetry-adapter-1.0-SNAPSHOT.jar
```

## 3. 如何做到“通用”

Java 代码只认识以下配置含义，不写死某个厂家的字段名：

- `device_identity_path`：设备身份在 JSON 中的位置；
- `timestamp_path`、`seq_path`：设备时间和序号的位置，可为空；
- `source_path → metric_code`：原字段到标准指标的映射；
- `source_unit → target_unit`、`scale`、`offset_value`：单位和线性换算；
- `required_flag`：字段缺失时是否整包拒绝；
- `source_topic + protocol_version`：选择唯一协议版本。

同一种协议的新设备无需新增 Java 或云端规则，只需在本地绑定身份和测点。JSON 结构不同的
新设备新增一份 MySQL 模板；二进制、加密或非 JSON 协议不属于当前解析器能力，需要单独解码器。

## 4. 当前电表报文的配置示例

假设原始 Topic 为 `device/raw/energy/up`，且设备上报：

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

可先新增模板。该报文没有设备时间和序号，所以标准报文使用云端接收时间，`seq` 为 `null`：

```sql
INSERT INTO iot_protocol_profile
(profile_id, profile_code, profile_version, source_topic,
 device_identity_type, device_identity_path,
 protocol_version_path, expected_protocol_version,
 timestamp_path, seq_path, enabled)
VALUES
('ENERGY_V1', 'ENERGY_METER_V1', 1, 'device/raw/energy/up',
 'MAC', '/MAC', NULL, NULL, NULL, NULL, 1);
```

字段映射示例：

```sql
INSERT INTO iot_protocol_field_mapping
(mapping_id, profile_id, source_path, metric_code, value_type,
 source_unit, target_unit, scale, offset_value, required_flag, enabled, sort_order)
VALUES
('ENERGY_V1_01','ENERGY_V1','/current_energy','CURRENT_ENERGY','DECIMAL','kWh','kWh',1,0,1,1,10),
('ENERGY_V1_02','ENERGY_V1','/current_co2','CURRENT_CO2','DECIMAL','kgCO2e','kgCO2e',1,0,1,1,20),
('ENERGY_V1_03','ENERGY_V1','/current_co2_factor','CURRENT_CO2_FACTOR','DECIMAL','kgCO2e/kWh','kgCO2e/kWh',1,0,1,1,30),
('ENERGY_V1_04','ENERGY_V1','/last_period_energy','LAST_PERIOD_ENERGY','DECIMAL','kWh','kWh',1,0,1,1,40),
('ENERGY_V1_05','ENERGY_V1','/last_period_co2','LAST_PERIOD_CO2','DECIMAL','kgCO2e','kgCO2e',1,0,1,1,50),
('ENERGY_V1_06','ENERGY_V1','/last_period_co2_factor','LAST_PERIOD_CO2_FACTOR','DECIMAL','kgCO2e/kWh','kgCO2e/kWh',1,0,1,1,60);
```

上面的能耗、碳排和因子单位是配置示例，不是已确认的硬件事实。启用前必须由硬件方确认单位、
`current_energy` 是累计量还是周期量，以及各字段是否每包必发；若单位不同，应修改映射或换算，
不能仅改页面显示。

## 5. 标准输出

适配器每收到一包原始数据，只发布一包标准多指标 JSON，不拆成六次网络消息：

```json
{
  "standardVersion": "1.0",
  "profileCode": "ENERGY_METER_V1",
  "profileVersion": 1,
  "deviceIdentity": {"type": "MAC", "value": "123456789012345"},
  "eventTime": 1785398400000,
  "receivedTime": 1785398400000,
  "timeSource": "SERVER_RECEIVED",
  "seq": null,
  "metrics": [
    {"code": "CURRENT_ENERGY", "value": 12.34, "unit": "kWh", "sourceField": "/current_energy"}
  ]
}
```

## 6. 失败与重投

- 非法 JSON、必填字段缺失、数值类型错误、规则不匹配：记录原因并 ACK，避免毒消息阻塞。
- 云端规则快照从未成功加载、标准 Topic 发布失败：不 ACK 原始消息，交由 EMQX 重投。
- 规则刷新失败但已有完整旧快照：继续使用旧快照。
- 发布采用 QoS 1、固定 clientId 和 `cleanSession=false`；系统按至少一次投递设计。

日志不打印 MQTT 密码和完整原始载荷。生产环境仍需在 EMQX 设置单设备账号、Topic ACL、TLS、
连接速率和消息大小限制，这些 Broker 配置不由本模块代码自动创建。
