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
$script:AuditDocumentPathPattern = '^docs/reviews/comment-audits/(?<year>\d{4})/(?<dateYear>\d{4})-(?<month>\d{2})-(?<day>\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$'

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

function Get-PrSection {
    param([string]$Body, [string]$Heading)
    $pattern = '(?ms)^##\s+' + [regex]::Escape($Heading) + '\s*\r?\n(?<content>.*?)(?=^##\s+|\z)'
    $match = [regex]::Match($Body, $pattern)
    if (-not $match.Success) { return $null }
    return $match.Groups['content'].Value.Trim()
}

function Test-PrSections {
    param([string]$Body)
    if ([string]::IsNullOrWhiteSpace($Body)) {
        Add-GuardrailError 'PR_BODY_MISSING' 'pull request body is empty'
        return
    }
    $withoutInstructions = [regex]::Replace($Body, '(?s)<!--.*?-->', '').Trim()
    foreach ($heading in @('变更内容', '测试')) {
        $section = Get-PrSection $withoutInstructions $heading
        if ([string]::IsNullOrWhiteSpace($section)) {
            Add-GuardrailError 'PR_SECTION_MISSING' $heading
        }
    }
    $auditSection = Get-PrSection $withoutInstructions '注释审计'
    if ([string]::IsNullOrWhiteSpace($auditSection) -and $Body -match '<!--\s*comment-audit:file=') {
        $auditSection = Get-PrSection $withoutInstructions '注释检查'
    }
    if ([string]::IsNullOrWhiteSpace($auditSection)) {
        Add-GuardrailError 'PR_SECTION_MISSING' '注释审计'
    }
}

function Get-AuditDocumentLinkTargets {
    param([string]$AuditSection)
    $targets = [System.Collections.Generic.List[string]]::new()
    if ([string]::IsNullOrWhiteSpace($AuditSection)) {
        return $targets.ToArray()
    }
    foreach ($match in [regex]::Matches($AuditSection, '(?m)(?<!!)\[[^\]\r\n]*\]\((?<target>[^)\r\n]+)\)')) {
        $targets.Add($match.Groups['target'].Value.Trim())
    }
    return $targets.ToArray()
}

function Resolve-AuditDocumentContent {
    param(
        [string]$RepositoryRoot,
        [string]$Body,
        [object[]]$ChangedFiles
    )
    # PR 模板通过 HTML 注释提供示例链接；这些隐藏说明不是用户提交的审计证据。
    $withoutInstructions = [regex]::Replace($Body, '(?s)<!--.*?-->', '').Trim()
    $auditSection = Get-PrSection $withoutInstructions '注释审计'
    $targets = @(Get-AuditDocumentLinkTargets $auditSection)
    if ($targets.Count -gt 1) {
        Add-GuardrailError 'AUDIT_DOCUMENT_LINK_MULTIPLE' ($targets -join ', ')
        return $null
    }
    if ($targets.Count -eq 0) {
        if ($Body -match '<!--\s*comment-audit:file=') {
            return $Body
        }
        Add-GuardrailError 'AUDIT_DOCUMENT_LINK_MISSING' 'production code changes require one linked audit document'
        return $null
    }

    $target = $targets[0].Replace('\', '/')
    $pathMatch = [regex]::Match($target, $script:AuditDocumentPathPattern)
    if (-not $pathMatch.Success -or $pathMatch.Groups['year'].Value -ne $pathMatch.Groups['dateYear'].Value) {
        Add-GuardrailError 'AUDIT_DOCUMENT_PATH_INVALID' $targets[0]
        return $null
    }
    try {
        $dateText = '{0}-{1}-{2}' -f $pathMatch.Groups['dateYear'].Value, $pathMatch.Groups['month'].Value, $pathMatch.Groups['day'].Value
        [void][DateTime]::ParseExact($dateText, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
    }
    catch {
        Add-GuardrailError 'AUDIT_DOCUMENT_PATH_INVALID' $target
        return $null
    }

    try {
        $rootFullPath = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd([char[]]@('\', '/'))
        $absolutePath = [IO.Path]::GetFullPath((Join-Path $rootFullPath $target))
    }
    catch {
        Add-GuardrailError 'AUDIT_DOCUMENT_PATH_INVALID' $target
        return $null
    }
    $rootPrefix = $rootFullPath + [IO.Path]::DirectorySeparatorChar
    if (-not $absolutePath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Add-GuardrailError 'AUDIT_DOCUMENT_PATH_INVALID' $target
        return $null
    }
    if (-not (Test-Path -LiteralPath $absolutePath)) {
        Add-GuardrailError 'AUDIT_DOCUMENT_NOT_FOUND' $target
        return $null
    }

    $item = Get-Item -Force -LiteralPath $absolutePath
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        Add-GuardrailError 'AUDIT_DOCUMENT_UNSAFE_TYPE' $target
        return $null
    }
    $changedDocument = @($ChangedFiles | Where-Object { $_.path -ceq $target -and $_.status -like 'A*' })
    if ($changedDocument.Count -ne 1) {
        Add-GuardrailError 'AUDIT_DOCUMENT_NOT_ADDED' $target
        return $null
    }

    try {
        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $content = [IO.File]::ReadAllText($absolutePath, $strictUtf8)
    }
    catch {
        Add-GuardrailError 'AUDIT_DOCUMENT_PATH_INVALID' "$target -> unreadable UTF-8"
        return $null
    }
    if ([string]::IsNullOrWhiteSpace($content)) {
        Add-GuardrailError 'AUDIT_DOCUMENT_EMPTY' $target
        return $null
    }
    return $content
}

function Get-AuditBlock {
    param([string]$Body, [string]$Path)
    $start = "<!-- comment-audit:file=$Path -->"
    $pattern = [regex]::Escape($start) + '(?<content>.*?)' + [regex]::Escape('<!-- comment-audit:end-file -->')
    $match = [regex]::Match($Body, $pattern, [Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { return $null }
    return $match.Groups['content'].Value
}

function Test-AuditMetadata {
    param([string]$Path, [string]$Block)
    foreach ($field in @('职责', '上游', '下游', '数据源', '结果消费者')) {
        $match = [regex]::Match($Block, '(?m)^-\s*' + [regex]::Escape($field) + '：(?<value>.+)$')
        if (-not $match.Success -or $match.Groups['value'].Value.Trim() -in @('', '待填写', '无')) {
            Add-GuardrailError 'AUDIT_METADATA_INCOMPLETE' "$Path -> $field"
        }
    }
    $freshness = [regex]::Match($Block, '(?m)^-\s*现有注释时效：(?<value>.+)$')
    if (-not $freshness.Success -or $freshness.Groups['value'].Value.Trim() -ne '已核验') {
        Add-GuardrailError 'AUDIT_FRESHNESS_UNVERIFIED' $Path
    }
    if ($Block -notmatch '(?m)^-\s*人工补充符号：\s*\S+') {
        Add-GuardrailError 'AUDIT_MANUAL_SYMBOLS_MISSING' $Path
    }
}

function Get-AuditRows {
    param([string]$Path, [string]$Block)
    $rows = @{}
    $pattern = '(?m)^\|\s*`(?<id>[^`]+)`\s*\|\s*`L\d+`\s*\|\s*`(?<decision>[^`]+)`\s*\|\s*(?<reason>.*?)\s*\|\s*$'
    foreach ($match in [regex]::Matches($Block, $pattern)) {
        $id = $match.Groups['id'].Value.Trim()
        if ($rows.ContainsKey($id)) {
            Add-GuardrailError 'AUDIT_SYMBOL_DUPLICATE' "$Path -> $id"
            continue
        }
        $rows[$id] = [pscustomobject]@{
            decision = $match.Groups['decision'].Value.Trim()
            reason = $match.Groups['reason'].Value.Trim()
        }
    }
    return $rows
}

function Test-CommentAuditContract {
    param([string]$RepositoryRoot, [string]$Body, [string]$Base, [string]$Head)
    $scanner = Join-Path $PSScriptRoot 'New-CommentAuditReport.ps1'
    if (-not (Test-Path -LiteralPath $scanner)) {
        Add-GuardrailError 'COMMENT_SCANNER_MISSING' $scanner
        return
    }
    try {
        $json = & $scanner -BaseRef $Base -HeadRef $Head -Format Json 2>&1 | Out-String
        $parsedReports = $json | ConvertFrom-Json
        $reports = [System.Collections.Generic.List[object]]::new()
        foreach ($parsedReport in $parsedReports) {
            $reports.Add($parsedReport)
        }
    }
    catch {
        Add-GuardrailError 'COMMENT_SCANNER_FAILED' $_.Exception.Message
        return
    }
    $allowedDecisions = @('关键-已新增说明', '关键-已更新说明', '关键-现有说明已核验', '简单-无需说明')
    foreach ($report in $reports) {
        if ($null -eq $report) { continue }
        $findings = @($report.commentFindings)
        foreach ($finding in $findings) {
            Add-GuardrailError 'STALE_OR_LOW_VALUE_COMMENT' "$($report.path):L$($finding.line) $($finding.rule)"
        }

        $block = Get-AuditBlock $Body $report.path
        if ($null -eq $block) {
            Add-GuardrailError 'AUDIT_FILE_MISSING' $report.path
            continue
        }
        Test-AuditMetadata $report.path $block
        $rows = Get-AuditRows $report.path $block
        foreach ($symbol in @($report.symbols)) {
            if (-not $rows.ContainsKey($symbol.id)) {
                Add-GuardrailError 'AUDIT_SYMBOL_MISSING' "$($report.path) -> $($symbol.id)"
                continue
            }
            $row = $rows[$symbol.id]
            if ($row.decision -notin $allowedDecisions) {
                Add-GuardrailError 'AUDIT_DECISION_INVALID' "$($report.path) -> $($symbol.id)"
            }
            if ([string]::IsNullOrWhiteSpace($row.reason) -or $row.reason -in @('待填写', '无需注释', '无')) {
                Add-GuardrailError 'AUDIT_REASON_INVALID' "$($report.path) -> $($symbol.id)"
            }
        }
    }
}

$repositoryRoot = Get-RepositoryRoot
Set-Location $repositoryRoot
$changedFiles = @(Get-ChangedFiles $Mode $BaseRef $HeadRef)

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
    Test-PrSections $PullRequestBody
    $productionFiles = @($changedFiles | Where-Object {
        $_.path -match '^src/main/java/.+\.java$' -or
        ($_.path -match '^web/src/.+\.(?:vue|ts)$' -and
            $_.path -notmatch '(^|/)(?:__tests__|tests?)/' -and
            $_.path -notmatch '\.(?:test|spec)\.(?:ts|tsx)$')
    })
    if ($productionFiles.Count -gt 0) {
        $auditContent = Resolve-AuditDocumentContent $repositoryRoot $PullRequestBody $changedFiles
        if ($null -ne $auditContent) {
            Test-CommentAuditContract $repositoryRoot $auditContent $BaseRef $HeadRef
        }
    }
}

if ($errors.Count -gt 0) {
    foreach ($guardrailError in $errors) {
        [Console]::Error.WriteLine($guardrailError)
    }
    exit 1
}

Write-Output 'REPOSITORY_GUARDRAILS_OK'
