param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Start', 'Stop', 'Restart', 'Status', 'Check')]
    [string]$Action,
    [string]$Root = (Join-Path $env:USERPROFILE '.building-energy-carbon/cxg-monthly-test')
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONUTF8 = '1'
$instanceFile = Join-Path $Root 'private/instance.json'
if (-not (Test-Path -LiteralPath $instanceFile)) { throw 'CXG_MONTHLY_INSTANCE_NOT_CONFIGURED' }
$instance = Get-Content -LiteralPath $instanceFile -Raw -Encoding UTF8 | ConvertFrom-Json
& $instance.executables.python (Join-Path $PSScriptRoot 'cxg_monthly_env.py') $Action.ToLowerInvariant() --root $Root
exit $LASTEXITCODE
