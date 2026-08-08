# iot-platform-demo 项目规则

本文件适用于整个仓库。用户当前指令优先；个人全局规则继续生效。本文件只保存每次任务都必须加载的硬约束和文档路由，详细规范按任务需要读取。

## 1. 建立任务上下文

每个任务开始时依次读取：

1. 本文件；
2. [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md)：稳定项目定位、模块、数据链路和文档入口；
3. [`PROJECT_STATUS.md`](PROJECT_STATUS.md)：当前完成项、技术债、风险和下一步；
4. 只读取与当前任务直接相关的设计、计划、代码和测试，不批量加载历史文档。

按任务条件继续读取：

- Git、Hook、预检、PR、CI 或合并后清理：[`docs/development/repository-guardrails.md`](docs/development/repository-guardrails.md)；
- Java、Vue 或 TypeScript 生产代码：[`docs/development/code-comments.md`](docs/development/code-comments.md)；
- 后端、前端、配置、测试、接口或跨端变化：[`docs/development/verification.md`](docs/development/verification.md)；
- Java 版本和环境要求：[`docs/development/java21.md`](docs/development/java21.md)；
- 具体历史决策：从 [`docs/superpowers/README.md`](docs/superpowers/README.md) 定位对应 spec/plan，不能把历史状态当作当前事实。

文档与代码冲突时，核验当前代码、自动化测试和已合并决策；证据不足必须标记为“部分完成”“未验证”或“待核验”。

## 2. 范围、设计与 Git 流程

一个任务、分支和 PR 只处理一个明确问题。禁止顺手提交无关格式化、重构、配置、生成文件或本地状态。

新模块、新 API、数据库结构、权限、缓存、并发、数据流程、跨子系统功能或大范围架构变化，必须先确认设计和实施范围。纯文档、注释、拼写和不改变行为的简单格式调整可以跳过独立设计，但不能跳过范围、验证和 Git 流程。

开始修改前必须：

1. 检查分支、远程和工作区，确认没有来源不明的改动；
2. `git fetch --prune origin`；
3. 切换本地 `main`，仅使用 `git merge --ff-only origin/main` 同步；
4. 从最新 `main` 创建 `feature/`、`fix/`、`perf/`、`refactor/`、`docs/`、`test/` 或 `chore/` 短期分支；
5. 在干净任务分支运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-TaskPreflight.ps1
```

成功标记必须是 `TASK_PREFLIGHT_OK`。首次 clone 或 Hook 未安装时，按仓库防护文档运行安装脚本。预检不能代替实际阅读规则。

如果本地 `main` 分叉、存在未推送提交、工作区不干净或基线不明，停止并报告；不得自动覆盖、重置、变基或把无关工作带入任务分支。

暂存必须明确列出文件：

```powershell
git add -- <file1> <file2>
git diff --cached --name-only
git diff --cached --check
```

禁止使用 `git add .` 随意暂存全部内容。提交信息采用 `type(scope): 简短说明`，每个提交内容单一、已验证且不含敏感信息、临时文件或无关改动。不得使用 `--no-verify` 绕过 Hook。

验证通过后推送任务分支，AI 提供 Compare 链接、PR 标题、说明、范围、测试、注释审计、冲突和无关文件检查。默认由用户创建并合并 PR；AI 交付后状态是“等待用户创建并合并 PR”，不能声称已经全部完成。

只有用户明确通知“已合并”后，才能同步本地 `main` 并安全清理本地分支或 worktree。优先使用仓库清理脚本的预览和显式 `-Apply` 流程。

除非用户明确授权并确认恢复方案，禁止：

- 直接在本地或远程 `main` 开发、提交或推送；
- `git push --force`、`git push --force-with-lease`、`git reset --hard`；
- 删除未合并分支、覆盖用户未提交内容、擅自丢弃提交；
- 提交密码、Token、私钥、个人环境配置和未脱敏数据。

## 3. 当前架构硬边界

- V1 保持 Spring Boot 单体后端和 Vue 前端；不因项目可能变大而提前拆仓库或微服务。正式拆分必须有独立部署、扩容、故障隔离、团队发布或已证实瓶颈等明确收益，并单独设计。
- 新功能先确定业务归属。Controller 负责入口，Service 负责业务和事务，Repository/Mapper 负责数据访问，Adapter/Client 负责外部系统，Config 只负责装配。禁止 Controller 直连数据层、跨模块直接操作其他模块 Repository、循环依赖和无边界大型 Service。
- 公共能力必须已有两个以上真实使用方、含义稳定且不会形成反向依赖；禁止为未来假设提前抽象万能 `common`、`utils`。
- MySQL 保存结构化业务数据，TDengine 保存原始时序、分钟和指标数据，Redis 是缓存，MQTT 负责设备上行，WebSocket 负责实时发布。多数据源必须明确 Bean 和 `Qualifier`，SQL 不得跨数据源执行。
- 外部资源地址、凭据和开关通过配置管理；不得硬编码环境信息。启动初始化、定时任务、补偿、MQTT 连接和演示数据必须在测试环境可关闭或替换。
- 普通自动化测试不得连接真实 MySQL、TDengine、Redis、MQTT Broker、现场设备或第三方服务；仅明确标记的集成/现场验证可以使用专用环境。
- 前端不得生成随机业务数据、虚假历史或默认指标，不在浏览器重算后端指标。前端权限只改善体验，后端鉴权和建筑范围才是安全边界。
- 旧电表 Demo 已退出当前产品范围，不得恢复为并行业务链。当前只有采集、分析和展示能力，控制能力必须另行安全设计和验收。
- 发现现有边界无法承载需求时，先说明涉及模块、最小改动、小范围整理、正式重构及成本风险；未经确认不得扩大重构。

稳定模块职责、数据链路和当前阶段策略以 `PROJECT_GUIDE.md` 为准，不在本文件重复维护具体实现清单。

## 4. 验证与结果

任何改动至少执行与范围直接相关的检查。生产 Java 代码通常需要定向测试和完整 Maven 测试；Vue/TypeScript 生产代码通常需要定向 Vitest、完整测试、lint、类型检查和构建。具体命令和专项边界必须读取 [`verification.md`](docs/development/verification.md)。

测试失败时不得推送为可合并 PR。提交和 PR 必须如实列出实际命令、通过、失败、跳过、未执行原因和自动化不能覆盖的人工确认。不能复用历史测试数量或把后端端点存在描述成完整用户功能。

纯文档或流程整改不机械运行无关业务测试，但必须运行与变化直接相关的链接、脚本、guardrail、配置解析和 Git 差异检查。

## 5. 生产代码注释与审计

修改 Java、Vue 或 TypeScript 生产代码前读取 [`code-comments.md`](docs/development/code-comments.md)。注释使用简洁中文，重点说明职责、上下游、数据源、业务边界、单位、状态、异常、副作用和设计原因；不逐行翻译，不记录任务历史，不把预留能力写成已实现。

只要生产文件发生变化，就检查该文件完整当前内容和全部方法/函数，包括私有项，并复核已有注释时效。

每个包含生产代码变化的 PR 必须新增一份：

```text
docs/reviews/comment-audits/<year>/<YYYY-MM-DD>-<task>.md
```

生成命令必须显式提供 `-OutputPath`，完整命令查看仓库防护文档。新任务不能复用 base 中的历史报告；报告必须人工完成，不能保留占位或只依赖 CI 判断语义。没有生产代码变化时，PR 填写“不涉及生产代码”，不创建空报告。

## 6. 项目信息分层

- `AGENTS.md`：长期固定规则和条件式文档路由，不记录任务进度；
- `PROJECT_GUIDE.md`：稳定项目定位、模块、数据链路、运行入口和文档导航；
- `PROJECT_STATUS.md`：当前版本的完成项、未完成项、风险、技术债和下一步；
- `docs/development`：按需读取的长期开发规范；
- `docs/superpowers/specs`、`plans`：具体任务当时的设计与实施历史；
- `docs/reviews/comment-audits`：生产代码 PR 的不可变注释审计快照；
- 当前会话：命令、错误、临时判断和未进入 Git 的状态；
- Codex Memories：个人偏好和跨项目经验，不保存本项目提交号、测试数量、端口、进程和临时路径。

稳定架构或文档入口变化时更新 `PROJECT_GUIDE.md`；阶段、完成项、技术债、风险或下一步变化时更新 `PROJECT_STATUS.md`。文档与代码一起走分支、验证、提交和 PR 流程。

任何项目文档都不得写入密码、Token、私钥、数据库凭据、未脱敏数据、个人临时路径、端口/进程快照或无证据完成声明。

## 7. 交付检查

交付前确认：

1. 变化严格属于当前任务，无无关文件和提交；
2. 设计边界、模块归属和数据源边界未被破坏；
3. 相关测试和检查已执行并如实记录；
4. 生产代码注释和任务级审计已完成，或明确不涉及生产代码；
5. `PROJECT_GUIDE.md`、`PROJECT_STATUS.md` 是否需要同步更新；
6. `git diff --check`、暂存文件、敏感信息和生成文件检查通过；
7. 已提交、推送任务分支并提供完整 PR 材料。
