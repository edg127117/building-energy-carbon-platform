# Java 21 Development and Backend CI Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让本地后端开发和 GitHub CI 统一使用 Java 21 与 Maven 3.9.9，并在版本不匹配时于构建早期给出明确失败。

**Architecture:** 仓库通过 `.java-version` 表达 JDK 主版本，通过官方 Maven Wrapper 固定 Maven 3.9.9，通过 Maven Enforcer 在 `validate` 阶段实施硬门禁。本地只读 PowerShell 脚本负责发现 `JAVA_HOME`、PATH 和 Wrapper 的不一致，GitHub Actions 使用 Temurin 21 执行同一条 Wrapper `verify` 命令。

**Tech Stack:** Java 21、Microsoft OpenJDK 21.0.11（当前 Windows 主机）、Eclipse Temurin 21（CI）、Apache Maven 3.9.9、Maven Wrapper Plugin 3.3.4、Maven Enforcer Plugin 3.6.3、PowerShell、GitHub Actions。

## Global Constraints

- Java 版本范围固定为 `[21,22)`；拒绝 Java 17、Java 22 和其他未经验证的主版本。
- Maven 版本范围固定为 `[3.9.9,4.0.0)`；Wrapper 固定下载 Maven 3.9.9。
- Maven 3.9.9 ZIP 的 SHA-256 固定为 `4ec3f26fb1a692473aea0235c300bd20f0f9fe741947c82c1234cefd76ac3a3c`。
- Maven Wrapper 3.3.4 JAR 的 SHA-256 固定为 `4e2fbf6554bc8a4702cdfdd3bef464f423393d784ddbb037216320ce55d5e4e1`。
- 普通 CI 不启动或连接真实 MySQL、TDengine、MQTT、Redis 或第三方接口。
- 不修改业务代码、数据库结构、前端代码、前端依赖、Vitest 或前端 CI。
- 本地检查脚本只读，不修改注册表、用户环境变量或系统环境变量。
- 只暂存本计划明确列出的文件，不使用 `git add .`。

---

### Task 1: 固定 Java 与 Maven 构建入口

**Files:**
- Create: `.java-version`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.mvn/wrapper/maven-wrapper.jar`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: 当前 `pom.xml` 的 `<java.version>21</java.version>` 和系统 Maven 3.9.9。
- Produces: Windows 的 `.\mvnw.cmd`、POSIX 的 `./mvnw`、Java `[21,22)` 与 Maven `[3.9.9,4.0.0)` 构建门禁。

- [ ] **Step 1: 验证统一入口尚不存在**

Run:

```powershell
Test-Path .\mvnw.cmd
Test-Path .\.java-version
Select-String -Path .\pom.xml -Pattern 'maven-enforcer-plugin'
```

Expected:

```text
False
False
Select-String 不返回匹配项
```

- [ ] **Step 2: 使用官方 Wrapper Plugin 生成 bin Wrapper**

Run:

```powershell
mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper `
  '-Dtype=bin' `
  '-Dmaven=3.9.9' `
  '-DdistributionSha256Sum=4ec3f26fb1a692473aea0235c300bd20f0f9fe741947c82c1234cefd76ac3a3c'
```

Expected:

```text
BUILD SUCCESS
mvnw、mvnw.cmd、maven-wrapper.jar 和 maven-wrapper.properties 已生成
```

生成后的 `.mvn/wrapper/maven-wrapper.properties` 必须包含：

```properties
wrapperVersion=3.3.4
distributionType=bin
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
distributionSha256Sum=4ec3f26fb1a692473aea0235c300bd20f0f9fe741947c82c1234cefd76ac3a3c
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar
wrapperSha256Sum=4e2fbf6554bc8a4702cdfdd3bef464f423393d784ddbb037216320ce55d5e4e1
```

使用 `bin` 类型是为了避开Wrapper 3.3.4 `only-script` 在Windows PowerShell 5中读取
普通 `.m2` 目录空 `Target` 时的启动错误；仓库只提交63 KB启动JAR，不提交Maven安装包。

- [ ] **Step 3: 声明仓库 Java 版本**

Create `.java-version`:

```text
21
```

- [ ] **Step 4: 在 Maven 生命周期最早阶段增加版本门禁**

在 `spring-boot-maven-plugin` 之前向 `pom.xml` 的 `<build><plugins>` 增加：

```xml
      <!--
        在编译和测试前统一校验开发环境，避免 JAVA_HOME、PATH 和 CI
        分别使用不同的 Java 或 Maven 版本，导致本地通过而流水线失败。
      -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.6.3</version>
        <executions>
          <execution>
            <id>enforce-build-toolchain</id>
            <goals>
              <goal>enforce</goal>
            </goals>
            <configuration>
              <rules>
                <requireJavaVersion>
                  <version>[21,22)</version>
                  <message>本项目必须使用 Java 21。请检查 JAVA_HOME 和 PATH 后重新执行构建。</message>
                </requireJavaVersion>
                <requireMavenVersion>
                  <version>[3.9.9,4.0.0)</version>
                  <message>本项目必须使用 Maven 3.9.9；请通过 mvnw 或 mvnw.cmd 执行构建。</message>
                </requireMavenVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

- [ ] **Step 5: 验证 Wrapper 与正确版本门禁**

Run:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd --batch-mode --no-transfer-progress validate
```

Expected:

```text
Apache Maven 3.9.9
Java version: 21.0.11
BUILD SUCCESS
```

- [ ] **Step 6: 验证 Java 17 被明确拒绝**

Run:

```powershell
$savedJavaHome = $env:JAVA_HOME
try {
  $env:JAVA_HOME = 'F:\jdk17'
  .\mvnw.cmd --batch-mode --no-transfer-progress validate
  if ($LASTEXITCODE -eq 0) {
    throw 'Java 17 未被 Maven Enforcer 拒绝'
  }
} finally {
  $env:JAVA_HOME = $savedJavaHome
}
```

Expected:

```text
RequireJavaVersion 失败
错误消息包含“本项目必须使用 Java 21”
```

- [ ] **Step 7: 提交构建入口**

Run:

```powershell
git add -- .java-version mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.jar .mvn/wrapper/maven-wrapper.properties pom.xml
git update-index --chmod=+x mvnw
git diff --cached --name-only
git diff --cached --check
git commit -m "build(java): enforce Java 21 toolchain"
```

Expected:

```text
只包含 .java-version、Maven Wrapper 和 pom.xml
提交成功
```

---

### Task 2: 增加 Windows 环境诊断与开发说明

**Files:**
- Create: `scripts/check-java21.ps1`
- Create: `docs/development/java21.md`

**Interfaces:**
- Consumes: Task 1 产生的 `mvnw.cmd`、`.java-version` 和 Maven Enforcer。
- Produces: `scripts/check-java21.ps1`，成功时退出码为 0，不一致时退出码为 1；开发者可执行的 Windows 配置说明。

- [ ] **Step 1: 记录当前 PATH 不一致的失败基线**

Run:

```powershell
java -version
javac -version
.\mvnw.cmd -version
```

Expected:

```text
java 和 javac 显示 17.0.8
Maven 显示 Java 21.0.11
```

- [ ] **Step 2: 创建只读检查脚本**

Create `scripts/check-java21.ps1`:

```powershell
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$failures = [System.Collections.Generic.List[string]]::new()
$repoRoot = Split-Path -Parent $PSScriptRoot

function Invoke-VersionCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    try {
        # Windows PowerShell 5 会把 java -version 写到 stderr 的正常内容包装成错误记录。
        # 使用 Process 分别读取 stdout 和 stderr，可以保留原始版本文本并可靠取得退出码。
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        if ([System.IO.Path]::GetExtension($Executable) -eq '.cmd') {
            $startInfo.FileName = $env:ComSpec
            $startInfo.Arguments = '/d /s /c ""' + $Executable + '" ' + ($Arguments -join ' ') + '"'
        } else {
            $startInfo.FileName = $Executable
            $startInfo.Arguments = $Arguments -join ' '
        }
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true

        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        $output = (($standardOutput, $standardError) |
            Where-Object { $_ } |
            ForEach-Object { $_.Trim() }) -join [Environment]::NewLine

        if ($process.ExitCode -ne 0) {
            throw "$Label 返回退出码 $($process.ExitCode)"
        }
        Write-Host "[$Label]"
        Write-Host $output
        return $output
    } catch {
        $script:failures.Add("$Label 检查失败：$($_.Exception.Message)")
        return ''
    }
}

function Assert-MajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [string]$Output,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    if ($Output -and $Output -notmatch $Pattern) {
        $script:failures.Add("$Label 必须使用 Java 21。")
    }
}

if (-not $env:JAVA_HOME) {
    $failures.Add('JAVA_HOME 未配置。')
} else {
    $javaHomeExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaHomeExecutable)) {
        $failures.Add("JAVA_HOME 中不存在 bin\java.exe：$env:JAVA_HOME")
    } else {
        $javaHomeOutput = Invoke-VersionCommand `
            -Label 'JAVA_HOME java' `
            -Executable $javaHomeExecutable `
            -Arguments @('-version')
        Assert-MajorVersion `
            -Label 'JAVA_HOME java' `
            -Output $javaHomeOutput `
            -Pattern 'version "21(?:[.\-"]|$)'
    }
}

$pathJavaCommand = @(Get-Command java -CommandType Application -ErrorAction SilentlyContinue) |
    Select-Object -First 1
if (-not $pathJavaCommand) {
    $failures.Add('PATH 中找不到 java。')
} else {
    $pathJava = $pathJavaCommand.Source
    Write-Host "[PATH java] $pathJava"
    $pathJavaOutput = Invoke-VersionCommand `
        -Label 'PATH java version' `
        -Executable $pathJava `
        -Arguments @('-version')
    Assert-MajorVersion `
        -Label 'PATH java' `
        -Output $pathJavaOutput `
        -Pattern 'version "21(?:[.\-"]|$)'
}

$pathJavacCommand = @(Get-Command javac -CommandType Application -ErrorAction SilentlyContinue) |
    Select-Object -First 1
if (-not $pathJavacCommand) {
    $failures.Add('PATH 中找不到 javac。')
} else {
    $pathJavac = $pathJavacCommand.Source
    Write-Host "[PATH javac] $pathJavac"
    $pathJavacOutput = Invoke-VersionCommand `
        -Label 'PATH javac version' `
        -Executable $pathJavac `
        -Arguments @('-version')
    Assert-MajorVersion `
        -Label 'PATH javac' `
        -Output $pathJavacOutput `
        -Pattern '^javac 21(?:[.\s\-]|$)'
}

$wrapperPath = Join-Path $repoRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $wrapperPath)) {
    $failures.Add("找不到 Maven Wrapper：$wrapperPath")
} else {
    $wrapperOutput = Invoke-VersionCommand `
        -Label 'Maven Wrapper' `
        -Executable $wrapperPath `
        -Arguments @('-version')
    if ($wrapperOutput -and $wrapperOutput -notmatch '(?m)^Apache Maven 3\.9\.9(?:\s|$)') {
        $failures.Add('Maven Wrapper 必须使用 Maven 3.9.9。')
    }
    if ($wrapperOutput -and $wrapperOutput -notmatch '(?m)^Java version: 21(?:[.\s,]|$)') {
        $failures.Add('Maven Wrapper 必须使用 Java 21。')
    }
}

if ($failures.Count -gt 0) {
    [Console]::Error.WriteLine("Java 21 环境检查未通过：`n- " + ($failures -join "`n- "))
    exit 1
}

Write-Host 'Java 21、javac 和 Maven Wrapper 环境一致。'
exit 0
```

该脚本包含中文提示，文件编码必须为带BOM的UTF-8，确保Windows PowerShell 5按UTF-8解析。

- [ ] **Step 3: 验证脚本能发现当前旧 PATH**

Run:

```powershell
.\scripts\check-java21.ps1
```

Expected:

```text
退出码 1
输出指出 PATH java 和 PATH javac 不是 Java 21
输出确认 Maven Wrapper 使用 Java 21
```

- [ ] **Step 4: 编写 Windows 开发说明**

Create `docs/development/java21.md`，包含以下完整操作：

````markdown
# Java 21 后端开发环境

## 统一版本

- JDK：Java 21，厂商不限。
- Maven：通过仓库根目录的 Maven Wrapper 使用 3.9.9。
- Windows 构建：`.\mvnw.cmd verify`
- Linux/macOS/CI 构建：`./mvnw verify`

## Windows 配置

1. 将 `JAVA_HOME` 指向 JDK 21 根目录。
2. 将 `%JAVA_HOME%\bin` 放在旧 Java、Oracle `javapath` 等入口之前。
3. 关闭并重新打开终端、IDE 和 Codex。
4. 在仓库根目录执行 `.\scripts\check-java21.ps1`。

当前开发机已经安装 `F:\jdk21`，只需把 `F:\jdk21\bin` 移到
`C:\Program Files\Common Files\Oracle\Java\javapath` 之前。

## 验证

执行：

```powershell
java -version
javac -version
.\mvnw.cmd -version
.\scripts\check-java21.ps1
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

前三条命令必须显示 Java 21，检查脚本必须返回退出码 0，完整构建必须显示 `BUILD SUCCESS`。

## 常见错误

- `release version 21 not supported`：Maven正在使用低于21的JDK，检查 `JAVA_HOME`。
- `UnsupportedClassVersionError`：运行时 `java` 仍是旧版本，检查PATH顺序。
- `RequireJavaVersion`：当前JDK主版本不是21。
- `RequireMavenVersion`：没有通过仓库的Maven Wrapper执行构建。
- Wrapper校验失败：下载内容与固定SHA-256不一致，不要绕过校验。
````

- [ ] **Step 5: 提交本地开发支持**

Run:

```powershell
git add -- scripts/check-java21.ps1 docs/development/java21.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(build): document Java 21 setup"
```

Expected:

```text
只包含检查脚本和Java 21开发说明
提交成功
```

---

### Task 3: 增加 GitHub 后端 CI

**Files:**
- Create: `.github/workflows/backend-ci.yml`

**Interfaces:**
- Consumes: Task 1 的 `.java-version`、`mvnw` 和 `pom.xml` 版本门禁。
- Produces: 面向 `main` 的 PR、推送到 `main` 和手工触发的 `Backend CI` 工作流。

- [ ] **Step 1: 确认仓库没有现有后端工作流**

Run:

```powershell
Test-Path .\.github\workflows\backend-ci.yml
```

Expected:

```text
False
```

- [ ] **Step 2: 创建最小权限后端 CI**

Create `.github/workflows/backend-ci.yml`:

```yaml
name: Backend CI

on:
  pull_request:
    branches:
      - main
  push:
    branches:
      - main
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: backend-ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  verify:
    name: Java 21 verify
    runs-on: ubuntu-latest
    timeout-minutes: 20

    steps:
      - name: Checkout repository
        uses: actions/checkout@v6

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version-file: .java-version
          cache: maven
          cache-dependency-path: pom.xml

      - name: Show build tool versions
        run: ./mvnw -version

      - name: Test and package backend
        run: ./mvnw --batch-mode --no-transfer-progress verify
```

- [ ] **Step 3: 静态检查工作流边界**

Run:

```powershell
Select-String -Path .\.github\workflows\backend-ci.yml `
  -Pattern 'pull_request:','branches:','main','java-version-file: .java-version','./mvnw --batch-mode --no-transfer-progress verify'
Select-String -Path .\.github\workflows\backend-ci.yml `
  -Pattern 'mysql','tdengine','mqtt','redis','npm','node'
```

Expected:

```text
第一条命令匹配PR、main、Java版本文件和Wrapper verify
第二条命令无匹配，证明CI没有加入真实外部服务或前端步骤
```

- [ ] **Step 4: 提交后端 CI**

Run:

```powershell
git add -- .github/workflows/backend-ci.yml
git diff --cached --name-only
git diff --cached --check
git commit -m "ci(build): verify backend on Java 21"
```

Expected:

```text
只包含 .github/workflows/backend-ci.yml
提交成功
```

---

### Task 4: 对齐当前 Windows 主机并完成回归验证

**Files:**
- Verify: `.java-version`
- Verify: `.mvn/wrapper/maven-wrapper.properties`
- Verify: `pom.xml`
- Verify: `scripts/check-java21.ps1`
- Verify: `.github/workflows/backend-ci.yml`
- Verify: `target/surefire-reports/*.xml`

**Interfaces:**
- Consumes: Tasks 1-3 的全部产物和当前主机已安装的 `F:\jdk21`。
- Produces: Java 21环境检查结果、完整后端回归结果、干净任务分支和PR交付材料。

- [ ] **Step 1: 调整系统 PATH 顺序前保留并检查原值**

Run:

```powershell
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$entries = $machinePath -split ';' | Where-Object { $_ }
$entries | ForEach-Object { Write-Output $_ }
```

Expected:

```text
同时包含 F:\jdk21\bin 和 C:\Program Files\Common Files\Oracle\Java\javapath
Oracle javapath 当前位于 F:\jdk21\bin 之前
```

- [ ] **Step 2: 经系统权限批准后仅重排两个Java入口**

Run in an elevated PowerShell:

```powershell
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$entries = [System.Collections.Generic.List[string]]::new()
foreach ($entry in ($machinePath -split ';')) {
    if ($entry -and
        $entry -ne 'F:\jdk21\bin' -and
        $entry -ne 'C:\Program Files\Common Files\Oracle\Java\javapath') {
        $entries.Add($entry)
    }
}
$entries.Insert(0, 'C:\Program Files\Common Files\Oracle\Java\javapath')
$entries.Insert(0, 'F:\jdk21\bin')
[Environment]::SetEnvironmentVariable('Path', ($entries -join ';'), 'Machine')
```

Expected:

```text
其他PATH条目保持原相对顺序
F:\jdk21\bin 位于 Oracle javapath 之前
```

- [ ] **Step 3: 用持久化环境变量模拟新终端并运行检查**

Run:

```powershell
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$env:Path = "$machinePath;$userPath"
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
java -version
javac -version
.\scripts\check-java21.ps1
```

Expected:

```text
java 和 javac 均显示 21.0.11
检查脚本输出“Java 21、javac 和 Maven Wrapper 环境一致。”
退出码 0
```

- [ ] **Step 4: 执行完整后端测试和打包**

Run:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Expected:

```text
BUILD SUCCESS
全部后端测试通过
```

- [ ] **Step 5: 汇总测试数量并检查范围**

Run:

```powershell
$reports = Get-ChildItem -Path .\target\surefire-reports -Filter 'TEST-*.xml'
$tests = 0
$failures = 0
$errors = 0
$skipped = 0
foreach ($report in $reports) {
    [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
    $tests += [int]$xml.testsuite.tests
    $failures += [int]$xml.testsuite.failures
    $errors += [int]$xml.testsuite.errors
    $skipped += [int]$xml.testsuite.skipped
}
Write-Output "tests=$tests failures=$failures errors=$errors skipped=$skipped"
git status --short --branch
git diff origin/main...HEAD --name-only
git diff origin/main...HEAD --check
```

Expected:

```text
failures=0 errors=0
改动只包含设计、计划、Java/Maven构建文件、本地检查文档和后端CI
不包含前端或业务源码
```

- [ ] **Step 6: 推送任务分支**

Run:

```powershell
git push -u origin codex/chore/java21-ci
```

Expected:

```text
远程任务分支推送成功
```

- [ ] **Step 7: 交付PR材料**

提供：

```text
Compare: https://github.com/edg127117/iot-platform-demo/compare/main...codex/chore/java21-ci?expand=1
Base: main
Compare branch: codex/chore/java21-ci
状态: 等待用户创建并合并PR
```

PR说明必须包含版本范围、Wrapper版本、CI触发条件、本地PATH修复、完整测试数量、跳过项、未执行项、冲突检查和无关文件检查结果。
