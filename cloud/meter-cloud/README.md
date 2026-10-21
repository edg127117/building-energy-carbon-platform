# 云端电表原始报文留档

此目录收录既有独立 Python `meter-cloud` 服务及原始报文留档修复，不参与 Maven 构建。`service.py` 与本次已部署修复源码一致；收录不触发部署、设备配置下发或历史补迁。既有新协议回执和首次北京时间配置行为保持不变，没有新增控制入口。

## 链路与证据边界

- 固定连接同机 `127.0.0.1:1883`，订阅 `device/raw/energy/up`，请求 QoS 1；MQTT 客户端 ID 为 `meter-cloud-receipt-v1`，启用持久会话与手动传输确认。同一 Broker 不应同时运行两个使用该 ID 的实例。
- 所有收到的原始字节先写入 `raw_uploads.sqlite3`，包括旧版 `SN + param`、保留报文和无效 JSON。记录接收 UTC 毫秒、Topic、QoS、Retain、SHA256、原始字节和可选自报 SN；随机事件 ID 只标识一次接收，不代表设备序号或业务幂等键。
- 旧版报文不再因缺少 `device_id` 而丢失原始证据，但不会获得伪造的应用 ACK。重复投递各自留档，不按报文摘要全局去重；相同数值可能来自不同采样时刻。
- 原始库使用 WAL、`synchronous=FULL`，数据库在 SQLite 打开前设为 `0600`。提交失败向外抛出，QoS 1/2 不发送传输 ACK；应由运行管理器重启进程。QoS 0 没有此重投保证；留档不能承诺 Broker 前、设备离线期间或进程未订阅时不丢数据。
- 原始提交成功后，既有 `Receipts` 校验 `device_id/seq/need_time`，有效新协议先提交 `receipts.sqlite3`，再发布 `EMETER/{device}/REPLY`。无效/旧协议及回执库失败不发应用 ACK，但原始留档成功仍可确认 MQTT 接收；应用应按原协议重试。回复入队成功也不代表设备已收到回复。
- **留档、MQTT 传输确认和新协议回执均不等于业务入库。** 业务测点解析仍由 [Java telemetry-adapter](../../telemetry-adapter) 转为 V2，再由平台保存 MySQL 回执及 TDengine 时序并返回 `PLATFORM_PERSISTED`。此 Python 服务不猜测身份映射、倍率、单位或采样时间，不把接收时间冒充设备时钟。

## 运行条件

目标为 Linux / Python 3.10+（使用 POSIX 文件权限），依赖见 `requirements.txt`。在独立虚拟环境安装依赖，并通过已有受保护的服务配置提供：

| 环境变量 | 说明 |
| --- | --- |
| `METER_CLOUD_USER` | Broker 的专用服务账号 |
| `METER_CLOUD_PASSWORD` | 对应密码；不得提交到仓库或写入命令历史 |
| `METER_CLOUD_DATA` | 可选，默认 `/var/lib/meter-cloud`；服务账号独占并可写的数据目录 |

服务账号只需订阅上述上传 Topic、发布既有回复 Topic 的权限，不应使用超级用户。数据目录由运维预先以该账号建立并限制目录访问。使用已有运行管理器执行 `python3 service.py`，设置异常重启；本目录不修改现有服务单元、凭据或网络配置。`timedatectl` 不可用或 NTP 未同步时，仍保存有效报文并回复 ACK，但不下发时间。

原始留档尚未提供自动清理、容量上限或自动恢复工具；部署方需监控磁盘、制定保留策略，并使用 SQLite 一致性备份方式保存数据库。运行中不可只复制主文件而忽略 WAL。回退源码不应删除现有数据库；历史补迁须独立审核身份、接收时间与重复投递来源。

## 离线验证

在 Linux 上从仓库根目录执行（不需要 Broker、设备、数据库服务器或运行凭据）：

```bash
python3 -m unittest discover -s cloud/meter-cloud -p 'test_*.py' -v
```

测试只用合成报文、客户端替身及临时 SQLite 文件，覆盖原始字节保留、新旧协议兼容、ACK 顺序、存储失败、冲突/重投、QoS 0、保留报文、SQLite 重开及文件权限。该命令不能代替真实设备、平台双库、现场网络或长期运行验收。Windows 不提供本服务依赖的 POSIX 权限语义；完整测试使用 Linux CI。
