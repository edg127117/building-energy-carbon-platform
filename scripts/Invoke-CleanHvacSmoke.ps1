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
$serverEnvFile = Join-Path $repoRoot 'server.env'
$smokeOutputDirectory = Join-Path $repoRoot 'target\smoke'
$knownContainers = @(
    'iot-mysql',
    'iot-emqx',
    'iot-tdengine',
    'iot-redis'
)
$dataDirectoryNames = @('mysql-data', 'taos-data', 'redis-data')
$expectedPointCodes = @(
    'WCR1_TWin',
    'WCR1_TWout',
    'WCR1_Flow',
    'WCR1_PPE',
    'WCR1_Voltage',
    'WCR1_Current',
    'WCR1_PF',
    'TOWER1_TCWin',
    'TOWER1_TCWout',
    'TOWER1_TWB',
    'PUMP1_Flow',
    'PUMP1_Pout',
    'PUMP1_Pin',
    'PUMP1_Z',
    'PUMP1_Power',
    'AHU1_TotalPress',
    'AHU1_EtaT',
    'DBO_TDB',
    'DBO_RH'
)
$applicationProcess = $null

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

function Import-ServerEnvironment([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }

        $parts = $trimmed -split '=', 2
        if ($parts.Count -ne 2 -or -not $parts[0].Trim()) {
            throw 'server.env 存在无法解析的配置行'
        }
        [Environment]::SetEnvironmentVariable(
            $parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}

function Resolve-AllowedDataTargets([string]$EnvRoot) {
    if (-not $EnvRoot) {
        return @()
    }

    $absoluteRoot = [IO.Path]::GetFullPath($EnvRoot)
    if ((Split-Path $absoluteRoot -Leaf) -ne 'env' -or
        (Split-Path (Split-Path $absoluteRoot) -Leaf) -ne 'src') {
        throw "数据根目录不是项目 src\env: $absoluteRoot"
    }

    return $dataDirectoryNames | ForEach-Object {
        [IO.Path]::GetFullPath((Join-Path $absoluteRoot $_))
    }
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments,
        [switch]$Capture
    )

    $output = & $script:dockerCli @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        $detail = ($output | Out-String).Trim()
        throw "Docker 命令执行失败。$detail"
    }

    if ($Capture) {
        return $output
    }
    $output | ForEach-Object { Write-Output $_ }
}

function Get-ContainerInspection([string]$Container) {
    $json = & $script:dockerCli inspect $Container 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    return ($json | ConvertFrom-Json)[0]
}

function Assert-ContainerDataMounts(
    [string]$Container,
    [string[]]$AllowedTargets
) {
    $inspection = Get-ContainerInspection $Container
    if ($null -eq $inspection) {
        return
    }

    $composeProject = $inspection.Config.Labels.'com.docker.compose.project'
    if ($composeProject -ne 'env') {
        throw "容器 $Container 不属于预期 Compose 项目 env"
    }

    foreach ($mount in $inspection.Mounts) {
        if ($mount.Destination -notin
            @('/var/lib/mysql', '/var/lib/taos', '/data')) {
            continue
        }

        $source = [IO.Path]::GetFullPath($mount.Source)
        if ($source -notin $AllowedTargets) {
            throw "容器 $Container 的数据挂载超出允许范围: $source"
        }
    }
}

function Remove-ValidatedDataDirectory(
    [string]$Target,
    [string[]]$AllowedTargets
) {
    $absolute = [IO.Path]::GetFullPath($Target)
    if ($absolute -notin $AllowedTargets) {
        throw "拒绝删除未批准路径: $absolute"
    }

    if (Test-Path -LiteralPath $absolute) {
        Write-Output "删除测试数据目录: $absolute"
        Remove-Item -LiteralPath $absolute -Recurse -Force
    }
}

function Test-PortListening([int]$Port) {
    return [bool](Get-NetTCPConnection `
        -State Listen `
        -LocalPort $Port `
        -ErrorAction SilentlyContinue)
}

function Wait-ContainerHealthy(
    [string]$Container,
    [datetime]$Deadline
) {
    while ((Get-Date) -lt $Deadline) {
        $inspection = Get-ContainerInspection $Container
        if ($null -eq $inspection) {
            Start-Sleep -Seconds 2
            continue
        }

        $status = if ($inspection.State.Health) {
            $inspection.State.Health.Status
        } else {
            $inspection.State.Status
        }
        if ($status -eq 'healthy') {
            Write-Output "容器健康: $Container"
            return
        }
        if ($status -eq 'unhealthy' -or $inspection.State.Status -eq 'exited') {
            $logs = Invoke-Docker `
                -Arguments @('logs', '--tail', '80', $Container) `
                -Capture
            throw "容器未能健康启动: $Container`n$($logs | Out-String)"
        }
        Start-Sleep -Seconds 2
    }

    throw "等待容器健康超时: $Container"
}

function Wait-ApplicationHealthy([datetime]$Deadline) {
    $healthUrl = 'http://127.0.0.1:8081/api/actuator/health'
    while ((Get-Date) -lt $Deadline) {
        if ($script:applicationProcess.HasExited) {
            throw 'Spring Boot 在健康检查通过前退出'
        }
        try {
            $response = Invoke-RestMethod `
                -Uri $healthUrl `
                -TimeoutSec 3
            if ($response.status -eq 'UP') {
                Write-Output 'Spring Boot 健康检查通过'
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }

    throw '等待 Spring Boot 健康检查超时'
}

function Invoke-MySqlScalar([string]$Sql) {
    $password = if ($env:MYSQL_PASSWORD) {
        $env:MYSQL_PASSWORD
    } else {
        'change-me'
    }
    $lines = Invoke-Docker `
        -Arguments @(
            'exec',
            '-e', "MYSQL_PWD=$password",
            'iot-mysql',
            'mysql',
            '-uroot',
            '-Nse', $Sql
        ) `
        -Capture
    return ($lines |
        Where-Object { $_.ToString().Trim() } |
        Select-Object -Last 1).ToString().Trim()
}

function Invoke-Taos([string]$Sql) {
    $lines = Invoke-Docker `
        -Arguments @(
            'exec',
            'iot-tdengine',
            'taos',
            '-s', $Sql
        ) `
        -Capture
    return ($lines | Out-String)
}

function Assert-DatabaseBoundaries {
    $formalCount = Invoke-MySqlScalar @"
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
"@
    if ($formalCount -ne '5') {
        throw "MySQL 正式表检查失败，预期 5，实际 $formalCount"
    }

    $legacyCount = Invoke-MySqlScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema='iot_platform'
  AND table_name IN (
    'iot_device',
    'iot_device_status_log',
    'control_commands'
  );
"@
    if ($legacyCount -ne '0') {
        throw "MySQL 仍存在旧电表表，数量 $legacyCount"
    }

    $stables = Invoke-Taos 'USE iot_telemetry; SHOW STABLES;'
    foreach ($stable in @(
        'st_raw_event',
        'st_raw_minute',
        'st_indicator_minute',
        'st_formula_calc_exception'
    )) {
        if ($stables -notmatch [regex]::Escape($stable)) {
            throw "TDengine 缺少 HVAC 超级表: $stable"
        }
    }
    if ($stables -match 'st_electric_data') {
        throw 'TDengine 仍存在旧电表超级表 st_electric_data'
    }

    Write-Output 'MySQL 与 TDengine 正式/旧表边界检查通过'
}

function Publish-And-AssertFrozenPoints {
    $simulator = Join-Path `
        $repoRoot `
        '.scripts\simulate-hvac-19-points.mjs'
    & node $simulator
    if ($LASTEXITCODE -ne 0) {
        throw 'HVAC 19 测点 MQTT 发布失败'
    }

    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        $rawPoints = Invoke-Taos @"
SELECT DISTINCT point_code
FROM iot_telemetry.st_raw_event;
"@
        $missing = @($expectedPointCodes | Where-Object {
            $rawPoints -notmatch [regex]::Escape($_)
        })
        if ($missing.Count -eq 0) {
            Write-Output 'TDengine 已记录全部 19 个冻结测点'
            return
        }
        Start-Sleep -Seconds 3
    }

    throw "TDengine 未记录全部冻结测点: $($missing -join ', ')"
}

if (-not $ResetData) {
    throw '必须显式传入 -ResetData 才允许重建测试数据'
}
if (-not (Test-Path -LiteralPath $composeFile)) {
    throw "找不到 Docker Compose 文件: $composeFile"
}
if (Test-PortListening 8081) {
    throw '端口 8081 已被未知进程占用，拒绝自动结束该进程'
}

$dockerCli = Resolve-DockerCli
Import-ServerEnvironment $serverEnvFile

$allowedTargets = @(
    Resolve-AllowedDataTargets $currentEnvRoot
)
if ($LegacyEnvRoot) {
    $allowedTargets += @(
        Resolve-AllowedDataTargets $LegacyEnvRoot
    )
}
$allowedTargets = @($allowedTargets | Select-Object -Unique)

Write-Output '即将处理的容器:'
$knownContainers | ForEach-Object { Write-Output "  $_" }
Write-Output '获准删除的测试数据目录:'
$allowedTargets | ForEach-Object { Write-Output "  $_" }

foreach ($container in $knownContainers) {
    Assert-ContainerDataMounts `
        -Container $container `
        -AllowedTargets $allowedTargets
}

$existingContainers = @($knownContainers | Where-Object {
    $null -ne (Get-ContainerInspection $_)
})
if ($existingContainers.Count -gt 0) {
    Invoke-Docker `
        -Arguments (@(
            'rm',
            '--force',
            '--volumes'
        ) + $existingContainers)
}
foreach ($target in $allowedTargets) {
    Remove-ValidatedDataDirectory `
        -Target $target `
        -AllowedTargets $allowedTargets
}

Invoke-Docker -Arguments @(
    'compose',
    '-f', $composeFile,
    'config',
    '--quiet'
)
Invoke-Docker -Arguments @(
    'compose',
    '-f', $composeFile,
    'up',
    '-d'
)

$infrastructureDeadline = (Get-Date).AddSeconds(
    $InfrastructureTimeoutSeconds)
foreach ($container in $knownContainers) {
    Wait-ContainerHealthy `
        -Container $container `
        -Deadline $infrastructureDeadline
}

New-Item `
    -ItemType Directory `
    -Path $smokeOutputDirectory `
    -Force | Out-Null
$backendOut = Join-Path $smokeOutputDirectory 'backend.out.log'
$backendError = Join-Path $smokeOutputDirectory 'backend.err.log'
foreach ($logFile in @($backendOut, $backendError)) {
    if (Test-Path -LiteralPath $logFile) {
        Remove-Item -LiteralPath $logFile -Force
    }
}

try {
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'mvnw.cmd') -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw 'Maven 打包失败'
        }
    } finally {
        Pop-Location
    }

    $jarFile = Join-Path `
        $repoRoot `
        'target\iot-platform-demo-1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jarFile)) {
        throw "找不到 Spring Boot JAR: $jarFile"
    }

    $java = (Get-Command java -ErrorAction Stop).Source
    $applicationProcess = Start-Process `
        -FilePath $java `
        -ArgumentList @('-jar', $jarFile) `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $backendOut `
        -RedirectStandardError $backendError `
        -PassThru

    Wait-ApplicationHealthy `
        -Deadline ((Get-Date).AddSeconds($ApplicationTimeoutSeconds))
    Assert-DatabaseBoundaries
    Publish-And-AssertFrozenPoints
    Write-Output 'CLEAN_HVAC_SMOKE_SUCCESS'
} catch {
    Write-Error $_
    Write-Output "后端标准输出: $backendOut"
    Write-Output "后端错误输出: $backendError"
    throw
} finally {
    if ($null -ne $applicationProcess -and
        -not $applicationProcess.HasExited) {
        Stop-Process -Id $applicationProcess.Id -Force
        $applicationProcess.WaitForExit()
    }
}
