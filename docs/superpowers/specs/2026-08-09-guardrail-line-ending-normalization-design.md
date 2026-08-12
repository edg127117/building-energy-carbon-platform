# Repository Guardrail 换行兼容修复设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看[历史任务目录](../README.md)、
> [项目状态](../../../PROJECT_STATUS.md)、当前代码与测试。


## 状态

已确认采用方案 1。

## 问题

PR #36 的 `Repository guardrails` 在“注释检查”已经填写风险级别、检查范围和结论的情况下仍报告：

```text
COMMENT_SCOPE_MISSING
```

本地使用 GitHub 当前 PR 正文重放后可稳定复现。PR 正文使用 CRLF 换行；现有字段正则按 LF 行尾编写，`检查范围` 位于正文中间时会在行尾残留 `\r`，从而被误判为空。位于正文末尾的 `结论` 不受影响，因此形成不一致行为。

## 决策

在 `Test-PrSections` 解析 PR 正文前统一换行：

1. 将 CRLF 转换为 LF；
2. 将剩余单独 CR 转换为 LF；
3. 后续章节提取和字段校验只处理统一后的 LF 文本。

字段正则继续限制在单行内，不改用可跨行的宽松 `\s*`，避免空字段错误吞入下一行内容。

## 备选方案

- 只在当前 `检查范围` 正则末尾增加 `\r?`：改动最小，但其他字段或以后新增字段仍可能重复出现同类问题。
- 手工修改当前 PR 正文格式：只能解除当前阻塞，无法保护后续 PR。

统一输入换行可以一次消除解析层的平台差异，因此作为最终方案。

## 变化范围

- 修改 `scripts/Test-RepositoryGuardrails.ps1`：在去除模板注释后统一正文换行，再提取章节。
- 修改 `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`：同一完整 PR 正文分别使用 LF 和 CRLF 执行，均必须通过。
- 保留风险级别、生产代码匹配、检查范围、结论和旧内嵌审计兼容规则，不降低任何门禁。

## 验证

- 使用 GitHub 当前 PR 正文重放，预期输出 `REPOSITORY_GUARDRAILS_OK`。
- LF 风险分级正文通过。
- CRLF 风险分级正文通过。
- 空检查范围在 LF 和 CRLF 下均失败并返回 `COMMENT_SCOPE_MISSING`。
- 运行完整 Repository Guardrail 测试套件、本地 Git 防护测试、PowerShell 语法解析和 `git diff --check`。

## 非目标

- 不通过放宽或删除字段校验解决问题。
- 不修改 PR 模板、生产代码、业务测试或项目状态。
- 不依赖人工编辑当前 PR 正文作为长期修复。
