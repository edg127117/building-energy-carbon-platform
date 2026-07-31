# MySQL Initialization UTF-8 Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure Docker fresh-install MySQL scripts store all Chinese DDL comments and seed values as correct UTF-8, then rebuild only the local MySQL test data and prove the browser displays correct names.

**Architecture:** Each automatically executed MySQL init script declares its own client session as `utf8mb4` before the first business DDL or DML. A file-contract test guards statement ordering and representative Chinese source values without connecting ordinary tests to MySQL; destructive verification removes only the explicitly approved local `src/env/mysql-data` directory and then performs a real clean initialization.

**Tech Stack:** MySQL 8.0, Docker Compose, SQL, Java 21, JUnit 5, AssertJ, Maven Wrapper, Spring Boot, MQTT/EMQX, TDengine 3.2.3, Redis, Vue 3, Vitest, Vite.

## Global Constraints

- Work only on `fix/mysql-init-utf8`, based on the latest `origin/main`.
- Do not modify or merge the frontend branch while implementing this fix.
- Do not add automatic conversion for unknown existing or production database values.
- Delete only the resolved `src/env/mysql-data` local test directory after validating its absolute path.
- Preserve `src/env/taos-data`, `src/env/redis-data`, EMQX configuration, the main checkout's uncommitted Controller comments, and `outputs/`.
- Ordinary Maven tests must not connect to MySQL, TDengine, MQTT, or Redis.
- Add concise Chinese comments explaining why the client charset must be explicit.
- Stage only the files named in this plan; never use `git add .`.

---

### Task 1: Lock the MySQL init-script charset contract

**Files:**
- Create: `src/test/java/com/platform/config/MySqlInitializationCharsetContractTest.java`

**Interfaces:**
- Consumes: UTF-8 SQL files at `src/env/init/01-init-tables.sql` and `src/env/init/03-init-hvac-schema.sql`.
- Produces: A JUnit contract that requires an explicit client charset before the first `CREATE TABLE` and `INSERT`.

- [ ] **Step 1: Write the failing contract test**

Create `src/test/java/com/platform/config/MySqlInitializationCharsetContractTest.java`:

```java
package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 MySQL 首次初始化的客户端字符集，避免 UTF-8 中文被按 latin1
 * 读取后再次转码并永久写成乱码。
 */
class MySqlInitializationCharsetContractTest {

    private static final String SET_NAMES =
            "SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;";

    @Test
    void everyAutomaticMySqlInitScriptSetsUtf8BeforeBusinessSql()
            throws IOException {
        List<InitScript> scripts = List.of(
                new InitScript(
                        Path.of("src/env/init/01-init-tables.sql"),
                        List.of("超级管理员", "建筑业主")),
                new InitScript(
                        Path.of("src/env/init/03-init-hvac-schema.sql"),
                        List.of("试点大楼", "冷冻水进水温度")));

        for (InitScript script : scripts) {
            String sql = Files.readString(script.path(), StandardCharsets.UTF_8);
            int declaration = sql.indexOf(SET_NAMES);
            int firstCreate = sql.indexOf("CREATE TABLE");
            int firstInsert = sql.indexOf("INSERT");

            assertThat(declaration)
                    .as("%s 必须声明 MySQL 客户端 UTF-8", script.path())
                    .isGreaterThanOrEqualTo(0);
            assertThat(firstCreate).isGreaterThan(declaration);
            assertThat(firstInsert).isGreaterThan(declaration);
            assertThat(script.representativeChinese())
                    .allSatisfy(seed -> assertThat(sql).contains(seed));
        }
    }

    private record InitScript(
            Path path,
            List<String> representativeChinese) {
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
.\mvnw.cmd "-Dtest=MySqlInitializationCharsetContractTest" test
```

Expected: 1 test runs and fails because neither SQL file contains the required `SET NAMES` statement.

- [ ] **Step 3: Confirm the failure is contract-specific**

Verify the failure reports:

```text
必须声明 MySQL 客户端 UTF-8
```

Expected: no compilation failure and no external service connection attempt.

---

### Task 2: Declare UTF-8 in both automatic MySQL scripts

**Files:**
- Modify: `src/env/init/01-init-tables.sql`
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Test: `src/test/java/com/platform/config/MySqlInitializationCharsetContractTest.java`

**Interfaces:**
- Consumes: MySQL Docker entrypoint execution of each mounted `.sql` file.
- Produces: A per-script MySQL client session using `utf8mb4_unicode_ci` before Chinese DDL and DML are parsed.

- [ ] **Step 1: Update `01-init-tables.sql`**

Insert immediately before its `USE iot_platform;` statement:

```sql
-- Docker entrypoint 的 MySQL 客户端可能默认使用 latin1。
-- 必须在首个中文 DDL/DML 前明确客户端字符集，否则种子中文会被双重编码成乱码。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 2: Update `03-init-hvac-schema.sql`**

Insert immediately before its `USE iot_platform;` statement:

```sql
-- Docker entrypoint 会为每份初始化脚本建立执行上下文，不能依赖上一份脚本的会话状态。
-- 在本脚本首个中文 DDL/DML 前再次声明 UTF-8，避免建筑和测点名称被按 latin1 读取。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [ ] **Step 3: Run the charset contract**

Run:

```powershell
.\mvnw.cmd "-Dtest=MySqlInitializationCharsetContractTest" test
```

Expected: 1 test passes with 0 failures, 0 errors, and 0 skipped.

- [ ] **Step 4: Run adjacent environment contracts**

Run:

```powershell
.\mvnw.cmd "-Dtest=MySqlInitializationCharsetContractTest,DockerComposeConfigurationTest,CleanHvacSmokeScriptContractTest,HvacSeedUnitContractTest" test
```

Expected: all selected tests pass and do not connect to external services.

- [ ] **Step 5: Commit the contract and SQL fix**

Run:

```powershell
git add -- `
  src/test/java/com/platform/config/MySqlInitializationCharsetContractTest.java `
  src/env/init/01-init-tables.sql `
  src/env/init/03-init-hvac-schema.sql
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(env): initialize mysql scripts with utf8"
```

Expected: exactly three files are committed.

---

### Task 3: Run the full isolated regression suite

**Files:**
- Verify only: all Maven test sources.

**Interfaces:**
- Consumes: the SQL contract and existing Spring test profile.
- Produces: evidence that the configuration-only fix does not break application behavior or test isolation.

- [ ] **Step 1: Run the full Maven suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: build success, 0 failures, 0 errors, and 0 skipped.

- [ ] **Step 2: Record exact totals**

Read `target/surefire-reports/TEST-*.xml` and sum `tests`, `failures`,
`errors`, and `skipped`.

Expected: the totals agree with Maven's successful exit code.

- [ ] **Step 3: Package the backend**

Run:

```powershell
.\mvnw.cmd -DskipTests package
```

Expected: `target/iot-platform-demo-1.0-SNAPSHOT.jar` is produced successfully.

---

### Task 4: Rebuild only the local MySQL test data

**Files:**
- Delete and recreate at runtime only: `src/env/mysql-data`
- Preserve: `src/env/taos-data`
- Preserve: `src/env/redis-data`
- Preserve: all repository source files and `outputs/`

**Interfaces:**
- Consumes: explicit user approval to remove the current local MySQL test data.
- Produces: a fresh MySQL 8.0 instance initialized by the corrected scripts.

- [ ] **Step 1: Resolve and validate the deletion target**

Run:

```powershell
$repoRoot = (Resolve-Path '.').Path
$envRoot = (Resolve-Path 'src/env').Path
$mysqlData = [IO.Path]::GetFullPath((Join-Path $envRoot 'mysql-data'))
$expected = [IO.Path]::GetFullPath((Join-Path $repoRoot 'src/env/mysql-data'))

if ($mysqlData -ne $expected) {
    throw "MySQL data path mismatch: $mysqlData"
}
if (-not $mysqlData.StartsWith($envRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw "MySQL data path escapes src/env: $mysqlData"
}

$mysqlData
Get-ChildItem -LiteralPath $envRoot -Force |
    Select-Object Name,FullName
docker ps --format "table {{.Names}}\t{{.Status}}"
```

Expected: the deletion target is exactly this worktree's
`src/env/mysql-data`, and `taos-data`/`redis-data` resolve to different paths.

- [ ] **Step 2: Stop the current Compose stack**

Run:

```powershell
docker compose -f src/env/docker-compose.yml down
```

Expected: `iot-mysql`, `iot-tdengine`, `iot-redis`, and `iot-emqx` containers stop and are removed; bind-mounted data remains on disk.

- [ ] **Step 3: Delete only the validated MySQL directory**

Run in the same PowerShell process that validated `$mysqlData`:

```powershell
Remove-Item -LiteralPath $mysqlData -Recurse -Force
Test-Path -LiteralPath $mysqlData
Test-Path -LiteralPath (Join-Path $envRoot 'taos-data')
Test-Path -LiteralPath (Join-Path $envRoot 'redis-data')
```

Expected: `mysql-data` is absent; the TDengine and Redis directories retain their pre-reset existence state.

- [ ] **Step 4: Start the Compose stack**

Run:

```powershell
docker compose -f src/env/docker-compose.yml up -d
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

Expected: exactly the four project containers start.

- [ ] **Step 5: Wait for infrastructure health**

Poll:

```powershell
docker inspect --format "{{.Name}} {{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" `
  iot-mysql iot-emqx iot-tdengine iot-redis
```

Expected: all four containers report `healthy`.

---

### Task 5: Prove the fresh MySQL values are correctly encoded

**Files:**
- Verify only: the rebuilt `iot_platform` database.

**Interfaces:**
- Consumes: the clean MySQL database initialized from corrected SQL files.
- Produces: direct text and byte-level evidence for representative Chinese values.

- [ ] **Step 1: Verify server and representative seed values**

Run:

```powershell
docker exec iot-mysql mysql --default-character-set=utf8mb4 `
  -uroot -pchange-me -D iot_platform -e "
SELECT @@character_set_server, @@collation_server;
SELECT username,nickname,HEX(nickname) FROM sys_user WHERE username='admin';
SELECT role_key,role_name,HEX(role_name) FROM sys_role ORDER BY role_key;
SELECT building_id,building_name,HEX(building_name)
FROM building WHERE building_id='BLD001';
SELECT point_id,point_name,HEX(point_name)
FROM biz_data_point
WHERE point_id IN ('POINT001','POINT018')
ORDER BY point_id;"
```

Expected:

```text
utf8mb4  utf8mb4_unicode_ci
admin  超级管理员
BLD001  试点大楼  E8AF95E782B9E5A4A7E6A5BC
```

The role and point names must also display as readable Chinese.

- [ ] **Step 2: Verify no representative mojibake remains**

Run:

```powershell
docker exec iot-mysql mysql --default-character-set=utf8mb4 `
  -uroot -pchange-me -D iot_platform -N -e "
SELECT COUNT(*) FROM building
WHERE building_name LIKE '%è%' OR building_name LIKE '%å%';
SELECT COUNT(*) FROM biz_data_point
WHERE point_name LIKE '%è%' OR point_name LIKE '%å%';"
```

Expected: both counts are `0`.

---

### Task 6: Run the real HVAC API and browser regression

**Files:**
- Verify only: `.scripts/simulate-hvac-19-points.mjs`
- Verify only: `web/`
- Verify only: packaged backend JAR.

**Interfaces:**
- Consumes: rebuilt MySQL metadata plus preserved TDengine/Redis and local EMQX.
- Produces: API and browser evidence that correct Chinese names coexist with the full real-data HVAC flow.

- [ ] **Step 1: Start the backend with local test ports**

Start `target/iot-platform-demo-1.0-SNAPSHOT.jar` with:

```text
MYSQL_PORT=13306
REDIS_PORT=16379
MQTT_ENABLED=true
```

Redirect stdout and stderr to uniquely named files under `%TEMP%`, record the
PID, and wait until port `8081` listens.

- [ ] **Step 2: Start the Vite frontend**

Run `npm run dev -- --host 127.0.0.1` from `web/` in a hidden background
process, redirect logs under `%TEMP%`, record the PID, and wait until port
`5173` listens.

- [ ] **Step 3: Publish the complete test dataset**

Run:

```powershell
node .scripts/simulate-hvac-19-points.mjs
```

Expected: 7 rounds, 19 points per round, 133 messages total.

- [ ] **Step 4: Verify the authenticated APIs**

Log in as local test administrator `admin`, call:

```text
GET /api/hvac/buildings/BLD001/snapshot
GET /api/hvac/buildings/BLD001/indicators/latest
```

Expected:

```text
snapshot: 19 rows, 19 distinct point codes, 19 dataQuality=0
indicators: 4 rows, 4 SUCCESS, 4 non-null values
```

- [ ] **Step 5: Verify the browser**

Open `http://127.0.0.1:5173/hvac-demo`, log in as the local administrator,
and verify:

```text
真实分钟数据已连接
试点大楼
固定测点 19
测点数据完整率 100%
19 个“实测 · NORMAL”
4 个“计算成功”
```

Verify representative names “1号机组冷冻水进水温度”和“室外干球温度”显示
正确，并确认浏览器控制台没有错误或警告。

- [ ] **Step 6: Stop only the temporary acceptance processes**

Stop the recorded backend Java PID, Vite Node PID, and its command wrapper.
Verify ports `8081` and `5173` are released. Leave the four Docker containers
running and healthy.

---

### Task 7: Final scope review, documentation commit, and branch delivery

**Files:**
- Verify: `docs/superpowers/specs/2026-07-31-mysql-init-utf8-design.md`
- Verify: `docs/superpowers/plans/2026-07-31-mysql-init-utf8.md`
- Verify: `src/env/init/01-init-tables.sql`
- Verify: `src/env/init/03-init-hvac-schema.sql`
- Verify: `src/test/java/com/platform/config/MySqlInitializationCharsetContractTest.java`

**Interfaces:**
- Consumes: all automated and real-environment evidence.
- Produces: one clean pushed task branch and complete PR materials.

- [ ] **Step 1: Commit the implementation plan**

Run:

```powershell
git add -- docs/superpowers/plans/2026-07-31-mysql-init-utf8.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(env): plan mysql init utf8 fix"
```

- [ ] **Step 2: Review final scope**

Run:

```powershell
git status --short --branch
git diff --check origin/main...HEAD
git diff --name-status origin/main...HEAD
git log --oneline origin/main..HEAD
```

Expected: only the five files listed in this task appear; local database
directories, logs, build output, frontend files, main-checkout comments, and
`outputs/` are absent.

- [ ] **Step 3: Check latest main and conflicts**

Run:

```powershell
git fetch --prune origin
git rev-list --left-right --count origin/main...HEAD
$base = git merge-base HEAD origin/main
git merge-tree $base HEAD origin/main
```

Expected: no remote-only commits and no conflict markers. If `origin/main`
advanced, merge it normally, rerun affected tests, and repeat the scope check.

- [ ] **Step 4: Push the task branch**

Run:

```powershell
git push -u origin fix/mysql-init-utf8
```

Expected: the remote branch is created without updating `main`.

- [ ] **Step 5: Deliver PR material**

Provide:

- compare URL for `main...fix/mysql-init-utf8`;
- Chinese PR title;
- copyable Markdown description;
- exact automated test totals;
- real MySQL text/hex evidence;
- API and browser results;
- destructive test-data scope;
- explicit exclusions and conflict status;
- state “等待用户创建并合并 PR”.
