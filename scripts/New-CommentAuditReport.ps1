[CmdletBinding()]
param(
    [string]$BaseRef = 'origin/main',
    [string]$HeadRef = 'HEAD',
    [ValidateSet('Markdown', 'Json')]
    [string]$Format = 'Markdown',
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:AuditDocumentPathPattern = '^docs/reviews/comment-audits/(?<year>\d{4})/(?<dateYear>\d{4})-(?<month>\d{2})-(?<day>\d{2})-(?<task>[a-z0-9]+(?:-[a-z0-9]+)*)\.md$'

function Get-RepositoryRoot {
    $root = (& git rev-parse --show-toplevel 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) {
        throw 'NOT_A_GIT_WORKTREE'
    }
    return $root
}

function Resolve-AuditOutputPath {
    param(
        [string]$RepositoryRoot,
        [string]$RelativePath,
        [string]$Base
    )
    $normalized = $RelativePath.Replace('\', '/')
    $match = [regex]::Match($normalized, $script:AuditDocumentPathPattern)
    if (-not $match.Success -or $match.Groups['year'].Value -ne $match.Groups['dateYear'].Value) {
        throw "AUDIT_DOCUMENT_PATH_INVALID: $RelativePath"
    }

    try {
        $dateText = '{0}-{1}-{2}' -f $match.Groups['dateYear'].Value, $match.Groups['month'].Value, $match.Groups['day'].Value
        [void][DateTime]::ParseExact($dateText, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
        $rootFullPath = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd([char[]]@('\', '/'))
        $absolutePath = [IO.Path]::GetFullPath((Join-Path $rootFullPath $normalized))
    }
    catch {
        throw "AUDIT_DOCUMENT_PATH_INVALID: $RelativePath"
    }

    $rootPrefix = $rootFullPath + [IO.Path]::DirectorySeparatorChar
    if (-not $absolutePath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "AUDIT_DOCUMENT_PATH_INVALID: $RelativePath"
    }

    $baseEntry = & git ls-tree --name-only $Base -- $normalized 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "GIT_BASE_REF_INVALID: $Base"
    }
    if (@($baseEntry).Count -gt 0) {
        throw "AUDIT_DOCUMENT_ALREADY_IN_BASE: $normalized"
    }

    if (Test-Path -LiteralPath $absolutePath) {
        $item = Get-Item -Force -LiteralPath $absolutePath
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "AUDIT_DOCUMENT_PATH_INVALID: $RelativePath"
        }
    }

    return [pscustomobject]@{
        RelativePath = $normalized
        AbsolutePath = $absolutePath
        Date = $dateText
        Task = $match.Groups['task'].Value
    }
}

function Get-LineNumber {
    param([string]$Content, [int]$Index)
    if ($Index -le 0) { return 1 }
    return ([regex]::Matches($Content.Substring(0, $Index), "`n").Count + 1)
}

function New-SymbolsWithStableIds {
    param([object[]]$Candidates)
    $nameCounts = @{}
    $symbols = [System.Collections.Generic.List[object]]::new()
    foreach ($candidate in ($Candidates | Sort-Object Index)) {
        if (-not $nameCounts.ContainsKey($candidate.Name)) {
            $nameCounts[$candidate.Name] = 0
        }
        $nameCounts[$candidate.Name]++
        $symbols.Add([pscustomobject]@{
            id = ("{0}#{1}" -f $candidate.Name, $nameCounts[$candidate.Name])
            name = $candidate.Name
            kind = $candidate.Kind
            line = $candidate.Line
        })
    }
    return $symbols.ToArray()
}

function Get-JavaSymbols {
    param([string]$Content)
    $classNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($classMatch in [regex]::Matches($Content, '\b(?:class|interface|record|enum)\s+(?<name>[A-Za-z_$][A-Za-z0-9_$]*)')) {
        [void]$classNames.Add($classMatch.Groups['name'].Value)
    }

    $candidates = [System.Collections.Generic.List[object]]::new()

    if ($classNames.Count -gt 0) {
        $escapedClassNames = @($classNames | ForEach-Object { [regex]::Escape($_) }) -join '|'
        $constructorPattern = '(?ms)^[ \t]*(?:@[A-Za-z_$][\w.$]*(?:\s*\([^)]*\))?\s*)*(?:(?:public|protected|private)\s+)*(?<name>' + $escapedClassNames + ')\s*\((?<params>[^(){};]*(?:\([^()]*\)[^(){};]*)*)\)\s*(?:throws\s+[^{;]+)?\{'
        foreach ($match in [regex]::Matches($Content, $constructorPattern)) {
            $candidates.Add([pscustomobject]@{
                Name = $match.Groups['name'].Value
                Kind = 'constructor'
                Line = Get-LineNumber $Content $match.Groups['name'].Index
                Index = $match.Groups['name'].Index
            })
        }
    }

    $methodPattern = '(?ms)^[ \t]*(?:@[A-Za-z_$][\w.$]*(?:\s*\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\s+)*(?:<[^>{};]+>\s*)?(?<returnType>[A-Za-z_$][\w.$<>\[\],?]*)\s+(?<name>[A-Za-z_$][\w$]*)\s*\((?<params>[^(){};]*(?:\([^()]*\)[^(){};]*)*)\)\s*(?:throws\s+[^{;]+)?(?<terminator>\{|;)'
    foreach ($match in [regex]::Matches($Content, $methodPattern)) {
        $returnType = $match.Groups['returnType'].Value
        if ($returnType -in @('return', 'throw', 'new', 'case', 'public', 'protected', 'private', 'static', 'final', 'abstract', 'default')) { continue }
        $name = $match.Groups['name'].Value
        if ($classNames.Contains($name)) { continue }
        $kind = 'method'
        if ($match.Groups['terminator'].Value -eq ';') {
            $kind = 'interface-method'
        }
        elseif ($match.Value -match '\bprivate\b') {
            $kind = 'private-method'
        }
        elseif ($match.Value -match '\bprotected\b') {
            $kind = 'protected-method'
        }
        elseif ($match.Value -match '\bpublic\b') {
            $kind = 'public-method'
        }

        $candidates.Add([pscustomobject]@{
            Name = $name
            Kind = $kind
            Line = Get-LineNumber $Content $match.Groups['name'].Index
            Index = $match.Groups['name'].Index
        })
    }
    return New-SymbolsWithStableIds $candidates.ToArray()
}

function Get-FrontendSymbols {
    param([string]$Content)
    $candidatesByIndex = @{}
    $excluded = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($keyword in @('if', 'for', 'while', 'switch', 'catch', 'function')) {
        [void]$excluded.Add($keyword)
    }

    $patterns = @(
        [pscustomobject]@{ Pattern = '(?m)^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(?<name>[A-Za-z_$][\w$]*)\s*\('; Kind = 'function' },
        [pscustomobject]@{ Pattern = '(?m)^\s*(?:export\s+)?(?:const|let|var)\s+(?<name>[A-Za-z_$][\w$]*)\s*(?::[^=\r\n]+)?=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)(?:\s*:\s*[^=\r\n]+)?\s*=>'; Kind = 'arrow-function' },
        [pscustomobject]@{ Pattern = '(?m)^\s*(?:export\s+)?const\s+(?<name>[A-Za-z_$][\w$]*)\s*(?::[^=\r\n]+)?=\s*(?<factory>computed|watch|watchEffect)\s*\('; Kind = 'reactive-entry' },
        [pscustomobject]@{ Pattern = '(?m)^\s*(?:(?:public|protected|private|static|readonly|abstract|override|async|get|set)\s+)*(?<name>[A-Za-z_$][\w$]*)\s*(?:<[^>\r\n]+>)?\s*\([^)]*\)\s*(?::[^\{=\r\n]+)?\s*\{'; Kind = 'object-or-class-method' },
        [pscustomobject]@{ Pattern = '(?m)^\s*(?<name>onMounted|onUnmounted|onBeforeMount|onBeforeUnmount|onUpdated|watch|watchEffect)\s*\('; Kind = 'lifecycle-or-watch' }
    )

    foreach ($entry in $patterns) {
        foreach ($match in [regex]::Matches($Content, $entry.Pattern)) {
            $name = $match.Groups['name'].Value
            if ($excluded.Contains($name)) { continue }
            $index = $match.Groups['name'].Index
            $key = "${index}:$name"
            if (-not $candidatesByIndex.ContainsKey($key)) {
                $candidatesByIndex[$key] = [pscustomobject]@{
                    Name = $name
                    Kind = $entry.Kind
                    Line = Get-LineNumber $Content $index
                    Index = $index
                }
            }
        }
    }
    return New-SymbolsWithStableIds @($candidatesByIndex.Values)
}

function Get-NormalizedCommentText {
    param([string]$RawText)
    $text = $RawText
    $text = $text -replace '^\s*/\*\*?', ''
    $text = $text -replace '\*/\s*$', ''
    $text = $text -replace '^\s*<!--', ''
    $text = $text -replace '-->\s*$', ''
    $lines = foreach ($line in ($text -split "`r?`n")) {
        ($line -replace '^\s*(?://|\*)?\s?', '').Trim()
    }
    return (($lines | Where-Object { $_ }) -join ' ').Trim()
}

function Get-CommentFindings {
    param([string]$Content)
    $findings = [System.Collections.Generic.List[object]]::new()
    $commentPattern = '(?ms)/\*.*?\*/|(?m)//[^\r\n]*|(?s)<!--.*?-->'
    foreach ($match in [regex]::Matches($Content, $commentPattern)) {
        $text = Get-NormalizedCommentText $match.Value
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        $rule = $null
        if ($text -match '(当前任务|本次修改|本\s*PR|本次修复)') {
            $rule = 'TASK_HISTORY'
        }
        elseif ($text -match '(以后|后续|未来).{0,12}(支持|实现|优化|补充|新增|完成)') {
            $rule = 'FUTURE_PROMISE'
        }
        elseif ($text -match '^\s*(获取数据|进行判断|循环处理|返回结果)[。.]?\s*$') {
            $rule = 'EMPTY_RESTATEMENT'
        }
        if ($rule) {
            $findings.Add([pscustomobject]@{
                line = Get-LineNumber $Content $match.Index
                rule = $rule
                text = $text
            })
        }
    }
    return $findings.ToArray()
}

function Get-ChangedProductionFiles {
    param([string]$Base, [string]$Head)
    $diffOutput = & git diff --name-status --diff-filter=ACMR "$Base...$Head" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "GIT_DIFF_FAILED: $($diffOutput | Out-String)"
    }
    $files = [System.Collections.Generic.List[object]]::new()
    foreach ($row in $diffOutput) {
        if ([string]::IsNullOrWhiteSpace($row)) { continue }
        $parts = $row -split "`t"
        $path = $parts[-1].Replace('\', '/')
        $isJavaProduction = $path -match '^src/main/java/.+\.java$'
        $isFrontendProduction = $path -match '^web/src/.+\.(?:vue|ts)$' -and
            $path -notmatch '(^|/)(?:__tests__|tests?)/' -and
            $path -notmatch '\.(?:test|spec)\.(?:ts|tsx)$'
        if ($isJavaProduction -or $isFrontendProduction) {
            $files.Add([pscustomobject]@{ status = $parts[0]; path = $path })
        }
    }
    return $files.ToArray()
}

function Write-MarkdownReport {
    param([object[]]$Reports)
    if ($Reports.Count -eq 0) {
        Write-Output '不涉及生产代码。'
        return
    }
    foreach ($report in $Reports) {
        Write-Output "<!-- comment-audit:file=$($report.path) -->"
        Write-Output '- 职责：待填写'
        Write-Output '- 上游：待填写'
        Write-Output '- 下游：待填写'
        Write-Output '- 数据源：待填写'
        Write-Output '- 结果消费者：待填写'
        Write-Output '- 现有注释时效：待核验'
        Write-Output '- 人工补充符号：无'
        Write-Output ''
        Write-Output '| 符号 ID | 位置 | 判定与处理 | 业务说明或免注释原因 |'
        Write-Output '| --- | --- | --- | --- |'
        foreach ($symbol in $report.symbols) {
            Write-Output "| ``$($symbol.id)`` | ``L$($symbol.line)`` | ``待填写`` | 待填写 |"
        }
        if ($report.commentFindings.Count -gt 0) {
            Write-Output ''
            Write-Output '- 注释风险：'
            foreach ($finding in $report.commentFindings) {
                Write-Output "  - ``$($finding.rule)``，L$($finding.line)：$($finding.text)"
            }
        }
        Write-Output '<!-- comment-audit:end-file -->'
        Write-Output ''
    }
}

$repositoryRoot = Get-RepositoryRoot
Set-Location $repositoryRoot
if ($OutputPath -and $Format -ne 'Markdown') {
    throw 'OUTPUT_PATH_REQUIRES_MARKDOWN'
}
$reports = [System.Collections.Generic.List[object]]::new()
foreach ($file in (Get-ChangedProductionFiles $BaseRef $HeadRef)) {
    $absolutePath = Join-Path $repositoryRoot $file.path
    if (-not (Test-Path -LiteralPath $absolutePath)) { continue }
    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $absolutePath
    $symbols = @(
        if ($file.path -match '\.java$') {
            Get-JavaSymbols $content
        }
        else {
            Get-FrontendSymbols $content
        }
    )
    $findings = @(Get-CommentFindings $content)
    $reports.Add([pscustomobject]@{
        path = $file.path
        status = $file.status
        symbols = $symbols
        commentFindings = $findings
    })
}

if ($OutputPath) {
    $resolvedOutput = Resolve-AuditOutputPath $repositoryRoot $OutputPath $BaseRef
    $baseLabel = $BaseRef -replace '^origin/', ''
    $documentLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in @(
        "# $($resolvedOutput.Task) 中文注释审计",
        '',
        "- 任务：$($resolvedOutput.Task)",
        "- 审计日期：$($resolvedOutput.Date)",
        "- 基线分支：$baseLabel",
        '- 审计范围：本任务相对基线的全部变化生产文件',
        ''
    )) {
        $documentLines.Add($line)
    }
    foreach ($line in @(Write-MarkdownReport @($reports.ToArray()))) {
        $documentLines.Add([string]$line)
    }

    $parent = Split-Path -Parent $resolvedOutput.AbsolutePath
    [void][IO.Directory]::CreateDirectory($parent)
    $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
    $documentText = (($documentLines.ToArray() -join "`n").TrimEnd() + "`n")
    [IO.File]::WriteAllText($resolvedOutput.AbsolutePath, $documentText, $utf8WithoutBom)
    Write-Output "COMMENT_AUDIT_DOCUMENT_WRITTEN: $($resolvedOutput.RelativePath)"
}
elseif ($Format -eq 'Json') {
    ConvertTo-Json -InputObject @($reports.ToArray()) -Depth 8
}
else {
    Write-MarkdownReport @($reports.ToArray())
}
