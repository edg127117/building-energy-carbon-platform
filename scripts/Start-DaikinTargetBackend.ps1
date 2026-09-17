param([string]$DockerPath = 'docker')

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
$settings = Get-Content -LiteralPath 'target/daikin-target-env.json' -Raw | ConvertFrom-Json
foreach ($container in @($settings.mysqlContainer, $settings.tdContainer)) {
    if ($container -notmatch '^daikin-e2e-[a-zA-Z0-9-]+$') { throw 'Not a dedicated Daikin container name.' }
    $inspection = & $DockerPath inspect $container | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $inspection.Count -ne 1) { throw 'Cannot identify dedicated target container.' }
    if ($inspection[0].Config.Labels.'codex.task' -ne 'daikin-target-e2e' -or -not $inspection[0].State.Running) {
        throw 'Target containers must be running and have the dedicated acceptance label.'
    }
    $mysql = $container -eq $settings.mysqlContainer
    $containerPort = if ($mysql) { '3306/tcp' } else { '6041/tcp' }
    $binding = @($inspection[0].NetworkSettings.Ports.$containerPort)
    $jdbcPrefix = if ($mysql) { 'jdbc:mysql://' } else { 'jdbc:TAOS-RS://' }
    $jdbcUrl = if ($mysql) { $settings.mysqlUrl } else { $settings.tdUrl }
    if ($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or
        -not $jdbcUrl.StartsWith($jdbcPrefix + '127.0.0.1:' + $binding[0].HostPort + '/')) {
        throw 'JDBC target does not match the dedicated container loopback port.'
    }
}
& .\mvnw.cmd test-compile dependency:build-classpath '-Dmdep.outputFile=target/daikin-target-classpath.txt' '-DincludeScope=test'
if ($LASTEXITCODE -ne 0) { throw 'Target harness compilation failed.' }
$classpath = 'target/test-classes;target/classes;' + (Get-Content 'target/daikin-target-classpath.txt' -Raw).Trim()
$previousIsolation = $env:DAIKIN_TARGET_ISOLATED
$env:DAIKIN_TARGET_ISOLATED = 'true'
try {
    & java '-cp' $classpath 'com.platform.iot.daikin.acceptance.DaikinTargetHarnessApplication'
    if ($LASTEXITCODE -ne 0) { throw 'Target backend exited with an error.' }
} finally {
    $env:DAIKIN_TARGET_ISOLATED = $previousIsolation
}
