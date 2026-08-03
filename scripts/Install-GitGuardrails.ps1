[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$rootOutput = & git rev-parse --show-toplevel 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'NOT_A_GIT_WORKTREE'
}
$root = $rootOutput.Trim()
if (-not $root) {
    throw 'NOT_A_GIT_WORKTREE'
}

foreach ($hook in @('pre-commit', 'pre-push')) {
    $hookPath = Join-Path $root ".githooks/$hook"
    if (-not (Test-Path -LiteralPath $hookPath -PathType Leaf)) {
        throw "MISSING_HOOK: $hook"
    }
}

& git config --local core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) {
    throw 'HOOK_INSTALL_FAILED'
}

[string]$configuredHooksPath = (& git config --local --get core.hooksPath 2>$null)
if ($LASTEXITCODE -ne 0 -or $configuredHooksPath.Trim() -ne '.githooks') {
    throw 'HOOK_INSTALL_FAILED'
}

Write-Output 'GIT_GUARDRAILS_INSTALLED'
