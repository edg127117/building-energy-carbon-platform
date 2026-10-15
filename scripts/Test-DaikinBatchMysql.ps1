[CmdletBinding()]
param(
    [string]$MySqlBinDir = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
    [ValidateRange(15, 300)][int]$ReadyTimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$batchRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$batchTarget = Join-Path $batchRoot 'target'
$batchRun = Join-Path $batchTarget ('daikin-batch-mysql-' + [guid]::NewGuid().ToString('N'))
$batchData = Join-Path $batchRun 'data'
$batchPidFile = Join-Path $batchRun 'mysqld.pid'
$batchMysql = Join-Path $MySqlBinDir 'mysql.exe'
$batchServerExe = Join-Path $MySqlBinDir 'mysqld.exe'
$batchAdmin = Join-Path $MySqlBinDir 'mysqladmin.exe'
foreach ($batchBinary in @($batchMysql, $batchServerExe, $batchAdmin)) {
    if (-not (Test-Path -LiteralPath $batchBinary -PathType Leaf)) { throw "MySQL binary missing: $batchBinary" }
}
$batchNames = @('MYSQL_PWD', 'DAIKIN_BATCH_MYSQL_URL', 'DAIKIN_BATCH_MYSQL_USER',
    'DAIKIN_BATCH_MYSQL_PASSWORD', 'DAIKIN_BATCH_MYSQL_ISOLATED',
    'DAIKIN_BATCH_MYSQL_SERVER_UUID', 'DAIKIN_BATCH_MYSQL_DATADIR')
$batchPrevious = @{}
foreach ($batchName in $batchNames) { $batchPrevious[$batchName] = [Environment]::GetEnvironmentVariable($batchName, 'Process') }
$batchServer = $null
$batchPort = 0
$batchVerified = $false
$batchExitCode = 1
New-Item -ItemType Directory -Path $batchData -Force | Out-Null
$batchClientError = Join-Path $batchRun 'mysql-client.err'

function Invoke-BatchMysql {
    param([string]$Sql)
    $batchOldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $batchOutput = @($Sql | & $batchMysql --no-defaults --protocol=TCP --host=127.0.0.1 `
            "--port=$batchPort" --user=root --batch --raw --skip-column-names 2> $batchClientError)
        $batchResult = $LASTEXITCODE
    } finally { $ErrorActionPreference = $batchOldPreference }
    if ($batchResult -ne 0) { throw 'Isolated MySQL command failed; inspect the local run log.' }
    return ($batchOutput -join "`n").Trim()
}

try {
    $batchVersion = (& $batchServerExe --no-defaults --version) -join ' '
    if ($LASTEXITCODE -ne 0 -or $batchVersion -notmatch 'Ver 8\.') { throw 'This acceptance runner requires MySQL 8.' }
    $batchInit = Start-Process -FilePath $batchServerExe -WindowStyle Hidden -PassThru -Wait `
        -ArgumentList @('--no-defaults', '--initialize-insecure', "--datadir=`"$batchData`"", '--console') `
        -RedirectStandardOutput (Join-Path $batchRun 'initialize.out') -RedirectStandardError (Join-Path $batchRun 'initialize.err')
    if ($batchInit.ExitCode -ne 0) { throw 'Could not initialize the dedicated MySQL data directory.' }
    $batchListener = New-Object System.Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $batchListener.Start()
    try { $batchPort = ([Net.IPEndPoint]$batchListener.LocalEndpoint).Port }
    finally { $batchListener.Stop() }
    $batchServer = Start-Process -FilePath $batchServerExe -WindowStyle Hidden -PassThru `
        -ArgumentList @('--no-defaults', "--datadir=`"$batchData`"", "--pid-file=`"$batchPidFile`"", '--bind-address=127.0.0.1',
            "--port=$batchPort", '--mysqlx=OFF', '--skip-log-bin', '--default-time-zone=+08:00',
            '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci', '--console') `
        -RedirectStandardOutput (Join-Path $batchRun 'server.out') -RedirectStandardError (Join-Path $batchRun 'server.err')
    $env:MYSQL_PWD = ''
    $batchDeadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    $batchReady = $false
    while ([DateTime]::UtcNow -lt $batchDeadline) {
        $batchServer.Refresh()
        if ($batchServer.HasExited) { throw 'The dedicated MySQL process exited before becoming ready.' }
        try {
            if ((Invoke-BatchMysql -Sql 'SELECT 1') -eq '1') { $batchReady = $true; break }
        } catch { Start-Sleep -Milliseconds 400 }
    }
    if (-not $batchReady) { throw 'The dedicated MySQL process did not become ready.' }

    # Prove ownership before ALTER USER or CREATE DATABASE: never mutate a service that won the port race.
    $batchActualData = Invoke-BatchMysql -Sql 'SELECT @@datadir'
    $batchCanonicalData = [IO.Path]::GetFullPath($batchActualData).TrimEnd('\', '/')
    if (-not $batchCanonicalData.Equals([IO.Path]::GetFullPath($batchData).TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'MySQL data directory does not match this run; refusing all writes.'
    }
    $batchUuid = Invoke-BatchMysql -Sql 'SELECT @@server_uuid'
    if ($batchUuid -notmatch '^[0-9a-f-]{36}$') { throw 'Unexpected dedicated MySQL UUID.' }
    $batchPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    Invoke-BatchMysql -Sql "ALTER USER 'root'@'localhost' IDENTIFIED BY '$batchPassword'; CREATE DATABASE iot_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
    $env:MYSQL_PWD = $batchPassword
    $batchVerified = $true
    if ((Invoke-BatchMysql -Sql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=''iot_platform''') -ne '0') {
        throw 'Acceptance schema must be empty.'
    }
    $env:DAIKIN_BATCH_MYSQL_URL = "jdbc:mysql://127.0.0.1:$batchPort/iot_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=15000"
    $env:DAIKIN_BATCH_MYSQL_USER = 'root'
    $env:DAIKIN_BATCH_MYSQL_PASSWORD = $batchPassword
    $env:DAIKIN_BATCH_MYSQL_ISOLATED = 'true'
    $env:DAIKIN_BATCH_MYSQL_SERVER_UUID = $batchUuid
    $env:DAIKIN_BATCH_MYSQL_DATADIR = $batchData
    Write-Output "DAIKIN_BATCH_MYSQL_READY version=$batchVersion"
    Push-Location $batchRoot
    try {
        & (Join-Path $batchRoot 'mvnw.cmd') --batch-mode --no-transfer-progress `
            '-Dtest=DaikinBatchMysqlAcceptanceTest' test *> (Join-Path $batchRun 'acceptance.log')
        $batchExitCode = $LASTEXITCODE
    } finally { Pop-Location }
    Get-Content -LiteralPath (Join-Path $batchRun 'acceptance.log') -Tail 22
    if ($batchExitCode -ne 0) { throw 'MySQL acceptance failed; preserve the run log for diagnosis.' }
} finally {
    try { if ($null -ne $batchServer) {
        $batchServer.Refresh()
        if (-not $batchServer.HasExited -and $batchVerified) {
            $batchOldPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                & $batchAdmin --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$batchPort" `
                    --user=root --shutdown-timeout=15 shutdown 2> (Join-Path $batchRun 'shutdown.err') | Out-Null
            } finally { $ErrorActionPreference = $batchOldPreference }
            $batchServer.WaitForExit(15000) | Out-Null
        }
        # Windows MySQL may use a monitor parent; its PID is not necessarily the storage process PID.
        if (Test-Path -LiteralPath $batchPidFile) {
            $batchOwnedPidText = (Get-Content -LiteralPath $batchPidFile -Raw).Trim()
            if ($batchOwnedPidText -notmatch '^\d+$') { throw 'Unexpected owned MySQL PID file; refusing process cleanup.' }
            $batchOwnedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$batchOwnedPidText"
            if ($null -ne $batchOwnedProcess) {
                if ([string]::IsNullOrEmpty($batchOwnedProcess.CommandLine) -or
                    $batchOwnedProcess.CommandLine.IndexOf($batchData, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
                    throw 'MySQL PID does not belong to this data directory; refusing process cleanup.'
                }
                Stop-Process -Id ([int]$batchOwnedPidText) -ErrorAction Stop
                Wait-Process -Id ([int]$batchOwnedPidText) -Timeout 10 -ErrorAction SilentlyContinue
            }
        }
        $batchServer.Refresh()
        if (-not $batchServer.HasExited) { $batchServer.Kill(); $batchServer.WaitForExit(10000) | Out-Null }
        if (-not $batchServer.HasExited) { throw 'Dedicated MySQL still running; data directory preserved.' }
    } } finally {
        foreach ($batchName in $batchNames) { [Environment]::SetEnvironmentVariable($batchName, $batchPrevious[$batchName], 'Process') }
    }
    # Delete only this freshly created run's data, including the isolated archive schema; retain diagnostic logs.
    $batchResolvedTarget = (Resolve-Path -LiteralPath $batchTarget).Path.TrimEnd('\', '/')
    $batchResolvedRun = (Resolve-Path -LiteralPath $batchRun).Path
    if ([IO.Directory]::GetParent($batchResolvedRun).FullName -ne $batchResolvedTarget -or
        [IO.Path]::GetFileName($batchResolvedRun) -notmatch '^daikin-batch-mysql-[0-9a-f]{32}$' -or
        ((Get-Item -LiteralPath $batchResolvedRun).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Unexpected run directory; refusing cleanup.'
    }
    if (Test-Path -LiteralPath $batchData) {
        $batchResolvedData = (Resolve-Path -LiteralPath $batchData).Path
        if ([IO.Directory]::GetParent($batchResolvedData).FullName -ne $batchResolvedRun -or
            ((Get-Item -LiteralPath $batchResolvedData).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Unexpected data directory; refusing cleanup.'
        }
        # Windows can retain file handles briefly after a killed MySQL worker has exited.
        for ($batchCleanAttempt = 0; $batchCleanAttempt -lt 20; $batchCleanAttempt++) {
            try { Remove-Item -LiteralPath $batchResolvedData -Recurse -Force; break }
            catch { if ($batchCleanAttempt -eq 19) { throw }; Start-Sleep -Milliseconds 250 }
        }
    }
    Write-Output "DAIKIN_BATCH_MYSQL_CLEANED logs=$batchRun"
}
Write-Output 'DAIKIN_BATCH_MYSQL_ACCEPTANCE_OK'
