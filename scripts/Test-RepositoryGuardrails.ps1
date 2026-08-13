[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Staged', 'PullRequest')]
    [string]$Mode,
    [string]$BaseRef = 'origin/main',
    [string]$HeadRef = 'HEAD',
    [string]$PullRequestBody = $env:PR_BODY
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$errors = [System.Collections.Generic.List[string]]::new()

function Add-GuardrailError {
    param([string]$Rule, [string]$Detail)
    $errors.Add("GUARDRAIL_ERROR: ${Rule}: $Detail")
}

function Get-RepositoryRoot {
    $root = (& git rev-parse --show-toplevel 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) {
        throw 'NOT_A_GIT_WORKTREE'
    }
    return $root
}

function Get-ChangedFiles {
    param([string]$CheckMode, [string]$Base, [string]$Head)
    if ($CheckMode -eq 'Staged') {
        $rows = & git diff --cached --name-status --diff-filter=ACMR 2>&1
    }
    else {
        $rows = & git diff --name-status --diff-filter=ACMR "$Base...$Head" 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        throw "GIT_DIFF_FAILED: $($rows | Out-String)"
    }

    $files = [System.Collections.Generic.List[object]]::new()
    foreach ($row in $rows) {
        if ([string]::IsNullOrWhiteSpace($row)) { continue }
        $parts = $row -split "`t"
        $files.Add([pscustomobject]@{
            status = $parts[0]
            path = $parts[-1].Replace('\', '/')
        })
    }
    return $files.ToArray()
}

function Get-FileContent {
    param([string]$RepositoryRoot, [string]$Path, [string]$CheckMode)
    if ($CheckMode -eq 'Staged') {
        $content = & git show ":$Path" 2>&1
        if ($LASTEXITCODE -ne 0) { return $null }
        return ($content -join "`n")
    }
    $absolutePath = Join-Path $RepositoryRoot $Path
    if (-not (Test-Path -LiteralPath $absolutePath)) { return $null }
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $absolutePath
}

function Test-ForbiddenPath {
    param([string]$Path)
    $isJavaSourceTargetPackage = $Path -match '^src/(?:main|test)/java/.+/target(/|$)'
    if ($Path -match '(^|/)target(/|$)' -and -not $isJavaSourceTargetPackage) { return $true }
    if ($Path -match '(^|/)(?:node_modules|\.tools|\.npm-cache)(/|$)') { return $true }
    if ($Path -match '^web/dist(/|$)') { return $true }
    if ($Path -match '^src/env/(?:mysql-data|taos-data|redis-data)(/|$)') { return $true }
    if ($Path -in @('server.env', 'web/.env', 'token.txt')) { return $true }
    if ($Path -match '(^|/)\.env\.(?!example$)[^/]+$') { return $true }
    if ($Path -match '(^|/)(?:id_rsa|id_ed25519|credentials(?:\.[^/]+)?|secrets?(?:\.[^/]+)?)$') { return $true }
    if ($Path -match '\.(?:pem|p12|pfx|key)$') { return $true }
    return $false
}

function Test-JavaClassJavadoc {
    param([string]$Content)
    if ([string]::IsNullOrWhiteSpace($Content)) { return $false }
    return $Content -match '(?s)/\*\*.+?\*/\s*(?:(?:public|protected|private|abstract|final|static)\s+)*(?:class|interface|enum|record)\s+[A-Za-z_$]'
}

function Test-FrontendBusinessComment {
    param([string]$Content)
    if ([string]::IsNullOrWhiteSpace($Content)) { return $false }
    foreach ($match in [regex]::Matches($Content, '(?ms)/\*.*?\*/|(?m)//[^\r\n]*|(?s)<!--.*?-->')) {
        $text = $match.Value -replace '(?s)^\s*/\*\*?|\*/\s*$|^\s*//|^\s*<!--|-->\s*$', ''
        $text = ($text -replace '[\s*]+', ' ').Trim()
        if ($text.Length -ge 6) { return $true }
    }
    return $false
}

function Test-IsProductionPath {
    param([string]$Path)
    if ($Path -match '^src/main/java/.+\.java$') { return $true }
    return $Path -match '^web/src/.+\.(?:vue|ts)$' -and
        $Path -notmatch '(^|/)(?:__tests__|tests?)/' -and
        $Path -notmatch '\.(?:test|spec)\.(?:ts|tsx)$'
}

function Get-HistoricalDocumentPaths {
    $rows = & git ls-files -- 'docs/superpowers/specs/*.md' 'docs/superpowers/plans/*.md' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "GIT_LS_FILES_FAILED: $($rows | Out-String)"
    }
    return @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Replace('\', '/') })
}

function Test-HistoricalDocumentContracts {
    param([string]$RepositoryRoot, [string]$CheckMode)

    foreach ($path in @(Get-HistoricalDocumentPaths)) {
        $content = Get-FileContent $RepositoryRoot $path $CheckMode
        if ([string]::IsNullOrWhiteSpace($content)) {
            Add-GuardrailError 'HISTORICAL_DOC_STATUS_MISSING' $path
            continue
        }

        $normalized = $content.Replace("`r`n", "`n").Replace("`r", "`n")
        $header = (($normalized -split "`n") | Select-Object -First 10) -join "`n"
        $expectedStatus = if ($path -match '^docs/superpowers/specs/.+\.md$') {
            '文档状态：历史任务设计记录'
        }
        else {
            '文档状态：历史任务实施计划'
        }

        if ($header -notmatch [regex]::Escape($expectedStatus)) {
            Add-GuardrailError 'HISTORICAL_DOC_STATUS_MISSING' $path
        }
        if ($header -notmatch '\]\(\.\./README\.md\)') {
            Add-GuardrailError 'HISTORICAL_DOC_INDEX_LINK_MISSING' $path
        }
        if ($header -notmatch '\]\(\.\./\.\./\.\./PROJECT_STATUS\.md\)') {
            Add-GuardrailError 'HISTORICAL_DOC_CURRENT_STATUS_LINK_MISSING' $path
        }
    }
}

function Get-PrSection {
    param([string]$Body, [string]$Heading)
    $pattern = '(?ms)^##\s+' + [regex]::Escape($Heading) + '\s*\r?\n(?<content>.*?)(?=^##\s+|\z)'
    $match = [regex]::Match($Body, $pattern)
    if (-not $match.Success) { return $null }
    return $match.Groups['content'].Value.Trim()
}

function Test-PrSections {
    param([string]$Body, [int]$ProductionFileCount)
    if ([string]::IsNullOrWhiteSpace($Body)) {
        Add-GuardrailError 'PR_BODY_MISSING' 'pull request body is empty'
        return
    }

    $normalizedBody = $Body.Replace("`r`n", "`n").Replace("`r", "`n")
    $withoutInstructions = [regex]::Replace($normalizedBody, '(?s)<!--.*?-->', '').Trim()
    $usesUnifiedContract = $withoutInstructions -match '(?m)^##\s+(状态影响|验证结果|文档与 ADR|风险与未验证项)\s*$'
    if ($usesUnifiedContract) {
        $unifiedHeadings = @('变更内容', '状态影响', '验证结果', '注释检查', '文档与 ADR', '风险与未验证项')
        foreach ($heading in $unifiedHeadings) {
            $section = Get-PrSection $withoutInstructions $heading
            if ([string]::IsNullOrWhiteSpace($section)) {
                Add-GuardrailError 'PR_SECTION_MISSING' $heading
            }
        }

        foreach ($heading in @('状态影响', '验证结果', '文档与 ADR')) {
            $section = Get-PrSection $withoutInstructions $heading
            if (-not [string]::IsNullOrWhiteSpace($section) -and
                $section -notmatch '(?im)^\s*-\s*\[[xX]\]') {
                Add-GuardrailError 'PR_SECTION_SELECTION_MISSING' $heading
            }
        }

        $requiredFields = @{
            '状态影响' = @('说明')
            '验证结果' = @('专项范围', '实际命令与结果', '未执行及原因')
            '文档与 ADR' = @('说明')
            '风险与未验证项' = @('风险', '未验证项')
        }
        foreach ($heading in $requiredFields.Keys) {
            $section = Get-PrSection $withoutInstructions $heading
            if ([string]::IsNullOrWhiteSpace($section)) { continue }
            foreach ($field in $requiredFields[$heading]) {
                if ($section -notmatch ('(?m)^\s*' + [regex]::Escape($field) + '\s*[：:]\s*\S[^\r\n]*$')) {
                    Add-GuardrailError 'PR_FIELD_MISSING' "$heading/$field"
                }
            }
        }
    }
    else {
        # 已打开的旧格式 PR 保持兼容；新 PR 由仓库模板进入统一合同。
        foreach ($heading in @('变更内容', '测试', '注释检查', '文档同步')) {
            $section = Get-PrSection $withoutInstructions $heading
            if ([string]::IsNullOrWhiteSpace($section)) {
                Add-GuardrailError 'PR_SECTION_MISSING' $heading
            }
        }

        $documentSection = Get-PrSection $withoutInstructions '文档同步'
        if (-not [string]::IsNullOrWhiteSpace($documentSection)) {
            if ($documentSection -notmatch '(?m)^\s*状态影响\s*[：:]\s*(有|无)\s*$') {
                Add-GuardrailError 'DOCUMENT_STATUS_IMPACT_MISSING' 'expected 有 or 无'
            }
            if ($documentSection -notmatch '(?m)^[ \t]*检查范围[ \t]*[：:][ \t]*\S[^\r\n]*$') {
                Add-GuardrailError 'DOCUMENT_SCOPE_MISSING' 'describe current documents and code or test evidence checked'
            }
            if ($documentSection -notmatch '(?m)^[ \t]*结论[ \t]*[：:][ \t]*\S[^\r\n]*$') {
                Add-GuardrailError 'DOCUMENT_RESULT_MISSING' 'describe updated current documents or why no update is required'
            }
        }
    }

    $commentSection = Get-PrSection $withoutInstructions '注释检查'
    if ([string]::IsNullOrWhiteSpace($commentSection)) { return }

    # 已经打开且仍使用旧内嵌 comment-audit 的 PR 保持兼容，不要求回写历史格式。
    if ($normalizedBody -match '<!--\s*comment-audit:file=[^>]+-->' -and
        $normalizedBody -match '<!--\s*comment-audit:end-file\s*-->') { return }

    $riskMatch = [regex]::Match($commentSection, '(?m)^\s*风险级别\s*[：:]\s*(?<value>高|普通|低|不涉及生产代码)\s*$')
    if (-not $riskMatch.Success) {
        Add-GuardrailError 'COMMENT_RISK_LEVEL_MISSING' 'expected 高, 普通, 低, or 不涉及生产代码'
    }
    elseif ($ProductionFileCount -gt 0 -and $riskMatch.Groups['value'].Value -eq '不涉及生产代码') {
        Add-GuardrailError 'COMMENT_RISK_LEVEL_INVALID' 'production code changed'
    }
    elseif ($ProductionFileCount -eq 0 -and $riskMatch.Groups['value'].Value -ne '不涉及生产代码') {
        Add-GuardrailError 'COMMENT_RISK_LEVEL_INVALID' 'no production code changed'
    }

    if ($commentSection -notmatch '(?m)^[ \t]*检查范围[ \t]*[：:][ \t]*\S[^\r\n]*$') {
        Add-GuardrailError 'COMMENT_SCOPE_MISSING' 'describe files, call chain, affected methods, or nearby changes'
    }
    if ($commentSection -notmatch '(?m)^[ \t]*结论[ \t]*[：:][ \t]*\S[^\r\n]*$') {
        Add-GuardrailError 'COMMENT_RESULT_MISSING' 'describe comment changes or why no change is required'
    }
}

function Test-HighConfidenceCommentFindings {
    param([string]$Base, [string]$Head)
    $scanner = Join-Path $PSScriptRoot 'New-CommentAuditReport.ps1'
    if (-not (Test-Path -LiteralPath $scanner)) {
        Add-GuardrailError 'COMMENT_SCANNER_MISSING' $scanner
        return
    }
    try {
        $json = & $scanner -BaseRef $Base -HeadRef $Head -Format Json 2>&1 | Out-String
        $parsedReports = $json | ConvertFrom-Json
    }
    catch {
        Add-GuardrailError 'COMMENT_SCANNER_FAILED' $_.Exception.Message
        return
    }
    foreach ($report in @($parsedReports)) {
        if ($null -eq $report) { continue }
        foreach ($finding in @($report.commentFindings)) {
            Add-GuardrailError 'STALE_OR_LOW_VALUE_COMMENT' "$($report.path):L$($finding.line) $($finding.rule)"
        }
    }
}

$repositoryRoot = Get-RepositoryRoot
Set-Location $repositoryRoot
$changedFiles = @(Get-ChangedFiles $Mode $BaseRef $HeadRef)

Test-HistoricalDocumentContracts $repositoryRoot $Mode

foreach ($file in $changedFiles) {
    if (Test-ForbiddenPath $file.path) {
        Add-GuardrailError 'FORBIDDEN_PATH' $file.path
    }
    if ($file.status -like 'A*' -and $file.path -match '^src/main/java/.+\.java$') {
        $content = Get-FileContent $repositoryRoot $file.path $Mode
        if (-not (Test-JavaClassJavadoc $content)) {
            Add-GuardrailError 'JAVA_CLASS_JAVADOC_MISSING' $file.path
        }
    }
    if ($file.status -like 'A*' -and
        $file.path -match '^web/src/(?:pages|components|composables|stores|api|router|types)/.+\.(?:vue|ts)$' -and
        $file.path -notmatch '(^|/)(?:__tests__|tests?)/' -and
        $file.path -notmatch '\.(?:test|spec)\.(?:ts|tsx)$') {
        $content = Get-FileContent $repositoryRoot $file.path $Mode
        if (-not (Test-FrontendBusinessComment $content)) {
            Add-GuardrailError 'FRONTEND_BUSINESS_COMMENT_MISSING' $file.path
        }
    }
}

if ($Mode -eq 'PullRequest') {
    $productionFiles = @($changedFiles | Where-Object { Test-IsProductionPath $_.path })
    Test-PrSections $PullRequestBody $productionFiles.Count
    if ($productionFiles.Count -gt 0) {
        Test-HighConfidenceCommentFindings $BaseRef $HeadRef
    }
}

if ($errors.Count -gt 0) {
    foreach ($guardrailError in $errors) {
        [Console]::Error.WriteLine($guardrailError)
    }
    exit 1
}

Write-Output 'REPOSITORY_GUARDRAILS_OK'
