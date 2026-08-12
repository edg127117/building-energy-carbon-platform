# 注释审计空符号集合兼容性修复设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。

> **文档状态：设计已确认，待实施**
>
> 本文记录 Repository Guardrails 在变化生产文件没有可识别方法时异常退出的修复边界。

## 1. 问题

`New-CommentAuditReport.ps1` 通过 `if` 语句给 `$symbols` 赋值。扫描结果为空时，PowerShell
会把该语句的“无输出”赋成 `$null`，JSON 报告因此不能稳定表示为空数组。随后
`Test-RepositoryGuardrails.ps1` 把该值当作符号对象集合遍历并读取 `$symbol.id`，在
`Set-StrictMode` 下直接异常退出。

这类文件包括仅声明类型的 TypeScript 文件、没有方法的实体类和空 Mapper。它们仍需要完整的
文件级审计元数据，但不应伪造方法记录，也不应导致 CI 校验器崩溃。

## 2. 方案比较

### 2.1 只在校验器中跳过无 `id` 对象

改动最小，但会把生成器产生的异常结构当作合法输入，可能掩盖真正损坏的扫描报告，不采用。

### 2.2 在 PR 正文中补虚构符号

无需修改脚本，但审计内容不真实，而且后续每个无方法文件都要手工规避，不采用。

### 2.3 从生成器保证空数组（采用）

用数组子表达式包住完整的 Java/前端扫描分支，使零个结果稳定保存为 `[]`。校验器继续按现有
合同处理真实符号，不放宽任何审计字段、方法覆盖或理由校验规则。

## 3. 修改范围

- 修改 `scripts/New-CommentAuditReport.ps1`：只调整 `$symbols` 的集合构造方式。
- 修改 `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`：在临时合同仓库中加入一个发生变化、
  但没有可识别方法的生产文件，验证完整 PR 合同仍能通过。
- 不修改业务 Java、Vue、TypeScript 文件。
- 不修改 PR 审计报告的字段、表格格式、判定枚举或业务说明。
- 不放宽 Repository Guardrails 的失败条件。

## 4. 数据流

变化生产文件 → Java 或前端符号扫描 → 数组子表达式收集零个或多个符号 → JSON 中稳定输出
`symbols: []` 或符号数组 → Guardrails 遍历实际符号 → 无方法文件只校验文件级元数据。

## 5. 测试

- 运行 `scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group CommentScanner`，验证扫描报告仍覆盖
  现有 Java、Vue 和 TypeScript 符号。
- 运行 `scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group RepositoryContract`，验证包含无方法
  生产文件的完整 PR 合同通过，缺章节、缺方法和弱理由场景仍失败。
- 运行 `scripts/tests/Invoke-RepositoryGuardrailTests.ps1 -Group All`，验证仓库 Guardrails 全量回归。
- 运行 `git diff --check`。
- 推送后确认 PR #25 的 `Repository Guardrails` 通过；后端与前端业务测试不因本次脚本修复重跑要求
  而被替代，结果按 GitHub Checks 如实记录。

## 6. 验收标准

- 无方法生产文件在 JSON 扫描报告中使用空数组表示符号集合。
- 包含这类文件的完整 PR 注释审计能够通过校验，不再出现 `property 'id' cannot be found`。
- 有方法文件仍必须逐项提供符号审计记录。
- 缺少文件块、元数据、符号行或有效理由时仍按原规则失败。
- 最终提交只包含生成器、对应回归测试和本设计记录。
