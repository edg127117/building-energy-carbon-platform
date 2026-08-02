# TDengine Stable Hostname Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 TDengine 3.2.3 使用稳定的 `iot-tdengine` 节点身份和独立于 Git worktree 的固定 Docker Volume，并在删除可再生测试数据后验证容器重建、真实写入和再次重启均正常。

**Architecture:** Compose 同时固定容器 hostname、`TAOS_FQDN` 和 `TAOS_FIRST_EP`，使操作系统身份、TDengine dnode 身份和首次连接端点保持一致。`/var/lib/taos` 改用显式命名的 Docker Volume，避免相对 bind mount 随短期 worktree 删除而失效；Java 业务代码和 TDengine 表结构保持不变。

**Tech Stack:** Docker Compose、TDengine 3.2.3.0、JUnit 5、AssertJ、Spring Boot 3.2.4、PowerShell、Maven、MQTT 19 测点模拟器

## Global Constraints

- 固定 hostname、FQDN 和 first endpoint 均为 `iot-tdengine`。
- 固定 Docker Volume 名称为 `iot-platform-demo-tdengine-data`。
- 当前 TDengine 测试数据允许删除，不执行导出或恢复。
- 不停止、删除或重建 `iot-mysql`、`iot-redis`、`iot-emqx`。
- 不修改 Java 生产代码、TDengine 表结构、公式、查询或 API。
- 普通 Maven 自动化测试不得连接真实 MySQL、TDengine、Redis 或 MQTT。
- 真实环境验证只操作容器 `iot-tdengine` 和 Volume `iot-platform-demo-tdengine-data`。

---

### Task 1: 用测试锁定 TDengine 稳定身份和持久化边界

**Files:**
- Modify: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`
- Test: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`

**Interfaces:**
- Consumes: `readCompose()` 返回的 `src/env/docker-compose.yml` 文本。
- Produces: `tdengineUsesStableIdentityAndProjectIndependentVolume()`，锁定 Compose 必须提供的 hostname、FQDN、first endpoint 和固定命名 Volume。

- [ ] **Step 1: 写入失败的 Compose 契约测试**

在 `DockerComposeConfigurationTest` 中增加：

```java
@Test
void tdengineUsesStableIdentityAndProjectIndependentVolume() {
    assertThat(compose)
            .contains(
                    "hostname: iot-tdengine",
                    "TAOS_FQDN: iot-tdengine",
                    "TAOS_FIRST_EP: iot-tdengine:6030",
                    "- tdengine-data:/var/lib/taos",
                    "name: iot-platform-demo-tdengine-data")
            .doesNotContain("- ./taos-data:/var/lib/taos");
    assertThat(countOccurrences(compose, "tdengine-data:")).isEqualTo(2);
}
```

- [ ] **Step 2: 运行测试并确认它因配置尚未实现而失败**

Run:

```powershell
mvn -Dtest=DockerComposeConfigurationTest test
```

Expected: `tdengineUsesStableIdentityAndProjectIndependentVolume` 失败，失败信息指出 Compose 缺少 `hostname: iot-tdengine` 等约束。

- [ ] **Step 3: 检查测试只增加了当前任务的约束**

Run:

```powershell
git diff -- src/test/java/com/platform/config/DockerComposeConfigurationTest.java
git diff --check
```

Expected: 仅新增一个测试方法，无生产代码和无关格式修改。

---

### Task 2: 实现稳定 hostname、FQDN 和固定命名 Volume

**Files:**
- Modify: `src/env/docker-compose.yml`
- Test: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`

**Interfaces:**
- Consumes: Task 1 的 Compose 文本契约。
- Produces: `tdengine` 服务固定身份，以及 Compose 顶层 `tdengine-data` Volume。

- [ ] **Step 1: 修改 TDengine 服务配置**

将 `tdengine` 服务改为：

```yaml
  tdengine:
    image: tdengine/tdengine:3.2.3.0
    container_name: iot-tdengine
    # TDengine 会把 FQDN 写入 dnode 元数据；容器重建后 hostname 变化会拒绝启动。
    hostname: iot-tdengine
    restart: always
    ports:
      - "6030-6049:6030-6049"
      - "6041:6041"       # REST API 端口 (Java JDBC 使用此端口通信)
    environment:
      TZ: Asia/Shanghai
      TAOS_FQDN: iot-tdengine
      TAOS_FIRST_EP: iot-tdengine:6030
    volumes:
      # 固定名称 Volume 不依赖短期 Git worktree，删除 worktree 不会删除时序数据。
      - tdengine-data:/var/lib/taos
    healthcheck:
      test: ["CMD-SHELL", "taos -s 'SHOW DATABASES;' >/dev/null 2>&1"]
      interval: 5s
      timeout: 5s
      retries: 30
      start_period: 20s
```

在文件末尾增加：

```yaml
volumes:
  tdengine-data:
    # 显式名称保证从不同目录执行 Compose 时仍使用同一个 TDengine 数据卷。
    name: iot-platform-demo-tdengine-data
```

- [ ] **Step 2: 渲染 Compose 并确认语法有效**

Run:

```powershell
& 'F:\docker-24.0.7\docker\docker.exe' compose `
  -f src/env/docker-compose.yml config
```

Expected: exit code `0`；渲染结果中 `tdengine.hostname` 为 `iot-tdengine`，
`TAOS_FQDN` 和 `TAOS_FIRST_EP` 正确，Volume 名称为
`iot-platform-demo-tdengine-data`。

- [ ] **Step 3: 运行定向测试并确认通过**

Run:

```powershell
mvn -Dtest=DockerComposeConfigurationTest test
```

Expected: `DockerComposeConfigurationTest` 全部通过。

- [ ] **Step 4: 检查并提交实现**

Run:

```powershell
git diff --check
git diff -- src/env/docker-compose.yml `
  src/test/java/com/platform/config/DockerComposeConfigurationTest.java
git add -- src/env/docker-compose.yml `
  src/test/java/com/platform/config/DockerComposeConfigurationTest.java
git diff --cached --check
git diff --cached --name-only
git commit -m "fix(env): 稳定 TDengine 容器节点身份"
```

Expected: 提交只包含 Compose 和对应契约测试。

---

### Task 3: 只重建 TDengine 并验证新节点身份

**Files:**
- Runtime-only validation; no repository files change.

**Interfaces:**
- Consumes: Task 2 的 Compose 配置和固定 Volume 名称。
- Produces: 全新 `iot-tdengine` 容器，endpoint 为 `iot-tdengine:6030`，其他三个基础服务保持原容器实例。

- [ ] **Step 1: 记录其他基础服务容器 ID**

Run:

```powershell
$docker = 'F:\docker-24.0.7\docker\docker.exe'
$protectedBefore = @{}
foreach ($name in 'iot-mysql','iot-redis','iot-emqx') {
    $protectedBefore[$name] = (& $docker inspect $name --format '{{.Id}}').Trim()
}
$protectedBefore
```

Expected: 三个容器各有一个非空 ID。

- [ ] **Step 2: 停止本次会话启动的 Java 后端**

Run:

```powershell
$listener = Get-NetTCPConnection -State Listen -LocalPort 8081 |
    Select-Object -First 1
if (-not $listener) {
    throw "8081 没有运行中的后端"
}
$backend = Get-Process -Id $listener.OwningProcess
if ($backend.ProcessName -ne 'java') {
    throw "8081 不是本次 Java 后端，拒绝停止：$($backend.ProcessName)"
}
Stop-Process -Id $backend.Id
```

Expected: 端口 `8081` 释放；不停止前端和四个基础容器。

- [ ] **Step 3: 删除旧 TDengine 容器和同名测试 Volume**

Run:

```powershell
& $docker rm -f iot-tdengine
$existingVolume = & $docker volume ls `
    --filter name='^iot-platform-demo-tdengine-data$' `
    --format '{{.Name}}'
if ($existingVolume -eq 'iot-platform-demo-tdengine-data') {
    & $docker volume rm iot-platform-demo-tdengine-data
}
```

Expected: 只删除 `iot-tdengine` 和明确允许清空的 TDengine 测试 Volume。

- [ ] **Step 4: 只创建 TDengine 服务**

Run:

```powershell
& $docker compose -f src/env/docker-compose.yml up -d tdengine
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Expected: 创建 `iot-platform-demo-tdengine-data` 和 `iot-tdengine`；不重新创建其他服务。

- [ ] **Step 5: 等待健康并验证节点身份**

Run:

```powershell
$deadline = (Get-Date).AddMinutes(3)
do {
    $health = (& $docker inspect iot-tdengine `
        --format '{{.State.Health.Status}}').Trim()
    if ($health -eq 'healthy') { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($health -ne 'healthy') { throw "TDengine 未在 3 分钟内健康：$health" }

& $docker exec iot-tdengine hostname
& $docker exec iot-tdengine taos -s 'SHOW DNODES;'
```

Expected: hostname 为 `iot-tdengine`；`SHOW DNODES` endpoint 为
`iot-tdengine:6030`，status 为 `ready`。

- [ ] **Step 6: 确认其他三个容器未被重建**

Run:

```powershell
foreach ($name in 'iot-mysql','iot-redis','iot-emqx') {
    $after = (& $docker inspect $name --format '{{.Id}}').Trim()
    if ($after -ne $protectedBefore[$name]) {
        throw "$name 容器 ID 发生变化，停止验证"
    }
}
```

Expected: 三个 ID 均未变化。

---

### Task 4: 验证真实数据写入和 TDengine 再次重启

**Files:**
- Runtime-only validation; no repository files change.

**Interfaces:**
- Consumes: Task 3 的新 TDengine 容器和现有 Java 后端初始化逻辑。
- Produces: 19 点 `NORMAL` 快照、四项 `SUCCESS` 指标和重启后仍存在的数据。

- [ ] **Step 1: 打包并启动当前分支后端**

先运行：

```powershell
mvn -DskipTests package
$env:MYSQL_PORT = '13306'
$env:REDIS_PORT = '16379'
$env:MQTT_ENABLED = 'true'
```

然后在专用终端中运行以下前台进程，并保持终端打开：

```powershell
java -jar target/iot-platform-demo-1.0-SNAPSHOT.jar `
  --spring.data.redis.host=127.0.0.1 `
  --spring.data.redis.port=16379
```

Expected: 后端监听 `8081`；初始化日志确认创建或检查 `iot_telemetry`
和四张 HVAC 超级表。

- [ ] **Step 2: 运行 19 测点模拟器**

Run from the stable main checkout that already has Node dependencies:

```powershell
node D:\word\iot-platform-demo\.scripts\simulate-hvac-19-points.mjs
```

Expected: 7 轮、每轮 19 条，共发布 133 条 MQTT 消息。

- [ ] **Step 3: 等待分钟冻结并验证 API**

Run:

```powershell
$loginBody = @{
    username = 'admin'
    password = '123456'
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:8081/api/auth/login' `
    -ContentType 'application/json' `
    -Body $loginBody `
    -TimeoutSec 15
$headers = @{ Authorization = 'Bearer ' + [string]$login.data.token }
$deadline = (Get-Date).AddMinutes(3)

do {
    $snapshot = Invoke-RestMethod -Method Get `
        -Uri 'http://127.0.0.1:8081/api/hvac/buildings/BLD001/snapshot' `
        -Headers $headers `
        -TimeoutSec 20
    $indicators = Invoke-RestMethod -Method Get `
        -Uri 'http://127.0.0.1:8081/api/hvac/buildings/BLD001/indicators/latest' `
        -Headers $headers `
        -TimeoutSec 20
    $normal = @($snapshot.data.points |
        Where-Object status -eq 'NORMAL').Count
    $success = @($indicators.data.indicators |
        Where-Object status -eq 'SUCCESS').Count
    if ($snapshot.data.points.Count -eq 19 -and
        $normal -eq 19 -and
        $indicators.data.indicators.Count -eq 4 -and
        $success -eq 4) {
        break
    }
    Start-Sleep -Seconds 10
} while ((Get-Date) -lt $deadline)

if ($snapshot.data.points.Count -ne 19 -or
    $normal -ne 19 -or
    $indicators.data.indicators.Count -ne 4 -or
    $success -ne 4) {
    throw "真实链路未收口：points=$($snapshot.data.points.Count), NORMAL=$normal, indicators=$($indicators.data.indicators.Count), SUCCESS=$success"
}
```

Expected:

```text
snapshot points = 19
NORMAL = 19
indicators = 4
SUCCESS = 4
```

- [ ] **Step 4: 记录数据量并重启 TDengine**

Run:

```powershell
$rawBeforeOutput = & $docker exec iot-tdengine taos -s `
    'SELECT COUNT(*) FROM iot_telemetry.st_raw_event;'
$rawBeforeMatch = [regex]::Match(
    ($rawBeforeOutput -join "`n"),
    '(?m)^\s*(\d+)\s*\|')
if (-not $rawBeforeMatch.Success) {
    throw "无法解析重启前原始事件行数"
}
$rawBefore = [long]$rawBeforeMatch.Groups[1].Value
if ($rawBefore -le 0) {
    throw "重启前原始事件为空"
}
& $docker restart iot-tdengine
```

Expected: 重启命令只影响 `iot-tdengine`。

- [ ] **Step 5: 再次等待健康并验证数据仍存在**

Run:

```powershell
$deadline = (Get-Date).AddMinutes(3)
do {
    $health = (& $docker inspect iot-tdengine `
        --format '{{.State.Health.Status}}').Trim()
    if ($health -eq 'healthy') { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($health -ne 'healthy') { throw "TDengine 重启后未恢复健康：$health" }

& $docker exec iot-tdengine hostname
& $docker exec iot-tdengine taos -s 'SHOW DNODES;'
$rawAfterOutput = & $docker exec iot-tdengine taos -s `
    'SELECT COUNT(*) FROM iot_telemetry.st_raw_event;'
$rawAfterMatch = [regex]::Match(
    ($rawAfterOutput -join "`n"),
    '(?m)^\s*(\d+)\s*\|')
if (-not $rawAfterMatch.Success) {
    throw "无法解析重启后原始事件行数"
}
$rawAfter = [long]$rawAfterMatch.Groups[1].Value
if ($rawAfter -ne $rawBefore) {
    throw "TDengine 重启前后数据量不一致：before=$rawBefore, after=$rawAfter"
}
```

Expected: hostname 和 endpoint 保持 `iot-tdengine`；原始事件行数大于 `0`
且与重启前一致。

---

### Task 5: 完整回归、注释检查和交付

**Files:**
- Verify: `src/env/docker-compose.yml`
- Verify: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`
- Verify: `docs/superpowers/specs/2026-07-31-tdengine-stable-hostname-design.md`
- Verify: `docs/superpowers/plans/2026-07-31-tdengine-stable-hostname-plan.md`

**Interfaces:**
- Consumes: 所有实现和真实环境验证结果。
- Produces: 可推送的单一修复分支和完整 PR 材料。

- [ ] **Step 1: 运行完整 Maven 测试**

Run:

```powershell
mvn test
```

Expected: 全部测试通过，记录 tests run、failures、errors 和 skipped。

- [ ] **Step 2: 检查注释和任务范围**

Run:

```powershell
git diff --check origin/main...HEAD
git diff --name-status origin/main...HEAD
git status --short --branch
```

Expected:

```text
docs/superpowers/specs/2026-07-31-tdengine-stable-hostname-design.md
docs/superpowers/plans/2026-07-31-tdengine-stable-hostname-plan.md
src/env/docker-compose.yml
src/test/java/com/platform/config/DockerComposeConfigurationTest.java
```

中文注释需解释 TDengine 为什么必须固定 hostname/FQDN，以及为什么命名
Volume 不能依赖 worktree；不得出现逐行翻译或无关重构。

- [ ] **Step 3: 如果计划文档尚未提交，单独提交计划**

Run:

```powershell
git add -- docs/superpowers/plans/2026-07-31-tdengine-stable-hostname-plan.md
git diff --cached --check
git commit -m "docs(env): 规划 TDengine 稳定 hostname 实施"
```

Expected: 计划文档形成独立提交；若已在编码前提交，本步骤只验证提交存在。

- [ ] **Step 4: 推送任务分支**

Run:

```powershell
git push -u origin fix/tdengine-stable-hostname
```

Expected: 远程分支创建成功，不直接更新 `main`。

- [ ] **Step 5: 准备 PR 材料**

PR 标题：

```text
fix(env): 稳定 TDengine 容器节点身份
```

PR 说明必须记录：

- 固定 hostname/FQDN/first endpoint；
- 切换到固定命名 TDengine Volume；
- 当前本地 TDengine 测试数据已按批准范围删除；
- MySQL、Redis、EMQX 容器 ID 未变化；
- 定向测试、完整 Maven 测试、Compose config 和真实重启验证结果；
- 不包含 Java 业务代码、表结构、公式、查询或 API 修改。
