# MQTT 测试隔离与干净 Docker 冒烟设计

## 1. 背景

旧电表 Demo 已从 `main` 的有效运行代码中删除，但全面测试前仍有两类环境风险：

- 普通 Spring 自动化测试会无条件创建 MQTT Client，并可能连接本机真实 EMQX；
- 当前四个基础设施容器来自旧 `ed53` 工作树，其 MySQL 初始化仍挂载已经删除的
  `02-init-10000-devices.sql`，数据目录也不属于最新主工作目录。

此外，最新 `src/env/docker-compose.yml` 仍引用已经删除的初始化文件，并保留
“电表数据”等旧注释。代码级清理已经完成，但尚未通过一次从空数据开始的真实
Docker 启动和 HVAC MQTT 链路验证。

## 2. 目标

本任务完成后应达到：

- 普通自动化测试不创建 MQTT Client、不连接 Broker、不订阅真实主题；
- 生产和真实冒烟环境默认启用 MQTT HVAC 上行链路；
- Docker Compose 可以在空数据目录中正常初始化四项基础设施；
- 当前旧工作树容器和测试数据被安全替换为最新 `main` 环境；
- MySQL 和 TDengine 中不存在旧电表表或超级表；
- 一轮受控 HVAC 19 测点 MQTT 数据能够进入后端并写入 TDengine；
- Docker 清理、启动和验收步骤可以重复执行，并具备明确的失败信息。

## 3. 非目标

本任务不包含：

- 正式 HVAC 前端功能建设；
- WebSocket 鉴权和建筑订阅隔离；
- HVAC 控制下行；
- Modbus、BACnet 或其他协议接入；
- 对生产数据库执行数据迁移；
- 重构采集、公式、数据质量或查询业务；
- 删除 `docs/superpowers` 中的历史设计记录；
- 清理与本项目无关的 Docker 容器、镜像或数据卷。

## 4. 方案选择

评估过以下方案：

1. 在独立临时容器中冒烟，不影响当前环境。风险最低，但无法让当前实际测试
   环境摆脱旧工作树和旧数据目录。
2. 停止当前四个测试容器，删除其测试数据，再从最新 `main` 重建。能够同时
   验证干净启动并完成当前环境切换。
3. 只提供手工命令，不固化脚本。改动最少，但难以重复验收，也容易误删错误
   目录或漏掉检查。

本任务采用方案 2。用户已确认当前数据库均为测试数据，可以删除并重建。

## 5. MQTT 测试隔离设计

### 5.1 配置开关

新增 `mqtt.enabled`：

- `application.yml` 默认值为 `true`，保持生产和本地真实运行行为；
- `application-test.yml` 明确设置为 `false`；
- 配置说明使用中文，明确开启时创建 Client 并订阅 HVAC 上行主题，关闭时不
  连接任何 Broker。

### 5.2 Bean 边界

`MqttConfig` 使用 Spring 条件配置，仅在 `mqtt.enabled=true` 或未显式配置时
注册。关闭时以下 Bean 都不存在：

- `IMqttClient`
- MQTT 启动连接 `CommandLineRunner`

`HvacMqttMessageHandler`、采集业务和 Repository 不受该开关影响，专项单元测试
仍可直接实例化 `MqttConfig` 并验证报文解析、QoS 1 手动 ACK 和存储失败不确认
语义。

### 5.3 自动化验证

新增配置边界测试，锁定：

- `MqttConfig` 的条件属性名称、开启值和默认开启策略；
- 测试 profile 必须显式关闭 MQTT；
- 普通 Spring 上下文中不存在 `IMqttClient`，从而不能连接真实 EMQX。

## 6. Docker Compose 修正

`src/env/docker-compose.yml` 做最小必要调整：

- 删除 Compose 已废弃的顶层 `version`；
- 删除不存在的 `02-init-10000-devices.sql` 挂载；
- MySQL 注释改为正式权限、建筑、设备和测点档案；
- EMQX 注释改为 HVAC 19 测点 MQTT 上行；
- TDengine 注释改为 HVAC 原始事件、分钟数据和指标数据；
- 为 MySQL、EMQX、TDengine 和 Redis 增加可用于脚本等待的健康检查；
- 保留现有端口环境变量和固定容器名，避免扩大部署改造范围。

## 7. 数据清理安全边界

### 7.1 容器范围

只允许处理以下容器：

- `iot-mysql`
- `iot-emqx`
- `iot-tdengine`
- `iot-redis`

删除前检查容器 Compose 标签和挂载来源。发现名称相同但不属于预期 Compose
项目，或挂载路径超出允许根目录时，脚本立即失败。

### 7.2 文件范围

允许删除的旧测试数据仅限：

- `C:\Users\yang\.codex\worktrees\ed53\iot-platform-demo\src\env\mysql-data`
- `C:\Users\yang\.codex\worktrees\ed53\iot-platform-demo\src\env\taos-data`
- `C:\Users\yang\.codex\worktrees\ed53\iot-platform-demo\src\env\redis-data`

允许重置的最新主目录测试数据仅限：

- `D:\word\iot-platform-demo\src\env\mysql-data`
- `D:\word\iot-platform-demo\src\env\taos-data`
- `D:\word\iot-platform-demo\src\env\redis-data`

脚本必须解析绝对路径并验证目标位于上述 `src\env` 目录内，禁止使用未解析的
通配符、环境变量或递归删除仓库根目录。`outputs`、源码、其他工作树和其他
Docker 项目不在清理范围。

### 7.3 运行进程

如果后端端口 `8081` 已被未知进程占用，脚本停止并报告，不自动结束 IDE 或
其他 Java 进程。脚本只终止自己启动的 Spring Boot 进程。

## 8. 冒烟流程

冒烟脚本按以下顺序执行：

1. 检查 Docker Engine、Compose、Java 21、Maven、Node.js 和项目依赖；
2. 输出将删除的容器、匿名卷和绑定数据目录；
3. 只有传入明确的重置参数后才执行删除；
4. 停止并移除四个旧容器及其 Compose 匿名卷；
5. 删除经过绝对路径校验的 MySQL、TDengine 和 Redis 测试数据目录；
6. 使用最新 `src/env/docker-compose.yml` 创建全新基础设施；
7. 在限定时间内等待四个容器健康；
8. 启动 Spring Boot，并等待 `/api/actuator/health`；
9. 发布一轮冻结书定义的 HVAC 19 测点数据；
10. 查询 MySQL、TDengine 和后端接口，完成结构与链路断言；
11. 输出逐项通过结果和日志位置；
12. 停止脚本启动的后端进程，保留四个全新容器继续运行。

任一步骤失败时，脚本返回非零退出码，保留新基础设施和日志用于诊断，不伪造
后续通过结果。

## 9. 验收标准

必须同时满足：

- `docker compose config` 成功且不引用不存在的文件；
- 四个基础设施容器均处于健康状态；
- Spring Boot 健康接口成功；
- MySQL 正式权限和 HVAC 表存在；
- MySQL 的 `iot_device`、`iot_device_status_log`、`control_commands` 不存在；
- TDengine 的 `st_raw_event`、`st_raw_minute`、`st_indicator_minute` 和
  `st_formula_calc_exception` 存在；
- TDengine 的 `st_electric_data` 不存在；
- MQTT 一轮 19 测点发布成功；
- TDengine 能查到本轮原始事件，且覆盖 19 个冻结测点；
- 自动化测试期间没有真实 MQTT Client；
- `mvn test` 全部通过；
- 前端测试和生产构建通过；
- Git 变更不包含生成数据、运行日志、数据库文件和无关文件。

## 10. 交付方式

设计文档、实施计划、代码、脚本和测试位于同一个
`test/mqtt-docker-clean-smoke` 分支。设计阶段不单独推送文档 PR；所有实现和
验证完成后统一提交、推送，并向用户提供 PR 创建材料。
