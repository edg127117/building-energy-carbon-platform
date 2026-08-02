# TDengine 稳定 hostname 与本地数据卷设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 1. 背景

当前本地环境使用 `tdengine/tdengine:3.2.3.0`。Compose 未显式设置
容器 hostname，Docker 每次创建容器时可能生成不同的随机 hostname。
TDengine 会把首次启动时的 FQDN 写入数据目录并用它标识 dnode；容器重建后，
如果运行时 hostname 与持久化元数据不一致，TDengine 可能拒绝启动。

现有实例的 dnode endpoint 为 `ea7c1555eecd:6030`。当前 TDengine 中只有
19 测点模拟器生成的本地测试数据：

- 原始事件 1121 条；
- 分钟数据 456 条；
- 成功指标 96 条；
- 公式异常记录 484 条。

这些数据都可重新生成，不属于需要保留的业务配置或审计事实。建筑、设备、
测点和用户配置仍由 MySQL 保存，不在本次清理范围内。

此外，当前 Compose 使用相对 bind mount。容器若从短期 Git worktree 启动，
数据目录会绑定到该 worktree；删除 worktree 后，容器的持久化路径也会失效。
因此本次同时把 TDengine 数据目录切换为固定名称的 Docker Volume。

## 2. 目标

1. TDengine 容器每次创建都使用固定 hostname `iot-tdengine`。
2. TDengine dnode FQDN 与容器 hostname 始终一致。
3. TDengine 数据卷不依赖任意 Git worktree 的生命周期。
4. 容器重建和普通重启后，TDengine 均能正常启动并保留新生成的数据。
5. 删除当前可再生的 TDengine 测试数据后，重新跑通 19 测点真实链路。

## 3. 非目标

- 不迁移或保留当前 TDengine 测试数据。
- 不修改 MySQL、Redis、EMQX 的容器或数据。
- 不修改 Java 业务代码、TDengine 表结构、公式、查询或 API。
- 不在本次任务中统一改造 MySQL、Redis 的持久化方式。
- 不升级 TDengine 版本。

## 4. 方案选择

### 4.1 采用方案

在 `src/env/docker-compose.yml` 的 `tdengine` 服务中：

```yaml
hostname: iot-tdengine
environment:
  TZ: Asia/Shanghai
  TAOS_FQDN: iot-tdengine
  TAOS_FIRST_EP: iot-tdengine:6030
volumes:
  - tdengine-data:/var/lib/taos
```

在 Compose 顶层声明固定名称的 Volume：

```yaml
volumes:
  tdengine-data:
    name: iot-platform-demo-tdengine-data
```

`hostname` 固定容器操作系统看到的主机名；`TAOS_FQDN` 固定 TDengine
写入 dnode 元数据的节点身份；`TAOS_FIRST_EP` 明确单节点首次加入时连接
自身。三者使用同一个名称，避免容器身份、TDengine 配置和持久化元数据分叉。

显式设置 Volume 的 `name`，确保从不同目录或不同 worktree 执行 Compose
时仍复用同一个 TDengine 数据卷，而不是跟随 Compose project name 创建
不同的数据卷。

### 4.2 未采用方案

1. **继续使用旧随机 hostname `ea7c1555eecd`**：能够兼容当前实例，
   但随机值不可移植，也无法保证其他开发环境一致。
2. **导出并恢复当前 TDengine 数据**：技术上可行，但现有数据全部来自
   本地模拟器，没有保留价值，会增加迁移和回滚复杂度。
3. **只增加 `hostname`，继续使用相对 bind mount**：只能解决节点身份，
   无法解决 worktree 被清理后数据目录失效的问题。

## 5. 运行时迁移

迁移只处理 `iot-tdengine`：

1. 停止本地 Java 后端，避免迁移期间继续访问 TDengine。
2. 确认 MySQL、Redis、EMQX 容器保持运行，不执行整组
   `docker compose down`。
3. 删除旧的 `iot-tdengine` 容器；当前 TDengine 测试数据不备份。
4. 使用修改后的 Compose 创建 `iot-platform-demo-tdengine-data`
   并重新创建 `iot-tdengine`。
5. 等待 TDengine healthcheck 通过，检查 `SHOW DNODES` 的 endpoint 为
   `iot-tdengine:6030`。
6. 启动 Java 后端，由现有初始化流程创建 `iot_telemetry` 数据库和四张
   HVAC 超级表。
7. 运行 19 测点模拟器，验证原始事件、分钟冻结和四项指标重新生成。
8. 重启 `iot-tdengine`，再次检查 healthcheck、`SHOW DNODES` 和数据行，
   证明固定 hostname 与命名 Volume 在重启后有效。

迁移过程不得删除或重建 `iot-mysql`、`iot-redis`、`iot-emqx`，也不得
删除它们的挂载数据。

## 6. 自动化约束

扩展 `DockerComposeConfigurationTest`，至少锁定以下契约：

- TDengine 服务包含 `hostname: iot-tdengine`；
- `TAOS_FQDN` 为 `iot-tdengine`；
- `TAOS_FIRST_EP` 为 `iot-tdengine:6030`；
- `/var/lib/taos` 使用 `tdengine-data`，不再使用 `./taos-data`；
- 顶层 Volume 的固定名称为 `iot-platform-demo-tdengine-data`。

测试只读取 Compose 文件，不连接真实 Docker，保持普通 Maven 测试与外部
环境隔离。

## 7. 验证

### 7.1 自动化测试

- 定向运行 `DockerComposeConfigurationTest`；
- 运行完整 `mvn test`；
- 运行 `docker compose config`，确认 Compose 语法和 Volume 引用有效。

### 7.2 真实环境验证

- 新建容器后 healthcheck 为 `healthy`；
- 容器内 `hostname` 返回 `iot-tdengine`；
- `SHOW DNODES` 返回 `iot-tdengine:6030` 且状态为 `ready`；
- 后端启动后存在 `iot_telemetry` 和四张 HVAC 超级表；
- 模拟器发布 19 测点后，快照为 19/19 `NORMAL`；
- 已冻结分钟的四项指标均为 `SUCCESS`；
- 重启 TDengine 后上述 dnode 身份与新数据仍存在。

## 8. 失败处理与回滚

- 如果新容器无法启动，不操作其他三个基础服务；收集 TDengine 日志和
  Compose 渲染结果后停止。
- 如果 `SHOW DNODES` 不是 `iot-tdengine:6030`，不继续写入模拟数据，
  先修正 hostname/FQDN 配置。
- 如果后端初始化失败，保留新命名 Volume，排查初始化日志，不清理
  MySQL、Redis 或 EMQX。
- 代码回滚只需恢复 Compose 和契约测试；本次旧 TDengine 测试数据明确
  不保留，回滚不承诺恢复旧数据。

## 9. 完成标准

- Compose、自动化测试和中文注释保持一致；
- TDengine 使用稳定 hostname 与固定命名 Volume；
- 定向测试、完整 Maven 测试和 Compose 配置检查通过；
- 真实 TDengine 新建、写入、重启验证通过；
- 任务分支只包含本设计、Compose 和对应契约测试；
- MySQL、Redis、EMQX 及其数据未被删除或重建。
