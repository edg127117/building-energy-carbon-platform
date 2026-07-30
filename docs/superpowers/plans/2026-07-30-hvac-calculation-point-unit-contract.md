# HVAC Calculation Point Unit Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复功率因数种子数据的无量纲单位，并用自动化和真实 Docker 回归证明 Q2 链路可以在全新环境运行。

**Architecture:** 不修改运行时业务逻辑和数据库结构，只统一生产初始化 SQL 与 H2 测试夹具。新增独立种子数据契约测试，一部分通过 H2 查询保护所有在线计算模拟量的单位非空，另一部分直接检查生产初始化脚本，防止生产与测试数据再次漂移。

**Tech Stack:** Java 21、JUnit 5、AssertJ、Spring JDBC、H2、MySQL 8、TDengine 3.2、Redis 7、EMQX 5、Maven、Docker Compose

## Global Constraints

- `POINT007/WCR1_PF` 保持比例值语义，单位使用 `1`，不转换为百分数。
- 不修改典型值服务对无单位测点返回 `409` 的行为。
- 不修改 MySQL 或 TDengine 表结构，不新增迁移脚本。
- 不增加测点新增或修改接口的运行时校验；该能力留给下一独立任务。
- 自动化测试不得连接真实外部资源；真实环境验证只在明确的 Docker 冒烟步骤执行。
- 所有新增测试类和关键契约使用直白中文注释说明业务原因。
- 只暂存本计划列出的文件，保留未跟踪 `outputs/`。

---

### Task 1: 修复并锁定初始化单位契约

**Files:**
- Create: `src/test/java/com/platform/HvacSeedUnitContractTest.java`
- Modify: `src/env/init/03-init-hvac-schema.sql:253`
- Modify: `src/test/resources/data-test.sql:85`

**Interfaces:**
- Consumes: `schema-test.sql` 和 `data-test.sql` 组成的 H2 测试初始化数据。
- Produces: 初始化契约——所有 `ONLINE + ANALOG + is_for_calc=1` 测点单位非空，且生产 `POINT007` 单位为 `'1'`。

- [ ] **Step 1: 写入会失败的单位契约测试**

```java
package com.platform;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 HVAC 种子数据的单位契约，避免无单位计算测点再次阻断典型值审批和 Q2。
 */
class HvacSeedUnitContractTest {

    @Test
    void allOnlineAnalogCalculationPointsHaveUnits() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:hvac-seed-unit-contract;"
                        + "MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        dataSource.setUser("sa");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("schema-test.sql"),
                new ClassPathResource("data-test.sql"));
        DatabasePopulatorUtils.execute(populator, dataSource);

        Integer missingUnitCount = new JdbcTemplate(dataSource).queryForObject(
                """
                SELECT COUNT(*)
                FROM biz_data_point
                WHERE UPPER(status) = 'ONLINE'
                  AND UPPER(data_type) = 'ANALOG'
                  AND is_for_calc = 1
                  AND (unit IS NULL OR TRIM(unit) = '')
                """,
                Integer.class);

        assertThat(missingUnitCount).isZero();
    }

    @Test
    void productionSeedUsesDimensionlessUnitForPowerFactor() throws IOException {
        Path productionSeed = Path.of(
                System.getProperty("user.dir"),
                "src", "env", "init", "03-init-hvac-schema.sql");
        String pointLine = Files.readAllLines(
                        productionSeed, StandardCharsets.UTF_8)
                .stream()
                .filter(line -> line.startsWith("('POINT007'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "生产初始化脚本缺少 POINT007"));

        assertThat(pointLine).contains("'PF','ANALOG','1',1)");
    }
}
```

- [ ] **Step 2: 运行测试并确认当前种子数据会失败**

Run:

```powershell
.\mvnw.cmd -Dtest=HvacSeedUnitContractTest test
```

Expected: 两项断言至少一项失败，指出 `POINT007` 单位为 `NULL`。

- [ ] **Step 3: 最小修复生产和测试种子数据**

在两个 SQL 文件中把 `POINT007` 元组的单位从 `NULL` 改为 `'1'`：

```sql
('POINT007','WCR1_PF','1号机组功率因数','BLD001','GROUP001',
 'EQUIP_WCR_B1','RULE_WCR_MAIN','WCR','MAIN','PF','ANALOG','1',1)
```

```sql
('POINT007', 'WCR1_PF', '一号机组功率因数', 'BLD001', 'GROUP001',
 'EQUIP_WCR_B1', 'RULE_WCR_MAIN', 'WCR', 'MAIN', 'PF', 'ANALOG',
 '1', 1, 'ONLINE', 0)
```

- [ ] **Step 4: 运行定向测试并确认通过**

Run:

```powershell
.\mvnw.cmd -Dtest=HvacSeedUnitContractTest test
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 5: 执行注释和差异检查**

Run:

```powershell
git diff --check
git diff -- src/env/init/03-init-hvac-schema.sql `
  src/test/resources/data-test.sql `
  src/test/java/com/platform/HvacSeedUnitContractTest.java
```

Expected: 只有单位契约修复和对应测试，无无关格式化；新增测试类具有中文用途注释。

- [ ] **Step 6: 提交单位契约修复**

```powershell
git add -- src/env/init/03-init-hvac-schema.sql `
  src/test/resources/data-test.sql `
  src/test/java/com/platform/HvacSeedUnitContractTest.java
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(data-quality): define power factor unit"
```

Expected: 暂存文件严格等于上述三个文件，`outputs/` 不进入提交。

### Task 2: 完整回归和真实 Docker Q2 验收

**Files:**
- Verify only: `src/env/docker-compose.yml`
- Verify only: `src/env/init/03-init-hvac-schema.sql`
- Verify only: `.scripts/simulate-hvac-19-points.mjs`

**Interfaces:**
- Consumes: Task 1 修复后的生产种子数据和当前数据质量/公式实现。
- Produces: 可写入 PR 的 Maven 与 Docker 验收证据。

- [ ] **Step 1: 运行完整 Maven 回归**

Run:

```powershell
.\mvnw.cmd test
```

Expected: 全部测试通过，无失败、错误或跳过项。

- [ ] **Step 2: 用空 MySQL 测试数据重新初始化**

停止测试后端，仅清理已确认属于当前 worktree 的 MySQL 测试数据目录，然后使用
`MYSQL_PORT=13306`、`REDIS_PORT=16379` 重建 Compose 服务。

Run:

```powershell
$expected = [System.IO.Path]::GetFullPath(
  "C:\Users\yang\.codex\worktrees\ed53\iot-platform-demo\src\env\mysql-data")
$actual = [System.IO.Path]::GetFullPath(
  (Join-Path (Get-Location) "src\env\mysql-data"))
if ($actual -ne $expected -or
    -not $actual.StartsWith(
      "C:\Users\yang\.codex\worktrees\ed53\iot-platform-demo\src\env\",
      [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "拒绝清理非预期路径: $actual"
}
docker compose -p env -f src/env/docker-compose.yml rm -s -f -v mysql
if (Test-Path -LiteralPath $actual) {
  Remove-Item -LiteralPath $actual -Recurse -Force
}
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
docker compose -p env -f src/env/docker-compose.yml up -d mysql redis emqx tdengine
docker exec iot-mysql mysql -uroot -pchange-me -D iot_platform `
  -e "SELECT point_id,point_code,unit FROM biz_data_point WHERE point_id='POINT007';"
```

Expected:

```text
POINT007  WCR1_PF  1
```

- [ ] **Step 3: 以隔离配置启动测试后端**

使用以下运行时覆盖，避免与本机已有后端竞争：

```powershell
$env:SERVER_PORT = "18081"
$env:MYSQL_PORT = "13306"
$env:REDIS_PORT = "16379"
$env:MQTT_CLIENT_ID = "platform-backend-smoke-unit-contract"
$env:MQTT_TOPICS_UPSTREAM = "device/data/up/smoke-unit-contract"
$env:AGGREGATION_FINALIZATION_DELAY_SECONDS = "2"
$env:AGGREGATION_SCAN_DELAY_MS = "1000"
$env:DATA_QUALITY_TYPICAL_CONFIG_REFRESH_MS = "1000"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outLog = Join-Path $env:TEMP "iot-unit-contract-$stamp.out.log"
$errLog = Join-Path $env:TEMP "iot-unit-contract-$stamp.err.log"
Start-Process -FilePath (Join-Path (Get-Location) "mvnw.cmd") `
  -ArgumentList @("spring-boot:run") `
  -WorkingDirectory (Get-Location) `
  -WindowStyle Hidden `
  -RedirectStandardOutput $outLog `
  -RedirectStandardError $errLog
```

Expected: 后端正常启动，MQTT 连接稳定且订阅专用主题。

- [ ] **Step 4: 通过 API 完成 19 个典型值审批**

使用临时 `ENERGY_MANAGER` 创建并提交 19 个配置，再由内置
`PLATFORM_ADMIN` 审批。不得直接插入典型值配置表。

Run:

```powershell
$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:18081/api"
$adminLogin = Invoke-RestMethod -Method Post `
  -Uri "$base/auth/login" `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"123456"}'
$adminHeaders = @{
  Authorization = "Bearer $($adminLogin.data.token)"
}
$username = "unit_contract_mgr_" + (Get-Date -Format "HHmmss")
$userBody = @{
  username = $username
  password = "123456"
  nickname = "单位契约回归管理员"
  roleKeys = @("ENERGY_MANAGER")
  buildingIds = @("BLD001")
} | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Headers $adminHeaders `
  -Uri "$base/system/users" `
  -ContentType "application/json" `
  -Body $userBody | Out-Null
$managerLogin = Invoke-RestMethod -Method Post `
  -Uri "$base/auth/login" `
  -ContentType "application/json" `
  -Body (@{
    username = $username
    password = "123456"
  } | ConvertTo-Json -Compress)
$managerHeaders = @{
  Authorization = "Bearer $($managerLogin.data.token)"
}
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$validFrom = [long]([math]::Floor($now / 60000) * 60000)
$validTo = $validFrom + 1800000
$values = @(12, 7, 100, 100, 380, 100, 0.9, 35, 30, 25,
  100, 300000, 100000, 1, 10, 1000, 60, 30, 50)
$approved = 0
for ($i = 1; $i -le 19; $i++) {
  $pointId = "POINT{0:D3}" -f $i
  $createBody = @{
    pointId = $pointId
    typicalValue = $values[$i - 1]
    sourceDescription = "全新数据库单位契约回归样本"
    reason = "验证无真实数据分钟自动生成质量2"
    validFrom = $validFrom
    validTo = $validTo
  } | ConvertTo-Json -Compress
  $created = Invoke-RestMethod -Method Post `
    -Headers $managerHeaders `
    -Uri "$base/iot/data-quality/typical-values" `
    -ContentType "application/json" `
    -Body $createBody
  $configId = $created.data.configId
  Invoke-RestMethod -Method Post -Headers $managerHeaders `
    -Uri "$base/iot/data-quality/typical-values/$configId/submit" |
    Out-Null
  $reviewBody = @{
    comment = "单位和来源证据检查通过"
  } | ConvertTo-Json -Compress
  $reviewed = Invoke-RestMethod -Method Post `
    -Headers $adminHeaders `
    -Uri "$base/iot/data-quality/typical-values/$configId/approve" `
    -ContentType "application/json" `
    -Body $reviewBody
  if ($reviewed.data.status -eq "APPROVED") {
    $approved++
  }
}
if ($approved -ne 19) {
  throw "典型值审批数量不正确: $approved"
}
Write-Output "APPROVED_CONFIGS=$approved"
```

Expected:

```text
APPROVED_CONFIGS=19
```

- [ ] **Step 5: 验证自动 Q2 全链路**

先连接 WebSocket，等待一个无真实数据分钟冻结：

```powershell
node -e "const ws=new WebSocket('ws://127.0.0.1:18081/api/ws/dashboard');const t=setTimeout(()=>{console.error('Q2_WEBSOCKET_TIMEOUT');process.exit(1)},180000);ws.onmessage=e=>{const m=JSON.parse(e.data),d=m.data;if(m.type==='HVAC_INDICATOR'&&d?.status==='SUCCESS'&&d?.dataQuality===2){console.log(e.data);clearTimeout(t);ws.close();process.exit(0)}};ws.onerror=()=>{clearTimeout(t);process.exit(1)}"
```

然后核对 TDengine 和 MySQL：

```powershell
docker exec iot-tdengine taos -s `
  "SELECT ts,data_quality,COUNT(*) AS rows FROM iot_telemetry.st_raw_minute GROUP BY ts,data_quality ORDER BY ts DESC LIMIT 1; SELECT indicator_code,ts,val,data_quality,formula_version FROM iot_telemetry.st_indicator_minute ORDER BY ts DESC LIMIT 4;"
docker exec iot-mysql mysql -uroot -pchange-me -D iot_platform `
  -e "SELECT data_quality,source_type,apply_status,COUNT(*) AS task_count FROM biz_data_quality_fill_task WHERE data_quality=2 GROUP BY data_quality,source_type,apply_status;"
```

最后核对 Redis 和三个 JWT API：

```powershell
docker exec iot-redis redis-cli --scan --pattern "iot:indicator:latest:*"
$base = "http://127.0.0.1:18081/api"
$login = Invoke-RestMethod -Method Post `
  -Uri "$base/auth/login" `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"123456"}'
$headers = @{
  Authorization = "Bearer $($login.data.token)"
}
$latest = Invoke-RestMethod -Headers $headers `
  -Uri "$base/hvac/buildings/BLD001/indicators/latest"
$minuteStart = ($latest.data.indicators |
  Where-Object indicatorCode -eq "WCR_COP").minuteStart
$minuteEnd = $minuteStart + 60000
$history = Invoke-RestMethod -Headers $headers `
  -Uri "$base/hvac/indicators/INDICATOR_WCR_COP_B1/history?from=$minuteStart&to=$minuteEnd"
$detail = Invoke-RestMethod -Headers $headers `
  -Uri "$base/hvac/indicators/INDICATOR_WCR_COP_B1/calculations/$minuteStart"
if (($latest.data.indicators | Where-Object {
      $_.status -eq "SUCCESS" -and $_.dataQuality -eq 2
    }).Count -ne 4) {
  throw "最新 API 未返回四个质量2成功指标"
}
if ($history.data.records.Count -ne 1 -or
    $history.data.records[0].dataQuality -ne 2) {
  throw "历史 API 与质量2分钟不一致"
}
if ($detail.data.status -ne "SUCCESS" -or
    $detail.data.dataQuality -ne 2) {
  throw "计算详情 API 与质量2分钟不一致"
}
```

Expected:

```text
TDengine st_raw_minute: 19 rows, data_quality=2
TDengine st_indicator_minute: 4 SUCCESS rows, data_quality=2
MySQL fill tasks: 19 TYPICAL_VALUE/APPLIED hourly tasks
Redis latest: 4 SUCCESS states, dataQuality=2
JWT latest/history/calculation APIs: consistent
WebSocket HVAC_INDICATOR: SUCCESS, dataQuality=2
```

- [ ] **Step 6: 清理测试后端并检查仓库**

停止仅属于当前 worktree 的 `18081` 测试后端，保留用户正在使用的其他后端。

Run:

```powershell
$connection = Get-NetTCPConnection -LocalPort 18081 -State Listen `
  -ErrorAction SilentlyContinue
if ($connection) {
  $pidToStop = $connection.OwningProcess
  $processInfo = Get-CimInstance Win32_Process `
    -Filter "ProcessId=$pidToStop"
  if ($null -eq $processInfo -or
      $processInfo.CommandLine -notlike
        "*C:\Users\yang\.codex\worktrees\ed53\iot-platform-demo\target\classes*") {
    throw "拒绝停止未确认的进程: $pidToStop"
  }
  Stop-Process -Id $pidToStop -Force
}
git status --short --branch
git diff --check
```

Expected: 只有既有 `outputs/` 未跟踪；任务分支比远程领先本次设计、计划和修复提交。

- [ ] **Step 7: 推送任务分支并更新 PR 材料**

```powershell
git push origin feature/hvac-data-quality-fill
```

PR 说明必须更新：

- 删除“未执行真实 Docker Compose 冒烟”；
- 写明 Q0/Q1/Q2、公式、TDengine、Redis、API、WebSocket 的真实结果；
- 写明本机多实例测试使用唯一 MQTT 客户端 ID 和专用主题；
- 写明完整 Maven 测试数量及无跳过项；
- 保留“运行期测点新增/修改单位校验不在本次范围”的说明。
