# MQTT TLS 与遥测补传可靠性 V2 设计

状态：活动候选设计，尚未完成生产网络、真实设备和网关缓存验收。

## 1. 目标与边界

本候选在不改变现有 V1 设备接入方式的前提下，增加可独立测试的 MQTT TLS、V2 标准报文、平台持久化回执、去重/冲突处理和只读证据查询。

本期包含：

- 平台和云端适配器的单向 TLS、可选 mTLS、主机名校验、证书/信任库外部加载；
- 连接、认证和授权失败分类，结构化日志与 Micrometer 指标；
- V2 报文对缺少 `messageId`、`seq`、采集时间的自适应；
- 平台身份校验、不可覆盖幂等、迟到标记、异常留痕；
- 严格区分设备上行 `PUBACK`、适配器标准发布 `PUBACK`、平台入站消费确认、应用 ACK 发布 `PUBACK` 与 `PLATFORM_PERSISTED`；
- 版本化只读查询 API 和软件模拟链路验证。

本期不包含生产 Broker/TLS 最终验收、真实网络抖动和长期稳定性、网关本地持久化缓存及限速、真实断网恢复补传、参数下发和设备控制。

## 2. 版本和主题

- 旧单点 `device/data/up` 和标准 V1 `device/telemetry/up` 继续兼容，行为不改。
- 云端适配器把厂商原始 Topic 映射为内部 V2 Topic，默认 `platform/telemetry/v2/up`。
- V2 Topic 只允许受信任适配器发布，平台订阅；设备不需要改为发布平台内部 Topic。
- 后续先从平台核心移除 V1 解析，再在设备迁移完成后删除适配器 V1 兼容。两步必须分别验证。

## 3. V2 报文契约

一条 V2 消息表示一个逻辑采样时刻，可包含多个测点。多时刻厂商包由适配器拆成多条 V2 消息，并保留批次位置。

关键字段：

| 字段 | 规则 |
| --- | --- |
| `canonicalMessageId` | 下游必有；由设备身份和受信任关联字段派生，或由适配器生成 |
| `sourceMessageId` | 设备原始消息号，可空 |
| `sourceSeq` | 设备序号，可空 |
| `collectedAt` | 设备采集时间，可空 |
| `adapterReceivedAt` | 适配器接收时间，必有 |
| `platformReceivedAt` | 平台每次收到消息时生成 |
| `persistedAt` | 全部原始测点和 MySQL 终态回执成功后生成 |
| `retransmittedAt` | 仅接受设备明确提供的补传时间，可空 |
| `batchId` | 批次号，可空；不代替单消息幂等键 |
| `idSource` | `DEVICE_REPORTED`、`ADAPTER_DERIVED` 或 `ADAPTER_GENERATED` |
| `timeSource` | `DEVICE_REPORTED` 或 `ADAPTER_RECEIVED` |
| `dedupMode` | `EXACT`、`DERIVED` 或 `NONE` |

缺少采集时间时，TDengine 有效事件时间使用 `adapterReceivedAt`，同时保存 `timeSource=ADAPTER_RECEIVED`；此时不得宣称已判断设备侧迟到。

## 4. ACK 和持久化语义

`PLATFORM_PERSISTED` 只表示本消息全部原始测点已写入 TDengine，且 MySQL 中一条轻量终态回执已持久化。它不等待聚合、质量、公式、Redis、WebSocket 或页面处理。

处理顺序固定为：

1. 校验身份、报文和测点；
2. 以不可覆盖方式持久化全部原始测点；
3. 插入或确认 MySQL 终态回执；
4. 有受信任 ACK 路由时，以 QoS 1 发布应用 ACK；
5. 应用 ACK 获得 Broker `PUBACK` 后，完成原入站消息的手动消费确认。

MySQL 或 TDengine 暂时失败、应用 ACK 发布失败时，不返回成功 ACK，也不完成原入站消费确认，由 Broker 重投。应用 ACK 发布失败写异常明细；成功路径不写完整载荷。

相同设备业务键和相同载荷返回 `DUPLICATE_PERSISTED`；相同业务键但不同载荷返回 `MESSAGE_CONFLICT`，禁止覆盖原值。永久业务错误返回 `PLATFORM_REJECTED`；未绑定设备返回 `DISCOVERED_NOT_ACTIVE`。

## 5. ACK 能力分级

- `DEVICE_DIRECT`：设备绑定具备可信设备响应 Topic，结果直接返回设备；
- `ADAPTER_PROXY`：结果只返回适配器，回执标记 `ADAPTER_ONLY`；
- `EVIDENCE_ONLY`：没有稳定关联键或可信响应能力，只保存持久化证据，不伪造设备级闭环。

实际模式由“协议声明上限、可信设备绑定、当前报文字段、可信 ACK 路由”的交集决定，只允许降级，不允许因载荷自报 Topic 或模式自动升级。每次回执保存配置模式、实际模式和降级原因。

## 6. 存储和压力控制

- 正常首报每个逻辑消息只保存一条轻量终态回执，不保存完整载荷，也不写 `RECEIVED -> PERSISTED` 双记录；
- 重投、重复、冲突、拒绝、存储失败和 ACK 失败才追加异常明细或累加尝试次数；
- Redis 只能加速查询，不能作为幂等权威；
- 开发/测试默认保留：成功回执 7 天、异常明细 180 天、TLS/MQTT 失败聚合 180 天、TDengine 原始数据 90 天，均由环境配置覆盖；生产值必须重新批准。

按 1000 台设备、15 秒周期、每报 5 点估算约 66.67 消息/秒、333.33 测点值/秒；软件短压测目标取 3 倍，即 200 消息/秒、1000 测点值/秒。本数字不是生产容量承诺。

## 7. TLS 和故障分类

- 单向 TLS 为默认安全基线，用户名/密码和 ACL 继续必需；可按部署启用 mTLS；
- 信任库、密钥库和密码全部由外部路径/环境变量提供，仓库不保存生产证书、私钥或密码；
- 强制默认主机名校验，禁止信任全部证书，TLS 失败不得降级明文；
- 连接失败分类至少覆盖 CA 不信任、证书时间无效、主机名不匹配、客户端证书拒绝、凭据错误、无授权、发布/订阅拒绝、DNS、拒绝连接、超时、Broker 不可用、协议错误和未知错误；
- 连接重试使用可配置的指数退避、抖动和上限。配置/安全错误采用慢重试；留痕失败不得阻塞 MQTT 重连。

## 8. 查询 API 和权限

后端提供：

- `GET /api/v1/telemetry-receipts`
- `GET /api/v1/telemetry-receipts/{canonicalMessageId}`
- `GET /api/v1/telemetry-receipts/statistics`
- `GET /api/v1/telemetry-receipts/transport-failures`（仅平台管理员）

接口返回独立 DTO 和稳定分页结构，明确区分全部时间和 ACK 证据字段。普通用户必须按建筑和设备范围过滤；TLS、账号、证书和 Broker 异常只允许平台管理员查询。任何接口都不得返回密码、私钥、完整证书或完整原始载荷。

## 9. 验证边界

自动化覆盖 TLS 加载/主机名校验/错误分类、所有缺字段组合、ACK 模式降级、ID 生成、去重/冲突、批次、迟到、部分写入恢复和 ACK 发布失败。隔离集成环境使用测试 Broker 和临时测试证书。

软件模拟通过不代表生产 Broker/TLS、真实网络、真实设备或网关断电缓存已经验收。
