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
    Set-Utf8File $root 'docs/superpowers/specs/fixture-design.md' @'
# Fixture Design

> **文档状态：历史任务设计记录**
>
> 当前状态查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。
'@
    Set-Utf8File $root 'docs/superpowers/plans/fixture.md' @'
# Fixture Plan

> **文档状态：历史任务实施计划**
>
> 复选框不代表当前完成状态；当前状态查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。
'@
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

## 文档同步
状态影响：无
检查范围：核对当前代码、测试和项目状态入口。
结论：本次不改变项目完成状态，无需更新当前状态文档。
"@
}

function New-RiskBody {
    param(
        [string]$Risk = '普通',
        [string]$Scope = '检查变化文件及受影响方法。',
        [string]$Result = '现有注释准确，无需修改。',
        [string]$StatusImpact = '有',
        [string]$DocumentScope = '核对 PROJECT_STATUS.md、当前代码和测试。',
        [string]$DocumentResult = '已同步当前项目状态。'
    )
    return @"
## 变更内容
更新设备档案查询实现。

## 测试
定向合同测试通过，无跳过项。

## 注释检查
风险级别：$Risk
检查范围：$Scope
结论：$Result

## 文档同步
状态影响：$StatusImpact
检查范围：$DocumentScope
结论：$DocumentResult
"@
}

function New-UnifiedRiskBody {
    return @"
## 变更内容
更新设备档案查询实现。

## 状态影响
- [x] 新增能力
说明：新增设备档案查询能力。

## 验证结果
- [x] 自动化测试
专项范围：后端
实际命令与结果：定向合同测试通过。
未执行及原因：外部联调不适用。

## 注释检查
风险级别：普通
检查范围：检查变化文件及受影响方法。
结论：现有注释准确，无需修改。

## 文档与 ADR
- [x] 无需更新当前文档
- [x] 未修改既有冻结历史文件
说明：项目状态和稳定架构未变化。

## 风险与未验证项
风险：无。
未验证项：无。
"@
}

function Invoke-RepositoryContractTests {
    Assert-True (Test-Path $guardrailScript) 'Test-RepositoryGuardrails.ps1 must exist'
    Assert-True (Test-Path $scannerScript) 'New-CommentAuditReport.ps1 must remain available for optional audits'

    $root = New-ContractFixture
    $body = New-RiskBody
    $valid = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $body }
    Assert-True ($valid.ExitCode -eq 0) "production PR without audit document should pass: $($valid.Output)"
    Assert-Contains $valid.Output 'REPOSITORY_GUARDRAILS_OK' 'success marker must be emitted'

    $crlfBody = $body -replace "`r?`n", "`r`n"
    $crlfValid = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $crlfBody }
    Assert-True ($crlfValid.ExitCode -eq 0) "CRLF production PR should pass: $($crlfValid.Output)"
    Assert-Contains $crlfValid.Output 'REPOSITORY_GUARDRAILS_OK' 'CRLF success marker must be emitted'

    $crBody = $body -replace "`r?`n", "`r"
    $crValid = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $crBody }
    Assert-True ($crValid.ExitCode -eq 0) "CR production PR should pass: $($crValid.Output)"
    Assert-Contains $crValid.Output 'REPOSITORY_GUARDRAILS_OK' 'CR success marker must be emitted'
    Complete-Case 'LF, CRLF, and CR production PR bodies all pass'

    $unifiedBody = New-UnifiedRiskBody
    $unified = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $unifiedBody }
    Assert-True ($unified.ExitCode -eq 0) "unified PR body should pass: $($unified.Output)"
    foreach ($heading in @('变更内容', '状态影响', '验证结果', '注释检查', '文档与 ADR', '风险与未验证项')) {
        $missingUnifiedBody = $unifiedBody -replace ('(?ms)^## ' + [regex]::Escape($heading) + '.*?(?=^## |\z)'), ''
        $missingUnified = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $missingUnifiedBody }
        Assert-True ($missingUnified.ExitCode -ne 0) "missing unified section must fail: $heading"
        Assert-Contains $missingUnified.Output "PR_SECTION_MISSING: $heading" "failure must identify missing unified section: $heading"
    }
    $missingUnifiedField = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $unifiedBody.Replace('未验证项：无。', '未验证项：') }
    Assert-True ($missingUnifiedField.ExitCode -ne 0) 'missing unified field must fail'
    Assert-Contains $missingUnifiedField.Output 'PR_FIELD_MISSING: 风险与未验证项/未验证项' 'unified field failure must be explicit'
    Complete-Case 'unified PR contract and required fields are enforced'

    foreach ($heading in @('变更内容', '测试', '注释检查', '文档同步')) {
        $missingBody = $body -replace ('(?ms)^## ' + [regex]::Escape($heading) + '.*?(?=^## |\z)'), ''
        $missing = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $missingBody }
        Assert-True ($missing.ExitCode -ne 0) "missing required section must fail: $heading"
        Assert-Contains $missing.Output "PR_SECTION_MISSING: $heading" "failure must identify missing section: $heading"
    }
    Complete-Case 'current four PR sections are enforced'

    $missingRisk = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-RiskBody -Risk '') }
    Assert-True ($missingRisk.ExitCode -ne 0) 'missing risk level must fail'
    Assert-Contains $missingRisk.Output 'COMMENT_RISK_LEVEL_MISSING' 'risk failure must use a stable error'

    $missingScope = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-RiskBody -Scope '') }
    Assert-True ($missingScope.ExitCode -ne 0) 'missing scope must fail'
    Assert-Contains $missingScope.Output 'COMMENT_SCOPE_MISSING' 'scope failure must use a stable error'

    $missingScopeCrlfBody = (New-RiskBody -Scope '') -replace "`r?`n", "`r`n"
    $missingScopeCrlf = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $missingScopeCrlfBody }
    Assert-True ($missingScopeCrlf.ExitCode -ne 0) 'missing CRLF scope must fail'
    Assert-Contains $missingScopeCrlf.Output 'COMMENT_SCOPE_MISSING' 'CRLF scope failure must use the stable error'

    $missingResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-RiskBody -Result '') }
    Assert-True ($missingResult.ExitCode -ne 0) 'missing result must fail'
    Assert-Contains $missingResult.Output 'COMMENT_RESULT_MISSING' 'result failure must use a stable error'
    Complete-Case 'risk level, scope, and result are required'

    foreach ($statusImpact in @('', '待确认')) {
        $missingStatusImpact = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-RiskBody -StatusImpact $statusImpact) }
        Assert-True ($missingStatusImpact.ExitCode -ne 0) "missing or invalid document status impact must fail: $statusImpact"
        Assert-Contains $missingStatusImpact.Output 'DOCUMENT_STATUS_IMPACT_MISSING' 'document status impact failure must use a stable error'
    }
    $missingDocumentScope = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-RiskBody -DocumentScope '') }
    Assert-True ($missingDocumentScope.ExitCode -ne 0) 'missing document scope must fail'
    Assert-Contains $missingDocumentScope.Output 'DOCUMENT_SCOPE_MISSING' 'document scope failure must use a stable error'
    $missingDocumentResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-RiskBody -DocumentResult '') }
    Assert-True ($missingDocumentResult.ExitCode -ne 0) 'missing document result must fail'
    Assert-Contains $missingDocumentResult.Output 'DOCUMENT_RESULT_MISSING' 'document result failure must use a stable error'
    Complete-Case 'document status impact, scope, and result are required'

    $wrongRisk = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-RiskBody -Risk '不涉及生产代码') }
    Assert-True ($wrongRisk.ExitCode -ne 0) 'production change cannot claim no production code'
    Assert-Contains $wrongRisk.Output 'COMMENT_RISK_LEVEL_INVALID' 'production risk mismatch must use a stable error'
    Complete-Case 'risk level must match production change presence'

    $legacyAudit = New-CompletedAuditContent $root
    $legacy = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = (New-LegacyAuditBody $legacyAudit) }
    Assert-True ($legacy.ExitCode -eq 0) "legacy inline audit should remain compatible: $($legacy.Output)"
    Complete-Case 'legacy inline audit remains compatible'

    $docRoot = New-TestRepository 'document-only'
    Set-Utf8File $docRoot 'README.md' "baseline`n"
    Commit-All $docRoot 'baseline'
    Invoke-GitChecked $docRoot @('switch', '-c', 'docs/guide') | Out-Null
    Set-Utf8File $docRoot 'README.md' "baseline`nupdated`n"
    Commit-All $docRoot 'update docs'
    $docResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $docRoot @{ PR_BODY = (New-RiskBody -Risk '不涉及生产代码' -Scope '仅检查 README 链接和差异。' -Result '不涉及生产代码注释。') }
    Assert-True ($docResult.ExitCode -eq 0) "document-only PR should pass: $($docResult.Output)"
    Complete-Case 'document-only PR passes without audit document'

    $historicalRoot = New-TestRepository 'historical-documents'
    Set-Utf8File $historicalRoot 'README.md' "baseline`n"
    Commit-All $historicalRoot 'baseline'
    Invoke-GitChecked $historicalRoot @('switch', '-c', 'docs/history-contract') | Out-Null
    Set-Utf8File $historicalRoot 'docs/superpowers/specs/missing-status-design.md' @'
# Missing Status Design

> 当前状态查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。
'@
    Set-Utf8File $historicalRoot 'docs/superpowers/plans/missing-index.md' @'
# Missing Index Plan

> **文档状态：历史任务实施计划**
>
> 当前状态查看[项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。
'@
    Set-Utf8File $historicalRoot 'docs/superpowers/specs/missing-current-status-design.md' @'
# Missing Current Status Design

> **文档状态：历史任务设计记录**
>
> 当前状态查看[历史任务目录](../README.md)和当前代码与测试。
'@
    Invoke-GitChecked $historicalRoot @(
        'add', '--',
        'docs/superpowers/specs/missing-status-design.md',
        'docs/superpowers/plans/missing-index.md',
        'docs/superpowers/specs/missing-current-status-design.md'
    ) | Out-Null
    $historicalStaged = Invoke-PowerShellScript $guardrailScript @('-Mode', 'Staged') $historicalRoot $null
    Assert-True ($historicalStaged.ExitCode -ne 0) 'staged invalid historical documents must fail'
    foreach ($errorCode in 'HISTORICAL_DOC_STATUS_MISSING', 'HISTORICAL_DOC_INDEX_LINK_MISSING', 'HISTORICAL_DOC_CURRENT_STATUS_LINK_MISSING') {
        Assert-Contains $historicalStaged.Output $errorCode "staged historical failure must contain $errorCode"
    }
    Commit-All $historicalRoot 'add invalid historical documents'
    $historicalPr = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $historicalRoot @{ PR_BODY = (New-RiskBody -Risk '不涉及生产代码') }
    Assert-True ($historicalPr.ExitCode -ne 0) 'PR with invalid historical documents must fail'
    foreach ($errorCode in 'HISTORICAL_DOC_STATUS_MISSING', 'HISTORICAL_DOC_INDEX_LINK_MISSING', 'HISTORICAL_DOC_CURRENT_STATUS_LINK_MISSING') {
        Assert-Contains $historicalPr.Output $errorCode "PR historical failure must contain $errorCode"
    }
    Complete-Case 'historical document headers and current-state links are enforced in staged and PR modes'

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
    Set-Utf8File $sourceTargetRoot 'src/main/java/com/example/target/SourceTarget.java' "package com.example.target;`n`n/** 合法源码包中的 target 类。 */`npublic class SourceTarget {}`n"
    Invoke-GitChecked $sourceTargetRoot @('add', '--', 'src/main/java/com/example/target/SourceTarget.java') | Out-Null
    $sourceTargetResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'Staged') $sourceTargetRoot $null
    Assert-True ($sourceTargetResult.ExitCode -eq 0) "source target package should pass: $($sourceTargetResult.Output)"
    Complete-Case 'source target package remains allowed'

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

    $javaRoot = New-TestRepository 'java-javadoc'
    Set-Utf8File $javaRoot 'README.md' "baseline`n"
    Commit-All $javaRoot 'baseline'
    Invoke-GitChecked $javaRoot @('switch', '-c', 'feature/java') | Out-Null
    Set-Utf8File $javaRoot 'src/main/java/com/example/MissingDoc.java' "package com.example;`npublic class MissingDoc {}`n"
    Invoke-GitChecked $javaRoot @('add', '--', 'src/main/java/com/example/MissingDoc.java') | Out-Null
    $javaResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'Staged') $javaRoot $null
    Assert-True ($javaResult.ExitCode -ne 0) 'new Java class without Javadoc must fail'
    Assert-Contains $javaResult.Output 'JAVA_CLASS_JAVADOC_MISSING' 'Java failure must identify missing class Javadoc'
    Complete-Case 'new Java class still requires class Javadoc'

    $frontendRoot = New-TestRepository 'frontend-comment'
    Set-Utf8File $frontendRoot 'README.md' "baseline`n"
    Commit-All $frontendRoot 'baseline'
    Invoke-GitChecked $frontendRoot @('switch', '-c', 'feature/frontend') | Out-Null
    Set-Utf8File $frontendRoot 'web/src/api/device.ts' "export const loadDevice = async () => ({ id: 'D1' })`n"
    Invoke-GitChecked $frontendRoot @('add', '--', 'web/src/api/device.ts') | Out-Null
    $frontendResult = Invoke-PowerShellScript $guardrailScript @('-Mode', 'Staged') $frontendRoot $null
    Assert-True ($frontendResult.ExitCode -ne 0) 'new frontend business file without comment must fail'
    Assert-Contains $frontendResult.Output 'FRONTEND_BUSINESS_COMMENT_MISSING' 'frontend failure must identify missing business comment'
    Complete-Case 'new frontend business file still requires a business comment'

    $servicePath = Join-Path $root 'src/main/java/com/example/ContractService.java'
    $serviceText = Get-Content -Raw -Encoding UTF8 -LiteralPath $servicePath
    $serviceText = $serviceText.Replace('public String load', "// 后续再支持缓存。`n    public String load")
    Set-Content -LiteralPath $servicePath -Value $serviceText -Encoding UTF8
    Commit-All $root 'add low value comment'
    $stale = Invoke-PowerShellScript $guardrailScript @('-Mode', 'PullRequest', '-BaseRef', 'main', '-HeadRef', 'HEAD') $root @{ PR_BODY = $body }
    Assert-True ($stale.ExitCode -ne 0) 'low-value future comment must fail'
    Assert-Contains $stale.Output 'STALE_OR_LOW_VALUE_COMMENT' 'low-value comment failure must remain stable'
    Complete-Case 'high-confidence low-value comments remain blocked'
}

function Invoke-CiContractTests {
    $template = Join-Path $repositoryRoot '.github\pull_request_template.md'
    $frontend = Join-Path $repositoryRoot '.github\workflows\frontend-ci.yml'
    $guardrails = Join-Path $repositoryRoot '.github\workflows\repository-guardrails.yml'
    $agents = Join-Path $repositoryRoot 'AGENTS.md'
    $guide = Join-Path $repositoryRoot 'docs\development\repository-guardrails.md'
    $archive = Join-Path $repositoryRoot 'docs\reviews\comment-audits\README.md'
    $skill = Join-Path $repositoryRoot '.agents\skills\iot-change-verification\SKILL.md'
    foreach ($path in @($template, $frontend, $guardrails, $agents, $guide, $archive, $skill)) {
        Assert-True (Test-Path $path) "required contract file must exist: $path"
    }

    $templateText = Get-Content -Raw -Encoding UTF8 -LiteralPath $template
    foreach ($heading in '## 变更内容', '## 状态影响', '## 验证结果', '## 注释检查', '## 文档与 ADR', '## 风险与未验证项') {
        Assert-Contains $templateText $heading "PR template must contain $heading"
    }
    Assert-NotContains $templateText '## 注释审计' 'PR template must not require permanent audit documents'
    foreach ($field in '风险级别：', '检查范围：', '结论：') {
        Assert-Contains $templateText $field "PR template must contain $field"
    }
    foreach ($field in '说明：', '专项范围：', '实际命令与结果：', '未执行及原因：', '风险：', '未验证项：') {
        Assert-Contains $templateText $field "PR template must contain $field"
    }

    $guardrailScriptText = Get-Content -Raw -Encoding UTF8 -LiteralPath $guardrailScript
    foreach ($removedContract in 'AUDIT_DOCUMENT_LINK_MISSING', 'AUDIT_DOCUMENT_NOT_ADDED', 'AUDIT_SYMBOL_MISSING') {
        Assert-NotContains $guardrailScriptText $removedContract "guardrail must not retain $removedContract"
    }
    foreach ($retainedContract in 'FORBIDDEN_PATH', 'JAVA_CLASS_JAVADOC_MISSING', 'FRONTEND_BUSINESS_COMMENT_MISSING', 'STALE_OR_LOW_VALUE_COMMENT', 'HISTORICAL_DOC_STATUS_MISSING', 'HISTORICAL_DOC_INDEX_LINK_MISSING', 'HISTORICAL_DOC_CURRENT_STATUS_LINK_MISSING', 'DOCUMENT_STATUS_IMPACT_MISSING', 'DOCUMENT_SCOPE_MISSING', 'DOCUMENT_RESULT_MISSING') {
        Assert-Contains $guardrailScriptText $retainedContract "guardrail must retain $retainedContract"
    }

    $agentsText = Get-Content -Raw -Encoding UTF8 -LiteralPath $agents
    Assert-Contains $agentsText 'iot-change-verification' 'AGENTS must trigger the repository verification skill'
    Assert-Contains $agentsText '普通生产代码 PR 不再强制新增永久审计文档' 'AGENTS must describe the new comment boundary'
    $archiveText = Get-Content -Raw -Encoding UTF8 -LiteralPath $archive
    Assert-Contains $archiveText '普通生产代码 PR 不再强制新增审计文档' 'archive must be historical and optional'

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
    Complete-Case 'risk-based PR template, repository skill, and GitHub Actions contract'
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
