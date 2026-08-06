[CmdletBinding()]
param(
    [ValidateSet('CommentScanner', 'RepositoryContract', 'CiContract', 'All')]
    [string]$Group = 'All'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scannerScript = Join-Path $repositoryRoot 'scripts\New-CommentAuditReport.ps1'
$guardrailScript = Join-Path $repositoryRoot 'scripts\Test-RepositoryGuardrails.ps1'
$localTestScript = Join-Path $repositoryRoot 'scripts\tests\Invoke-LocalGitGuardrailTests.ps1'
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
    if (-not $Actual.Contains($Expected)) {
        throw "ASSERTION_FAILED: $Message`nEXPECTED: $Expected`nACTUAL:`n$Actual"
    }
}

function Assert-NotContains {
    param([string]$Actual, [string]$Unexpected, [string]$Message)
    if ($Actual.Contains($Unexpected)) {
        throw "ASSERTION_FAILED: $Message`nUNEXPECTED: $Unexpected`nACTUAL:`n$Actual"
    }
}

function Complete-Case {
    param([string]$Name)
    $script:passedCases++
    Write-Output "CASE_PASSED: $Name"
}

function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [string]$WorkingDirectory
    )
    $previous = Get-Location
    $previousErrorAction = $ErrorActionPreference
    try {
        if ($WorkingDirectory) { Set-Location $WorkingDirectory }
        $ErrorActionPreference = 'Continue'
        $output = & $FilePath @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
        Set-Location $previous
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
    param([string]$ScriptPath, [string[]]$Arguments, [string]$WorkingDirectory, [hashtable]$Environment)
    $oldValues = @{}
    if ($Environment) {
        foreach ($key in $Environment.Keys) {
            $oldValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, [string]$Environment[$key], 'Process')
        }
    }
    try {
        return Invoke-Native -FilePath $powerShellPath -Arguments (@('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + $Arguments) -WorkingDirectory $WorkingDirectory
    }
    finally {
        if ($Environment) {
            foreach ($key in $Environment.Keys) {
                [Environment]::SetEnvironmentVariable($key, $oldValues[$key], 'Process')
            }
        }
    }
}

function New-TestRepository {
    param([string]$Name)
    $base = Join-Path ([IO.Path]::GetTempPath()) "iot-guardrail-tests"
    if (-not (Test-Path $base)) { New-Item -ItemType Directory -Path $base | Out-Null }
    $root = Join-Path $base ("{0}-{1}" -f $Name, [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $root | Out-Null
    $resolvedBase = [IO.Path]::GetFullPath($base).TrimEnd('\')
    $resolvedRoot = [IO.Path]::GetFullPath($root)
    if (-not $resolvedRoot.StartsWith($resolvedBase + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "UNSAFE_TEST_ROOT: $resolvedRoot"
    }
    $script:temporaryRoots.Add($resolvedRoot)
    Invoke-GitChecked $root @('init', '-b', 'main') | Out-Null
    Invoke-GitChecked $root @('config', 'user.name', 'Guardrail Tests') | Out-Null
    Invoke-GitChecked $root @('config', 'user.email', 'guardrails@example.invalid') | Out-Null
    return $root
}

function Set-Utf8File {
    param([string]$Root, [string]$RelativePath, [string]$Content)
    $path = Join-Path $Root $RelativePath
    $parent = Split-Path -Parent $path
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Set-Content -LiteralPath $path -Value $Content -Encoding UTF8
}

function Commit-All {
    param([string]$Root, [string]$Message)
    Invoke-GitChecked $Root @('add', '--all') | Out-Null
    Invoke-GitChecked $Root @('commit', '-m', $Message) | Out-Null
}

function New-ScannerFixture {
    $root = New-TestRepository 'scanner'
    Set-Utf8File $root 'src/main/java/com/example/AuditService.java' @'
package com.example;

/** 提供设备审计查询，并将结果交给接口层。 */
public class AuditService {
    // 后续支持导出功能
    public AuditService() {
    }

    public void query(String id) {
    }

    private void query(int limit) {
    }

    public String execute() {
        query("self");
        return load();
    }

    // 当前温度来自现场传感器。
    protected String load() {
        return "ok";
    }
}
'@
    Set-Utf8File $root 'web/src/pages/AuditPage.vue' @'
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

const source = ref(1)
function refresh() {
  source.value += 1
}
const selectBuilding = async () => {
  refresh()
}
const currentValue = computed(() => source.value)
watch(source, () => refresh())
onMounted(() => refresh())
</script>
'@
    Set-Utf8File $root 'web/src/stores/telemetry.ts' @'
// 后续节点由调用方负责选择。
export function useTelemetryStore() {
  const loadLatest = async () => 1
  return { loadLatest }
}
'@
    Set-Utf8File $root 'web/src/pages/AuditPage.test.ts' @'
import { describe, it } from 'vitest'
describe('audit page', () => { it('renders', () => undefined) })
'@
    Set-Utf8File $root 'web/src/domain/AuditModel.ts' @'
export class AuditModel {
  private normalize(value: string): string {
    return value.trim()
  }

  public read(): string {
    return this.normalize('value')
  }
}
'@
    Set-Utf8File $root 'src/main/java/com/example/UnchangedService.java' @'
package com.example;
public class UnchangedService { public void untouched() {} }
'@
    Commit-All $root 'baseline'
    Invoke-GitChecked $root @('switch', '-c', 'feature/comment-audit') | Out-Null

    Add-Content -LiteralPath (Join-Path $root 'src/main/java/com/example/AuditService.java') -Value "`n" -Encoding UTF8
    Add-Content -LiteralPath (Join-Path $root 'web/src/pages/AuditPage.vue') -Value "`n" -Encoding UTF8
    Add-Content -LiteralPath (Join-Path $root 'web/src/stores/telemetry.ts') -Value "`n" -Encoding UTF8
    Add-Content -LiteralPath (Join-Path $root 'web/src/pages/AuditPage.test.ts') -Value "`n" -Encoding UTF8
    Add-Content -LiteralPath (Join-Path $root 'web/src/domain/AuditModel.ts') -Value "`n" -Encoding UTF8
    Commit-All $root 'change production files'
    return $root
}

function Invoke-CommentScannerTests {
    Assert-True (Test-Path $scannerScript) 'New-CommentAuditReport.ps1 must exist'
    $root = New-ScannerFixture
    $result = Invoke-PowerShellScript $scannerScript @('-BaseRef', 'main', '-HeadRef', 'HEAD', '-Format', 'Json') $root $null
    Assert-True ($result.ExitCode -eq 0) "scanner JSON should succeed: $($result.Output)"
    $report = $result.Output | ConvertFrom-Json
    $json = $report | ConvertTo-Json -Depth 10

    Assert-Contains $json 'AuditService#1' 'Java constructor must be listed'
    Assert-Contains $json 'query#1' 'first overload must be listed'
    Assert-Contains $json 'query#2' 'private overload must be listed'
    Assert-NotContains $json 'query#3' 'method invocation must not be listed as a declaration'
    Assert-Contains $json 'load#1' 'protected method must be listed'
    Assert-NotContains $json 'load#2' 'returned method invocation must not be listed as a declaration'
    Assert-Contains $json 'execute#1' 'real method surrounding invocations must be listed'
    Assert-Contains $json 'refresh#1' 'Vue function declaration must be listed'
    Assert-Contains $json 'selectBuilding#1' 'Vue named arrow function must be listed'
    Assert-Contains $json 'currentValue#1' 'computed value must be listed'
    Assert-Contains $json 'watch#1' 'watch entry must be listed'
    Assert-Contains $json 'onMounted#1' 'lifecycle entry must be listed'
    Assert-Contains $json 'useTelemetryStore#1' 'store factory must be listed'
    Assert-Contains $json 'loadLatest#1' 'nested named action must be listed'
    Assert-Contains $json 'normalize#1' 'private TypeScript class method must be listed'
    Assert-Contains $json 'read#1' 'public TypeScript class method must be listed'
    Assert-NotContains $json 'UnchangedService.java' 'unchanged production files must not be listed'
    Assert-NotContains $json 'AuditPage.test.ts' 'frontend test files must not be treated as production files'
    Assert-Contains $json 'FUTURE_PROMISE' 'stale comment outside changed lines must be reported'
    Assert-Contains $json '后续支持导出功能' 'finding must identify stale text'
    Assert-NotContains $json '后续节点由调用方负责选择' 'business meaning must not be a future-promise finding'
    Assert-NotContains $json '当前温度来自现场传感器' 'business meaning must not be task history'
    Complete-Case 'comment scanner finds complete symbols and stale comments'

    $markdown = Invoke-PowerShellScript $scannerScript @('-BaseRef', 'main', '-HeadRef', 'HEAD', '-Format', 'Markdown') $root $null
    Assert-True ($markdown.ExitCode -eq 0) "scanner Markdown should succeed: $($markdown.Output)"
    Assert-Contains $markdown.Output '<!-- comment-audit:file=src/main/java/com/example/AuditService.java -->' 'Markdown must contain stable file marker'
    Assert-Contains $markdown.Output '| `query#2` |' 'Markdown must include private method row'
    Assert-Contains $markdown.Output '现有注释时效：待核验' 'Markdown must require freshness review'
    Complete-Case 'comment scanner emits parseable Markdown'

    $outputPath = 'docs/reviews/comment-audits/2026/2026-08-06-device-contract.md'
    $written = Invoke-PowerShellScript $scannerScript @(
        '-BaseRef', 'main', '-HeadRef', 'HEAD', '-OutputPath', $outputPath
    ) $root $null
    Assert-True ($written.ExitCode -eq 0) "document output should succeed: $($written.Output)"
    Assert-Contains $written.Output 'COMMENT_AUDIT_DOCUMENT_WRITTEN' 'document output must emit a success marker'
    $absoluteOutputPath = Join-Path $root $outputPath
    Assert-True (Test-Path -LiteralPath $absoluteOutputPath -PathType Leaf) 'document must be created as a file'
    $document = Get-Content -Raw -Encoding UTF8 -LiteralPath $absoluteOutputPath
    Assert-Contains $document '# device-contract 中文注释审计' 'document title must identify the task slug'
    Assert-Contains $document '审计日期：2026-08-06' 'document metadata must use the filename date'
    Assert-Contains $document '基线分支：main' 'document metadata must identify the base ref'
    Assert-Contains $document '<!-- comment-audit:file=src/main/java/com/example/AuditService.java -->' 'document must retain stable file markers'
    Assert-Contains $document '职责：待填写' 'document must retain human review placeholders'
    Assert-Contains $document '后续支持导出功能' 'UTF-8 Chinese text must round-trip through the document'
    $bytes = [IO.File]::ReadAllBytes($absoluteOutputPath)
    $hasUtf8Bom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    Assert-True (-not $hasUtf8Bom) 'document must use deterministic UTF-8 without BOM'
    Complete-Case 'comment scanner writes permanent UTF-8 document'

    $jsonWithOutput = Invoke-PowerShellScript $scannerScript @(
        '-BaseRef', 'main', '-HeadRef', 'HEAD', '-Format', 'Json', '-OutputPath', 'docs/reviews/comment-audits/2026/2026-08-06-json-conflict.md'
    ) $root $null
    Assert-True ($jsonWithOutput.ExitCode -ne 0) 'JSON and document output must not be combined'
    Assert-Contains $jsonWithOutput.Output 'OUTPUT_PATH_REQUIRES_MARKDOWN' 'format conflict must have a stable error'

    foreach ($invalidPath in @(
        'https://example.invalid/audit.md',
        'C:/temp/2026-08-06-audit.md',
        '../docs/reviews/comment-audits/2026/2026-08-06-audit.md',
        'docs/reviews/2026/2026-08-06-audit.md',
        'docs/reviews/comment-audits/2025/2026-08-06-audit.md',
        'docs/reviews/comment-audits/2026/2026-13-40-audit.md',
        'docs/reviews/comment-audits/2026/2026-08-06-Audit_Name.md'
    )) {
        $invalid = Invoke-PowerShellScript $scannerScript @(
            '-BaseRef', 'main', '-HeadRef', 'HEAD', '-OutputPath', $invalidPath
        ) $root $null
        Assert-True ($invalid.ExitCode -ne 0) "invalid document path must fail: $invalidPath"
        Assert-Contains $invalid.Output 'AUDIT_DOCUMENT_PATH_INVALID' "invalid path must use a stable error: $invalidPath"
    }
    Complete-Case 'comment scanner rejects unsafe output paths'

    Commit-All $root 'add current audit document'
    $historical = Invoke-PowerShellScript $scannerScript @(
        '-BaseRef', 'HEAD', '-HeadRef', 'HEAD', '-OutputPath', $outputPath
    ) $root $null
    Assert-True ($historical.ExitCode -ne 0) 'base-existing audit document must not be overwritten'
    Assert-Contains $historical.Output 'AUDIT_DOCUMENT_ALREADY_IN_BASE' 'historical overwrite must have a stable error'
    Complete-Case 'comment scanner preserves historical audit documents'
}

function New-ContractFixture {
    $root = New-TestRepository 'contract'
    Set-Utf8File $root 'README.md' "baseline`n"
    Set-Utf8File $root 'docs/reviews/comment-audits/2026/2026-08-05-historical.md' "# 已合并的历史审计`n"
    Set-Utf8File $root 'src/main/java/com/example/ContractService.java' @'
package com.example;

/** 读取设备档案并返回给查询接口。 */
public class ContractService {
    public String load(String id) {
        return id;
    }

    private boolean allowed(String id) {
        return id != null;
    }
}
'@
    Set-Utf8File $root 'web/src/types/ContractTypes.ts' @'
export interface ContractRecord {
  id: string
}
'@
    Commit-All $root 'baseline'
    Invoke-GitChecked $root @('switch', '-c', 'feature/contract') | Out-Null
    $file = Join-Path $root 'src/main/java/com/example/ContractService.java'
    $content = Get-Content -Raw -LiteralPath $file
    $content = $content.Replace('return id;', 'return allowed(id) ? id : "";')
    Set-Content -LiteralPath $file -Value $content -Encoding UTF8
    Set-Utf8File $root 'web/src/types/ContractTypes.ts' @'
export interface ContractRecord {
  id: string
  name?: string
}
'@
    Commit-All $root 'change service'
    return $root
}

function New-CompletedAuditContent {
    param([string]$Root)
    $generated = Invoke-PowerShellScript $scannerScript @('-BaseRef', 'main', '-HeadRef', 'HEAD', '-Format', 'Markdown') $Root $null
    if ($generated.ExitCode -ne 0) { throw "REPORT_GENERATION_FAILED`n$($generated.Output)" }
    $audit = $generated.Output
    $audit = $audit.Replace('职责：待填写', '职责：读取设备档案并服务查询接口')
    $audit = $audit.Replace('上游：待填写', '上游：设备查询 Controller')
    $audit = $audit.Replace('下游：待填写', '下游：设备档案 Repository')
    $audit = $audit.Replace('数据源：待填写', '数据源：MySQL 设备档案')
    $audit = $audit.Replace('结果消费者：待填写', '结果消费者：设备详情页面')
    $audit = $audit.Replace('现有注释时效：待核验', '现有注释时效：已核验')
    $audit = [regex]::Replace($audit, '\| `([^`]+)` \| `L(\d+)` \| `待填写` \| 待填写 \|', '| `$1` | `L$2` | `关键-现有说明已核验` | 说明当前方法在设备档案查询链路中的职责 |')
    return $audit
}

function New-LinkedAuditBody {
    param([string]$AuditPath = 'docs/reviews/comment-audits/2026/2026-08-06-contract.md')
    return @"
## 变更内容
更新设备档案查询实现。

## 测试
定向合同测试通过，无跳过项。

## 注释审计
[查看完整中文注释审计]($AuditPath)
"@
}

function New-LegacyAuditBody {
    param([string]$AuditContent)
    return @"
## 解决的问题
防止生产代码注释和 PR 证据回退。

## 变更内容
更新设备档案查询实现。

## 不包含范围
不修改数据库结构。

## 文件范围检查
已确认没有生成文件、凭据、运行数据或无关修改。

## 测试
定向合同测试通过，无跳过项。

## 注释检查
$AuditContent
"@
}

function Invoke-RepositoryContractTests {
    Assert-True (Test-Path $guardrailScript) 'Test-RepositoryGuardrails.ps1 must exist'
    Assert-True (Test-Path $scannerScript) 'New-CommentAuditReport.ps1 must exist'
    $root = New-ContractFixture
    $auditPath = 'docs/reviews/comment-audits/2026/2026-08-06-contract.md'
    $auditContent = New-CompletedAuditContent $root
    Set-Utf8File $root $auditPath $auditContent
    Commit-All $root 'add current audit document'
    $body = New-LinkedAuditBody $auditPath
    $scannerResult = Invoke-PowerShellScript $scannerScript @('-BaseRef', 'main', '-HeadRef', 'HEAD', '-Format', 'Json') $root $null
    Assert-True ($scannerResult.ExitCode -eq 0) "empty-symbol scanner JSON should succeed: $($scannerResult.Output)"
    $reports = $scannerResult.Output | ConvertFrom-Json
    $emptyReports = @($reports | Where-Object { $_.path -eq 'web/src/types/ContractTypes.ts' })
    Assert-True ($emptyReports.Count -eq 1) 'changed type-only production file must be listed'
    $emptySymbolCount = @($emptyReports[0].symbols).Count
    $emptySymbolsJson = $emptyReports[0].symbols | ConvertTo-Json -Depth 4 -Compress
    Assert-True ($emptySymbolCount -eq 0) "type-only production file symbols must be an empty array, actual: $emptySymbolsJson"
    $valid = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $body }
    Assert-True ($valid.ExitCode -eq 0) "complete PR contract should pass: $($valid.Output)"
    Assert-Contains $valid.Output 'REPOSITORY_GUARDRAILS_OK' 'success marker must be emitted'
    Complete-Case 'linked production audit document passes'

    $bodyWithTemplateInstructions = $body.Replace(
        '## 注释审计',
        "## 注释审计`n<!-- [模板示例](docs/reviews/comment-audits/2026/2026-08-06-example.md) -->"
    )
    $withTemplateInstructions = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $bodyWithTemplateInstructions }
    Assert-True ($withTemplateInstructions.ExitCode -eq 0) "hidden template links must be ignored: $($withTemplateInstructions.Output)"
    Complete-Case 'hidden PR template links are not treated as audit evidence'

    $legacyBody = New-LegacyAuditBody $auditContent
    $legacy = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $legacyBody }
    Assert-True ($legacy.ExitCode -eq 0) "legacy inline audit should pass: $($legacy.Output)"
    Complete-Case 'legacy inline production audit remains compatible'

    foreach ($heading in @('变更内容', '测试', '注释审计')) {
        $missingSectionBody = $body -replace ('(?ms)^## ' + [regex]::Escape($heading) + '.*?(?=^## |\z)'), ''
        $missingSection = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $missingSectionBody }
        Assert-True ($missingSection.ExitCode -ne 0) "missing required section must fail: $heading"
        Assert-Contains $missingSection.Output "PR_SECTION_MISSING: $heading" "failure must identify missing section: $heading"
    }
    Complete-Case 'three required PR sections are enforced'

    $missingLinkBody = @'
## 变更内容
更新设备档案查询实现。
## 测试
定向合同测试通过。
## 注释审计
完整报告另行保存。
'@
    $missingLink = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $missingLinkBody }
    Assert-True ($missingLink.ExitCode -ne 0) 'production PR without linked or inline evidence must fail'
    Assert-Contains $missingLink.Output 'AUDIT_DOCUMENT_LINK_MISSING' 'missing evidence must use the document-link error'
    Complete-Case 'production PR requires linked or legacy inline evidence'

    $multipleLinksBody = $body.Replace(
        "[查看完整中文注释审计]($auditPath)",
        "[审计一]($auditPath) 和 [审计二](docs/reviews/comment-audits/2026/2026-08-06-second.md)"
    )
    $multipleLinks = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $multipleLinksBody }
    Assert-True ($multipleLinks.ExitCode -ne 0) 'multiple audit links must fail'
    Assert-Contains $multipleLinks.Output 'AUDIT_DOCUMENT_LINK_MULTIPLE' 'multiple links must use a stable error'
    Complete-Case 'multiple audit document links fail'

    foreach ($invalidPath in @(
        'https://example.invalid/audit.md',
        'C:/temp/2026-08-06-audit.md',
        '../docs/reviews/comment-audits/2026/2026-08-06-audit.md',
        'docs/reviews/2026/2026-08-06-audit.md',
        'docs/reviews/comment-audits/2025/2026-08-06-audit.md',
        'docs/reviews/comment-audits/2026/2026-13-40-audit.md',
        'docs/reviews/comment-audits/2026/2026-08-06-Audit_Name.md'
    )) {
        $invalidBody = New-LinkedAuditBody $invalidPath
        $invalid = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $invalidBody }
        Assert-True ($invalid.ExitCode -ne 0) "invalid audit link must fail: $invalidPath"
        Assert-Contains $invalid.Output 'AUDIT_DOCUMENT_PATH_INVALID' "invalid link must use a stable error: $invalidPath"
    }
    Complete-Case 'unsafe audit document links fail'

    $notFoundBody = New-LinkedAuditBody 'docs/reviews/comment-audits/2026/2026-08-06-missing.md'
    $notFound = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $notFoundBody }
    Assert-True ($notFound.ExitCode -ne 0) 'missing linked document must fail'
    Assert-Contains $notFound.Output 'AUDIT_DOCUMENT_NOT_FOUND' 'missing document must use a stable error'

    $historicalBody = New-LinkedAuditBody 'docs/reviews/comment-audits/2026/2026-08-05-historical.md'
    $historical = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $historicalBody }
    Assert-True ($historical.ExitCode -ne 0) 'base-existing linked document must fail'
    Assert-Contains $historical.Output 'AUDIT_DOCUMENT_NOT_ADDED' 'historical document must not be reusable'
    Complete-Case 'missing and historical audit documents fail'

    $emptyPath = 'docs/reviews/comment-audits/2026/2026-08-06-empty.md'
    Set-Utf8File $root $emptyPath ''
    Commit-All $root 'add empty audit document'
    $empty = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-LinkedAuditBody $emptyPath) }
    Assert-True ($empty.ExitCode -ne 0) 'empty linked document must fail'
    Assert-Contains $empty.Output 'AUDIT_DOCUMENT_EMPTY' 'empty document must use a stable error'

    $directoryPath = 'docs/reviews/comment-audits/2026/2026-08-06-directory.md'
    New-Item -ItemType Directory -Path (Join-Path $root $directoryPath) -Force | Out-Null
    $unsafeType = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-LinkedAuditBody $directoryPath) }
    Assert-True ($unsafeType.ExitCode -ne 0) 'directory target must fail'
    Assert-Contains $unsafeType.Output 'AUDIT_DOCUMENT_UNSAFE_TYPE' 'directory target must use a stable error'
    Complete-Case 'empty and unsafe audit document types fail'

    $missingPrivateContent = ($auditContent -split "`r?`n" | Where-Object { $_ -notmatch '\| `allowed#1` \|' }) -join "`n"
    Set-Utf8File $root $auditPath $missingPrivateContent
    $missingPrivate = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $body }
    Assert-True ($missingPrivate.ExitCode -ne 0) 'missing private method must fail'
    Assert-Contains $missingPrivate.Output 'AUDIT_SYMBOL_MISSING' 'failure must identify missing symbol'
    Complete-Case 'missing private method evidence fails'

    $weakReasonContent = [regex]::Replace($auditContent, '`关键-现有说明已核验` \| 说明当前方法在设备档案查询链路中的职责', '`简单-无需说明` | 无需注释', 1)
    Set-Utf8File $root $auditPath $weakReasonContent
    $weakReason = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $body }
    Assert-True ($weakReason.ExitCode -ne 0) 'simple method without concrete reason must fail'
    Assert-Contains $weakReason.Output 'AUDIT_REASON_INVALID' 'failure must identify weak exclusion reason'
    Complete-Case 'simple method requires concrete exclusion reason'

    $invalidDecisionContent = [regex]::Replace($auditContent, '`关键-现有说明已核验`', '`未知判定`', 1)
    Set-Utf8File $root $auditPath $invalidDecisionContent
    $invalidDecision = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $body }
    Assert-True ($invalidDecision.ExitCode -ne 0) 'unknown audit decision must fail'
    Assert-Contains $invalidDecision.Output 'AUDIT_DECISION_INVALID' 'decision failure must retain the existing error'

    $missingMetadataContent = $auditContent.Replace('职责：读取设备档案并服务查询接口', '职责：待填写')
    Set-Utf8File $root $auditPath $missingMetadataContent
    $missingMetadata = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $body }
    Assert-True ($missingMetadata.ExitCode -ne 0) 'incomplete document metadata must fail'
    Assert-Contains $missingMetadata.Output 'AUDIT_METADATA_INCOMPLETE' 'metadata failure must retain the existing error'
    Set-Utf8File $root $auditPath $auditContent
    Complete-Case 'linked document retains complete audit contract checks'

    $docRoot = New-TestRepository 'document-only'
    Set-Utf8File $docRoot 'README.md' "baseline`n"
    Commit-All $docRoot 'baseline'
    Invoke-GitChecked $docRoot @('switch', '-c', 'docs/guide') | Out-Null
    Set-Utf8File $docRoot 'README.md' "baseline`nupdated`n"
    Commit-All $docRoot 'update docs'
    $docBody = @'
## 变更内容
更新 README。
## 测试
git diff --check 通过，无跳过项。
## 注释审计
不涉及生产代码。
'@
    $docResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $docRoot @{ PR_BODY = $docBody }
    Assert-True ($docResult.ExitCode -eq 0) "document-only PR should pass: $($docResult.Output)"
    Complete-Case 'document-only PR passes without audit table'

    $stagedRoot = New-TestRepository 'staged'
    Set-Utf8File $stagedRoot 'README.md' "baseline`n"
    Commit-All $stagedRoot 'baseline'
    Invoke-GitChecked $stagedRoot @('switch', '-c', 'feature/staged') | Out-Null
    Set-Utf8File $stagedRoot 'server.env' "DB_PASSWORD=secret`n"
    Invoke-GitChecked $stagedRoot @('add', '--', 'server.env') | Out-Null
    $stagedResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'Staged') $stagedRoot $null
    Assert-True ($stagedResult.ExitCode -ne 0) 'staged local configuration must fail'
    Assert-Contains $stagedResult.Output 'FORBIDDEN_PATH' 'failure must identify forbidden path'
    Complete-Case 'staged sensitive local configuration fails'

    $sourceTargetRoot = New-TestRepository 'source-target-package'
    Set-Utf8File $sourceTargetRoot 'README.md' "baseline`n"
    Commit-All $sourceTargetRoot 'baseline'
    Invoke-GitChecked $sourceTargetRoot @('switch', '-c', 'docs/source-target') | Out-Null
    Set-Utf8File $sourceTargetRoot 'src/main/java/com/example/target/SourceTarget.java' @'
package com.example.target;

/** 合法源码包中的 target 类。 */
public class SourceTarget {
}
'@
    Invoke-GitChecked $sourceTargetRoot @('add', '--', 'src/main/java/com/example/target/SourceTarget.java') | Out-Null
    $sourceTargetResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'Staged') $sourceTargetRoot $null
    Assert-True ($sourceTargetResult.ExitCode -eq 0) "source target package should pass: $($sourceTargetResult.Output)"
    Complete-Case 'source target package is not treated as build output'

    $buildTargetRoot = New-TestRepository 'build-target-directory'
    Set-Utf8File $buildTargetRoot 'README.md' "baseline`n"
    Commit-All $buildTargetRoot 'baseline'
    Invoke-GitChecked $buildTargetRoot @('switch', '-c', 'test/build-target') | Out-Null
    Set-Utf8File $buildTargetRoot 'target/generated.txt' "generated`n"
    Invoke-GitChecked $buildTargetRoot @('add', '--', 'target/generated.txt') | Out-Null
    $buildTargetResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'Staged') $buildTargetRoot $null
    Assert-True ($buildTargetResult.ExitCode -ne 0) 'real build target directory must fail'
    Assert-Contains $buildTargetResult.Output 'FORBIDDEN_PATH' 'build target failure must identify forbidden path'
    Complete-Case 'real build target directory remains forbidden'
}

function Invoke-CiContractTests {
    $template = Join-Path $repositoryRoot '.github\pull_request_template.md'
    $frontend = Join-Path $repositoryRoot '.github\workflows\frontend-ci.yml'
    $guardrails = Join-Path $repositoryRoot '.github\workflows\repository-guardrails.yml'
    $agents = Join-Path $repositoryRoot 'AGENTS.md'
    $guide = Join-Path $repositoryRoot 'docs\development\repository-guardrails.md'
    $archive = Join-Path $repositoryRoot 'docs\reviews\comment-audits\README.md'
    foreach ($path in @($template, $frontend, $guardrails, $agents, $guide, $archive)) {
        Assert-True (Test-Path $path) "required CI contract file must exist: $path"
    }
    $templateText = Get-Content -Raw -Encoding UTF8 -LiteralPath $template
    foreach ($heading in '## 变更内容', '## 测试', '## 注释审计') {
        Assert-Contains $templateText $heading "PR template must contain $heading"
    }
    foreach ($obsoleteHeading in '## 解决的问题', '## 不包含范围', '## 文件范围检查', '## 注释检查') {
        Assert-NotContains $templateText $obsoleteHeading "PR template must not require $obsoleteHeading"
    }
    Assert-Contains $templateText 'New-CommentAuditReport.ps1' 'PR template must identify report command'
    Assert-Contains $templateText '-OutputPath' 'PR template must generate a permanent document'

    $archivePattern = 'docs/reviews/comment-audits/<year>/<YYYY-MM-DD>-<task>.md'
    foreach ($contractPath in @($agents, $guide, $archive)) {
        $contractText = Get-Content -Raw -Encoding UTF8 -LiteralPath $contractPath
        Assert-Contains $contractText $archivePattern "repository contract must identify the permanent archive path: $contractPath"
        Assert-Contains $contractText '-OutputPath' "repository contract must show document generation: $contractPath"
        Assert-Contains $contractText '不能复用' "repository contract must forbid historical report reuse: $contractPath"
    }

    $frontendText = Get-Content -Raw -Encoding UTF8 -LiteralPath $frontend
    foreach ($command in 'npm ci', 'npm run lint', 'npm run test:run', 'npm run check', 'npm run build') {
        Assert-Contains $frontendText $command "frontend workflow must run $command"
    }
    Assert-Contains $frontendText 'name: Frontend verify' 'frontend job name must be stable'

    $guardrailText = Get-Content -Raw -Encoding UTF8 -LiteralPath $guardrails
    Assert-Contains $guardrailText 'fetch-depth: 0' 'guardrail workflow needs full history'
    Assert-Contains $guardrailText 'name: Repository guardrails' 'guardrail job name must be stable'
    Assert-Contains $guardrailText 'github.event.pull_request.base.sha' 'workflow must pass base SHA'
    Assert-Contains $guardrailText 'github.event.pull_request.head.sha' 'workflow must pass head SHA'
    Assert-Contains $guardrailText 'PR_BODY:' 'workflow must pass PR body'
    Complete-Case 'PR template, permanent audit archive, and GitHub Actions contract'
}

try {
    if ($Group -in @('CommentScanner', 'All')) { Invoke-CommentScannerTests }
    if ($Group -in @('RepositoryContract', 'All')) { Invoke-RepositoryContractTests }
    if ($Group -in @('CiContract', 'All')) { Invoke-CiContractTests }
    if ($Group -eq 'All' -and (Test-Path $localTestScript)) {
        $localResult = Invoke-PowerShellScript $localTestScript @('-Group', 'All') $repositoryRoot $null
        Assert-True ($localResult.ExitCode -eq 0) "local Git guardrail tests should pass: $($localResult.Output)"
        Write-Output $localResult.Output.Trim()
    }
    Write-Output "GUARDRAIL_TESTS_PASSED: $script:passedCases repository cases"
}
finally {
    $safeBase = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) 'iot-guardrail-tests')).TrimEnd('\')
    foreach ($temporaryRoot in $script:temporaryRoots) {
        $resolved = [IO.Path]::GetFullPath($temporaryRoot)
        if ($resolved.StartsWith($safeBase + '\', [StringComparison]::OrdinalIgnoreCase) -and (Test-Path $resolved)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}
