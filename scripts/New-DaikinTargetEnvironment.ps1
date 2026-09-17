param(
    [string]$DockerPath = 'docker',
    [ValidateRange(1024, 65535)][int]$BackendPort = 18089
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$target = Join-Path $root 'target'
$environmentFile = Join-Path $target 'daikin-target-env.json'
if (Test-Path -LiteralPath $environmentFile) {
    throw 'Target environment already exists. Preserve it or explicitly archive it before creating another environment.'
}
New-Item -ItemType Directory -Force -Path $target | Out-Null

function Invoke-Docker {
    param([string[]]$Arguments)
    $output = & $DockerPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($output -join ' ')" }
    return ($output -join "`n").Trim()
}

Invoke-Docker -Arguments @('version', '--format', '{{.Server.Version}}') | Out-Null
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$mysqlContainer = "daikin-e2e-mysql-$suffix"
$tdContainer = "daikin-e2e-td-$suffix"
$mysqlPassword = [guid]::NewGuid().ToString('N')

# All ports are loopback-only; Docker chooses unused host ports. No existing container is reused.
$previousRootPassword = $env:MYSQL_ROOT_PASSWORD
$env:MYSQL_ROOT_PASSWORD = $mysqlPassword
try {
    Invoke-Docker -Arguments @('run', '-d', '--name', $mysqlContainer, '--label', 'codex.task=daikin-target-e2e',
        '-p', '127.0.0.1::3306', '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_DATABASE=iot_platform',
        'mysql:8.4', '--default-time-zone=+08:00') | Out-Null
} finally {
    $env:MYSQL_ROOT_PASSWORD = $previousRootPassword
}
Invoke-Docker -Arguments @('run', '-d', '--name', $tdContainer, '--label', 'codex.task=daikin-target-e2e',
    '-p', '127.0.0.1::6041', '-e', 'TZ=Asia/Shanghai', 'tdengine/tdengine:3.2.3.0') | Out-Null
$mysqlPort = [int]((Invoke-Docker -Arguments @('port', $mysqlContainer, '3306/tcp')) -split ':')[-1]
$tdPort = [int]((Invoke-Docker -Arguments @('port', $tdContainer, '6041/tcp')) -split ':')[-1]
$settings = [ordered]@{
    mysqlContainer = $mysqlContainer
    tdContainer = $tdContainer
    mysqlUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/iot_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    mysqlUser = 'root'
    mysqlPassword = $mysqlPassword
    tdUrl = "jdbc:TAOS-RS://127.0.0.1:$tdPort/?httpConnectTimeout=5000&httpSocketTimeout=30000"
    tdUser = 'root'
    tdPassword = 'taosdata'
    tdDatabase = "daikin_e2e_$suffix"
    backendPort = $BackendPort
    mysqlPort = $mysqlPort
    tdPort = $tdPort
    adminPassword = 'Dk!' + [guid]::NewGuid().ToString('N')
    ownerPassword = 'Dk!' + [guid]::NewGuid().ToString('N')
}
# target/ is ignored. Credentials are never printed or copied into acceptance reports.
$settings | ConvertTo-Json | Set-Content -LiteralPath $environmentFile -Encoding UTF8
$deadline = [DateTime]::UtcNow.AddMinutes(3)
$ready = $false
$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $mysqlPassword
try {
    while ([DateTime]::UtcNow -lt $deadline) {
        & $DockerPath exec -e MYSQL_PWD $mysqlContainer mysql -uroot -N -e 'SELECT 1' 2>$null | Out-Null
        $mysqlReady = $LASTEXITCODE -eq 0
        try {
            $authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('root:taosdata'))
            $response = Invoke-RestMethod -Uri "http://127.0.0.1:$tdPort/rest/sql" -Method Post `
                -Headers @{ Authorization = "Basic $authorization" } -Body 'SELECT SERVER_VERSION()' -TimeoutSec 5
            $ready = $mysqlReady -and $response.code -eq 0
        } catch { $ready = $false }
        if ($ready) { break }
        Start-Sleep -Seconds 2
    }
} finally {
    $env:MYSQL_PWD = $previousMysqlPassword
}
if (-not $ready) { throw 'Dedicated engines did not become ready. Inspect only containers recorded in target/daikin-target-env.json.' }
Write-Output 'DAIKIN_TARGET_ENGINES_READY'
Write-Output "MySQL container: $mysqlContainer; TDengine container: $tdContainer"
Write-Output 'Environment and generated credentials: target/daikin-target-env.json (ignored)'
