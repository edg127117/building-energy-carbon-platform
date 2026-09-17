[CmdletBinding()]
param(
    [switch]$InfrastructureOnly,
    [switch]$KeepContainers,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'src\env\docker-compose.yml'
$developmentComposeFile = Join-Path $repoRoot 'src\env\docker-compose.development.yml'
$frontendRoot = Join-Path $repoRoot 'web'
$backendWrapper = Join-Path $repoRoot 'mvnw.cmd'
$childProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$containersStarted = $false
$dockerExecutable = $null

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    & $dockerExecutable compose -f $composeFile -f $developmentComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE."
    }
}

function Stop-LaunchedProcessTree {
    param([System.Diagnostics.Process]$Process)

    if ($null -eq $Process) {
        return
    }

    $Process.Refresh()
    if (-not $Process.HasExited) {
        & taskkill.exe /PID $Process.Id /T /F *> $null
    }
}

foreach ($requiredFile in @($composeFile, $developmentComposeFile, $backendWrapper)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required local development file is missing: $requiredFile"
    }
}

$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $dockerExecutable = $dockerCommand.Source
}
else {
    $dockerCandidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'),
        (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe')
    )
    $dockerExecutable = $dockerCandidates |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
}

if (-not $dockerExecutable) {
    throw 'The docker command was not found. Install and start Docker Desktop first.'
}

& $dockerExecutable info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not ready. Wait until the tray icon shows that it is running.'
}

Invoke-Compose config --quiet
if ($ValidateOnly) {
    Write-Output 'LOCAL_DEVELOPMENT_CONFIG_OK'
    exit 0
}

try {
    Write-Host '[1/3] Starting project infrastructure containers...'
    $containersStarted = $true
    Invoke-Compose up -d --wait

    if ($InfrastructureOnly) {
        Write-Host '[2/3] Infrastructure is ready. Press Enter to stop the project containers.'
        [void](Read-Host)
        return
    }

    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
        throw 'npm.cmd was not found, so the frontend cannot be started.'
    }

    Write-Host '[2/3] Starting the Spring Boot backend...'
    $backendProcess = Start-Process `
        -FilePath 'powershell.exe' `
        -WorkingDirectory $repoRoot `
        -ArgumentList @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', '& .\mvnw.cmd spring-boot:run') `
        -PassThru
    $childProcesses.Add($backendProcess)

    Write-Host '[3/3] Starting the Vue frontend...'
    $frontendCommand = 'if (-not (Test-Path -LiteralPath node_modules)) { npm ci; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }; npm run dev'
    $frontendProcess = Start-Process `
        -FilePath 'powershell.exe' `
        -WorkingDirectory $frontendRoot `
        -ArgumentList @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $frontendCommand) `
        -PassThru
    $childProcesses.Add($frontendProcess)

    Write-Host 'Local development is running. Cleanup starts after both processes exit or Ctrl+C is pressed.'
    while ($true) {
        Start-Sleep -Seconds 2
        $backendProcess.Refresh()
        $frontendProcess.Refresh()
        if ($backendProcess.HasExited -and $frontendProcess.HasExited) {
            break
        }
    }
}
finally {
    foreach ($childProcess in $childProcesses) {
        Stop-LaunchedProcessTree -Process $childProcess
    }

    if ($containersStarted -and -not $KeepContainers) {
        Write-Host 'Stopping project containers. Containers and named volumes are preserved...'
        Invoke-Compose stop
    }
}
