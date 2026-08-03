[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^(feature|fix|perf|refactor|docs|test|chore)/[a-z0-9][a-z0-9-]*$')]
    [string]$TaskBranch,
    [string]$WorktreePath,
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-GitRepositoryRoot {
    $rootOutput = & git rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'NOT_A_GIT_WORKTREE'
    }

    [string]$root = $rootOutput.Trim()
    if (-not $root) {
        throw 'NOT_A_GIT_WORKTREE'
    }
    return [IO.Path]::GetFullPath($root)
}

function Assert-CleanupRepositoryState {
    param([string]$TaskBranch)

    [string]$currentBranch = (& git branch --show-current 2>$null)
    if ($LASTEXITCODE -ne 0 -or $currentBranch.Trim() -ne 'main') {
        throw 'CLEANUP_REQUIRES_MAIN_WORKTREE'
    }

    $mainStatus = & git status --porcelain 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'MAIN_WORKTREE_STATUS_UNAVAILABLE'
    }
    if ($mainStatus) {
        throw 'MAIN_WORKTREE_NOT_CLEAN'
    }

    & git show-ref --verify --quiet "refs/heads/$TaskBranch"
    if ($LASTEXITCODE -ne 0) {
        throw 'TASK_BRANCH_NOT_FOUND'
    }

    & git rev-parse --verify --quiet origin/main 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'REMOTE_MAIN_NOT_AVAILABLE'
    }

    & git merge-base --is-ancestor $TaskBranch origin/main 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'TASK_BRANCH_NOT_MERGED'
    }

    & git merge-base --is-ancestor main origin/main 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'MAIN_CANNOT_FAST_FORWARD'
    }
}

function Get-RegisteredWorktree {
    param([string]$ResolvedPath)

    $worktreeLines = & git worktree list --porcelain 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'WORKTREE_LIST_FAILED'
    }

    $records = [System.Collections.Generic.List[object]]::new()
    $current = $null
    foreach ($line in @($worktreeLines)) {
        if ($line.StartsWith('worktree ')) {
            if ($null -ne $current) {
                $records.Add([pscustomobject]$current)
            }
            $current = @{
                Path = [IO.Path]::GetFullPath($line.Substring('worktree '.Length))
                Branch = $null
            }
        }
        elseif ($line.StartsWith('branch ') -and $null -ne $current) {
            $current.Branch = $line.Substring('branch '.Length)
        }
        elseif ([string]::IsNullOrWhiteSpace($line) -and $null -ne $current) {
            $records.Add([pscustomobject]$current)
            $current = $null
        }
    }
    if ($null -ne $current) {
        $records.Add([pscustomobject]$current)
    }

    foreach ($record in $records) {
        if ([string]::Equals($record.Path, $ResolvedPath, [StringComparison]::OrdinalIgnoreCase)) {
            return $record
        }
    }
    return $null
}

function Resolve-TaskWorktree {
    param(
        [string]$RequestedPath,
        [string]$RepositoryRoot,
        [string]$TaskBranch
    )

    if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        throw 'INVALID_WORKTREE_PATH'
    }
    if (-not [IO.Path]::IsPathRooted($RequestedPath) -or $RequestedPath -match '[*?]' -or $RequestedPath -match '(^|[\\/])~($|[\\/])') {
        throw 'INVALID_WORKTREE_PATH'
    }

    try {
        $resolvedPath = [IO.Path]::GetFullPath($RequestedPath)
    }
    catch {
        throw 'INVALID_WORKTREE_PATH'
    }
    if ([string]::Equals($resolvedPath, $RepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'INVALID_WORKTREE_PATH'
    }

    $registeredWorktree = Get-RegisteredWorktree -ResolvedPath $resolvedPath
    if ($null -eq $registeredWorktree) {
        throw 'UNREGISTERED_WORKTREE_PATH'
    }
    if ($registeredWorktree.Branch -ne "refs/heads/$TaskBranch") {
        throw 'WORKTREE_BRANCH_MISMATCH'
    }

    $worktreeStatus = & git -C $resolvedPath status --porcelain 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'TASK_WORKTREE_STATUS_UNAVAILABLE'
    }
    if ($worktreeStatus) {
        throw 'TASK_WORKTREE_NOT_CLEAN'
    }

    return $resolvedPath
}

$root = Get-GitRepositoryRoot
if (-not $Apply -or $WhatIfPreference) {
    Assert-CleanupRepositoryState -TaskBranch $TaskBranch
    $previewWorktree = $null
    if ($PSBoundParameters.ContainsKey('WorktreePath')) {
        $previewWorktree = Resolve-TaskWorktree -RequestedPath $WorktreePath -RepositoryRoot $root -TaskBranch $TaskBranch
    }
    $actions = [System.Collections.Generic.List[string]]::new()
    $actions.Add('git fetch --prune origin')
    $actions.Add('git merge --ff-only origin/main')
    if ($previewWorktree) {
        $actions.Add("git worktree remove -- $previewWorktree")
    }
    $actions.Add("git branch -d -- $TaskBranch")
    foreach ($action in $actions) {
        Write-Output "WOULD_RUN: $action"
    }
    Write-Output 'CLEANUP_PREVIEW_OK'
    exit 0
}

& git fetch --prune origin
if ($LASTEXITCODE -ne 0) {
    throw 'REMOTE_REFRESH_FAILED'
}

Assert-CleanupRepositoryState -TaskBranch $TaskBranch
$resolvedWorktree = $null
if ($PSBoundParameters.ContainsKey('WorktreePath')) {
    $resolvedWorktree = Resolve-TaskWorktree -RequestedPath $WorktreePath -RepositoryRoot $root -TaskBranch $TaskBranch
}

& git merge --ff-only origin/main
if ($LASTEXITCODE -ne 0) {
    throw 'MAIN_FAST_FORWARD_FAILED'
}

if ($resolvedWorktree) {
    & git worktree remove -- $resolvedWorktree
    if ($LASTEXITCODE -ne 0) {
        throw 'WORKTREE_REMOVE_FAILED'
    }
}

& git branch -d -- $TaskBranch
if ($LASTEXITCODE -ne 0) {
    throw 'BRANCH_DELETE_FAILED'
}

Write-Output 'POST_MERGE_CLEANUP_OK'
