# Modbus 边缘适配器

`modbus-edge-adapter` 是独立部署的 Java 21 只读采集进程。它从 Modbus TCP 或
Modbus RTU 设备轮询测点，将一个采样批次转换成平台现有 V2 多测点 MQTT 报文，
并监听平台应用 ACK。它不属于云端 `telemetry-adapter`，也不把 Modbus 连接带入平台
核心进程。

```text
Modbus TCP/RTU 设备
→ modbus-edge-adapter（读取、解码、身份/时间归一化）
→ MQTT QoS 1 + TLS
→ platform/telemetry/v2/up
→ 平台身份校验、去重、TDengine/MySQL 持久化
→ PLATFORM_PERSISTED 应用 ACK
→ modbus-edge-adapter ACK 监控
```

## 当前能力与边界

- 支持只读功能码 01、02、03、04；没有写寄存器、写线圈或设备控制入口。
- 支持 `BOOLEAN`、`INT16`、`UINT16`、`INT32`、`UINT32`、`FLOAT32`、
  `FLOAT64`，字节序、字序、倍率、偏移和单位均由部署配置声明。
- 相邻且同功能码的点合并读取；一个批次只有全部点成功后才发布，禁止半包进入平台。
- Modbus 读取和 MQTT 发布采用有限重试。进程没有本地持久化缓存，重试耗尽或进程退出后
  不能恢复该批消息，因此不属于断网补传网关。
- MQTT 同步 QoS 1 发布成功只代表取得 Broker `PUBACK`；平台应用 ACK 另行关联和计数，
  二者不会混写为同一种成功。
- 当前自动化已验证解码、批量读取、重试、报文生成、TLS 配置与 ACK 语义；RTU 真串口、
  厂家寄存器点表、真实设备、生产 Broker/TLS 和现场网络尚未验证。

## 构建与启动

在仓库根目录执行：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -f modbus-edge-adapter\pom.xml verify
java -jar modbus-edge-adapter\target\modbus-edge-adapter-1.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:C:/deployment/application-modbus.yml
```

应用默认 `modbus-edge.enabled=false`，没有经过确认的外部点表时不会连接设备或 Broker。
复制 [`application-modbus.example.yml`](application-modbus.example.yml) 到部署目录，替换其中
全部测试占位值并完成平台预注册后，才可启用。

## 配置规则

示例文件只展示结构，不代表任何厂家协议。关键规则如下：

- `address` 是从 0 开始的 Modbus PDU 地址，不是手册中的 `40001/30001` 表示法；必须按
  厂家文档换算并复核。
- `identity-type + identity-value` 是平台预注册身份，不能用进程内临时值或设备显示名称代替。
- `profile-code/profile-version` 和每个 `point.code/unit` 必须与平台已配置契约一致。
- TCP 配置 `host/port/unit-id`；RTU 配置 `serial-port/baud-rate/data-bits/stop-bits/parity/unit-id`。
- `poll-interval`、Modbus/MQTT 超时、并发数和重试次数均可部署调整，没有按预计设备数量写死。
- 多字寄存器先按 `word-order` 排列 16 位字，再按 `byte-order` 排列每个字内字节。
- TLS 默认强制开启并执行证书链和主机名校验。信任库、客户端证书和密码由部署环境提供，
  不提交到仓库；明文开关仅允许隔离自动化测试。

平台侧必须为该身份配置 V2 绑定与点码映射。若需要代理 ACK，还必须将最大 ACK 模式设为
`ADAPTER_PROXY`、关联策略设为 `BOOT_ID_AND_SEQ`，并让可信 `adapterAckTopic` 与本模块的
`application-ack-topic` 完全一致。报文自身不能指定或提升可信 ACK Topic。

## 身份、时间与 ACK

设备没有 `messageId`、序号或采集时间时，本模块不会伪造设备事实：

- 每次进程启动生成新的 `bootId`，按设备身份生成单调 `sourceSeq`；
- `canonicalMessageId` 由设备身份、`bootId` 和 `sourceSeq` 派生；
- `collectedAt=null`，`timeSource=ADAPTER_RECEIVED`，只填边缘接收时间
  `adapterReceivedAt`；
- 声明 `ADAPTER_PROXY + BOOT_ID_AND_SEQ`，最终模式仍由平台可信绑定降级决定；
- 收到 `deliveryScope=ADAPTER_ONLY` 且消息编号匹配的应用 ACK，才记为平台 ACK 已收到。

## 运行观测

健康检查和指标默认只绑定 `127.0.0.1:8092`，可查询 `/actuator/health` 与
`/actuator/metrics`。主要计数器：

- `modbus.edge.poll`：完整采样批次结果；
- `modbus.edge.read`：按 `failure.type` 分类的读取失败；
- `modbus.edge.mqtt.publish`：Broker QoS 1 发布结果及 TLS、认证、网络失败分类；
- `modbus.edge.mqtt.connection`：已建立连接的异常断开分类；
- `modbus.edge.application.ack`：平台 ACK 的 `received/unmatched/invalid/timeout`。

日志只记录设备配置名称、失败类型和必要关联编号，不输出 MQTT 密码、私钥、完整证书或
完整原始报文。
