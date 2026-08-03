# 仓库开发流程与注释质量防回退机制设计

> **文档状态：历史任务设计记录**
>
> 本文记录任务确认时的设计、取舍和验收边界。合并后判断当前行为时，应优先查看
> [固定工作规则](../../../AGENTS.md)、[项目指南](../../../PROJECT_GUIDE.md)、
> [项目状态](../../../PROJECT_STATUS.md) 和当前代码。

## 1. 背景

仓库已经在 `AGENTS.md` 中明确规定 Git/PR、测试、注释和合并后清理流程，但当前大部分要求仍是自然语言约束。现有可验证的机械检查主要是后端 GitHub Actions；本地没有启用 Git Hook，前端没有独立 CI，PR 内容和增量注释质量也没有自动检查。

这会产生两类风险：

- AI 或开发人员漏读规则后，仍可能在 `main` 提交、尝试直接推送、混入生成文件或遗漏测试材料；
- 新增或修改业务代码时，注释检查容易停留在“类上有一句 Javadoc”，关键私有方法、调用链、数据来源和输出去向继续形成欠账。

本设计不试图用脚本判断全部业务语义，而是建立“文字规则、客户端拦截、PR/CI 证据、服务器端保护”四层防线。脚本负责可可靠判断的结构问题，主代理和 PR 审核负责业务正确性。

## 2. 目标

- 在本地提交阶段阻止直接在 `main` 提交。
- 在本地推送阶段阻止任何直接更新远程 `main` 的 push。
- 为新任务提供统一预检，验证分支、工作区、规则入口和 `origin/main` 基线。
- 为合并后清理提供默认只读、显式应用且带安全校验的脚本。
- 为前端增加与后端并列的自动测试、类型检查和生产构建。
- 使用统一 PR 模板要求范围、测试和注释检查证据。
- 对每个发生变化的生产 Java、Vue、TypeScript 文件生成完整方法/函数清单，要求逐项判定关键性和注释处理结果。
- 对新增生产文件、增量低质量注释、敏感/生成文件和 PR 必填内容进行自动检查。
- 提供 GitHub `main` 分支保护的准确配置说明，使服务器端最终拒绝绕过 PR 的更新。
- 让后续功能 PR 同步完成注释审查，减少再次进行全系统补注释或重写注释的概率。

## 3. 非目标

- 本任务不重整现有业务代码注释；历史欠账仍按独立的全系统注释审计计划分批处理。
- 不修改业务逻辑、API、数据库、Docker、MQTT、TDengine、Redis 或页面行为。
- 不以注释行数或所有方法 Javadoc 覆盖率作为质量标准。
- 不尝试让正则表达式判断复杂业务注释是否完全正确。
- 不强制每个 PR 必须由另一名用户批准，避免单维护者仓库无法自行合并。
- 不自动修改 GitHub 仓库设置；服务器端分支保护需要合并后由仓库所有者启用并验证。
- 不让清理脚本在缺少明确 `-Apply` 参数、未确认合并或工作区不干净时删除分支和 worktree。

## 4. 方案比较

### 4.1 只增加文字规则

成本最低，但当前问题正是规则可能被漏读，无法形成可靠拦截，因此不采用。

### 4.2 平衡型四层防线（采用）

组合使用 `AGENTS.md`、Git Hook/PowerShell 脚本、GitHub Actions/PR 模板和 GitHub 分支保护。可机械判断的错误直接失败，语义判断保留给主代理和审核者。该方案能覆盖主要风险，同时避免注释覆盖率带来的大量废话和误报。

### 4.3 自定义 Java/Vue AST 注释审计器

可以进一步分析方法结构，但关键方法仍依赖业务职责，开发与维护成本较高。V1 阶段不采用；只有增量检查长期出现明显漏报时再单独设计。

## 5. 总体架构

### 5.1 第一层：仓库固定规则

`AGENTS.md` 继续作为完整规则来源，并增加以下可执行入口：

- 安装本地 Git 防护；
- 开始任务预检；
- PR/注释增量检查；
- 合并后安全清理。

规则解释“为什么和什么时候执行”，脚本负责重复、可验证的检查，两者不能互相替代。

### 5.2 第二层：本地 Git 防护

新增仓库级 `.githooks`：

- `pre-commit`：如果当前分支是 `main`，立即失败；随后对暂存差异执行仓库防回退检查。
- `pre-push`：解析 Git 通过标准输入传入的远程引用，只要目标包含 `refs/heads/main` 就拒绝推送。

Hook 使用 Git 可直接执行的 shell 包装器，并调用 PowerShell 检查脚本。Windows Git 优先使用 `pwsh`，不可用时使用 `powershell.exe`；两者都不可用时明确失败，不静默跳过。

新增 `scripts/Install-GitGuardrails.ps1`：

- 检查 `.githooks/pre-commit` 和 `.githooks/pre-push` 存在；
- 执行 `git config core.hooksPath .githooks`；
- 回读配置并输出 `GIT_GUARDRAILS_INSTALLED`；
- 只修改当前 Git 仓库配置，不修改用户全局 Git 配置。

### 5.3 第三层：任务预检和合并后清理

新增 `scripts/Invoke-TaskPreflight.ps1`，执行以下检查：

- `AGENTS.md`、`PROJECT_GUIDE.md`、`PROJECT_STATUS.md` 存在；
- 当前处于 Git 工作树且分支名符合仓库前缀约定；
- 当前分支不是 `main`；
- 工作区开始时干净；
- `origin/main` 是当前任务分支 `HEAD` 的祖先；
- 本地 Git Hook 已安装并指向 `.githooks`。

预检成功输出 `TASK_PREFLIGHT_OK`。脚本不能证明人或 AI 已理解规则，因此同时打印三个入口文件和本任务必须复核的关键要求；它只把“未读取风险”转化为明确、可重复的启动步骤。

新增 `scripts/Invoke-PostMergeCleanup.ps1`：

- 必须显式传入任务分支名；可选传入任务 worktree 路径；
- 默认只显示将执行的同步和清理动作，不修改状态；
- 只有传入 `-Apply` 才执行；
- 执行前刷新远程引用，确认任务分支已进入 `origin/main`；
- 确认任务 worktree 没有未提交文件；
- 使用 `git merge --ff-only origin/main` 同步主工作目录；
- 只删除精确匹配的任务 worktree 和本地已合并分支；
- 任一检查失败立即停止，不使用 `reset --hard`、force 或路径通配符。

### 5.4 第四层：PR、CI 和服务器端保护

新增 `.github/pull_request_template.md`，固定包含：

- 解决的问题；
- 变更内容；
- 明确不包含范围；
- 文件范围检查；
- 测试命令、结果、跳过项和未验证内容；
- 注释检查，包括涉及类/组件、完整方法/函数清单、关键方法、调用链、注释处理结果和有意不加注释的简单方法。

新增 `scripts/New-CommentAuditReport.ps1`，以 PR base 和当前 `HEAD` 为输入：

- 找出所有发生变化的生产 Java、Vue 和 TypeScript 文件，不只检查新增文件；
- 读取每个变化文件的完整当前内容，而不是只读取 diff 新增行；
- 枚举 Java 构造器、接口方法以及 `public`、`protected`、`private` 方法；
- 枚举 Vue/TypeScript 中具名函数、具名箭头函数、计算属性、监听器、生命周期入口和 Store/Composable 公开动作；
- 生成固定 Markdown 表格，要求每个符号填写 `关键-已新增说明`、`关键-已更新说明`、`关键-现有说明已核验` 或 `简单-无需说明`；
- 对 `简单-无需说明` 强制填写简短原因，不能只写“无需注释”；
- 为每个文件保留类/组件职责、上游、下游、数据源和最终消费者字段。

该报告粘贴到 PR 的“注释检查”章节，不作为长期仓库文件提交。扫描器无法可靠识别的匿名回调或动态结构必须在文件级“人工补充符号”栏记录，不能因此跳过整个文件。

新增 `.github/workflows/frontend-ci.yml`，在所有面向 `main` 的 PR 和 `main` push 上执行：

1. checkout；
2. Node 环境与 npm 缓存；
3. `npm ci`；
4. `npm run test:run`；
5. `npm run check`；
6. `npm run build`。

新增 `.github/workflows/repository-guardrails.yml`，在 PR 打开、编辑、同步和重新打开时执行：

- checkout 完整历史；
- 使用 base/head SHA 计算增量文件与新增行；
- 检查 PR 必填章节；
- 对所有发生变化的生产 Java、Vue、TypeScript 文件重新生成预期的方法/函数清单；
- 验证 PR 注释检查表覆盖每个变化文件和扫描器识别出的全部方法/函数；
- 验证每一项都有关键性判定、注释处理结果和必要的排除原因；
- 检查新增生产 Java 类是否有类级 Javadoc；
- 检查新增前端 Page、Component、Composable、Store、API、Router 或 Type 文件是否完全缺少业务说明；
- 检查新增注释中是否出现任务历史、未来承诺和无信息代码复述；
- 拒绝新增常见生成文件、本地配置、凭据文件和运行数据目录；
- 生产代码发生变化时，要求 PR 的“注释检查”明确列出类/组件、全部方法/函数、关键方法、调用链和有意排除项。

结构覆盖检查处理所有发生变化的生产文件；低质量文本扫描只检查新增注释。功能 PR 不要求修复同一文件之外的历史欠账，但只要修改了某个生产文件，就必须完整审查该文件的现有类级说明和全部方法，防止在大型旧类中继续新增无说明代码。

### 5.5 GitHub `main` 分支保护

新增 `docs/development/repository-guardrails.md`，记录仓库所有者需要在 GitHub 设置中启用的规则：

- 必须通过 Pull Request 合并；
- 必须通过 `Java 21 verify`、`Frontend verify`、`Repository guardrails`；
- 合并前分支必须为最新；
- 必须解决 PR 对话；
- 禁止 force push；
- 禁止删除 `main`；
- 当前不要求另一名审核者批准；
- 当前不强制签名提交。

只有在 GitHub 页面确认规则已启用后，才能报告服务器端保护已生效。仓库文件和 CI 存在不等于分支保护已经启用。

## 6. 注释防回退边界

### 6.1 能机械检查的内容

- 每个发生变化的生产文件是否出现在 PR 注释检查表中；
- 变化文件中的全部可识别方法/函数是否逐项登记；
- 每个方法/函数是否填写关键性判定和注释处理结果；
- 标记为简单方法时是否填写了具体排除原因；
- 每个变化文件是否填写职责、上下游、数据源和结果消费者；
- 新增生产 Java 类完全没有类级 Javadoc；
- 新增核心前端文件完全没有业务说明；
- 新增注释包含“当前任务”“本次修改”“以后支持”“后续支持”等任务历史或未来承诺；
- 新增注释只写“获取数据”“进行判断”“循环处理”“返回结果”等无信息复述；
- 生产代码变化但 PR 没有填写注释检查证据；
- PR 没有说明完整方法清单、关键方法、调用链或简单方法排除项。

### 6.2 必须人工或由主代理判断的内容

- 私有方法是否承担完整业务步骤；
- 注释描述的上下游、数据源、单位、状态和异常是否真实；
- 注释是否足以让业务阅读者理解流程；
- 哪些简单方法不需要注释；
- 是否存在跨三个以上关键层、需要同时保留设计文档的复杂流程。

因此，CI 通过只代表结构和证据完整，不能替代主代理逐项审查，也不能单独证明业务注释正确。

关键方法的最终分类仍由主代理负责，但主代理不能跳过任何变化文件或扫描器已识别的方法。CI 不判断分类结论是否正确，只保证“已经逐项作出判断并留下可审核证据”。

## 7. 文件结构与职责

### 新增

- `.githooks/pre-commit`：阻止 `main` 提交并调用暂存差异检查。
- `.githooks/pre-push`：阻止远程 `main` 更新。
- `.github/pull_request_template.md`：统一 PR 范围、测试和注释证据。
- `.github/workflows/frontend-ci.yml`：前端测试、类型检查和构建。
- `.github/workflows/repository-guardrails.yml`：PR 合同与增量防回退检查。
- `scripts/Install-GitGuardrails.ps1`：安装当前仓库 Git Hook。
- `scripts/Invoke-TaskPreflight.ps1`：任务开始预检。
- `scripts/Invoke-PostMergeCleanup.ps1`：默认只读的合并后安全清理。
- `scripts/New-CommentAuditReport.ps1`：为全部变化生产文件生成完整方法/函数审查表。
- `scripts/Test-RepositoryGuardrails.ps1`：暂存差异或 PR 差异检查。
- `scripts/tests/Invoke-RepositoryGuardrailTests.ps1`：在临时 Git 仓库中验证 Hook、预检、PR 合同和失败边界。
- `docs/development/repository-guardrails.md`：安装、使用、故障处理和 GitHub 设置说明。
- `docs/superpowers/plans/2026-08-03-repository-workflow-quality-guardrails.md`：实施计划。

### 修改

- `AGENTS.md`：引用机械检查入口并明确 CI 通过不替代语义复核。
- `PROJECT_GUIDE.md`：增加仓库防护、前后端 CI 和使用入口。
- `PROJECT_STATUS.md`：合并后记录工程防护能力及 GitHub 分支保护的现场验证边界。
- `docs/superpowers/README.md`：登记本任务 spec/plan。
- `.gitattributes`：确保 Git Hook 使用 LF，PowerShell 脚本使用稳定文本规则。

## 8. 数据流与失败处理

### 8.1 本地提交

`git commit` → `pre-commit` → 检查是否为 `main` → 调用暂存差异防回退检查 → 通过后创建提交。

任一步失败时返回非零退出码，Git 不创建提交。输出必须说明失败文件、规则和修复方向。

### 8.2 本地推送

`git push` → `pre-push` 读取引用更新 → 目标为远程 `main` 时失败 → 任务分支允许继续。

Hook 判断远程目标引用，而不是只判断当前本地分支，避免从其他本地分支直接执行 `HEAD:main` 绕过。

### 8.3 Pull Request

PR 事件 → 后端 CI、前端 CI、仓库防回退 CI 并行 → GitHub 分支保护等待三个必需检查 → 全部通过且对话解决后允许合并。

GitHub Actions 失败只阻止合并，不自动修改代码或 PR 正文。

### 8.4 合并后清理

用户通知“已合并” → 主代理先运行清理脚本默认模式查看动作 → 确认分支已进入 `origin/main` 且 worktree 干净 → 使用 `-Apply` 执行快进、worktree 移除和本地分支删除。

网络失败、无法快进、分支未合并、路径不匹配或工作区不干净时全部停止，不做部分破坏性清理。

## 9. 测试策略

### 9.1 脚本与 Hook 测试

`scripts/tests/Invoke-RepositoryGuardrailTests.ps1` 在系统临时目录创建独立 Git 仓库，覆盖：

- `main` 上 pre-commit 失败；
- 任务分支 pre-commit 允许通过；
- pre-push 对远程 `main` 失败、对任务分支通过；
- 安装脚本只配置当前仓库；
- 预检对 `main`、脏工作区、缺入口文件、过期基线和未安装 Hook 分别失败；
- 新 Java 生产类无 Javadoc 失败；
- 新核心前端文件无说明失败；
- 修改已有大型 Java 类但 PR 未列出其私有方法时失败；
- 修改已有 Vue/TypeScript 文件但 PR 方法清单不完整时失败；
- 方法标记为简单但没有具体排除原因时失败；
- 完整覆盖变化文件全部方法、关键性、处理结果和调用链的报告通过；
- 新增低质量注释失败；
- PR 缺少必填章节或注释证据失败；
- 合法的文档-only PR 和具备注释证据的生产代码 PR 通过；
- 清理脚本默认模式不改变仓库；分支未合并或 worktree 脏时 `-Apply` 失败。

测试只删除自己创建并验证位于系统临时目录下的测试仓库。

### 9.2 仓库验证

- PowerShell 脚本测试通过；
- 直接执行 Hook 的成功与失败场景通过；
- `git diff --check` 通过；
- YAML 能被解析，Action 名称与分支保护文档一致；
- 后端执行 `./mvnw test` 或 Windows 对应 Wrapper；
- 前端执行 `npm run test:run`、`npm run check`、`npm run build`；
- 检查差异没有业务代码、数据库、运行配置和生成文件。

## 10. Git 与交付边界

- 使用单一分支 `chore/workflow-quality-guardrails`。
- 允许按职责拆成“设计与计划”“本地防护”“CI 与 PR 防回退”“文档入口”多个清晰提交。
- 子代理不得提交、推送、创建分支或管理 worktree。
- 推送任务分支后，AI 只提供 Compare 链接、中文 PR 标题、说明和测试结果，由用户创建和合并 PR。
- GitHub 分支保护是合并后的外部配置步骤，未现场确认前必须标记为“待启用/待验证”。
- 用户通知“已合并”后，才同步 `main` 并清理本地任务分支和 worktree。

## 11. 验收标准

- 本地在 `main` 执行 commit 会被拒绝。
- 任何尝试更新远程 `main` 的 push 会被本地 Hook 拒绝。
- 任务分支正常 commit 和 push 不受阻。
- 新任务可以通过一个预检命令发现分支、基线、工作区和 Hook 问题。
- 合并后清理默认不改变状态，只有通过全部安全检查并显式 `-Apply` 才执行。
- 所有 PR 自动运行后端 CI、前端 CI 和仓库防回退检查。
- 生产代码 PR 缺少注释审查证据时检查失败。
- 生产代码 PR 漏掉任一变化文件或可识别方法/函数时检查失败。
- 修改现有大型类与新增类采用相同的完整方法清单要求，不能只检查新增文件。
- 新业务类或核心前端文件完全没有业务说明时检查失败。
- 所有变化文件的方法/函数必须进入审查清单；不要求每个简单方法都写注释，但必须逐项填写无需注释的具体理由。
- PR 模板和 CI 明确区分机械检查与业务语义复核。
- 文档准确说明 GitHub 分支保护必须单独启用；未验证时不声称已经强制。
- `PROJECT_GUIDE.md`、`PROJECT_STATUS.md` 和历史任务目录按各自职责更新。
- 最终差异不包含业务行为修改和无关文件。
