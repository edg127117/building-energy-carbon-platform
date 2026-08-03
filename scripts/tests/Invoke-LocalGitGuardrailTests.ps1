[CmdletBinding()]
param(
    [ValidateSet('LocalWorkflow', 'Cleanup', 'All')]
    [string]$Group = 'All'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$preCommitHook = Join-Path $repositoryRoot '.githooks\pre-commit'
$prePushHook = Join-Path $repositoryRoot '.githooks\pre-push'
$installerScript = Join-Path $repositoryRoot 'scripts\Install-GitGuardrails.ps1'
$preflightScript = Join-Path $repositoryRoot 'scripts\Invoke-TaskPreflight.ps1'
$cleanupScript = Join-Path $repositoryRoot 'scripts\Invoke-PostMergeCleanup.ps1'
$attributesFile = Join-Path $repositoryRoot '.gitattributes'
$powerShellCommand = Get-Command pwsh -ErrorAction SilentlyContinue
if (-not $powerShellCommand) {
    $powerShellCommand = Get-Command powershell.exe -ErrorAction Stop
}
$powerShellPath = $powerShellCommand.Source
$script:passedCases = 0
$script:temporaryRoots = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool]$Condition, [string]$Message)

    if (-not $Condition) {
        throw "ASSERTION_FAILED: $Message"
    }
}

function Assert-Contains {
    param([string]$Actual, [string]$Expected, [string]$Message)

    $actualWithoutConsoleWraps = $Actual -replace '[\r\n]', ''
    $expectedWithoutConsoleWraps = $Expected -replace '[\r\n]', ''
    if ($Actual -notlike "*$Expected*" -and $actualWithoutConsoleWraps -notlike "*$expectedWithoutConsoleWraps*") {
        throw "ASSERTION_FAILED: $Message`nEXPECTED: $Expected`nACTUAL:`n$Actual"
    }
}

function Assert-Result {
    param(
        [string]$Name,
        [psobject]$Result,
        [int]$ExpectedExitCode,
        [string]$ExpectedText
    )

    if ($Result.ExitCode -ne $ExpectedExitCode) {
        throw "CASE_FAILED: $Name`nEXPECTED_EXIT_CODE: $ExpectedExitCode`nACTUAL_EXIT_CODE: $($Result.ExitCode)`nOUTPUT:`n$($Result.Output)"
    }
    if ($ExpectedText) {
        Assert-Contains -Actual $Result.Output -Expected $ExpectedText -Message "$Name should contain the expected marker"
    }

    $script:passedCases++
    Write-Output "CASE_PASSED: $Name"
}

function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory,
        [AllowEmptyString()][string]$InputText
    )

    $previousLocation = Get-Location
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        if ($WorkingDirectory) {
            Set-Location -LiteralPath $WorkingDirectory
        }

        # Git writes normal progress and hook diagnostics to stderr. Capture those
        # diagnostics for assertions instead of letting the test-wide Stop setting
        # turn them into a premature terminating PowerShell error.
        $ErrorActionPreference = 'Continue'

        if ($PSBoundParameters.ContainsKey('InputText')) {
            $output = $InputText | & $FilePath @Arguments 2>&1 | Out-String
        }
        else {
            $output = & $FilePath @Arguments 2>&1 | Out-String
        }
        $exitCode = $LASTEXITCODE
        return [pscustomobject]@{
            ExitCode = $exitCode
            Output = $output
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Set-Location $previousLocation
    }
}

function Invoke-GitChecked {
    param([string]$WorkingDirectory, [string[]]$Arguments)

    $result = Invoke-Native -FilePath 'git' -Arguments $Arguments -WorkingDirectory $WorkingDirectory
    if ($result.ExitCode -ne 0) {
        throw "GIT_COMMAND_FAILED: git $($Arguments -join ' ')`n$($result.Output)"
    }
    return $result.Output.Trim()
}

function Invoke-PowerShellScript {
    param([string]$ScriptPath, [string[]]$Arguments, [string]$WorkingDirectory)

    $commandArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath)
    $commandArguments += $Arguments
    return Invoke-Native -FilePath $powerShellPath -Arguments $commandArguments -WorkingDirectory $WorkingDirectory
}

function New-TemporaryRoot {
    $path = Join-Path ([IO.Path]::GetTempPath()) ("iot-platform-demo-git-guardrails-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    $script:temporaryRoots.Add($path)
    return $path
}

function Add-GuardrailStub {
    param([string]$Repository)

    $stubDirectory = Join-Path $Repository 'scripts'
    New-Item -ItemType Directory -Path $stubDirectory -Force | Out-Null
    $stubPath = Join-Path $stubDirectory 'Test-RepositoryGuardrails.ps1'
    @'
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Staged', 'PullRequest')]
    [string]$Mode
)

if ($env:LOCAL_GUARDRAIL_STUB_FAIL -eq '1') {
    Write-Error 'STUB_REPOSITORY_GUARDRAILS_FAILED'
    exit 1
}

Write-Output "STUB_REPOSITORY_GUARDRAILS_OK: $Mode"
exit 0
'@ | Set-Content -LiteralPath $stubPath -Encoding UTF8
}

function Add-HookFiles {
    param([string]$Repository)

    $hookDirectory = Join-Path $Repository '.githooks'
    New-Item -ItemType Directory -Path $hookDirectory -Force | Out-Null
    Copy-Item -LiteralPath $preCommitHook -Destination (Join-Path $hookDirectory 'pre-commit') -Force
    Copy-Item -LiteralPath $prePushHook -Destination (Join-Path $hookDirectory 'pre-push') -Force
    Add-GuardrailStub -Repository $Repository
}

function New-TestRepository {
    param(
        [string[]]$MissingEntries = @(),
        [switch]$CopyHooks,
        [switch]$ConfigureHooks
    )

    $temporaryRoot = New-TemporaryRoot
    $repository = Join-Path $temporaryRoot 'repository'
    $remote = Join-Path $temporaryRoot 'remote.git'
    New-Item -ItemType Directory -Path $repository -Force | Out-Null

    Invoke-GitChecked -WorkingDirectory $repository -Arguments @('init', '--initial-branch=main', '--quiet') | Out-Null
    Invoke-GitChecked -WorkingDirectory $repository -Arguments @('config', 'user.name', 'Guardrail Test') | Out-Null
    Invoke-GitChecked -WorkingDirectory $repository -Arguments @('config', 'user.email', 'guardrail-test@example.invalid') | Out-Null

    foreach ($entry in @('AGENTS.md', 'PROJECT_GUIDE.md', 'PROJECT_STATUS.md')) {
        if ($MissingEntries -notcontains $entry) {
            Set-Content -LiteralPath (Join-Path $repository $entry) -Value "test entry: $entry" -Encoding UTF8
        }
    }
    Set-Content -LiteralPath (Join-Path $repository 'README.md') -Value 'temporary repository for local Git guardrail tests' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $repository -Arguments @('add', '--', '.') | Out-Null
    Invoke-GitChecked -WorkingDirectory $repository -Arguments @('commit', '--quiet', '-m', 'test: create baseline') | Out-Null

    Invoke-GitChecked -WorkingDirectory $temporaryRoot -Arguments @('init', '--bare', '--quiet', $remote) | Out-Null
    Invoke-GitChecked -WorkingDirectory $repository -Arguments @('remote', 'add', 'origin', $remote) | Out-Null
    Invoke-GitChecked -WorkingDirectory $repository -Arguments @('push', '--quiet', '-u', 'origin', 'main') | Out-Null

    if ($CopyHooks) {
        Add-HookFiles -Repository $repository
        Invoke-GitChecked -WorkingDirectory $repository -Arguments @('add', '--', '.githooks', 'scripts/Test-RepositoryGuardrails.ps1') | Out-Null
        Invoke-GitChecked -WorkingDirectory $repository -Arguments @('commit', '--quiet', '-m', 'test: add local hook stubs') | Out-Null
        Invoke-GitChecked -WorkingDirectory $repository -Arguments @('push', '--quiet', 'origin', 'main') | Out-Null
    }
    if ($ConfigureHooks) {
        Invoke-GitChecked -WorkingDirectory $repository -Arguments @('config', '--local', 'core.hooksPath', '.githooks') | Out-Null
    }

    return [pscustomobject]@{
        TemporaryRoot = $temporaryRoot
        Repository = $repository
        Remote = $remote
    }
}

function Switch-ToTaskBranch {
    param([string]$Repository, [string]$BranchName)

    Invoke-GitChecked -WorkingDirectory $Repository -Arguments @('switch', '--quiet', '-c', $BranchName) | Out-Null
}

function Assert-RequiredSources {
    $requiredSources = @(
        [pscustomobject]@{ Path = $preCommitHook; Label = '.githooks/pre-commit' },
        [pscustomobject]@{ Path = $prePushHook; Label = '.githooks/pre-push' },
        [pscustomobject]@{ Path = $installerScript; Label = 'scripts/Install-GitGuardrails.ps1' },
        [pscustomobject]@{ Path = $preflightScript; Label = 'scripts/Invoke-TaskPreflight.ps1' },
        [pscustomobject]@{ Path = $attributesFile; Label = '.gitattributes' }
    )
    if ($Group -in @('Cleanup', 'All')) {
        $requiredSources += [pscustomobject]@{ Path = $cleanupScript; Label = 'scripts/Invoke-PostMergeCleanup.ps1' }
    }

    foreach ($source in $requiredSources) {
        if (-not (Test-Path -LiteralPath $source.Path)) {
            throw "MISSING_SOURCE_FILE: $($source.Label)"
        }
    }

    $attributes = Get-Content -LiteralPath $attributesFile -Raw -Encoding UTF8
    Assert-Contains -Actual $attributes -Expected '.githooks/* text eol=lf' -Message 'Git attributes should keep hooks on LF'
    Assert-Contains -Actual $attributes -Expected '*.ps1 text eol=crlf' -Message 'Git attributes should declare stable PowerShell line endings'
}

function Invoke-LocalWorkflowTests {
    $mainRepository = New-TestRepository -CopyHooks -ConfigureHooks
    Set-Content -LiteralPath (Join-Path $mainRepository.Repository 'main-change.txt') -Value 'blocked commit' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $mainRepository.Repository -Arguments @('add', '--', 'main-change.txt') | Out-Null
    $result = Invoke-Native -FilePath 'git' -Arguments @('commit', '-m', 'test: blocked main commit') -WorkingDirectory $mainRepository.Repository
    Assert-Result -Name 'pre-commit rejects commits on main' -Result $result -ExpectedExitCode 1 -ExpectedText 'GIT_GUARDRAIL_BLOCKED: commits on main are forbidden'

    $taskRepository = New-TestRepository -CopyHooks -ConfigureHooks
    Switch-ToTaskBranch -Repository $taskRepository.Repository -BranchName 'chore/local-workflow'
    Set-Content -LiteralPath (Join-Path $taskRepository.Repository 'task-change.txt') -Value 'allowed task commit' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $taskRepository.Repository -Arguments @('add', '--', 'task-change.txt') | Out-Null
    $result = Invoke-Native -FilePath 'git' -Arguments @('commit', '-m', 'test: allowed task commit') -WorkingDirectory $taskRepository.Repository
    Assert-Result -Name 'pre-commit invokes the controllable staged guardrail stub on task branches' -Result $result -ExpectedExitCode 0 -ExpectedText 'STUB_REPOSITORY_GUARDRAILS_OK: Staged'

    $pushRepository = New-TestRepository -CopyHooks -ConfigureHooks
    Switch-ToTaskBranch -Repository $pushRepository.Repository -BranchName 'chore/push-source'
    Set-Content -LiteralPath (Join-Path $pushRepository.Repository 'push-change.txt') -Value 'push target update' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $pushRepository.Repository -Arguments @('add', '--', 'push-change.txt') | Out-Null
    Invoke-GitChecked -WorkingDirectory $pushRepository.Repository -Arguments @('commit', '--quiet', '-m', 'test: prepare push target') | Out-Null
    $result = Invoke-Native -FilePath 'git' -Arguments @('push', 'origin', 'HEAD:main') -WorkingDirectory $pushRepository.Repository
    Assert-Result -Name 'pre-push rejects HEAD to remote main' -Result $result -ExpectedExitCode 1 -ExpectedText 'GIT_GUARDRAIL_BLOCKED: direct push to remote main is forbidden'
    $result = Invoke-Native -FilePath 'git' -Arguments @('push', '--quiet', 'origin', 'HEAD:refs/heads/chore/push-source') -WorkingDirectory $pushRepository.Repository
    Assert-Result -Name 'pre-push allows a task branch target' -Result $result -ExpectedExitCode 0 -ExpectedText ''

    $installerRepository = New-TestRepository -CopyHooks
    $globalBefore = Invoke-Native -FilePath 'git' -Arguments @('config', '--global', '--get', 'core.hooksPath') -WorkingDirectory $installerRepository.Repository
    $result = Invoke-PowerShellScript -ScriptPath $installerScript -Arguments @() -WorkingDirectory $installerRepository.Repository
    Assert-Result -Name 'installer configures hooks only for the current repository' -Result $result -ExpectedExitCode 0 -ExpectedText 'GIT_GUARDRAILS_INSTALLED'
    $localHookPath = Invoke-GitChecked -WorkingDirectory $installerRepository.Repository -Arguments @('config', '--local', '--get', 'core.hooksPath')
    Assert-True -Condition ($localHookPath -eq '.githooks') -Message 'installer should set the local hooks path to .githooks'
    $globalAfter = Invoke-Native -FilePath 'git' -Arguments @('config', '--global', '--get', 'core.hooksPath') -WorkingDirectory $installerRepository.Repository
    Assert-True -Condition ($globalBefore.ExitCode -eq $globalAfter.ExitCode -and $globalBefore.Output -eq $globalAfter.Output) -Message 'installer must not change the global hooks path'

    $mainPreflightRepository = New-TestRepository -CopyHooks -ConfigureHooks
    $result = Invoke-PowerShellScript -ScriptPath $preflightScript -Arguments @() -WorkingDirectory $mainPreflightRepository.Repository
    Assert-Result -Name 'preflight rejects main' -Result $result -ExpectedExitCode 1 -ExpectedText 'INVALID_TASK_BRANCH: main'

    $cleanPreflightRepository = New-TestRepository -CopyHooks -ConfigureHooks
    Switch-ToTaskBranch -Repository $cleanPreflightRepository.Repository -BranchName 'chore/clean-preflight'
    $result = Invoke-PowerShellScript -ScriptPath $preflightScript -Arguments @() -WorkingDirectory $cleanPreflightRepository.Repository
    Assert-Result -Name 'preflight accepts a clean task branch with an installed hook and current baseline' -Result $result -ExpectedExitCode 0 -ExpectedText 'TASK_PREFLIGHT_OK'

    $dirtyPreflightRepository = New-TestRepository -CopyHooks -ConfigureHooks
    Switch-ToTaskBranch -Repository $dirtyPreflightRepository.Repository -BranchName 'chore/dirty-preflight'
    Set-Content -LiteralPath (Join-Path $dirtyPreflightRepository.Repository 'uncommitted.txt') -Value 'dirty' -Encoding UTF8
    $result = Invoke-PowerShellScript -ScriptPath $preflightScript -Arguments @() -WorkingDirectory $dirtyPreflightRepository.Repository
    Assert-Result -Name 'preflight rejects a dirty worktree' -Result $result -ExpectedExitCode 1 -ExpectedText 'WORKTREE_NOT_CLEAN'

    $missingEntryRepository = New-TestRepository -MissingEntries @('PROJECT_GUIDE.md') -CopyHooks -ConfigureHooks
    Switch-ToTaskBranch -Repository $missingEntryRepository.Repository -BranchName 'chore/missing-entry'
    $result = Invoke-PowerShellScript -ScriptPath $preflightScript -Arguments @() -WorkingDirectory $missingEntryRepository.Repository
    Assert-Result -Name 'preflight rejects missing fixed rule entries' -Result $result -ExpectedExitCode 1 -ExpectedText 'MISSING_RULE_ENTRY: PROJECT_GUIDE.md'

    $uninstalledRepository = New-TestRepository -CopyHooks
    Switch-ToTaskBranch -Repository $uninstalledRepository.Repository -BranchName 'chore/uninstalled-hook'
    $result = Invoke-PowerShellScript -ScriptPath $preflightScript -Arguments @() -WorkingDirectory $uninstalledRepository.Repository
    Assert-Result -Name 'preflight rejects an uninstalled hook path' -Result $result -ExpectedExitCode 1 -ExpectedText 'GIT_HOOKS_NOT_INSTALLED'

    $staleRepository = New-TestRepository -CopyHooks -ConfigureHooks
    Switch-ToTaskBranch -Repository $staleRepository.Repository -BranchName 'chore/stale-baseline'
    Invoke-GitChecked -WorkingDirectory $staleRepository.Repository -Arguments @('config', '--local', '--unset', 'core.hooksPath') | Out-Null
    Invoke-GitChecked -WorkingDirectory $staleRepository.Repository -Arguments @('switch', '--quiet', 'main') | Out-Null
    Set-Content -LiteralPath (Join-Path $staleRepository.Repository 'upstream-change.txt') -Value 'upstream only' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $staleRepository.Repository -Arguments @('add', '--', 'upstream-change.txt') | Out-Null
    Invoke-GitChecked -WorkingDirectory $staleRepository.Repository -Arguments @('commit', '--quiet', '-m', 'test: advance origin main') | Out-Null
    Invoke-GitChecked -WorkingDirectory $staleRepository.Repository -Arguments @('push', '--quiet', 'origin', 'main') | Out-Null
    Invoke-GitChecked -WorkingDirectory $staleRepository.Repository -Arguments @('switch', '--quiet', 'chore/stale-baseline') | Out-Null
    Invoke-GitChecked -WorkingDirectory $staleRepository.Repository -Arguments @('config', '--local', 'core.hooksPath', '.githooks') | Out-Null
    $result = Invoke-PowerShellScript -ScriptPath $preflightScript -Arguments @() -WorkingDirectory $staleRepository.Repository
    Assert-Result -Name 'preflight rejects a branch behind origin main' -Result $result -ExpectedExitCode 1 -ExpectedText 'OUTDATED_OR_DIVERGED_BASELINE'
}

function New-CleanupScenario {
    param([switch]$AddTaskWorktree, [switch]$AddOtherWorktree)

    $scenario = New-TestRepository
    $taskBranch = 'chore/cleanup-target'
    Switch-ToTaskBranch -Repository $scenario.Repository -BranchName $taskBranch
    Set-Content -LiteralPath (Join-Path $scenario.Repository 'merged-task.txt') -Value 'merged task' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $scenario.Repository -Arguments @('add', '--', 'merged-task.txt') | Out-Null
    Invoke-GitChecked -WorkingDirectory $scenario.Repository -Arguments @('commit', '--quiet', '-m', 'test: merged cleanup task') | Out-Null
    Invoke-GitChecked -WorkingDirectory $scenario.Repository -Arguments @('switch', '--quiet', 'main') | Out-Null
    Invoke-GitChecked -WorkingDirectory $scenario.Repository -Arguments @('merge', '--ff-only', $taskBranch) | Out-Null
    Invoke-GitChecked -WorkingDirectory $scenario.Repository -Arguments @('push', '--quiet', 'origin', 'main') | Out-Null

    $taskWorktree = $null
    $otherWorktree = $null
    if ($AddTaskWorktree) {
        $taskWorktree = Join-Path $scenario.TemporaryRoot 'cleanup-target-worktree'
        Invoke-GitChecked -WorkingDirectory $scenario.Repository -Arguments @('worktree', 'add', '--quiet', $taskWorktree, $taskBranch) | Out-Null
    }
    if ($AddOtherWorktree) {
        $otherWorktree = Join-Path $scenario.TemporaryRoot 'cleanup-other-worktree'
        Invoke-GitChecked -WorkingDirectory $scenario.Repository -Arguments @('worktree', 'add', '--quiet', '-b', 'chore/cleanup-other', $otherWorktree, 'origin/main') | Out-Null
    }

    return [pscustomobject]@{
        TemporaryRoot = $scenario.TemporaryRoot
        Repository = $scenario.Repository
        Remote = $scenario.Remote
        TaskBranch = $taskBranch
        TaskWorktree = $taskWorktree
        OtherWorktree = $otherWorktree
    }
}

function Advance-RemoteMain {
    param([psobject]$Scenario)

    $upstreamClone = Join-Path $Scenario.TemporaryRoot 'upstream-clone'
    Invoke-GitChecked -WorkingDirectory $Scenario.TemporaryRoot -Arguments @('clone', '--quiet', '--branch', 'main', $Scenario.Remote, $upstreamClone) | Out-Null
    Invoke-GitChecked -WorkingDirectory $upstreamClone -Arguments @('config', 'user.name', 'Guardrail Upstream Test') | Out-Null
    Invoke-GitChecked -WorkingDirectory $upstreamClone -Arguments @('config', 'user.email', 'guardrail-upstream@example.invalid') | Out-Null
    Set-Content -LiteralPath (Join-Path $upstreamClone 'upstream-after-merge.txt') -Value 'remote update after task merge' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $upstreamClone -Arguments @('add', '--', 'upstream-after-merge.txt') | Out-Null
    Invoke-GitChecked -WorkingDirectory $upstreamClone -Arguments @('commit', '--quiet', '-m', 'test: advance remote main after merge') | Out-Null
    Invoke-GitChecked -WorkingDirectory $upstreamClone -Arguments @('push', '--quiet', 'origin', 'main') | Out-Null
}

function Invoke-CleanupTests {
    $previewScenario = New-CleanupScenario -AddTaskWorktree -AddOtherWorktree
    Advance-RemoteMain -Scenario $previewScenario
    $taskRefBefore = Invoke-GitChecked -WorkingDirectory $previewScenario.Repository -Arguments @('rev-parse', $previewScenario.TaskBranch)
    $originMainBefore = Invoke-GitChecked -WorkingDirectory $previewScenario.Repository -Arguments @('rev-parse', 'origin/main')
    $result = Invoke-PowerShellScript -ScriptPath $cleanupScript -Arguments @('-TaskBranch', $previewScenario.TaskBranch, '-WorktreePath', $previewScenario.TaskWorktree) -WorkingDirectory $previewScenario.Repository
    Assert-Result -Name 'cleanup preview does not alter the task branch or registered worktree' -Result $result -ExpectedExitCode 0 -ExpectedText 'CLEANUP_PREVIEW_OK'
    Assert-True -Condition (Test-Path -LiteralPath $previewScenario.TaskWorktree) -Message 'cleanup preview must retain the task worktree'
    Assert-True -Condition ((Invoke-GitChecked -WorkingDirectory $previewScenario.Repository -Arguments @('rev-parse', $previewScenario.TaskBranch)) -eq $taskRefBefore) -Message 'cleanup preview must retain the task branch reference'
    Assert-True -Condition ((Invoke-GitChecked -WorkingDirectory $previewScenario.Repository -Arguments @('rev-parse', 'origin/main')) -eq $originMainBefore) -Message 'cleanup preview must not refresh remote tracking references'

    $result = Invoke-PowerShellScript -ScriptPath $cleanupScript -Arguments @('-TaskBranch', $previewScenario.TaskBranch, '-WorktreePath', $previewScenario.TaskWorktree, '-Apply') -WorkingDirectory $previewScenario.Repository
    Assert-Result -Name 'cleanup apply removes only the merged target worktree and branch' -Result $result -ExpectedExitCode 0 -ExpectedText 'POST_MERGE_CLEANUP_OK'
    Assert-True -Condition (-not (Test-Path -LiteralPath $previewScenario.TaskWorktree)) -Message 'cleanup apply should remove the exact task worktree'
    Assert-True -Condition (Test-Path -LiteralPath $previewScenario.OtherWorktree) -Message 'cleanup apply must retain unrelated registered worktrees'
    $deletedBranch = Invoke-Native -FilePath 'git' -Arguments @('show-ref', '--verify', '--quiet', "refs/heads/$($previewScenario.TaskBranch)") -WorkingDirectory $previewScenario.Repository
    Assert-True -Condition ($deletedBranch.ExitCode -ne 0) -Message 'cleanup apply should delete the exact merged task branch'
    $otherBranch = Invoke-Native -FilePath 'git' -Arguments @('show-ref', '--verify', '--quiet', 'refs/heads/chore/cleanup-other') -WorkingDirectory $previewScenario.Repository
    Assert-True -Condition ($otherBranch.ExitCode -eq 0) -Message 'cleanup apply must retain unrelated local branches'

    $remoteMergedRepository = New-TestRepository
    Switch-ToTaskBranch -Repository $remoteMergedRepository.Repository -BranchName 'chore/remote-merged-cleanup'
    Set-Content -LiteralPath (Join-Path $remoteMergedRepository.Repository 'remote-merged-task.txt') -Value 'merged by remote PR' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $remoteMergedRepository.Repository -Arguments @('add', '--', 'remote-merged-task.txt') | Out-Null
    Invoke-GitChecked -WorkingDirectory $remoteMergedRepository.Repository -Arguments @('commit', '--quiet', '-m', 'test: task merged remotely') | Out-Null
    Invoke-GitChecked -WorkingDirectory $remoteMergedRepository.Repository -Arguments @('push', '--quiet', 'origin', 'chore/remote-merged-cleanup') | Out-Null
    Invoke-GitChecked -WorkingDirectory $remoteMergedRepository.Repository -Arguments @('switch', '--quiet', 'main') | Out-Null
    $remoteMergeClone = Join-Path $remoteMergedRepository.TemporaryRoot 'remote-merge-clone'
    Invoke-GitChecked -WorkingDirectory $remoteMergedRepository.TemporaryRoot -Arguments @('clone', '--quiet', '--branch', 'main', $remoteMergedRepository.Remote, $remoteMergeClone) | Out-Null
    Invoke-GitChecked -WorkingDirectory $remoteMergeClone -Arguments @('config', 'user.name', 'Guardrail Remote Merge Test') | Out-Null
    Invoke-GitChecked -WorkingDirectory $remoteMergeClone -Arguments @('config', 'user.email', 'guardrail-remote-merge@example.invalid') | Out-Null
    Invoke-GitChecked -WorkingDirectory $remoteMergeClone -Arguments @('merge', '--ff-only', 'origin/chore/remote-merged-cleanup') | Out-Null
    Invoke-GitChecked -WorkingDirectory $remoteMergeClone -Arguments @('push', '--quiet', 'origin', 'main') | Out-Null
    $cachedMergeCheck = Invoke-Native -FilePath 'git' -Arguments @('merge-base', '--is-ancestor', 'chore/remote-merged-cleanup', 'origin/main') -WorkingDirectory $remoteMergedRepository.Repository
    Assert-True -Condition ($cachedMergeCheck.ExitCode -ne 0) -Message 'fixture must keep the original origin/main stale before cleanup apply'
    $result = Invoke-PowerShellScript -ScriptPath $cleanupScript -Arguments @('-TaskBranch', 'chore/remote-merged-cleanup', '-Apply') -WorkingDirectory $remoteMergedRepository.Repository
    Assert-Result -Name 'cleanup apply refreshes origin before checking a remotely merged task branch' -Result $result -ExpectedExitCode 0 -ExpectedText 'POST_MERGE_CLEANUP_OK'

    $unmergedRepository = New-TestRepository
    Switch-ToTaskBranch -Repository $unmergedRepository.Repository -BranchName 'chore/unmerged-cleanup'
    Set-Content -LiteralPath (Join-Path $unmergedRepository.Repository 'unmerged-task.txt') -Value 'unmerged task' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $unmergedRepository.Repository -Arguments @('add', '--', 'unmerged-task.txt') | Out-Null
    Invoke-GitChecked -WorkingDirectory $unmergedRepository.Repository -Arguments @('commit', '--quiet', '-m', 'test: unmerged task') | Out-Null
    Invoke-GitChecked -WorkingDirectory $unmergedRepository.Repository -Arguments @('switch', '--quiet', 'main') | Out-Null
    $result = Invoke-PowerShellScript -ScriptPath $cleanupScript -Arguments @('-TaskBranch', 'chore/unmerged-cleanup', '-Apply') -WorkingDirectory $unmergedRepository.Repository
    Assert-Result -Name 'cleanup rejects an unmerged task branch' -Result $result -ExpectedExitCode 1 -ExpectedText 'TASK_BRANCH_NOT_MERGED'

    $dirtyScenario = New-CleanupScenario -AddTaskWorktree
    Set-Content -LiteralPath (Join-Path $dirtyScenario.TaskWorktree 'uncommitted.txt') -Value 'dirty registered worktree' -Encoding UTF8
    $result = Invoke-PowerShellScript -ScriptPath $cleanupScript -Arguments @('-TaskBranch', $dirtyScenario.TaskBranch, '-WorktreePath', $dirtyScenario.TaskWorktree, '-Apply') -WorkingDirectory $dirtyScenario.Repository
    Assert-Result -Name 'cleanup rejects a dirty registered task worktree' -Result $result -ExpectedExitCode 1 -ExpectedText 'TASK_WORKTREE_NOT_CLEAN'

    $unregisteredScenario = New-CleanupScenario
    $unregisteredPath = Join-Path $unregisteredScenario.TemporaryRoot 'not-a-registered-worktree'
    New-Item -ItemType Directory -Path $unregisteredPath -Force | Out-Null
    $result = Invoke-PowerShellScript -ScriptPath $cleanupScript -Arguments @('-TaskBranch', $unregisteredScenario.TaskBranch, '-WorktreePath', $unregisteredPath) -WorkingDirectory $unregisteredScenario.Repository
    Assert-Result -Name 'cleanup rejects an unregistered worktree path' -Result $result -ExpectedExitCode 1 -ExpectedText 'UNREGISTERED_WORKTREE_PATH'

    $divergedScenario = New-CleanupScenario
    Set-Content -LiteralPath (Join-Path $divergedScenario.Repository 'local-main-only.txt') -Value 'local main divergence' -Encoding UTF8
    Invoke-GitChecked -WorkingDirectory $divergedScenario.Repository -Arguments @('add', '--', 'local-main-only.txt') | Out-Null
    Invoke-GitChecked -WorkingDirectory $divergedScenario.Repository -Arguments @('commit', '--quiet', '-m', 'test: diverge local main') | Out-Null
    $result = Invoke-PowerShellScript -ScriptPath $cleanupScript -Arguments @('-TaskBranch', $divergedScenario.TaskBranch) -WorkingDirectory $divergedScenario.Repository
    Assert-Result -Name 'cleanup rejects a main branch that cannot fast-forward' -Result $result -ExpectedExitCode 1 -ExpectedText 'MAIN_CANNOT_FAST_FORWARD'
}

function Remove-TemporaryRoots {
    $temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    foreach ($root in $script:temporaryRoots) {
        $resolvedRoot = [IO.Path]::GetFullPath($root)
        $temporaryPrefix = $temporaryBase + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedRoot.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "REFUSING_TO_DELETE_NON_TEMPORARY_TEST_PATH: $resolvedRoot"
        }
        if (Test-Path -LiteralPath $resolvedRoot) {
            Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
        }
    }
}

try {
    Assert-RequiredSources
    if ($Group -in @('LocalWorkflow', 'All')) {
        Invoke-LocalWorkflowTests
    }
    if ($Group -in @('Cleanup', 'All')) {
        Invoke-CleanupTests
    }
    Write-Output "GUARDRAIL_TESTS_PASSED: $script:passedCases local cases"
}
finally {
    Remove-TemporaryRoots
}
