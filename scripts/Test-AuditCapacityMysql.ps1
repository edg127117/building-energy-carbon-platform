[CmdletBinding()]
param(
    [string]$MySqlImage = 'mysql:8.4',
    [int]$ReadyTimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($MySqlImage -notmatch '^mysql:8(?:[.:@-].*)?$') {
    throw '审计容量验证只允许使用 MySQL 8 官方镜像'
}
if ($ReadyTimeoutSeconds -lt 10 -or $ReadyTimeoutSeconds -gt 300) {
    throw 'MySQL 就绪等待时间必须在10至300秒之间'
}

$capacityRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$capacityWrapper = Join-Path $capacityRoot 'mvnw.cmd'
$capacityDocker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $capacityDocker) {
    throw '找不到 Docker CLI'
}
if (-not (Test-Path -LiteralPath $capacityWrapper -PathType Leaf)) {
    throw '找不到仓库 Maven Wrapper'
}

$capacityContainer = "codex-audit-capacity-mysql-$PID"
$capacityPassword = 'capacity-root'
$capacityStarted = $false
$capacityPreviousEnvironment = @{
    AUDIT_CAPACITY_MYSQL_URL = $env:AUDIT_CAPACITY_MYSQL_URL
    AUDIT_CAPACITY_MYSQL_USER = $env:AUDIT_CAPACITY_MYSQL_USER
    AUDIT_CAPACITY_MYSQL_PASSWORD = $env:AUDIT_CAPACITY_MYSQL_PASSWORD
    AUDIT_CAPACITY_MYSQL_ISOLATED = $env:AUDIT_CAPACITY_MYSQL_ISOLATED
}

try {
    & $capacityDocker.Source run --rm -d --name $capacityContainer `
        -e "MYSQL_ROOT_PASSWORD=$capacityPassword" `
        -e 'MYSQL_DATABASE=iot_platform' `
        -p '127.0.0.1::3306' $MySqlImage `
        --character-set-server=utf8mb4 `
        --collation-server=utf8mb4_0900_ai_ci `
        --skip-log-bin | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw '一次性 MySQL 8 容器启动失败'
    }
    $capacityStarted = $true

    $capacityDeadline = [DateTimeOffset]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    $capacityReady = $false
    while ([DateTimeOffset]::UtcNow -lt $capacityDeadline) {
        & $capacityDocker.Source exec -e "MYSQL_PWD=$capacityPassword" `
            $capacityContainer mysqladmin ping -uroot --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $capacityReady = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $capacityReady) {
        & $capacityDocker.Source logs --tail 80 $capacityContainer
        throw '一次性 MySQL 8 容器未在限定时间内就绪'
    }

    $capacityPortText = (& $capacityDocker.Source port $capacityContainer '3306/tcp').Trim()
    if ($LASTEXITCODE -ne 0 -or $capacityPortText -notmatch ':(?<port>\d+)$') {
        throw '无法解析一次性 MySQL 8 映射端口'
    }

    $env:AUDIT_CAPACITY_MYSQL_URL = "jdbc:mysql://127.0.0.1:$($Matches.port)/iot_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    $env:AUDIT_CAPACITY_MYSQL_USER = 'root'
    $env:AUDIT_CAPACITY_MYSQL_PASSWORD = $capacityPassword
    $env:AUDIT_CAPACITY_MYSQL_ISOLATED = 'true'

    Push-Location $capacityRoot
    try {
        & $capacityWrapper --batch-mode --no-transfer-progress `
            '-Dtest=AuditCapacityMysqlIntegrationTest' test
        if ($LASTEXITCODE -ne 0) {
            throw '审计容量 MySQL 集成测试失败'
        }
    } finally {
        Pop-Location
    }

    Write-Output 'AUDIT_CAPACITY_MYSQL_OK'
} finally {
    foreach ($capacityName in $capacityPreviousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable(
            $capacityName, $capacityPreviousEnvironment[$capacityName], 'Process')
    }
    if ($capacityStarted) {
        & $capacityDocker.Source stop $capacityContainer 2>$null | Out-Null
    }
}
