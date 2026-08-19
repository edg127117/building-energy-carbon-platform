[CmdletBinding()]
param(
    [string]$ApiBase = 'http://127.0.0.1:8081/api',
    [string]$AdminUsername = 'admin',
    [string]$MySqlContainer = 'iot-smoke-mysql',
    [string]$TdengineContainer = 'iot-smoke-tdengine',
    [string]$ExpectedComposeProject = 'iot-platform-demo-hvac-smoke'
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$publisher = Join-Path $repoRoot '.scripts\publish-device-onboarding-message.mjs'
$identity = 'E3A-SYNTHETIC-MAC-001'
$productCode = 'E3A_SYNTHETIC_WCR'
$sourcePointCode = "MAC:${identity}:temperature"
$productId = $null
$pendingId = $null
$identityId = $null
$equipmentId = $null
$pointId = $null
$stage = 'initialize'

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

function Invoke-DockerCapture([string[]]$Arguments) {
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $script:dockerCli @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $text = ($output | ForEach-Object { $_.ToString() } | Out-String).Trim()
    if ($exitCode -ne 0) {
        throw "Docker 命令执行失败。$text"
    }
    return $text
}

function Assert-IsolatedContainer([string]$Container) {
    if ($Container -notin @('iot-smoke-mysql', 'iot-smoke-tdengine')) {
        throw "拒绝操作非白名单容器: $Container"
    }
    $inspection = Invoke-DockerCapture @('inspect', $Container) |
        ConvertFrom-Json
    $project = $inspection[0].Config.Labels.'com.docker.compose.project'
    if ($project -ne $ExpectedComposeProject) {
        throw "容器 $Container 不属于隔离的 smoke 项目"
    }
}

function Invoke-MySqlScalar([string]$Sql) {
    $password = if ($env:MYSQL_PASSWORD) {
        $env:MYSQL_PASSWORD
    } else {
        'change-me'
    }
    $output = Invoke-DockerCapture @(
        'exec', '-e', "MYSQL_PWD=$password", $MySqlContainer,
        'mysql', '-uroot', '-Nse', $Sql
    )
    return @($output -split "`r?`n" |
        Where-Object { $_.Trim() } |
        Select-Object -Last 1)[0].Trim()
}

function Invoke-Taos([string]$Sql) {
    return Invoke-DockerCapture @('exec', $TdengineContainer, 'taos', '-s', $Sql)
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Path,
        [string]$Token,
        [object]$Body
    )
    $parameters = @{
        Method = $Method
        Uri = "$($ApiBase.TrimEnd('/'))/$($Path.TrimStart('/'))"
        Headers = @{}
        TimeoutSec = 20
    }
    if ($Token) {
        $parameters.Headers.Authorization = "Bearer $Token"
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    return Invoke-RestMethod @parameters
}

function Assert-Success([object]$Response, [string]$Operation) {
    if ($null -eq $Response -or -not $Response.success) {
        throw "$Operation 未返回成功结果"
    }
}

function Publish-SyntheticPacket {
    param(
        [Parameter(Mandatory)] [long]$EventTime,
        [Parameter(Mandatory)] [long]$Sequence,
        [string]$ProfileCode = 'HVAC_DEVICE_V1',
        [string]$Mode = 'valid'
    )
    $previous = @{
        ONBOARDING_IDENTITY = $env:ONBOARDING_IDENTITY
        ONBOARDING_EVENT_TIME_MS = $env:ONBOARDING_EVENT_TIME_MS
        ONBOARDING_SEQUENCE = $env:ONBOARDING_SEQUENCE
        ONBOARDING_PROFILE_CODE = $env:ONBOARDING_PROFILE_CODE
        ONBOARDING_MESSAGE_MODE = $env:ONBOARDING_MESSAGE_MODE
    }
    try {
        $env:ONBOARDING_IDENTITY = $identity
        $env:ONBOARDING_EVENT_TIME_MS = $EventTime.ToString()
        $env:ONBOARDING_SEQUENCE = $Sequence.ToString()
        $env:ONBOARDING_PROFILE_CODE = $ProfileCode
        $env:ONBOARDING_MESSAGE_MODE = $Mode
        & node $publisher
        if ($LASTEXITCODE -ne 0) {
            throw '合成设备 MQTT 报文发布失败'
        }
    } finally {
        foreach ($name in $previous.Keys) {
            [Environment]::SetEnvironmentVariable(
                $name, $previous[$name], 'Process')
        }
    }
}

function Get-RawEventCount {
    $output = Invoke-Taos @"
SELECT COUNT(*) AS event_count
FROM iot_telemetry.st_raw_event
WHERE source_point_code='$sourcePointCode';
"@
    $match = [regex]::Match($output, '(?m)^\s*(\d+)\s*\|\s*$')
    if (-not $match.Success) {
        throw '无法解析 TDengine 原始事件计数'
    }
    return [int]$match.Groups[1].Value
}

function Wait-MySqlValue {
    param(
        [Parameter(Mandatory)] [string]$Sql,
        [Parameter(Mandatory)] [string]$Expected,
        [Parameter(Mandatory)] [string]$Description,
        [int]$TimeoutSeconds = 20
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastValue = $null
    while ((Get-Date) -lt $deadline) {
        $lastValue = Invoke-MySqlScalar $Sql
        if ($lastValue -eq $Expected) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Description 超时，期望 $Expected，实际 $lastValue"
}

function Wait-RawEventCount([int]$Expected, [string]$Description) {
    $deadline = (Get-Date).AddSeconds(20)
    $lastValue = -1
    while ((Get-Date) -lt $deadline) {
        $lastValue = Get-RawEventCount
        if ($lastValue -eq $Expected) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Description 超时，期望 $Expected，实际 $lastValue"
}

function Assert-SafeId([string]$Value, [string]$Name) {
    if (-not $Value -or $Value -notmatch '^[A-Za-z0-9_]+$') {
        throw "$Name 不是安全标识符: $Value"
    }
}

function Remove-SyntheticFacts {
    if ($pointId) {
        Assert-SafeId $pointId 'pointId'
        Invoke-Taos "DROP TABLE IF EXISTS iot_telemetry.st_raw_event_$pointId;" |
            Out-Null
        Invoke-Taos "DROP TABLE IF EXISTS iot_telemetry.st_raw_minute_$pointId;" |
            Out-Null
    }

    $objectIds = @($productId, $pendingId, $identityId, $equipmentId, $pointId) |
        Where-Object { $_ }
    foreach ($id in $objectIds) {
        Assert-SafeId $id 'audit object id'
    }
    if ($objectIds.Count -gt 0) {
        $quotedIds = ($objectIds | ForEach-Object { "'$_'" }) -join ','
        Invoke-MySqlScalar "DELETE FROM iot_platform.biz_onboarding_audit_log WHERE object_id IN ($quotedIds); SELECT 'OK';" |
            Out-Null
    }
    Invoke-MySqlScalar @"
DELETE FROM iot_platform.biz_pending_device WHERE identity_value='$identity';
DELETE FROM iot_platform.biz_point_alias
 WHERE source_point_code='$sourcePointCode'
    OR point_id IN (
      SELECT point_id FROM iot_platform.biz_data_point WHERE equip_id IN (
        SELECT equip_id FROM iot_platform.biz_equipment WHERE product_id IN (
          SELECT product_id FROM iot_platform.biz_device_product WHERE product_code='$productCode'
        )
      )
    );
DELETE FROM iot_platform.biz_device_identity WHERE identity_value='$identity';
DELETE FROM iot_platform.biz_data_point
 WHERE equip_id IN (
   SELECT equip_id FROM iot_platform.biz_equipment WHERE product_id IN (
     SELECT product_id FROM iot_platform.biz_device_product WHERE product_code='$productCode'
   )
 );
DELETE FROM iot_platform.biz_equipment WHERE product_id IN (
  SELECT product_id FROM iot_platform.biz_device_product WHERE product_code='$productCode'
);
DELETE FROM iot_platform.biz_product_point_template WHERE product_id IN (
  SELECT product_id FROM iot_platform.biz_device_product WHERE product_code='$productCode'
);
DELETE FROM iot_platform.biz_device_product WHERE product_code='$productCode';
SELECT 'OK';
"@ | Out-Null
}

if (-not $env:HVAC_SMOKE_ADMIN_PASSWORD) {
    throw 'HVAC_SMOKE_ADMIN_PASSWORD 必须通过进程环境提供'
}
if (-not (Test-Path -LiteralPath $publisher)) {
    throw "找不到合成设备发布器: $publisher"
}

$dockerCli = Resolve-DockerCli
Assert-IsolatedContainer $MySqlContainer
Assert-IsolatedContainer $TdengineContainer

try {
    $stage = 'clean stale synthetic facts'
    Remove-SyntheticFacts

    $stage = 'admin login'
    $login = Invoke-JsonApi -Method Post -Path '/auth/login' -Body @{
        username = $AdminUsername
        password = $env:HVAC_SMOKE_ADMIN_PASSWORD
    }
    $token = $login.data.token
    if (-not $login.success -or -not $token) {
        throw '管理员登录未返回有效 JWT'
    }

    $baseTime = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $stage = 'unknown discovery'
    Publish-SyntheticPacket -EventTime $baseTime -Sequence 1
    Wait-MySqlValue `
        -Sql "SELECT COUNT(*) FROM iot_platform.biz_pending_device WHERE identity_value='$identity' AND status='DISCOVERED';" `
        -Expected '1' `
        -Description '未知设备发现'
    $pendingId = Invoke-MySqlScalar "SELECT pending_id FROM iot_platform.biz_pending_device WHERE identity_value='$identity';"
    Assert-SafeId $pendingId 'pendingId'
    $pending = Invoke-JsonApi -Method Get -Path "/v1/device-onboarding/pending/$pendingId" -Token $token
    Assert-Success $pending '查询待绑定详情'
    if ($pending.data.identityValue -ne $identity -or
        $pending.data.latestMetrics.metrics[0].code -ne 'temperature') {
        throw '待绑定样例与合成报文不一致'
    }
    Wait-RawEventCount 0 '未知设备不得写入 TDengine'

    $stage = 'create and enable product'
    $product = Invoke-JsonApi -Method Post -Path '/v1/device-products' -Token $token -Body @{
        productCode = $productCode
        productName = 'E3A Synthetic Chiller'
        manufacturer = 'Codex Smoke'
        model = 'SYNTHETIC-1'
        equipmentTypeCode = 'WCR'
        expectedProfileCode = 'HVAC_DEVICE_V1'
        identityType = 'MAC'
        points = @(@{
            metricCode = 'temperature'
            pointNameTemplate = 'Synthetic inlet temperature'
            suffixCode = 'TWin'
            unit = '℃'
            minValue = -20
            maxValue = 80
            forCalc = $false
            required = $true
            sortOrder = 1
            enabled = $true
        })
    }
    Assert-Success $product '创建合成产品模板'
    $productId = $product.data.productId
    Assert-SafeId $productId 'productId'
    $enabledProduct = Invoke-JsonApi -Method Post -Path "/v1/device-products/$productId/enable" -Token $token
    Assert-Success $enabledProduct '启用合成产品模板'

    $stage = 'bind but keep inactive'
    $bound = Invoke-JsonApi -Method Post -Path "/v1/device-onboarding/pending/$pendingId/bind" -Token $token -Body @{
        productId = $productId
        buildingId = 'BLD001'
        spaceId = 'SPACE001'
        systemGroupId = 'GROUP001'
        existingEquipmentId = $null
        newEquipment = @{
            equipmentName = 'E3A Synthetic Chiller 001'
            manufacturer = 'Codex Smoke'
        }
        pointBindings = @(@{
            metricCode = 'temperature'
            existingPointId = $null
            pointCode = 'WCR2_TWin'
            pointName = 'E3A Synthetic inlet temperature'
            namingRuleId = 'RULE_WCR_MAIN'
            familyCode = 'WCR'
            componentCode = 'MAIN'
            dataType = 'ANALOG'
        })
    }
    Assert-Success $bound '绑定合成设备'
    $identityId = $bound.data.identityId
    $equipmentId = $bound.data.equipmentId
    $pointId = @($bound.data.pointIds)[0]
    Assert-SafeId $identityId 'identityId'
    Assert-SafeId $equipmentId 'equipmentId'
    Assert-SafeId $pointId 'pointId'
    if ($bound.data.status -ne 'BOUND' -or -not $bound.data.configEffective) {
        throw '绑定结果未停留在 BOUND 且配置未生效'
    }
    Publish-SyntheticPacket -EventTime ($baseTime + 1000) -Sequence 2
    Wait-RawEventCount 0 '已绑定但未启用的身份不得写入 TDengine'
    $reportCount = Invoke-MySqlScalar "SELECT report_count FROM iot_platform.biz_pending_device WHERE pending_id='$pendingId';"
    if ($reportCount -ne '1') {
        throw '已登记但停用的身份被错误当作未知设备'
    }

    $stage = 'activate and ingest'
    $activated = Invoke-JsonApi -Method Post -Path "/v1/device-onboarding/identities/$identityId/activate" -Token $token
    Assert-Success $activated '启用合成设备身份'
    if ($activated.data.status -ne 'ACTIVE' -or -not $activated.data.configEffective) {
        throw '身份启用结果与运行时配置不一致'
    }
    $acceptedTime = $baseTime + 2000
    Publish-SyntheticPacket -EventTime $acceptedTime -Sequence 3
    Wait-RawEventCount 1 '启用后下一包未进入 TDengine'
    $owner = Invoke-Taos @"
SELECT building_id,equip_id,point_id,source_point_code
FROM iot_telemetry.st_raw_event
WHERE source_point_code='$sourcePointCode';
"@
    foreach ($expected in @('BLD001', $equipmentId, $pointId, $sourcePointCode)) {
        if ($owner -notmatch [regex]::Escape($expected)) {
            throw "TDengine 原始事件缺少期望归属: $expected"
        }
    }

    $stage = 'duplicate idempotency'
    Publish-SyntheticPacket -EventTime $acceptedTime -Sequence 3
    Wait-RawEventCount 1 '重复报文未保持幂等'

    $stage = 'missing field rejection'
    Publish-SyntheticPacket -EventTime ($baseTime + 3000) -Sequence 4 -Mode 'missing-metrics'
    Wait-RawEventCount 1 '缺字段报文被错误写入 TDengine'

    $stage = 'profile conflict rejection'
    Publish-SyntheticPacket -EventTime ($baseTime + 4000) -Sequence 5 -ProfileCode 'CONFLICT_PROFILE'
    Wait-RawEventCount 1 '协议冲突报文被错误写入 TDengine'

    $stage = 'deactivate and reject next packet'
    $deactivated = Invoke-JsonApi -Method Post -Path "/v1/device-onboarding/identities/$identityId/deactivate" -Token $token
    Assert-Success $deactivated '停用合成设备身份'
    if ($deactivated.data.status -ne 'DISABLED' -or -not $deactivated.data.configEffective) {
        throw '身份停用结果与运行时配置不一致'
    }
    Publish-SyntheticPacket -EventTime ($baseTime + 5000) -Sequence 6
    Wait-RawEventCount 1 '停用后报文被错误写入 TDengine'

    Write-Output 'DEVICE_ONBOARDING_SYNTHETIC_FLOW_OK'
} catch {
    throw "合成设备接入验收失败，阶段: $stage。$($_.Exception.Message)"
} finally {
    $cleanupFailure = $null
    try {
        Remove-SyntheticFacts
        $mysqlRemaining = Invoke-MySqlScalar @"
SELECT
  (SELECT COUNT(*) FROM iot_platform.biz_pending_device WHERE identity_value='$identity') +
  (SELECT COUNT(*) FROM iot_platform.biz_device_identity WHERE identity_value='$identity') +
  (SELECT COUNT(*) FROM iot_platform.biz_device_product WHERE product_code='$productCode');
"@
        if ($mysqlRemaining -ne '0') {
            throw "MySQL 仍有 $mysqlRemaining 条合成设备主事实"
        }
        if ((Get-RawEventCount) -ne 0) {
            throw 'TDengine 仍有合成设备原始事件'
        }
        Write-Output 'DEVICE_ONBOARDING_SYNTHETIC_CLEANUP_OK'
    } catch {
        $cleanupFailure = $_
    }
    if ($cleanupFailure) {
        throw "合成数据清理失败。$($cleanupFailure.Exception.Message)"
    }
}
