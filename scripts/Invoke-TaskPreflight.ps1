[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$errors = [System.Collections.Generic.List[string]]::new()
$rootOutput = & git rev-parse --show-toplevel 2>$null
if ($LASTEXITCODE -ne 0) {
    $errors.Add('NOT_A_GIT_WORKTREE')
    $root = $null
}
else {
    $root = $rootOutput.Trim()
    if (-not $root) {
        $errors.Add('NOT_A_GIT_WORKTREE')
    }
}

if ($root) {
    foreach ($entry in @('AGENTS.md', 'PROJECT_GUIDE.md', 'PROJECT_STATUS.md')) {
        if (-not (Test-Path -LiteralPath (Join-Path $root $entry) -PathType Leaf)) {
            $errors.Add("MISSING_RULE_ENTRY: $entry")
        }
    }
}

$branchOutput = & git branch --show-current 2>$null
if ($LASTEXITCODE -ne 0) {
    $errors.Add('CURRENT_BRANCH_UNAVAILABLE')
    $branch = ''
}
else {
    $branch = $branchOutput.Trim()
}

if ($branch -eq 'main') {
    $errors.Add('INVALID_TASK_BRANCH: main')
}
if ($branch -notmatch '^(feature|fix|perf|refactor|docs|test|chore)/[a-z0-9][a-z0-9-]*$') {
    $errors.Add("INVALID_TASK_BRANCH_NAME: $branch")
}

$worktreeStatus = & git status --porcelain 2>$null
if ($LASTEXITCODE -ne 0) {
    $errors.Add('WORKTREE_STATUS_UNAVAILABLE')
}
elseif ($worktreeStatus) {
    $errors.Add('WORKTREE_NOT_CLEAN')
}

& git merge-base --is-ancestor origin/main HEAD 2>$null
if ($LASTEXITCODE -ne 0) {
    $errors.Add('OUTDATED_OR_DIVERGED_BASELINE')
}

[string]$hooksPath = (& git config --local --get core.hooksPath 2>$null)
if ($LASTEXITCODE -ne 0 -or $hooksPath.Trim() -ne '.githooks') {
    $errors.Add('GIT_HOOKS_NOT_INSTALLED')
}

if ($errors.Count -gt 0) {
    foreach ($errorMessage in $errors) {
        Write-Output $errorMessage
    }
    exit 1
}

Write-Output 'RULE_ENTRY: AGENTS.md'
Write-Output 'RULE_ENTRY: PROJECT_GUIDE.md'
Write-Output 'RULE_ENTRY: PROJECT_STATUS.md'
Write-Output 'REQUIRED_REVIEW: verify scope, changed files, tests, and comment check before commit or PR'
Write-Output 'TASK_PREFLIGHT_OK'
