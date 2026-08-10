[CmdletBinding()]
param(
    [string]$ApiBase = 'http://127.0.0.1:8081/api',
    [string]$WebSocketUrl = 'ws://127.0.0.1:8081/api/ws/hvac',
    [string]$AdminUsername = 'admin',
    [string]$RestrictedUsername = 'hvac_realtime_smoke',
    [string]$SecondBuildingId = 'BLD-SMOKE-002'
)

$ErrorActionPreference = 'Stop'

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$nodeHelper = Join-Path $repoRoot '.scripts\test-hvac-realtime.mjs'
$adminPassword = $env:HVAC_SMOKE_ADMIN_PASSWORD
$restrictedPassword = $env:HVAC_SMOKE_RESTRICTED_PASSWORD
$dockerCli = $null

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
    throw 'Docker CLI is unavailable'
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Path,
        [string]$Token,
        [object]$Body
    )

    $headers = @{}
    if ($Token) {
        $headers.Authorization = "Bearer $Token"
    }
    $parameters = @{
        Method = $Method
        Uri = "$($ApiBase.TrimEnd('/'))/$($Path.TrimStart('/'))"
        Headers = $headers
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    return Invoke-RestMethod @parameters
}

function Get-RequiredToken([string]$Username, [string]$Password) {
    $response = Invoke-JsonApi `
        -Method Post `
        -Path '/auth/login' `
        -Body @{ username = $Username; password = $Password }
    if (-not $response.success -or -not $response.data.token) {
        throw 'Smoke account login returned no valid JWT'
    }
    return $response.data.token
}

function Assert-RuntimeAvailable {
    $script:dockerCli = Resolve-DockerCli
    $running = & $script:dockerCli inspect `
        --format '{{.State.Running}}' iot-mysql 2>$null
    if ($LASTEXITCODE -ne 0 -or $running -ne 'true') {
        throw 'Dedicated Docker MySQL container is not running'
    }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw 'Node.js is unavailable'
    }
    & node -e "if(typeof fetch!=='function'||typeof WebSocket!=='function')process.exit(1)"
    if ($LASTEXITCODE -ne 0) {
        throw 'Node.js runtime requires global fetch and WebSocket'
    }
    if (-not (Test-Path -LiteralPath $nodeHelper)) {
        throw 'HVAC realtime Node smoke helper is missing'
    }
}

function Ensure-SecondBuilding([string]$AdminToken) {
    $response = Invoke-JsonApi `
        -Method Get `
        -Path '/building/list?page=1&size=100' `
        -Token $AdminToken
    $existing = @($response.data.records | Where-Object {
        $_.buildingId -eq $SecondBuildingId
    })
    if ($existing.Count -gt 0) {
        return
    }

    $created = Invoke-JsonApi `
        -Method Post `
        -Path '/building/add' `
        -Token $AdminToken `
        -Body @{
            buildingId = $SecondBuildingId
            buildingName = 'HVAC realtime smoke building'
            buildingCode = 'HVAC-RT-SMOKE-002'
            buildingType = 'OFFICE'
            totalGfa = 1000
            climateZone = 'HOT_SUMMER_COLD_WINTER'
            regionCode = '330100'
        }
    if (-not $created.success) {
        throw 'Unable to prepare second building'
    }
}

function Ensure-RestrictedUser([string]$AdminToken) {
    $response = Invoke-JsonApi `
        -Method Get `
        -Path "/system/users?page=1&size=100&keyword=$([uri]::EscapeDataString($RestrictedUsername))&includeDeleted=true" `
        -Token $AdminToken
    $user = @($response.data.records | Where-Object {
        $_.username -eq $RestrictedUsername
    }) | Select-Object -First 1

    if ($null -eq $user) {
        $created = Invoke-JsonApi `
            -Method Post `
            -Path '/system/users' `
            -Token $AdminToken `
            -Body @{
                username = $RestrictedUsername
                password = $restrictedPassword
                nickname = 'HVAC realtime restricted smoke account'
                roleKeys = @('BUILDING_OWNER')
                buildingIds = @($SecondBuildingId)
            }
        if (-not $created.success -or -not $created.data.id) {
            throw 'Unable to create restricted smoke account'
        }
        $user = $created.data
    }

    if ($user.delFlag -eq 1) {
        Invoke-JsonApi -Method Put `
            -Path "/system/users/$($user.id)/restore" `
            -Token $AdminToken | Out-Null
    }
    if ($user.status -ne 1) {
        Invoke-JsonApi -Method Put `
            -Path "/system/users/$($user.id)/status" `
            -Token $AdminToken -Body @{ status = 1 } | Out-Null
    }
    Invoke-JsonApi -Method Put `
        -Path "/system/users/$($user.id)/password" `
        -Token $AdminToken -Body @{ password = $restrictedPassword } | Out-Null
    Invoke-JsonApi -Method Put `
        -Path "/system/users/$($user.id)/roles" `
        -Token $AdminToken -Body @{ roleKeys = @('BUILDING_OWNER') } | Out-Null
    Invoke-JsonApi -Method Put `
        -Path "/system/users/$($user.id)/buildings" `
        -Token $AdminToken -Body @{ buildingIds = @($SecondBuildingId) } | Out-Null
}

if (-not $adminPassword -or -not $restrictedPassword) {
    throw 'Dedicated smoke passwords must be provided by environment'
}

Assert-RuntimeAvailable
$adminToken = $null
$restrictedToken = $null
$previousEnvironment = @{}
$environmentNames = @(
    'HVAC_RT_ADMIN_TOKEN',
    'HVAC_RT_RESTRICTED_TOKEN',
    'HVAC_RT_API_BASE',
    'HVAC_RT_WS_URL',
    'HVAC_RT_SECOND_BUILDING_ID'
)
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable(
        $name, 'Process')
}

try {
    $adminToken = Get-RequiredToken $AdminUsername $adminPassword
    Ensure-SecondBuilding $adminToken
    Ensure-RestrictedUser $adminToken
    $restrictedToken = Get-RequiredToken `
        $RestrictedUsername $restrictedPassword

    [Environment]::SetEnvironmentVariable(
        'HVAC_RT_ADMIN_TOKEN', $adminToken, 'Process')
    [Environment]::SetEnvironmentVariable(
        'HVAC_RT_RESTRICTED_TOKEN', $restrictedToken, 'Process')
    [Environment]::SetEnvironmentVariable(
        'HVAC_RT_API_BASE', $ApiBase, 'Process')
    [Environment]::SetEnvironmentVariable(
        'HVAC_RT_WS_URL', $WebSocketUrl, 'Process')
    [Environment]::SetEnvironmentVariable(
        'HVAC_RT_SECOND_BUILDING_ID', $SecondBuildingId, 'Process')

    & node $nodeHelper
    if ($LASTEXITCODE -ne 0) {
        throw 'HVAC realtime Node smoke failed'
    }
    Write-Output 'HVAC_REALTIME_SMOKE_OK'
} catch {
    Write-Error 'HVAC realtime smoke failed; sensitive request data hidden'
    throw
} finally {
    $adminToken = $null
    $restrictedToken = $null
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name, $previousEnvironment[$name], 'Process')
    }
}
