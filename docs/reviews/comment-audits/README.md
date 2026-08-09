# 中文注释审计历史归档

本目录保存此前生产代码任务合并时形成的完整中文注释审计快照，用于追溯“当时哪些文件和符号经过了什么判断”。历史报告不代替当前源码、测试或业务设计文档，并保持不可变。

自风险分级注释检查机制启用后，普通生产代码 PR 不再强制新增审计文档或在 PR 中链接历史报告。当前要求是在 PR 的“注释检查”中记录风险级别、检查范围和结论。

## 专项审计

只有明确开展全量或专项注释审计时，才按以下路径新增报告：

```text
docs/reviews/comment-audits/<year>/<YYYY-MM-DD>-<task>.md
```

可使用保留的生成器：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/New-CommentAuditReport.ps1 `
  -BaseRef origin/main `
  -HeadRef HEAD `
  -OutputPath docs/reviews/comment-audits/2026/2026-08-09-example.md
```

生成器只提供扫描和模板，人工仍需核对文件职责、上下游、数据源、现有注释时效、遗漏符号和每项判定，清除所有占位内容后再提交。

## 历史不可变规则

- 已合并报告不得因后续代码变化而回写；
- 报告中的源码行号只对应当时版本；
- 新的专项审计使用新文档，不复用 base 分支中的历史报告；
- 不涉及专项审计的任务不创建空报告。
