# MySQL and Redis Stable Volumes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将本地 MySQL 和 Redis 从短期 Git worktree 绑定目录迁移到固定 Docker 命名卷，同时完整保留 MySQL 数据并重新生成 Redis 缓存。

**Architecture:** Compose 使用显式 `name` 的 Docker Volume 隔离数据生命周期与仓库路径。MySQL 先从仍在运行的旧容器执行逻辑备份，再将备份恢复到挂载最终命名卷的临时容器并完成一致性校验，验证成功后才切换正式容器；Redis 作为可重建缓存直接使用空命名卷重建。

**Tech Stack:** Docker Desktop、Docker Compose V2、MySQL 8.0、Redis 7.2 Alpine、PowerShell 5.1、Java 21、Spring Boot、JUnit 5、AssertJ、Maven

## Global Constraints

- MySQL 固定卷名必须为 `iot-platform-demo-mysql-data`。
- Redis 固定卷名必须为 `iot-platform-demo-redis-data`。
- TDengine 固定卷 `iot-platform-demo-tdengine-data` 保持不变。
- MySQL `iot_platform` 的全部 21 张现有业务表和数据必须保留。
- Redis 现有 24 个缓存键不迁移，由应用重新生成。
- MySQL 备份必须位于仓库外的 `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes`。
- 禁止执行整套 `docker compose down` 或任何 `docker compose down -v`。
- 禁止停止、删除或重建 `iot-tdengine` 和 `iot-emqx`。
- 禁止修改 Java 生产业务代码、数据库表结构和 TDengine 数据。
- 禁止触碰 `D:\word\iot-platform-demo` 中现有的 Controller 修改和 `outputs/`。
- 真实迁移命令必须显式使用稳定项目目录 `D:\word\iot-platform-demo\src\env`。
- 备份、数据库数据、日志和运行时诊断文件不得加入 Git。

---

### Task 1: 用契约测试锁定 MySQL 和 Redis 命名卷

**Files:**
- Modify: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`
- Modify: `src/env/docker-compose.yml`

**Interfaces:**
- Consumes: 当前 Compose 的 `mysql`、`redis` 和顶层 `volumes` 配置。
- Produces: `mysql-data` 与 `redis-data` 两个逻辑卷，以及固定物理卷名 `iot-platform-demo-mysql-data`、`iot-platform-demo-redis-data`。

- [ ] **Step 1: 添加失败的 Compose 契约测试**

在 `tdengineUsesStableIdentityAndProjectIndependentVolume()` 后增加：

```java
@Test
void mysqlAndRedisUseProjectIndependentVolumes() {
    assertThat(compose)
            .contains(
                    "- mysql-data:/var/lib/mysql",
                    "name: iot-platform-demo-mysql-data",
                    "- redis-data:/data",
                    "name: iot-platform-demo-redis-data")
            .doesNotContain(
                    "- ./mysql-data:/var/lib/mysql",
                    "- ./redis-data:/data");
    assertThat(countOccurrences(compose, "mysql-data:")).isEqualTo(2);
    assertThat(countOccurrences(compose, "redis-data:")).isEqualTo(2);
}
```

该测试的中文类级注释已经说明 Compose 边界，无需添加重复逐行注释。

- [ ] **Step 2: 运行定向测试并确认先失败**

Run:

```powershell
mvn -Dtest=DockerComposeConfigurationTest test
```

Expected:

- 共运行 4 个测试；
- 新测试失败；
- 失败信息指出 Compose 缺少 MySQL/Redis 命名卷，并仍包含两个相对绑定目录；
- 其余 3 个测试通过。

- [ ] **Step 3: 修改 Compose 的 MySQL 数据挂载**

将 MySQL 的第一条数据挂载替换为：

```yaml
    volumes:
      # 固定名称 Volume 不依赖 Git worktree；删除分支目录不会删除 MySQL 业务数据。
      - mysql-data:/var/lib/mysql
      # 仅自动执行全新 MySQL 所需脚本。已有数据卷不会重跑 init 目录。
```

保留后面的两个只读初始化 SQL 挂载，不修改其顺序。

- [ ] **Step 4: 修改 Compose 的 Redis 数据挂载**

将 Redis 挂载替换为：

```yaml
    volumes:
      # Redis 缓存允许重建，但固定名称 Volume 可避免普通容器重建产生无意义的缓存空窗。
      - redis-data:/data
```

- [ ] **Step 5: 增加顶层固定命名卷**

把顶层 `volumes` 改为：

```yaml
volumes:
  mysql-data:
    # 显式名称保证从不同目录执行 Compose 时仍使用同一个 MySQL 数据卷。
    name: iot-platform-demo-mysql-data
  redis-data:
    # 显式名称保证 Redis 缓存卷不随短期 worktree 或 Compose 项目名称变化。
    name: iot-platform-demo-redis-data
  tdengine-data:
    # 显式名称保证从不同目录执行 Compose 时仍使用同一个 TDengine 数据卷。
    name: iot-platform-demo-tdengine-data
```

- [ ] **Step 6: 运行定向测试并确认通过**

Run:

```powershell
mvn -Dtest=DockerComposeConfigurationTest test
```

Expected: 4 tests run，0 failures，0 errors，0 skipped。

- [ ] **Step 7: 验证 Compose 渲染结果及稳定配置路径**

Run:

```powershell
$repoRoot = 'C:\Users\yang\.codex\worktrees\d9a9\iot-platform-demo'
$composeFile = Join-Path $repoRoot 'src\env\docker-compose.yml'
$stableEnvRoot = 'D:\word\iot-platform-demo\src\env'
$env:MYSQL_PORT = '13306'
$env:REDIS_PORT = '16379'

docker compose `
  -p env `
  --project-directory $stableEnvRoot `
  -f $composeFile `
  config --quiet

$rendered = docker compose `
  -p env `
  --project-directory $stableEnvRoot `
  -f $composeFile `
  config

foreach ($expected in @(
  'name: iot-platform-demo-mysql-data',
  'name: iot-platform-demo-redis-data',
  'name: iot-platform-demo-tdengine-data'
)) {
  if ($rendered -notmatch [regex]::Escape($expected)) {
    throw "Compose 渲染结果缺少 $expected"
  }
}

foreach ($forbidden in @(
  './mysql-data',
  './redis-data'
)) {
  if ($rendered -match [regex]::Escape($forbidden)) {
    throw "Compose 仍引用相对数据目录 $forbidden"
  }
}
```

Expected: `config --quiet` exit 0，三个固定卷名全部存在，不包含旧相对数据目录。

- [ ] **Step 8: 检查注释、暂存范围并提交**

Run:

```powershell
git diff --check
git diff -- src/env/docker-compose.yml `
  src/test/java/com/platform/config/DockerComposeConfigurationTest.java
git add -- `
  src/env/docker-compose.yml `
  src/test/java/com/platform/config/DockerComposeConfigurationTest.java
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(env): 稳定 MySQL Redis 数据卷"
```

Expected: 暂存和提交仅包含上述两个文件。

---

### Task 2: 在破坏性迁移前完成自动化回归

**Files:**
- Verify: `pom.xml`
- Verify: `target/surefire-reports/`

**Interfaces:**
- Consumes: Task 1 的 Compose 配置和契约测试。
- Produces: 允许进入真实容器迁移的自动化测试门槛。

- [ ] **Step 1: 运行完整 Maven 回归**

Run:

```powershell
mvn test
```

Expected:

- Maven exit 0；
- 0 failures；
- 0 errors；
- 0 skipped；
- 测试总数不低于当前基线 440。

- [ ] **Step 2: 汇总 Surefire 精确结果**

Run:

```powershell
[xml[]]$reports = Get-ChildItem target\surefire-reports\TEST-*.xml |
  ForEach-Object { [xml](Get-Content -Raw $_.FullName) }

$tests = ($reports.testsuite | Measure-Object tests -Sum).Sum
$failures = ($reports.testsuite | Measure-Object failures -Sum).Sum
$errors = ($reports.testsuite | Measure-Object errors -Sum).Sum
$skipped = ($reports.testsuite | Measure-Object skipped -Sum).Sum

"tests=$tests failures=$failures errors=$errors skipped=$skipped"

if ($tests -lt 440 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
  throw '完整 Maven 回归不满足迁移门槛'
}
```

Expected: 至少 440 tests，failures/errors/skipped 均为 0。

- [ ] **Step 3: 迁移前确认 Git 工作区只包含计划内提交**

Run:

```powershell
git status --short --branch
git log --oneline origin/main..HEAD
```

Expected: 工作区干净；分支仅领先设计、计划和 Compose 修复提交。

---

### Task 3: 备份 MySQL 并在最终命名卷中预恢复

**Files:**
- Create outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\iot_platform.sql`
- Create outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\source-tables.txt`
- Create outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\source-table-counts.tsv`
- Create outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\source-utf8-hex.tsv`
- Create outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\SHA256SUMS.txt`
- Create outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\migration-notes.txt`

**Interfaces:**
- Consumes: 正在运行的 `iot-mysql` 和不存在的目标卷 `iot-platform-demo-mysql-data`。
- Produces: 可恢复 SQL 备份、源数据基线、已经验证的最终 MySQL 命名卷。

本任务至 Task 5 使用同一个提升权限的 PowerShell 会话，避免把数据库密码
写入磁盘。任何断言失败都必须停止，不能继续执行下一步。

- [ ] **Step 1: 固定所有精确路径、容器名和卷名**

Run:

```powershell
$repoRoot = 'C:\Users\yang\.codex\worktrees\d9a9\iot-platform-demo'
$stableEnvRoot = 'D:\word\iot-platform-demo\src\env'
$composeFile = Join-Path $repoRoot 'src\env\docker-compose.yml'
$backupRoot = 'D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes'
$mysqlContainer = 'iot-mysql'
$mysqlCheckContainer = 'iot-mysql-migration-check'
$mysqlVolume = 'iot-platform-demo-mysql-data'
$redisVolume = 'iot-platform-demo-redis-data'
$tdContainer = 'iot-tdengine'
$emqxContainer = 'iot-emqx'
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$env:MYSQL_PORT = '13306'
$env:REDIS_PORT = '16379'

if ([IO.Path]::GetFullPath($repoRoot) -ne
    [IO.Path]::GetFullPath('C:\Users\yang\.codex\worktrees\d9a9\iot-platform-demo')) {
  throw '任务仓库路径不匹配'
}
if ([IO.Path]::GetFullPath($stableEnvRoot) -ne
    [IO.Path]::GetFullPath('D:\word\iot-platform-demo\src\env')) {
  throw '稳定 Compose 项目目录不匹配'
}
if (Test-Path -LiteralPath $backupRoot) {
  throw "备份目录已经存在，禁止覆盖：$backupRoot"
}

$mysqlVolumeExists = docker volume ls `
  --filter "name=^$mysqlVolume$" `
  --format '{{.Name}}'
$redisVolumeExists = docker volume ls `
  --filter "name=^$redisVolume$" `
  --format '{{.Name}}'

if ($mysqlVolumeExists -or $redisVolumeExists) {
  throw '目标命名卷已经存在，禁止自动删除或覆盖'
}
```

Expected: 路径精确匹配，备份目录和两个目标卷均不存在。

- [ ] **Step 2: 确认容器健康并记录保护对象 ID**

Run:

```powershell
foreach ($name in $mysqlContainer,$tdContainer,$emqxContainer,'iot-redis') {
  $status = docker inspect $name --format '{{.State.Status}}'
  $health = docker inspect $name `
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
  if ($status -ne 'running' -or $health -ne 'healthy') {
    throw "$name 不是 running/healthy：status=$status health=$health"
  }
}

$sourceMysqlId = docker inspect $mysqlContainer --format '{{.Id}}'
$protectedTdId = docker inspect $tdContainer --format '{{.Id}}'
$protectedEmqxId = docker inspect $emqxContainer --format '{{.Id}}'

$mysqlPasswordLine = docker inspect $mysqlContainer `
  --format '{{range .Config.Env}}{{println .}}{{end}}' |
  Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' }

if (-not $mysqlPasswordLine) {
  throw '无法从运行中 MySQL 容器取得 MYSQL_ROOT_PASSWORD'
}
$mysqlPassword = $mysqlPasswordLine.Substring('MYSQL_ROOT_PASSWORD='.Length)
if ([string]::IsNullOrEmpty($mysqlPassword)) {
  throw 'MySQL root 密码为空，停止迁移'
}
```

Expected: 四个容器健康，变量记录源 MySQL、TDengine、EMQX 的完整容器 ID；
命令不打印密码。

- [ ] **Step 3: 建立仓库外备份目录并记录非敏感元数据**

Run:

```powershell
New-Item -ItemType Directory -Path $backupRoot -ErrorAction Stop | Out-Null

$notes = @(
  "startedAt=$([DateTimeOffset]::Now.ToString('o'))",
  "sourceMysqlContainer=$mysqlContainer",
  "sourceMysqlId=$sourceMysqlId",
  "sourceMysqlImage=$(docker inspect $mysqlContainer --format '{{.Config.Image}}')",
  "protectedTdengineId=$protectedTdId",
  "protectedEmqxId=$protectedEmqxId",
  "mysqlVolume=$mysqlVolume",
  "redisVolume=$redisVolume"
)
[IO.File]::WriteAllLines(
  (Join-Path $backupRoot 'migration-notes.txt'),
  $notes,
  $utf8NoBom)
```

Expected: 只创建仓库外备份目录，不写入密码。

- [ ] **Step 4: 采集源库表清单、准确行数和中文 UTF-8 字节**

Run:

```powershell
function Get-TableNames([string]$container, [string]$password) {
  @(
    docker exec -e "MYSQL_PWD=$password" $container `
      mysql -uroot --default-character-set=utf8mb4 -N -B `
      -e "SELECT table_name FROM information_schema.tables WHERE table_schema='iot_platform' ORDER BY table_name;"
  )
}

function Get-TableCounts(
    [string]$container,
    [string]$password,
    [string[]]$tables) {
  @(
    foreach ($table in $tables) {
      $escapedTable = $table.Replace('`', '``')
      $sql = 'SELECT COUNT(*) FROM `{0}`;' -f $escapedTable
      $count = (
        docker exec -e "MYSQL_PWD=$password" $container `
          mysql -uroot --default-character-set=utf8mb4 -N -B `
          iot_platform -e $sql
      ).Trim()
      if ($count -notmatch '^\d+$') {
        throw "无法取得 $table 的准确行数：$count"
      }
      "$table`t$count"
    }
  )
}

function Get-Utf8Evidence([string]$container, [string]$password) {
  $sql = @"
SELECT CONCAT('biz_equipment:', equip_code), HEX(equip_name)
FROM biz_equipment
UNION ALL
SELECT CONCAT('building:', building_id), HEX(building_name)
FROM building
ORDER BY 1;
"@
  @(
    docker exec -e "MYSQL_PWD=$password" $container `
      mysql -uroot --default-character-set=utf8mb4 -N -B `
      iot_platform -e $sql
  )
}

$sourceTables = Get-TableNames $mysqlContainer $mysqlPassword
if ($sourceTables.Count -ne 21) {
  throw "源库表数不是基线 21：$($sourceTables.Count)"
}
$sourceCounts = Get-TableCounts $mysqlContainer $mysqlPassword $sourceTables
$sourceUtf8 = Get-Utf8Evidence $mysqlContainer $mysqlPassword

if ($sourceUtf8 -notcontains
    "building:BLD001`tE8AF95E782B9E5A4A7E6A5BC") {
  throw '试点大楼名称不是预期 UTF-8 字节'
}
if ($sourceUtf8 -notcontains
    "biz_equipment:AHU1`t31E58FB7E7A9BAE6B094E5A484E79086E69CBAE7BB84") {
  throw 'AHU1 设备名称不是预期 UTF-8 字节'
}

[IO.File]::WriteAllLines(
  (Join-Path $backupRoot 'source-tables.txt'),
  $sourceTables,
  $utf8NoBom)
[IO.File]::WriteAllLines(
  (Join-Path $backupRoot 'source-table-counts.tsv'),
  $sourceCounts,
  $utf8NoBom)
[IO.File]::WriteAllLines(
  (Join-Path $backupRoot 'source-utf8-hex.tsv'),
  $sourceUtf8,
  $utf8NoBom)
```

Expected: 21 张表；所有行数是非负整数；建筑和 AHU1 名称字节为正确 UTF-8。

- [ ] **Step 5: 从运行中 MySQL 创建完整逻辑备份**

Run:

```powershell
$containerDump = '/tmp/iot_platform-stable-volumes.sql'
$hostDump = Join-Path $backupRoot 'iot_platform.sql'

docker exec -e "MYSQL_PWD=$mysqlPassword" $mysqlContainer `
  mysqldump -uroot `
  --single-transaction `
  --routines `
  --triggers `
  --events `
  --hex-blob `
  --default-character-set=utf8mb4 `
  --databases iot_platform `
  --result-file=$containerDump

if ($LASTEXITCODE -ne 0) {
  throw 'mysqldump 失败'
}

docker cp "${mysqlContainer}:${containerDump}" $hostDump
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $hostDump)) {
  throw '无法把 MySQL 备份复制到仓库外'
}

$dumpFile = Get-Item -LiteralPath $hostDump
if ($dumpFile.Length -le 0) {
  throw 'MySQL 备份文件为空'
}

$dumpHash = Get-FileHash -Algorithm SHA256 -LiteralPath $hostDump
[IO.File]::WriteAllText(
  (Join-Path $backupRoot 'SHA256SUMS.txt'),
  "$($dumpHash.Hash.ToLowerInvariant())  iot_platform.sql`r`n",
  $utf8NoBom)
```

Expected: `iot_platform.sql` 非空，`SHA256SUMS.txt` 包含 64 位 SHA-256。

- [ ] **Step 6: 创建最终 MySQL 卷并启动无宿主机端口的验证容器**

Run:

```powershell
docker volume create $mysqlVolume
if ($LASTEXITCODE -ne 0) {
  throw '创建 MySQL 命名卷失败'
}

docker run -d `
  --name $mysqlCheckContainer `
  --mount "type=volume,source=$mysqlVolume,target=/var/lib/mysql" `
  -e "MYSQL_ROOT_PASSWORD=$mysqlPassword" `
  -e 'MYSQL_DATABASE=iot_platform' `
  mysql:8.0

if ($LASTEXITCODE -ne 0) {
  throw '启动 MySQL 预恢复容器失败'
}

$ready = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
  docker exec -e "MYSQL_PWD=$mysqlPassword" $mysqlCheckContainer `
    mysqladmin ping -h 127.0.0.1 -uroot --silent *> $null
  if ($LASTEXITCODE -eq 0) {
    $ready = $true
    break
  }
  Start-Sleep -Seconds 2
}
if (-not $ready) {
  throw 'MySQL 预恢复容器未在 120 秒内就绪'
}
```

Expected: 创建唯一目标卷；临时容器不发布 3306/13306 端口且 MySQL 可连接。

- [ ] **Step 7: 恢复备份并逐项比较**

Run:

```powershell
docker cp $hostDump "${mysqlCheckContainer}:/tmp/iot_platform.sql"
if ($LASTEXITCODE -ne 0) {
  throw '无法把 SQL 备份复制到预恢复容器'
}

docker exec -e "MYSQL_PWD=$mysqlPassword" $mysqlCheckContainer `
  sh -c 'mysql -uroot < /tmp/iot_platform.sql'
if ($LASTEXITCODE -ne 0) {
  throw 'MySQL 预恢复失败'
}

$restoredTables = Get-TableNames $mysqlCheckContainer $mysqlPassword
$restoredCounts = Get-TableCounts `
  $mysqlCheckContainer `
  $mysqlPassword `
  $restoredTables
$restoredUtf8 = Get-Utf8Evidence $mysqlCheckContainer $mysqlPassword

if (@(Compare-Object $sourceTables $restoredTables).Count -ne 0) {
  throw '预恢复库的表清单与源库不一致'
}
if (@(Compare-Object $sourceCounts $restoredCounts).Count -ne 0) {
  throw '预恢复库的准确行数与源库不一致'
}
if (@(Compare-Object $sourceUtf8 $restoredUtf8).Count -ne 0) {
  throw '预恢复库的中文 UTF-8 字节与源库不一致'
}
```

Expected: 表清单、每表准确行数、中文 UTF-8 证据全部一致。

- [ ] **Step 8: 删除临时容器但保留已验证的最终卷**

Run:

```powershell
docker stop $mysqlCheckContainer
if ($LASTEXITCODE -ne 0) {
  throw '停止 MySQL 预恢复容器失败'
}
docker rm $mysqlCheckContainer
if ($LASTEXITCODE -ne 0) {
  throw '删除 MySQL 预恢复容器失败'
}

$mysqlVolumeAfterCheck = docker volume ls `
  --filter "name=^$mysqlVolume$" `
  --format '{{.Name}}'
if ($mysqlVolumeAfterCheck -ne $mysqlVolume) {
  throw '已验证的 MySQL 命名卷不存在'
}

$oldMysqlStillRunning = docker inspect $mysqlContainer `
  --format '{{.State.Status}}'
if ($oldMysqlStillRunning -ne 'running') {
  throw '旧 MySQL 在预恢复阶段被意外停止'
}
```

Expected: 临时容器已删除；最终命名卷存在；旧 `iot-mysql` 仍在运行。

---

### Task 4: 把正式 MySQL 切换到已验证命名卷

**Files:**
- Read outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\source-tables.txt`
- Read outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\source-table-counts.tsv`
- Read outside Git: `D:\word\iot-platform-demo-runtime-backups\2026-07-31-mysql-redis-stable-volumes\source-utf8-hex.tsv`

**Interfaces:**
- Consumes: Task 3 验证过的 `iot-platform-demo-mysql-data`。
- Produces: 正式 `iot-mysql` 容器挂载命名卷并保持原业务数据。

- [ ] **Step 1: 再次确认保护容器 ID 和最终卷**

Run:

```powershell
if ((docker inspect $tdContainer --format '{{.Id}}') -ne $protectedTdId) {
  throw 'TDengine 容器 ID 已变化，停止迁移'
}
if ((docker inspect $emqxContainer --format '{{.Id}}') -ne $protectedEmqxId) {
  throw 'EMQX 容器 ID 已变化，停止迁移'
}
if ((docker volume ls --filter "name=^$mysqlVolume$" --format '{{.Name}}') -ne
    $mysqlVolume) {
  throw '已验证的 MySQL 命名卷不存在'
}
```

Expected: TDengine、EMQX ID 未变，最终 MySQL 卷存在。

- [ ] **Step 2: 只停止并删除旧 MySQL 容器**

Run:

```powershell
docker stop $mysqlContainer
if ($LASTEXITCODE -ne 0) {
  throw '停止旧 MySQL 失败'
}
docker rm $mysqlContainer
if ($LASTEXITCODE -ne 0) {
  throw '删除旧 MySQL 容器失败'
}
```

Expected: 仅 `iot-mysql` 被停止和删除；备份、最终卷、TDengine、Redis、
EMQX 均保持。

- [ ] **Step 3: 使用稳定项目目录只创建 MySQL**

Run:

```powershell
$env:MYSQL_PASSWORD = $mysqlPassword

docker compose `
  -p env `
  --project-directory $stableEnvRoot `
  -f $composeFile `
  up -d mysql

if ($LASTEXITCODE -ne 0) {
  throw '从最终命名卷创建正式 MySQL 失败'
}

$healthy = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
  $health = docker inspect $mysqlContainer `
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
  if ($health -eq 'healthy') {
    $healthy = $true
    break
  }
  Start-Sleep -Seconds 2
}
if (-not $healthy) {
  throw '正式 MySQL 未在 120 秒内健康'
}
```

Expected: 只创建 `iot-mysql`，宿主机端口为 13306，容器健康。

- [ ] **Step 4: 验证 MySQL 数据卷和只读配置挂载来源**

Run:

```powershell
$mysqlMounts = docker inspect $mysqlContainer |
  ConvertFrom-Json |
  Select-Object -ExpandProperty Mounts

$dataMount = $mysqlMounts |
  Where-Object { $_.Destination -eq '/var/lib/mysql' }
if ($dataMount.Type -ne 'volume' -or $dataMount.Name -ne $mysqlVolume) {
  throw '正式 MySQL 未挂载目标命名卷'
}

$initMounts = @(
  $mysqlMounts |
    Where-Object { $_.Destination -like '/docker-entrypoint-initdb.d/*' }
)
if ($initMounts.Count -ne 2) {
  throw 'MySQL 初始化 SQL 挂载数量不是 2'
}
foreach ($mount in $initMounts) {
  $source = [IO.Path]::GetFullPath($mount.Source)
  if (-not $source.StartsWith(
      [IO.Path]::GetFullPath($stableEnvRoot),
      [StringComparison]::OrdinalIgnoreCase)) {
    throw "MySQL 只读初始化文件仍来自短期 worktree：$source"
  }
}
```

Expected: `/var/lib/mysql` 使用目标 Volume；两个初始化 SQL 来自
`D:\word\iot-platform-demo\src\env\init`。

- [ ] **Step 5: 再次验证数据和 UTF-8**

Run:

```powershell
$expectedTables = @(
  Get-Content -Encoding UTF8 `
    (Join-Path $backupRoot 'source-tables.txt')
)
$expectedCounts = @(
  Get-Content -Encoding UTF8 `
    (Join-Path $backupRoot 'source-table-counts.tsv')
)
$expectedUtf8 = @(
  Get-Content -Encoding UTF8 `
    (Join-Path $backupRoot 'source-utf8-hex.tsv')
)

$finalTables = Get-TableNames $mysqlContainer $mysqlPassword
$finalCounts = Get-TableCounts $mysqlContainer $mysqlPassword $finalTables
$finalUtf8 = Get-Utf8Evidence $mysqlContainer $mysqlPassword

if (@(Compare-Object $expectedTables $finalTables).Count -ne 0) {
  throw '正式 MySQL 表清单不一致'
}
if (@(Compare-Object $expectedCounts $finalCounts).Count -ne 0) {
  throw '正式 MySQL 准确行数不一致'
}
if (@(Compare-Object $expectedUtf8 $finalUtf8).Count -ne 0) {
  throw '正式 MySQL 中文 UTF-8 数据不一致'
}
```

Expected: 三项比较全部一致。

- [ ] **Step 6: 单独重启 MySQL 并复验持久性**

Run:

```powershell
docker restart $mysqlContainer
if ($LASTEXITCODE -ne 0) {
  throw '重启正式 MySQL 失败'
}

$healthy = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
  if ((docker inspect $mysqlContainer `
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}') `
      -eq 'healthy') {
    $healthy = $true
    break
  }
  Start-Sleep -Seconds 2
}
if (-not $healthy) {
  throw 'MySQL 重启后未恢复健康'
}

$restartTables = Get-TableNames $mysqlContainer $mysqlPassword
$restartCounts = Get-TableCounts $mysqlContainer $mysqlPassword $restartTables
if (@(Compare-Object $expectedTables $restartTables).Count -ne 0 -or
    @(Compare-Object $expectedCounts $restartCounts).Count -ne 0) {
  throw 'MySQL 重启后数据不一致'
}
```

Expected: MySQL 重启后健康，表清单和准确行数不变。

---

### Task 5: 使用空命名卷重建 Redis 并重新生成缓存

**Files:**
- Runtime only: Docker Volume `iot-platform-demo-redis-data`
- Runtime only: `%TEMP%\iot-platform-demo-migration-backend.log`
- Runtime only: `%TEMP%\iot-platform-demo-migration-backend.err.log`

**Interfaces:**
- Consumes: Task 1 的 Redis Compose 配置，以及已迁移的 MySQL、现有
  TDengine、现有 EMQX。
- Produces: 使用固定命名卷的 `iot-redis` 和重新生成的 HVAC 指标缓存。

- [ ] **Step 1: 确认 Redis 目标卷不存在并记录旧容器范围**

Run:

```powershell
$redisContainer = 'iot-redis'
$oldRedisId = docker inspect $redisContainer --format '{{.Id}}'
$oldRedisKeys = [int](docker exec $redisContainer redis-cli DBSIZE)
if (docker volume ls --filter "name=^$redisVolume$" --format '{{.Name}}') {
  throw 'Redis 目标命名卷已经存在，禁止覆盖'
}
"oldRedisId=$oldRedisId oldRedisKeys=$oldRedisKeys"
```

Expected: 记录旧 Redis ID 和迁移时的实际键数；目标卷不存在。缓存键带 TTL，
因此实际键数允许不同于设计阶段观测到的 24。

- [ ] **Step 2: 只停止并删除旧 Redis**

Run:

```powershell
docker stop $redisContainer
if ($LASTEXITCODE -ne 0) {
  throw '停止旧 Redis 失败'
}
docker rm $redisContainer
if ($LASTEXITCODE -ne 0) {
  throw '删除旧 Redis 失败'
}
```

Expected: 只删除 `iot-redis`，不操作其他容器。

- [ ] **Step 3: 使用稳定项目目录只创建 Redis**

Run:

```powershell
docker compose `
  -p env `
  --project-directory $stableEnvRoot `
  -f $composeFile `
  up -d redis

if ($LASTEXITCODE -ne 0) {
  throw '使用命名卷创建 Redis 失败'
}

$healthy = $false
for ($attempt = 1; $attempt -le 30; $attempt++) {
  if ((docker inspect $redisContainer `
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}') `
      -eq 'healthy') {
    $healthy = $true
    break
  }
  Start-Sleep -Seconds 2
}
if (-not $healthy) {
  throw 'Redis 未在 60 秒内健康'
}
```

Expected: 新 Redis 使用宿主机端口 16379 并健康。

- [ ] **Step 4: 验证 Redis 命名卷、空缓存和 AOF**

Run:

```powershell
$redisMounts = docker inspect $redisContainer |
  ConvertFrom-Json |
  Select-Object -ExpandProperty Mounts
$redisDataMount = $redisMounts |
  Where-Object { $_.Destination -eq '/data' }

if ($redisDataMount.Type -ne 'volume' -or
    $redisDataMount.Name -ne $redisVolume) {
  throw 'Redis 未挂载目标命名卷'
}
if ((docker exec $redisContainer redis-cli PING) -ne 'PONG') {
  throw 'Redis PING 失败'
}
if ([int](docker exec $redisContainer redis-cli DBSIZE) -ne 0) {
  throw '新 Redis 不是空缓存'
}

$redisPersistence = docker exec $redisContainer redis-cli INFO persistence
if ($redisPersistence -notcontains 'aof_enabled:1' -or
    $redisPersistence -notcontains 'aof_last_write_status:ok') {
  throw 'Redis AOF 没有正常启用'
}
```

Expected: `/data` 是固定命名卷，Redis 为空，AOF 已启用且可写。

- [ ] **Step 5: 构建并启动任务分支后端**

Run:

```powershell
mvn -DskipTests package
if ($LASTEXITCODE -ne 0) {
  throw '构建后端失败'
}

$existing8081 = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -eq 8081 }
if ($existing8081) {
  throw "8081 已被 PID $($existing8081.OwningProcess) 占用"
}

$backendOut = Join-Path $env:TEMP 'iot-platform-demo-migration-backend.log'
$backendErr = Join-Path $env:TEMP 'iot-platform-demo-migration-backend.err.log'
$backend = Start-Process `
  -FilePath 'F:\jdk21\bin\java.exe' `
  -ArgumentList @(
    '-jar',
    'target/iot-platform-demo-1.0-SNAPSHOT.jar',
    '--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/iot_platform?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai',
    '--spring.datasource.username=root',
    '--spring.data.redis.host=127.0.0.1',
    '--spring.data.redis.port=16379'
  ) `
  -WorkingDirectory $repoRoot `
  -RedirectStandardOutput $backendOut `
  -RedirectStandardError $backendErr `
  -WindowStyle Hidden `
  -PassThru

$backendReady = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
  try {
    $login = Invoke-RestMethod `
      -Method Post `
      -Uri 'http://127.0.0.1:8081/api/auth/login' `
      -ContentType 'application/json' `
      -Body '{"username":"admin","password":"123456"}' `
      -TimeoutSec 3
    if ($login.code -eq 200 -and $login.data.token) {
      $backendReady = $true
      break
    }
  } catch {
    Start-Sleep -Seconds 2
  }
}
if (-not $backendReady) {
  throw "后端未就绪，日志：$backendOut / $backendErr"
}
```

Expected: 后端继承 Task 4 已设置的 `MYSQL_PASSWORD` 环境变量，密码不出现在
Java 命令行中；后端 PID 被记录，登录成功并取得 Token。

- [ ] **Step 6: 发送 HVAC 数据并验证缓存和 API**

Run:

```powershell
node 'D:\word\iot-platform-demo\.scripts\simulate-hvac-19-points.mjs'
if ($LASTEXITCODE -ne 0) {
  throw 'HVAC 19 测点模拟器执行失败'
}

Start-Sleep -Seconds 15

$keys = @(
  docker exec $redisContainer redis-cli `
    --scan `
    --pattern 'iot:indicator:latest:*'
)
if ($keys.Count -ne 4) {
  throw "重新生成的最新指标缓存不是 4 个：$($keys.Count)"
}

$headers = @{ Authorization = "Bearer $($login.data.token)" }
$snapshot = Invoke-RestMethod `
  -Method Get `
  -Uri 'http://127.0.0.1:8081/api/hvac/buildings/BLD001/snapshot' `
  -Headers $headers `
  -TimeoutSec 15
$indicators = Invoke-RestMethod `
  -Method Get `
  -Uri 'http://127.0.0.1:8081/api/hvac/buildings/BLD001/indicators/latest' `
  -Headers $headers `
  -TimeoutSec 15

if ($snapshot.code -ne 200 -or $snapshot.data.points.Count -ne 19) {
  throw 'HVAC 快照不是 19 个测点'
}
if ($indicators.code -ne 200 -or $indicators.data.indicators.Count -ne 4) {
  throw 'HVAC 最新指标不是 4 个'
}
```

Expected: 模拟器发送 7 × 19 = 133 条消息；Redis 有 4 个最新指标键；API
返回 19 个测点和 4 个指标。

- [ ] **Step 7: 单独重启 Redis 并验证 AOF 恢复**

Run:

```powershell
$keysBeforeRestart = @(
  docker exec $redisContainer redis-cli `
    --scan `
    --pattern 'iot:indicator:latest:*'
)

docker restart $redisContainer
if ($LASTEXITCODE -ne 0) {
  throw '重启 Redis 失败'
}

$healthy = $false
for ($attempt = 1; $attempt -le 30; $attempt++) {
  if ((docker inspect $redisContainer `
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}') `
      -eq 'healthy') {
    $healthy = $true
    break
  }
  Start-Sleep -Seconds 2
}
if (-not $healthy) {
  throw 'Redis 重启后未恢复健康'
}

$keysAfterRestart = @(
  docker exec $redisContainer redis-cli `
    --scan `
    --pattern 'iot:indicator:latest:*'
)
if (@(Compare-Object $keysBeforeRestart $keysAfterRestart).Count -ne 0) {
  throw 'Redis 重启后最新指标缓存没有从 AOF 恢复'
}
```

Expected: 重启前后的 4 个最新指标键一致。

- [ ] **Step 8: 停止任务后端并保留基础设施容器**

Run:

```powershell
Stop-Process -Id $backend.Id -Force
Start-Sleep -Seconds 1
if (Get-Process -Id $backend.Id -ErrorAction SilentlyContinue) {
  throw '任务后端进程仍在运行'
}
```

Expected: 8081 释放；MySQL、Redis、TDengine、EMQX 保持运行。

---

### Task 6: 最终边界核验、提交状态检查和推送

**Files:**
- Verify: `docs/superpowers/specs/2026-07-31-mysql-redis-stable-volumes-design.md`
- Verify: `docs/superpowers/plans/2026-07-31-mysql-redis-stable-volumes.md`
- Verify: `src/env/docker-compose.yml`
- Verify: `src/test/java/com/platform/config/DockerComposeConfigurationTest.java`

**Interfaces:**
- Consumes: 前五个任务的代码提交、测试结果、备份和运行时迁移结果。
- Produces: 只包含计划内四个文件的远程任务分支和完整 PR 材料。

- [ ] **Step 1: 验证三个数据卷和四个容器**

Run:

```powershell
$expectedMounts = @{
  'iot-mysql' = @{
    Destination = '/var/lib/mysql'
    Volume = 'iot-platform-demo-mysql-data'
  }
  'iot-redis' = @{
    Destination = '/data'
    Volume = 'iot-platform-demo-redis-data'
  }
  'iot-tdengine' = @{
    Destination = '/var/lib/taos'
    Volume = 'iot-platform-demo-tdengine-data'
  }
}

foreach ($entry in $expectedMounts.GetEnumerator()) {
  $container = $entry.Key
  $expected = $entry.Value
  $mount = docker inspect $container |
    ConvertFrom-Json |
    Select-Object -ExpandProperty Mounts |
    Where-Object { $_.Destination -eq $expected.Destination }
  if ($mount.Type -ne 'volume' -or $mount.Name -ne $expected.Volume) {
    throw "$container 的数据卷不正确"
  }
}

foreach ($name in 'iot-mysql','iot-redis','iot-tdengine','iot-emqx') {
  $status = docker inspect $name --format '{{.State.Status}}'
  $health = docker inspect $name `
    --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
  if ($status -ne 'running' -or $health -ne 'healthy') {
    throw "$name 最终状态不是 running/healthy"
  }
}

if ((docker inspect $tdContainer --format '{{.Id}}') -ne $protectedTdId) {
  throw 'TDengine 在迁移期间被重建'
}
if ((docker inspect $emqxContainer --format '{{.Id}}') -ne $protectedEmqxId) {
  throw 'EMQX 在迁移期间被重建'
}

$completionNotes = @(
  "completedAt=$([DateTimeOffset]::Now.ToString('o'))",
  "finalMysqlId=$(docker inspect iot-mysql --format '{{.Id}}')",
  "finalRedisId=$(docker inspect iot-redis --format '{{.Id}}')",
  "finalTdengineId=$(docker inspect iot-tdengine --format '{{.Id}}')",
  "finalEmqxId=$(docker inspect iot-emqx --format '{{.Id}}')",
  'mysqlTableComparison=PASS',
  'mysqlUtf8Comparison=PASS',
  'mysqlRestartPersistence=PASS',
  'redisCacheRegeneration=PASS',
  'redisRestartPersistence=PASS'
)
[IO.File]::AppendAllLines(
  (Join-Path $backupRoot 'migration-notes.txt'),
  $completionNotes,
  $utf8NoBom)
```

Expected: 三个数据库/缓存服务使用准确命名卷；四个容器健康；TDengine 和
EMQX ID 未变；仓库外迁移记录包含最终容器 ID 和验证结论。

- [ ] **Step 2: 再次运行定向测试和完整回归**

Run:

```powershell
mvn -Dtest=DockerComposeConfigurationTest test
mvn test
```

Expected: 定向 4/4；完整测试不少于 440；0 failures/errors/skipped。

- [ ] **Step 3: 自检备份与 Git 排除边界**

Run:

```powershell
$hostDump = Join-Path $backupRoot 'iot_platform.sql'
$recordedHash = (
  Get-Content -Encoding UTF8 `
    (Join-Path $backupRoot 'SHA256SUMS.txt')
).Split(' ', [StringSplitOptions]::RemoveEmptyEntries)[0]
$actualHash = (
  Get-FileHash -Algorithm SHA256 -LiteralPath $hostDump
).Hash.ToLowerInvariant()
if ($recordedHash -ne $actualHash) {
  throw 'MySQL 备份 SHA-256 已变化'
}

git status --short
git diff --check
git diff --name-only origin/main...HEAD

$changedFiles = @(git diff --name-only origin/main...HEAD)
$expectedFiles = @(
  'docs/superpowers/plans/2026-07-31-mysql-redis-stable-volumes.md',
  'docs/superpowers/specs/2026-07-31-mysql-redis-stable-volumes-design.md',
  'src/env/docker-compose.yml',
  'src/test/java/com/platform/config/DockerComposeConfigurationTest.java'
)

if (@(Compare-Object $expectedFiles $changedFiles).Count -ne 0) {
  throw 'Git 变更范围包含计划外文件或缺少计划文件'
}

foreach ($forbidden in 'mysql-data','redis-data','iot_platform.sql','outputs/') {
  if ($changedFiles -match [regex]::Escape($forbidden)) {
    throw "Git 变更包含禁止内容：$forbidden"
  }
}
```

Expected: 备份哈希一致；Git 仅包含四个计划文件；没有数据、备份或日志。

- [ ] **Step 4: 获取最新远程状态并确认分支可合并**

Run:

```powershell
git fetch --prune origin
if ($LASTEXITCODE -ne 0) {
  throw '无法获取最新 origin 状态'
}

git merge-base --is-ancestor origin/main HEAD
if ($LASTEXITCODE -ne 0) {
  throw 'origin/main 已前进；停止推送，先审查并合并最新 main 后重跑测试'
}

git diff --check
git status --short --branch
```

Expected: `origin/main` 是当前分支祖先，工作区干净。

- [ ] **Step 5: 推送任务分支**

Run:

```powershell
git push -u origin fix/mysql-redis-stable-volumes
```

Expected: 远程分支创建成功，本地与远程任务分支 ahead/behind 为 0/0。

- [ ] **Step 6: 准备 PR 材料**

PR 创建链接：

```text
https://github.com/edg127117/iot-platform-demo/compare/main...fix/mysql-redis-stable-volumes?expand=1
```

建议标题：

```text
fix(env): 稳定 MySQL Redis 数据卷
```

PR 说明必须包含：

- MySQL/Redis 固定命名卷；
- MySQL SQL 备份和预恢复一致性结果；
- Redis 旧缓存已按批准范围丢弃并重新生成；
- 三个数据卷的最终名称；
- 定向测试与完整 Maven 测试数量；
- MySQL、Redis 重启验证；
- TDengine、EMQX 容器 ID 未变；
- SQL 备份只位于仓库外且未提交；
- 不包含 Java 生产代码、数据库结构、TDengine 数据和用户主目录改动；
- 当前无冲突、无无关文件；
- 状态为等待用户创建并合并 PR。
