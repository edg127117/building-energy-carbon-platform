# Configurable Local Infrastructure Ports Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Docker MySQL 和 Redis 在本机使用 `13306`、`16379`，同时保留项目标准端口默认值并让 Spring Boot 自动连接正确端口。

**Architecture:** Docker Compose 使用现有 `MYSQL_PORT`、`REDIS_PORT` 环境变量决定宿主机发布端口，容器内部仍使用 `3306`、`6379`。Spring Boot 已读取同名变量，因此 Compose 和后端共享单一端口来源；本机通过用户环境变量持久覆盖，仓库默认行为不变。

**Tech Stack:** Docker Desktop 4.84.0、Docker Compose v5、PowerShell、Spring Boot、Maven、Node.js

## Global Constraints

- 仓库默认端口必须继续为 MySQL `3306`、Redis `6379`。
- 本机覆盖端口固定为 MySQL `13306`、Redis `16379`。
- 不停止、不禁用、不修改现有 Windows `MySQL80` 和 `Redis` 服务。
- 不修改容器内部端口，不迁移或删除 `mysql-data`、`redis-data`、`taos-data`。
- 只修改端口配置和对应文档，不进行无关重构。
- 不暂存或提交现有未跟踪目录 `outputs/`。

---

### Task 1: 参数化 Compose 端口并同步文档

**Files:**
- Modify: `src/env/docker-compose.yml:10`
- Modify: `src/env/docker-compose.yml:57`
- Modify: `server.env.example:5-8`
- Modify: `docs/MQTT-硬件数据对接说明.md:188-206`

**Interfaces:**
- Consumes: PowerShell 环境变量 `MYSQL_PORT`、`REDIS_PORT`
- Produces: Compose 宿主机端口映射和 Spring Boot JDBC/Lettuce 连接端口

- [ ] **Step 1: 验证现有配置不能覆盖端口**

Run:

```powershell
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
docker compose -f src/env/docker-compose.yml config |
  Select-String -Pattern 'published: "13306"','published: "16379"'
```

Expected: 没有匹配输出，因为当前 Compose 固定发布 `3306` 和 `6379`。

- [ ] **Step 2: 修改 Compose 的宿主机端口映射**

将 MySQL 和 Redis 的 `ports` 改为：

```yaml
mysql:
  ports:
    - "${MYSQL_PORT:-3306}:3306"

redis:
  ports:
    - "${REDIS_PORT:-6379}:6379"
```

- [ ] **Step 3: 补充环境变量示例说明**

在 `server.env.example` 的端口变量前加入：

```dotenv
# Local Compose host ports and Spring Boot connection ports.
# Keep the defaults unless the host already runs MySQL or Redis.
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=change-me

REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=0
```

- [ ] **Step 4: 更新冒烟文档**

在默认端口表后说明：

````markdown
本机已有 MySQL 或 Redis 时，可在启动 Compose 和后端前设置相同的端口变量。
例如 MySQL 使用 `13306`、Redis 使用 `16379`：

```powershell
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
```

Compose 只改变宿主机发布端口；容器内部仍使用标准端口。后端读取同名变量，
因此不需要另外修改 `application.yml`。
````

- [ ] **Step 5: 验证覆盖值和默认值**

Run with overrides:

```powershell
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
docker compose -f src/env/docker-compose.yml config |
  Select-String -Pattern 'published: "13306"','published: "16379"'
```

Expected: 分别匹配 MySQL `13306` 和 Redis `16379`。

Run without overrides in a clean child process:

```powershell
powershell -NoProfile -Command {
  Remove-Item Env:MYSQL_PORT,Env:REDIS_PORT -ErrorAction SilentlyContinue
  docker compose -f src/env/docker-compose.yml config
} | Select-String -Pattern 'published: "3306"','published: "6379"'
```

Expected: 分别匹配默认 MySQL `3306` 和 Redis `6379`。

- [ ] **Step 6: 检查并提交配置变更**

```powershell
git diff --check
git add -- src/env/docker-compose.yml server.env.example docs/MQTT-硬件数据对接说明.md
git diff --cached --name-only
git commit -m "chore(env): support configurable local ports"
```

Expected: 只提交以上三个文件，不包含 `outputs/`。

### Task 2: 持久设置本机端口并启动基础设施

**Files:**
- No repository files

**Interfaces:**
- Consumes: 用户环境变量 `MYSQL_PORT=13306`、`REDIS_PORT=16379`
- Produces: `iot-mysql`、`iot-emqx`、`iot-tdengine`、`iot-redis`

- [ ] **Step 1: 确认新端口空闲且旧服务仍运行**

```powershell
netstat -ano -p tcp |
  Select-String -Pattern ':13306\s',':16379\s'
sc.exe query MySQL80
sc.exe query Redis
```

Expected: 新端口没有监听项；两个 Windows 服务状态均为 `RUNNING`。

- [ ] **Step 2: 持久设置用户环境变量**

```powershell
[Environment]::SetEnvironmentVariable("MYSQL_PORT", "13306", "User")
[Environment]::SetEnvironmentVariable("REDIS_PORT", "16379", "User")
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
```

Expected: 新 PowerShell 自动继承，当前会话立即使用相同值。

- [ ] **Step 3: 拉取并启动四个服务**

```powershell
docker compose -f src/env/docker-compose.yml pull
docker compose -f src/env/docker-compose.yml up -d
docker compose -f src/env/docker-compose.yml ps
```

Expected: `iot-mysql`、`iot-emqx`、`iot-tdengine`、`iot-redis` 均为运行状态；MySQL 发布到 `13306`，Redis 发布到 `16379`。

- [ ] **Step 4: 验证基础设施**

```powershell
docker exec iot-mysql mysqladmin ping -h 127.0.0.1 -uroot -pchange-me
docker exec iot-redis redis-cli PING
docker exec iot-tdengine taos -s "SHOW DATABASES;"
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18083
```

Expected: MySQL 输出 `mysqld is alive`；Redis 输出 `PONG`；TDengine 返回数据库列表；EMQX 返回 HTTP 200。

### Task 3: 运行 HVAC 19 测点完整冒烟

**Files:**
- Read: `docs/MQTT-硬件数据对接说明.md:183`
- No repository files

**Interfaces:**
- Consumes: 四个 Docker 基础设施服务和本机端口变量
- Produces: TDengine 分钟指标、Redis 最新状态和三个只读 API 响应

- [ ] **Step 1: 使用本机端口启动后端**

```powershell
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
mvn spring-boot:run
```

Expected: 后端在 `http://127.0.0.1:8081/api` 启动，日志中无 MySQL、Redis、MQTT 或 TDengine 连接失败。

- [ ] **Step 2: 发布完整 19 测点并等待冻结**

```powershell
node .scripts/simulate-hvac-19-points.mjs
Start-Sleep -Seconds 90
```

Expected: 模拟器发布 7 轮且退出码为 0。

- [ ] **Step 3: 核对指标存储**

```powershell
docker exec iot-tdengine taos -s "SELECT indicator_code, ts, val, data_quality, formula_version FROM iot_telemetry.st_indicator_minute ORDER BY ts DESC LIMIT 4;"
docker exec iot-redis redis-cli --scan --pattern "iot:indicator:latest:*"
```

Expected: 返回 `WCR_COP`、`TOWER_EFF`、`PUMP_EFF`、`AHU_POW_EFF` 四个成功指标。

- [ ] **Step 4: 验证三个只读 API**

```powershell
$login = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"123456"}'
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$latest = Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:8081/api/hvac/buildings/BLD001/indicators/latest"
$minuteStart = ($latest.data.indicators | Where-Object indicatorId -eq "INDICATOR_WCR_COP_B1").minuteStart
$minuteEnd = $minuteStart + 60000
$history = Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:8081/api/hvac/indicators/INDICATOR_WCR_COP_B1/history?from=$minuteStart&to=$minuteEnd"
$calculation = Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:8081/api/hvac/indicators/INDICATOR_WCR_COP_B1/calculations/$minuteStart"
$latest.data.indicators
$history.data
$calculation.data
```

Expected: 最新接口有四个 `SUCCESS` 指标；历史接口返回该分钟 COP；计算详情包含输入、步骤、质量和公式版本。

- [ ] **Step 5: 验证缺少水泵功率的失败分钟**

```powershell
Start-Sleep -Seconds (61 - (Get-Date).Second)
$env:HVAC_OMIT_POINT = "PUMP1_Power"
try {
  node .scripts/simulate-hvac-19-points.mjs
} finally {
  Remove-Item Env:HVAC_OMIT_POINT -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 90
docker exec iot-tdengine taos -s "SELECT indicator_code, ts, calc_status, reason_code, missing_inputs, formula_version FROM iot_telemetry.st_formula_calc_exception WHERE indicator_code='PUMP_EFF' ORDER BY ts DESC LIMIT 1;"
docker exec iot-redis redis-cli GET "iot:indicator:latest:INDICATOR_PUMP_EFF_B1"
```

Expected: `PUMP_EFF` 为 `MISSING_INPUT` 且 `value=null`，缺失项包含 `Pc/PPE`；同一分钟另外三个指标成功。

### Task 4: 回归检查并推送任务分支

**Files:**
- Verify: `src/env/docker-compose.yml`
- Verify: `server.env.example`
- Verify: `docs/MQTT-硬件数据对接说明.md`

**Interfaces:**
- Consumes: Task 1 的配置变更和 Task 2、Task 3 的验证结果
- Produces: 可供用户创建 PR 的远程任务分支

- [ ] **Step 1: 运行回归和无关文件检查**

```powershell
mvn test
git status --short
git diff origin/main...HEAD --check
git diff origin/main...HEAD --name-only
```

Expected: Maven 测试通过；变更只包含设计、计划和三个配置/文档文件；`outputs/` 保持未跟踪且未提交。

- [ ] **Step 2: 推送分支**

```powershell
git push -u origin chore/configurable-local-ports
```

Expected: 远程分支创建成功，可通过以下链接创建 PR：

```text
https://github.com/edg127117/iot-platform-demo/compare/main...chore/configurable-local-ports?expand=1
```
