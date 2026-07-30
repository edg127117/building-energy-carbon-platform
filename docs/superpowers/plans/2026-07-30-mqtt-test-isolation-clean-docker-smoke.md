# MQTT Test Isolation and Clean Docker Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent ordinary tests from connecting to real MQTT, replace the stale Docker test environment with a clean HVAC-only stack, and prove the 19-point MQTT-to-TDengine path end to end.

**Architecture:** Gate the complete MQTT Spring configuration with one deployment property while leaving the ingestion domain independently testable. Correct the existing Compose file instead of introducing a second deployment model, then use one guarded PowerShell runner to validate exact deletion targets, rebuild infrastructure, launch the application, publish the frozen 19-point set, and assert both positive HVAC structures and negative legacy-meter structures.

**Tech Stack:** Java 21, Spring Boot 3.2, JUnit 5, Maven Wrapper, Docker Desktop 4.84, Docker Engine 29.6, Docker Compose v5, MySQL 8, EMQX 5.6, TDengine 3.2.3, Redis 7.2, PowerShell, Node.js MQTT 5.

## Global Constraints

- Keep V1 as a monolith; do not introduce a new service or protocol abstraction.
- `mqtt.enabled` defaults to `true` outside tests and is explicitly `false` in the test profile.
- MySQL remains the business/configuration store; TDengine remains the time-series store.
- Only the four exact project containers and exact `mysql-data`, `taos-data`, and `redis-data` children may be deleted.
- Do not delete source files, `outputs`, worktrees, unrelated containers, images, or volumes.
- Do not stop an unknown process occupying port `8081`.
- Use direct Chinese comments to explain configuration switches, resource boundaries, and destructive safeguards.
- Keep the accepted design, implementation plan, code, tests, and smoke evidence in `test/mqtt-docker-clean-smoke`; do not create a documentation-only PR.

---

### Task 1: Disable MQTT infrastructure in the test profile

**Files:**
- Modify: `src/main/java/com/platform/config/MqttConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `server.env.example`
- Modify: `src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java`
- Create: `src/test/java/com/platform/config/MqttTestProfileIsolationTest.java`

**Interfaces:**
- Consumes: Spring Boot property binding through `mqtt.enabled`.
- Produces: `MqttConfig` registered only when `mqtt.enabled=true` or the property is absent.

- [ ] **Step 1: Write the failing annotation contract test**

Add to `DataSourceIsolationConfigurationTest`:

```java
@Test
void mqttInfrastructureCanBeDisabledByProperty() {
    ConditionalOnProperty condition =
            MqttConfig.class.getAnnotation(ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("mqtt");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isTrue();
}
```

- [ ] **Step 2: Write the failing test-profile context test**

Create `MqttTestProfileIsolationTest`:

```java
package com.platform.config;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证普通自动化测试不会创建 MQTT 基础设施，避免测试订阅并确认真实 HVAC 报文。
 */
@SpringBootTest
@ActiveProfiles("test")
class MqttTestProfileIsolationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void testProfileDoesNotRegisterMqttClientOrConnectionRunner() {
        assertThat(applicationContext.getBeansOfType(MqttConfig.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(IMqttClient.class)).isEmpty();
    }
}
```

- [ ] **Step 3: Run the targeted tests and verify the new contracts fail**

Run:

```powershell
.\mvnw.cmd -Dtest=DataSourceIsolationConfigurationTest,MqttTestProfileIsolationTest test
```

Expected: the annotation test fails because `MqttConfig` has no
`@ConditionalOnProperty`; the context test may also expose an `IMqttClient`.

- [ ] **Step 4: Gate the complete MQTT configuration**

Add the import and class annotation in `MqttConfig`:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(
        prefix = "mqtt",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MqttConfig {
```

Extend the class-level Chinese comment:

```java
 * <p>整个配置受 {@code mqtt.enabled} 控制。测试环境关闭后不会创建 Client 或执行
 * 连接任务，从而避免普通自动化测试接触真实 Broker；采集业务单元测试仍可直接
 * 构造本类验证协议和 ACK 语义。</p>
```

- [ ] **Step 5: Define production and test configuration values**

Add before `mqtt.broker-url` in `application.yml`:

```yaml
mqtt:
  # true：创建 MQTT Client 并订阅 HVAC 上行；false：完全不连接 Broker，供自动化测试隔离。
  enabled: ${MQTT_ENABLED:true}
```

Preserve the existing MQTT child properties under the same map. Add to
`application-test.yml`:

```yaml
# 普通自动化测试不创建 MQTT Client，也不会连接或订阅真实 EMQX。
mqtt:
  enabled: false
```

Add to `server.env.example` before the broker URL:

```dotenv
# Set false only when this deployment must not connect to the HVAC MQTT broker.
MQTT_ENABLED=true
```

- [ ] **Step 6: Run MQTT and configuration tests**

Run:

```powershell
.\mvnw.cmd -Dtest=DataSourceIsolationConfigurationTest,MqttTestProfileIsolationTest,MqttConfigTest test
```

Expected: 8 tests pass; logs contain no real MQTT connect, reconnect, or
subscription messages from the Spring test context.

- [ ] **Step 7: Commit the isolated MQTT change**

```powershell
git add -- `
  src/main/java/com/platform/config/MqttConfig.java `
  src/main/resources/application.yml `
  src/test/resources/application-test.yml `
  server.env.example `
  src/test/java/com/platform/config/DataSourceIsolationConfigurationTest.java `
  src/test/java/com/platform/config/MqttTestProfileIsolationTest.java
git diff --cached --check
git commit -m "test(mqtt): isolate broker from test profile"
```

---

### Task 2: Make the HVAC Docker stack valid from empty storage

**Files:**
- Modify: `src/env/docker-compose.yml`
- Create: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`

**Interfaces:**
- Consumes: `MYSQL_PORT`, `MYSQL_PASSWORD`, `MQTT_PASSWORD`, and `REDIS_PORT`.
- Produces: one valid Compose definition with four health-checkable HVAC infrastructure services.

- [ ] **Step 1: Write the failing Compose contract test**

Create `DockerComposeConfigurationTest`:

```java
package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定空数据环境所使用的 Compose 边界，防止旧电表初始化重新进入部署链路。
 */
class DockerComposeConfigurationTest {

    private final String compose = readCompose();

    @Test
    void composeDoesNotReferenceDeletedMeterInitialization() {
        assertThat(compose)
                .doesNotContain("02-init-10000-devices.sql")
                .doesNotContain("电表数据")
                .doesNotContain("电表电压电流")
                .doesNotStartWith("version:");
    }

    @Test
    void allInfrastructureServicesExposeHealthChecks() {
        assertThat(compose).contains(
                "mysql:",
                "emqx:",
                "tdengine:",
                "redis:");
        assertThat(countOccurrences(compose, "healthcheck:")).isEqualTo(4);
    }

    private int countOccurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length())
                / needle.length();
    }

    private String readCompose() {
        try {
            return Files.readString(Path.of("src/env/docker-compose.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 Docker Compose 配置", e);
        }
    }
}
```

- [ ] **Step 2: Run the Compose contract test and verify it fails**

Run:

```powershell
.\mvnw.cmd -Dtest=DockerComposeConfigurationTest test
```

Expected: FAIL because the deleted `02-init-10000-devices.sql`, obsolete
`version`, old comments, and missing health checks are still present.

- [ ] **Step 3: Correct the Compose file**

Remove the top-level `version`. Remove:

```yaml
- ./init/02-init-10000-devices.sql:/docker-entrypoint-initdb.d/02-init-10000-devices.sql:ro
```

Use these service descriptions:

```yaml
# 1. 关系型数据底座（正式权限、建筑、设备和测点档案）
# 2. MQTT 消息接入中枢（接收 HVAC 19 测点上行）
# 3. 时序数据底座（保存 HVAC 原始事件、分钟数据和指标结果）
# 4. 最新指标状态缓存
```

Add the following health checks at each service level:

```yaml
mysql:
  healthcheck:
    test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p$${MYSQL_ROOT_PASSWORD} --silent"]
    interval: 5s
    timeout: 5s
    retries: 30
    start_period: 20s

emqx:
  healthcheck:
    test: ["CMD", "/opt/emqx/bin/emqx", "ctl", "status"]
    interval: 5s
    timeout: 5s
    retries: 30
    start_period: 20s

tdengine:
  healthcheck:
    test: ["CMD-SHELL", "taos -s 'SHOW DATABASES;' >/dev/null 2>&1"]
    interval: 5s
    timeout: 5s
    retries: 30
    start_period: 20s

redis:
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 5s
    retries: 30
    start_period: 5s
```

- [ ] **Step 4: Validate tests and Compose rendering**

Run:

```powershell
.\mvnw.cmd -Dtest=DockerComposeConfigurationTest test
$docker = 'C:\Users\yang\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
& $docker compose -f src/env/docker-compose.yml config --quiet
```

Expected: test passes; Compose exits 0 without missing-file or obsolete-version
warnings.

- [ ] **Step 5: Commit the Compose correction**

```powershell
git add -- src/env/docker-compose.yml `
  src/test/java/com/platform/config/DockerComposeConfigurationTest.java
git diff --cached --check
git commit -m "fix(env): make hvac stack clean-startable"
```

---

### Task 3: Add a guarded clean-environment smoke runner

**Files:**
- Create: `scripts/Invoke-CleanHvacSmoke.ps1`
- Create: `src/test/java/com/platform/config/CleanHvacSmokeScriptContractTest.java`

**Interfaces:**
- Consumes: `-ResetData`, optional `-LegacyEnvRoot`, `server.env`, the Maven Wrapper, Docker Compose, and `.scripts/simulate-hvac-19-points.mjs`.
- Produces: non-zero exit on any failed guard/assertion; `CLEAN_HVAC_SMOKE_SUCCESS` only after all structure and data-path assertions pass.

- [ ] **Step 1: Write the failing script contract test**

Create `CleanHvacSmokeScriptContractTest`:

```java
package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约束清库冒烟脚本的破坏边界，防止后续维护时扩大到仓库或其他 Docker 项目。
 */
class CleanHvacSmokeScriptContractTest {

    private final String script = readScript();

    @Test
    void destructiveExecutionRequiresExplicitResetFlagAndLiteralPaths() {
        assertThat(script).contains(
                "[switch]$ResetData",
                "LegacyEnvRoot",
                "[IO.Path]::GetFullPath",
                "Remove-Item -LiteralPath",
                "mysql-data",
                "taos-data",
                "redis-data");
    }

    @Test
    void scriptOnlyTargetsTheFourKnownContainers() {
        assertThat(script).contains(
                "'iot-mysql'",
                "'iot-emqx'",
                "'iot-tdengine'",
                "'iot-redis'");
        assertThat(script).doesNotContain(
                "docker system prune",
                "docker volume prune",
                "Remove-Item $repoRoot",
                "Remove-Item -Recurse -Force $env:");
    }

    @Test
    void scriptRequiresAllRuntimeAndLegacyAssertions() {
        assertThat(script).contains(
                "iot_device",
                "iot_device_status_log",
                "control_commands",
                "st_electric_data",
                "st_raw_event",
                "st_raw_minute",
                "st_indicator_minute",
                "st_formula_calc_exception",
                "CLEAN_HVAC_SMOKE_SUCCESS");
    }

    private String readScript() {
        try {
            return Files.readString(
                    Path.of("scripts/Invoke-CleanHvacSmoke.ps1"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取干净环境冒烟脚本", e);
        }
    }
}
```

- [ ] **Step 2: Run the contract test and verify the missing script fails**

Run:

```powershell
.\mvnw.cmd -Dtest=CleanHvacSmokeScriptContractTest test
```

Expected: FAIL with `无法读取干净环境冒烟脚本`.

- [ ] **Step 3: Implement Docker resolution, environment loading, and guards**

Start `Invoke-CleanHvacSmoke.ps1` with:

```powershell
[CmdletBinding()]
param(
    [switch]$ResetData,
    [string]$LegacyEnvRoot,
    [int]$InfrastructureTimeoutSeconds = 240,
    [int]$ApplicationTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$currentEnvRoot = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'src\env'))
$composeFile = Join-Path $currentEnvRoot 'docker-compose.yml'
$knownContainers = @(
    'iot-mysql',
    'iot-emqx',
    'iot-tdengine',
    'iot-redis'
)
$dataDirectoryNames = @('mysql-data', 'taos-data', 'redis-data')

function Resolve-DockerCli {
    $command = Get-Command docker -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $desktopCli = Join-Path $env:LOCALAPPDATA `
        'Programs\DockerDesktop\resources\bin\docker.exe'
    if (Test-Path -LiteralPath $desktopCli) {
        return $desktopCli
    }
    throw '找不到 Docker CLI'
}

function Import-ServerEnvironment([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) {
        return
    }
    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $name, $value = $trimmed -split '=', 2
        if (-not $name -or $null -eq $value) {
            throw "server.env 存在无法解析的配置行"
        }
        [Environment]::SetEnvironmentVariable(
            $name.Trim(), $value.Trim(), 'Process')
    }
}

function Resolve-AllowedDataTargets([string]$envRoot) {
    if (-not $envRoot) {
        return @()
    }
    $absoluteRoot = [IO.Path]::GetFullPath($envRoot)
    if ((Split-Path $absoluteRoot -Leaf) -ne 'env' -or
        (Split-Path (Split-Path $absoluteRoot) -Leaf) -ne 'src') {
        throw "数据根目录不是项目 src\env: $absoluteRoot"
    }
    return $dataDirectoryNames | ForEach-Object {
        [IO.Path]::GetFullPath((Join-Path $absoluteRoot $_))
    }
}

if (-not $ResetData) {
    throw '必须显式传入 -ResetData 才允许重建测试数据'
}
```

- [ ] **Step 4: Implement exact mount validation and deletion**

Add helpers that:

```powershell
function Invoke-Docker([string[]]$Arguments) {
    & $script:dockerCli @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker 命令失败: docker $($Arguments -join ' ')"
    }
}

function Assert-ContainerDataMounts(
    [string]$container,
    [string[]]$allowedTargets
) {
    $json = & $script:dockerCli inspect $container 2>$null
    if ($LASTEXITCODE -ne 0) {
        return
    }
    $inspection = $json | ConvertFrom-Json
    foreach ($mount in $inspection[0].Mounts) {
        if ($mount.Destination -notin
            @('/var/lib/mysql', '/var/lib/taos', '/data')) {
            continue
        }
        $source = [IO.Path]::GetFullPath($mount.Source)
        if ($source -notin $allowedTargets) {
            throw "容器 $container 的数据挂载超出允许范围: $source"
        }
    }
}

function Remove-ValidatedDataDirectory(
    [string]$target,
    [string[]]$allowedTargets
) {
    $absolute = [IO.Path]::GetFullPath($target)
    if ($absolute -notin $allowedTargets) {
        throw "拒绝删除未批准路径: $absolute"
    }
    if (Test-Path -LiteralPath $absolute) {
        Remove-Item -LiteralPath $absolute -Recurse -Force
    }
}
```

Build `allowedTargets` only from `currentEnvRoot` and the explicitly supplied
`LegacyEnvRoot`. Validate all running container data mounts before running:

```powershell
Invoke-Docker (@('rm', '--force', '--volumes') + $knownContainers)
```

Then delete only each exact target returned by
`Resolve-AllowedDataTargets`.

- [ ] **Step 5: Implement infrastructure and application readiness**

Use:

```powershell
Invoke-Docker @(
    'compose', '-f', $composeFile,
    'config', '--quiet'
)
Invoke-Docker @(
    'compose', '-f', $composeFile,
    'up', '-d'
)
```

Poll each known container with:

```powershell
& $dockerCli inspect `
  --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' `
  $container
```

Require `healthy` before the infrastructure timeout. Import `server.env`,
run:

```powershell
& (Join-Path $repoRoot 'mvnw.cmd') -DskipTests package
```

Start `target/iot-platform-demo-1.0-SNAPSHOT.jar` with `Start-Process`,
`-WindowStyle Hidden`, `-PassThru`, and stdout/stderr files under
`target\smoke`. Poll `http://127.0.0.1:8081/api/actuator/health`. If port 8081
was already listening before launch, fail without stopping its owning process.
In `finally`, stop only the `Process` instance started by the script.

- [ ] **Step 6: Implement positive and negative database assertions**

Use `docker exec` to execute:

```sql
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema='iot_platform'
  AND table_name IN (
    'sys_user',
    'sys_role',
    'building',
    'biz_equipment',
    'biz_data_point'
  );
```

Require `5`. Execute:

```sql
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema='iot_platform'
  AND table_name IN (
    'iot_device',
    'iot_device_status_log',
    'control_commands'
  );
```

Require `0`. Use `taos -s` to run:

```sql
USE iot_telemetry;
SHOW STABLES;
```

Require all of:

```text
st_raw_event
st_raw_minute
st_indicator_minute
st_formula_calc_exception
```

and reject `st_electric_data`.

- [ ] **Step 7: Implement MQTT publish and TDengine evidence checks**

Run the existing deterministic publisher:

```powershell
& node (Join-Path $repoRoot '.scripts\simulate-hvac-19-points.mjs')
```

It publishes seven rounds of all 19 frozen points. Poll TDengine with:

```sql
SELECT DISTINCT point_code
FROM iot_telemetry.st_raw_event;
```

Normalize the CLI output and require all 19 aliases from the simulator. Only
after every assertion passes, print:

```powershell
Write-Output 'CLEAN_HVAC_SMOKE_SUCCESS'
```

- [ ] **Step 8: Run script contract and PowerShell syntax checks**

Run:

```powershell
.\mvnw.cmd -Dtest=CleanHvacSmokeScriptContractTest test
powershell -NoProfile -Command `
  "$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile('scripts/Invoke-CleanHvacSmoke.ps1',[ref]$tokens,[ref]$errors) > $null; if($errors.Count){$errors | Format-List; exit 1}"
```

Expected: 3 Java tests pass; PowerShell parser exits 0.

- [ ] **Step 9: Commit the guarded smoke runner**

```powershell
git add -- scripts/Invoke-CleanHvacSmoke.ps1 `
  src/test/java/com/platform/config/CleanHvacSmokeScriptContractTest.java
git diff --cached --check
git commit -m "test(env): add guarded clean hvac smoke"
```

---

### Task 4: Destroy the approved test data and execute the real smoke

**Files:**
- Runtime evidence only: `target/smoke/*` (ignored)
- No source file changes expected unless a directly observed defect requires a scoped fix.

**Interfaces:**
- Consumes: completed Tasks 1–3 and the approved legacy environment root.
- Produces: a fresh running HVAC-only infrastructure stack and recorded pass/fail evidence.

- [ ] **Step 1: Reconfirm clean Git and exact Docker ownership**

Run:

```powershell
git status --short --branch
$docker = 'C:\Users\yang\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
& $docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
foreach ($name in 'iot-mysql','iot-emqx','iot-tdengine','iot-redis') {
  & $docker inspect $name `
    --format '{{.Name}} {{range .Mounts}}{{.Source}}->{{.Destination}} {{end}}'
}
```

Expected: only `outputs/` is unrelated/untracked; the four containers point to
the approved `ed53\...\src\env` data paths.

- [ ] **Step 2: Execute the approved destructive reset and smoke**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File `
  scripts/Invoke-CleanHvacSmoke.ps1 `
  -ResetData `
  -LegacyEnvRoot `
  'C:\Users\yang\.codex\worktrees\ed53\iot-platform-demo\src\env'
```

Expected final marker:

```text
CLEAN_HVAC_SMOKE_SUCCESS
```

- [ ] **Step 3: Verify the replacement stack belongs to latest main**

Run:

```powershell
foreach ($name in 'iot-mysql','iot-emqx','iot-tdengine','iot-redis') {
  & $docker inspect $name `
    --format '{{.Name}} {{range .Mounts}}{{.Source}}->{{.Destination}} {{end}}'
}
```

Expected: bind data mounts now point to
`D:\word\iot-platform-demo\src\env`; no bind mount references `ed53` or
`02-init-10000-devices.sql`.

- [ ] **Step 4: Run complete backend regression**

Run:

```powershell
.\mvnw.cmd test
```

Expected: 438 tests pass with 0 failures, 0 errors, and 0 skipped; no Spring
test context logs a real MQTT connection or subscription.

- [ ] **Step 5: Run frontend regression and build**

Run:

```powershell
Set-Location web
npm run test:run -- --maxWorkers=1 --minWorkers=1
npm run build
Set-Location ..
```

Expected: router tests pass and Vite production build succeeds. Existing
large-chunk warnings may be recorded but do not fail this task.

- [ ] **Step 6: Perform final residue and repository checks**

Run:

```powershell
rg -n -i `
  'meter-001|iot_device|iot_device_status_log|control_commands|st_electric_data|02-init-10000-devices|demo-simulator-enabled|/ws/dashboard' `
  src/main web/src src/env src/test scripts .scripts
git status --short
git diff --check main...HEAD
```

Expected: matches occur only in intentional negative assertions and the
approved smoke script; generated Docker data and logs remain ignored;
`outputs/` remains untouched.

- [ ] **Step 7: Prepare the final implementation commit if smoke-driven fixes were needed**

If Tasks 1–3 already contain all source changes, do not create an empty
commit. If the real smoke exposed a directly related defect, stage only the
applicable files from this task's fixed file set and commit:

```powershell
git add -- `
  src/main/java/com/platform/config/MqttConfig.java `
  src/main/resources/application.yml `
  src/test/resources/application-test.yml `
  src/env/docker-compose.yml `
  scripts/Invoke-CleanHvacSmoke.ps1
git diff --cached --check
git commit -m "fix(env): resolve clean hvac smoke failure"
```

Before committing, use `git diff --cached --name-only` and unstage any file
that did not receive a directly related smoke fix; never use `git add .`.

---

### Task 5: Push the tested task branch and hand off PR material

**Files:**
- No new files.

**Interfaces:**
- Consumes: all committed changes and complete test evidence.
- Produces: remote task branch plus user-owned PR creation material.

- [ ] **Step 1: Inspect branch scope and conflicts**

Run:

```powershell
git status --short --branch
git log --oneline main..HEAD
git diff --name-status main...HEAD
git diff --check main...HEAD
git fetch --prune origin
git merge-tree (git merge-base HEAD origin/main) HEAD origin/main
```

Expected: only design, plan, MQTT isolation, Compose, smoke script, and their
tests are present; `outputs/` is not staged; no merge conflict markers.

- [ ] **Step 2: Push the task branch**

```powershell
git push -u origin test/mqtt-docker-clean-smoke
```

- [ ] **Step 3: Deliver PR material without creating the PR**

Provide:

- Base: `main`
- Compare: `test/mqtt-docker-clean-smoke`
- Suggested title: `test(env): 隔离 MQTT 测试并验证干净 HVAC 环境`
- Compare URL:
  `https://github.com/edg127117/iot-platform-demo/compare/main...test/mqtt-docker-clean-smoke?expand=1`
- Exact backend/frontend test totals
- Docker versions, clean-reset evidence, positive HVAC assertions, negative
  legacy assertions, skipped items, warnings, and conflict status
- Explicit status: waiting for the user to create and merge the PR
