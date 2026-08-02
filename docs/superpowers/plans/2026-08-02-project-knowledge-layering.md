# Project Knowledge Layering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 `AGENTS.md`、`PROJECT_GUIDE.md`、`PROJECT_STATUS.md`、Codex Memories 和当前会话之间可持续维护的信息分层。

**Architecture:** `AGENTS.md` 只定义固定路由规则，`PROJECT_GUIDE.md` 保存稳定项目地图，`PROJECT_STATUS.md` 保存当前 Git 版本的动态状态，任务设计与计划继续保留在 `docs/superpowers`。Codex Memories 通过受支持的纠正说明回归个人偏好、一般背景和跨项目经验，不再承担项目状态管理。

**Tech Stack:** Markdown、Git、PowerShell、Codex Memories ad-hoc correction notes

## Global Constraints

- 本任务只修改项目文档和固定工作规则，不修改业务代码、数据库、接口或运行配置。
- 不重写或删除现有 `docs/superpowers/specs` 和 `docs/superpowers/plans`。
- 不把密码、Token、私钥、数据库凭据、临时备份路径或未脱敏数据写入文档。
- `PROJECT_GUIDE.md` 只保存稳定信息，`PROJECT_STATUS.md` 只保存其所在 Git 版本可验证的当前状态。
- 旧设计和实施计划属于历史任务记录；本任务只在项目指南中说明其使用边界，不批量修正文档正文。
- 仓库变更必须保留在 `docs/project-knowledge-layering` 分支，使用明确文件列表暂存。
- Codex Memories 不能直接修改主记忆文件，只能在 `extensions/ad_hoc/notes` 新增一份小型纠正说明。

---

## File Map

- Create: `PROJECT_GUIDE.md` — 稳定项目入口，说明业务范围、架构、数据链路、目录和文档导航。
- Create: `PROJECT_STATUS.md` — 动态状态入口，说明当前阶段、已完成、未完成、风险、技术债和下一步。
- Modify: `AGENTS.md` — 新增“规则四：项目信息分层与维护”，规定后续 AI 的读取和更新行为。
- Create outside Git: `C:\Users\yang\.codex\memories\extensions\ad_hoc\notes\20260802-211928-iot-project-memory-slimming.md` — 请求瘦身项目状态类长期记忆。
- Existing design: `docs/superpowers/specs/2026-08-02-project-knowledge-layering-design.md` — 本计划的设计依据。
- This plan: `docs/superpowers/plans/2026-08-02-project-knowledge-layering.md` — 实施步骤和验证清单。

## Task 0: 自审并提交实施计划

**Files:**
- Create: `docs/superpowers/plans/2026-08-02-project-knowledge-layering.md`

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-08-02-project-knowledge-layering-design.md`
- Produces: Tasks 1-4 使用的已提交实施步骤

- [ ] **Step 1: 执行禁止词和格式扫描**

Run:

```powershell
$patterns = @('T' + 'BD', 'TO' + 'DO', '待' + '定', '占' + '位', '类似 ' + 'Task', '适当' + '处理')
Select-String -Path docs/superpowers/plans/2026-08-02-project-knowledge-layering.md -Pattern $patterns
git diff --check
```

Expected: 禁止词扫描无输出；diff check 通过。

- [ ] **Step 2: 提交计划文件**

```bash
git add -- docs/superpowers/plans/2026-08-02-project-knowledge-layering.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(project): plan knowledge layering rollout"
```

Expected: 只提交本计划文件。

## Task 1: 建立稳定项目指南和固定信息路由规则

**Files:**
- Create: `PROJECT_GUIDE.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: 当前代码目录、`docs/设计冻结书-V1.0-19测点.md`、`docs/MQTT-硬件数据对接说明.md`、`docs/HVAC控制能力设计备忘.md`、`docs/development/java21.md`
- Produces: 后续任务开始时使用的稳定项目地图和强制读取、更新规则

- [ ] **Step 1: 创建 `PROJECT_GUIDE.md`**

文件必须包含以下章节和事实：

```markdown
# iot-platform-demo 项目指南

## 1. 使用方式
说明先读 AGENTS、GUIDE、STATUS，再按需读专题文档。

## 2. 项目定位与 V1 边界
说明中央空调经常性调适、V1 单体、19 测点、查询和分析优先、禁止恢复旧电表 Demo、控制能力不在 V1。

## 3. 系统结构与模块职责
列出 web、system/security、hvac、iot/ingest、iot/aggregation、iot/dataquality、iot/formula、iot/temporal、cache、generator、config。

## 4. 核心业务数据链路
列出 MQTT 采集到前端展示、登录权限、前端真实数据三条可追踪链路。

## 5. 数据源与外部资源边界
说明 MySQL、TDengine、Redis、MQTT、WebSocket 的职责和测试隔离。

## 6. 主要代码与配置入口
链接 PlatformApplication、MqttConfig、HvacMqttMessageHandler、HvacFormulaEngine、HvacQueryController、HvacDemoPage、docker-compose、示例配置。

## 7. 文档导航和生命周期
区分当前项目入口、当前专题说明、历史任务设计/计划、Git 历史；明确历史文档不是当前状态来源。

## 8. 维护规则
规定只在稳定结构、边界、数据流和文档入口变化时更新。
```

- [ ] **Step 2: 在 `AGENTS.md` 末尾新增规则四**

新增内容必须使用以下结构：

```markdown
## 规则四：项目信息分层与维护

### 1. 信息职责
明确 Memories、AGENTS、GUIDE、STATUS、任务文档、当前会话的唯一职责。

### 2. 任务开始时的读取顺序
AGENTS -> PROJECT_GUIDE -> PROJECT_STATUS -> 任务相关专题文档和代码。

### 3. 任务执行与交付时的更新规则
稳定变化更新 GUIDE；动态状态变化更新 STATUS；具体设计和计划进入 docs/superpowers；会话细节不机械沉淀。

### 4. 冲突和过期处理
项目事实以当前 Git 代码和文档为准；证据不足标记待核验；历史任务文档不得冒充当前状态。

### 5. 安全边界
禁止写入凭据、临时备份路径、生产数据和无证据完成声明。

### 6. 完成检查
检查该更新的入口是否已更新、是否重复、是否泄露敏感信息。
```

- [ ] **Step 3: 验证稳定入口职责和链接目标**

Run:

```powershell
rg -n "^## " PROJECT_GUIDE.md AGENTS.md
Test-Path PROJECT_STATUS.md
Test-Path docs/设计冻结书-V1.0-19测点.md
Test-Path docs/MQTT-硬件数据对接说明.md
Test-Path src/main/java/com/platform/iot/ingest/HvacMqttMessageHandler.java
Test-Path web/src/pages/HvacDemoPage.vue
```

Expected: GUIDE 和 AGENTS 显示全部计划章节；`PROJECT_STATUS.md` 在 Task 2 前为 `False`；其他引用目标全部为 `True`。

- [ ] **Step 4: 提交稳定指南和固定规则**

```bash
git add -- AGENTS.md PROJECT_GUIDE.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(project): add project guide and knowledge rules"
```

Expected: 只提交 `AGENTS.md` 和 `PROJECT_GUIDE.md`。

## Task 2: 建立当前项目状态入口

**Files:**
- Create: `PROJECT_STATUS.md`

**Interfaces:**
- Consumes: `origin/main` 基线 `e1fc110`、当前代码、已合并 PR 历史、`PROJECT_GUIDE.md`
- Produces: 后续任务判断当前完成范围、未完成项和优先级的唯一动态入口

- [ ] **Step 1: 创建 `PROJECT_STATUS.md`**

文件必须包含以下已核验内容：

```markdown
# iot-platform-demo 项目状态

## 1. 状态基准
记录日期 2026-08-02、main 基线 e1fc110，说明运行环境状态不属于 Git 项目状态。

## 2. 当前阶段
V1 功能整合与稳定化；继续保持单体，不进行微服务拆分。

## 3. 已完成并进入 main
四角色 RBAC 和建筑范围；HVAC 资产与 19 测点；MQTT 接入；TDengine 原始、分钟、指标；数据质量补全和重算；四类指标公式；Redis 缓存；查询 API；WebSocket 后端端点；前端真实数据基线；旧电表 Demo 下线；本地基础设施稳定卷和端口配置。

## 4. 尚未完成或待继续
前端 WebSocket 未接入、公式计算详情未接入、历史数据页面能力待核验和实现、真实现场长期运行验收未完成。

## 5. 当前阻塞与风险
无已确认的代码阻塞；外部资源和现场数据必须以实际环境验证为准，不能由 Git 推断。

## 6. 已确认技术债
设计冻结书第 11、12 章包含阶段性旧状态；历史 specs/plans 容易被误读；前端仍以 30 秒轮询代替 WebSocket。

## 7. 下一步优先级
前端公式/历史真实能力 -> 前端 WebSocket -> 文档健康核验 -> 现场运行验收。

## 8. 证据入口
链接 GUIDE、冻结书、MQTT 说明、关键后端类、前端页面和相关设计目录。

## 9. 更新规则
只有其所在 Git 版本有证据的事项才能标记完成；任务分支随实现同步更新，合并后成为 main 当前状态。
```

- [ ] **Step 2: 验证动态状态没有冒充本地运行结果**

Run:

```powershell
rg -n "^## " PROJECT_STATUS.md
rg -n "WebSocket|公式计算详情|文档健康|现场" PROJECT_STATUS.md
rg -n -i "localhost|127\.0\.0\.1|password\s*[:=]|secret\s*[:=]|123456" PROJECT_STATUS.md
```

Expected: 显示九个章节和四类未完成事项；敏感或本机运行信息扫描无输出。

- [ ] **Step 3: 提交状态入口**

```bash
git add -- PROJECT_STATUS.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(project): add current project status"
```

Expected: 只提交 `PROJECT_STATUS.md`。

## Task 3: 提交 Codex Memories 瘦身纠正说明

**Files:**
- Create outside Git: `C:\Users\yang\.codex\memories\extensions\ad_hoc\notes\20260802-211928-iot-project-memory-slimming.md`

**Interfaces:**
- Consumes: 已确认的四层信息职责
- Produces: 后续 Codex 记忆整理使用的小型纠正请求

- [ ] **Step 1: 新增纠正说明**

文件必须只表达以下内容：

```markdown
# iot-platform-demo 项目记忆瘦身纠正

## 保留
- 用户个人偏好、沟通方式、交付习惯和一般技术背景。
- 不依赖单个仓库版本的跨项目经验。

## 移出长期记忆
- iot-platform-demo 的提交号、PR 编号、分支状态和单次测试数量。
- 页面、接口、模块和文件的阶段性完成状态。
- 本地端口、进程、容器、备份路径和临时排查结果。
- 已经能够从 Git 代码、PROJECT_GUIDE.md、PROJECT_STATUS.md 或任务文档获取的项目事实。

## 后续路由
- 项目固定规则读取 AGENTS.md。
- 稳定项目结构读取 PROJECT_GUIDE.md。
- 当前项目状态读取 PROJECT_STATUS.md。
- 具体执行细节只保留在当前会话或任务级设计与计划中。

本纠正只调整记忆边界，不声明或修改项目当前完成状态。
```

- [ ] **Step 2: 验证纠正说明未混入项目状态或敏感信息**

Run:

```powershell
Get-Content -Raw -Encoding UTF8 C:\Users\yang\.codex\memories\extensions\ad_hoc\notes\20260802-211928-iot-project-memory-slimming.md
```

Expected: 只包含“保留、移出长期记忆、后续路由”三类规则；不包含真实密码、Token、数据库账号或项目完成清单。

## Task 4: 完成全局文档验证并推送分支

**Files:**
- Verify: `AGENTS.md`
- Verify: `PROJECT_GUIDE.md`
- Verify: `PROJECT_STATUS.md`
- Verify: `docs/superpowers/specs/2026-08-02-project-knowledge-layering-design.md`
- Verify: `docs/superpowers/plans/2026-08-02-project-knowledge-layering.md`

**Interfaces:**
- Consumes: Tasks 1-3 的全部交付物
- Produces: 可审阅的文档分支和完整 PR 材料

- [ ] **Step 1: 执行全局文档检查**

Run:

```powershell
rg -n "^## " PROJECT_GUIDE.md PROJECT_STATUS.md AGENTS.md
rg -n -i "password\s*[:=]|passwd\s*[:=]|secret\s*[:=]|api[_-]?key\s*[:=]|123456" PROJECT_GUIDE.md PROJECT_STATUS.md docs/superpowers/specs/2026-08-02-project-knowledge-layering-design.md docs/superpowers/plans/2026-08-02-project-knowledge-layering.md
git diff main...HEAD --check
git status --short --branch
```

Expected: 必要章节齐全；敏感值扫描无输出；diff check 通过；工作区干净。

- [ ] **Step 2: 核对任务范围**

Run:

```powershell
git diff --name-only main...HEAD
git log --oneline main..HEAD
```

Expected repository files:

```text
AGENTS.md
PROJECT_GUIDE.md
PROJECT_STATUS.md
docs/superpowers/plans/2026-08-02-project-knowledge-layering.md
docs/superpowers/specs/2026-08-02-project-knowledge-layering-design.md
```

Expected: 不包含业务代码、运行配置、旧文档改写或本地文件。

- [ ] **Step 3: 推送任务分支并准备 PR 材料**

```bash
git push -u origin docs/project-knowledge-layering
```

Expected: 远程分支创建成功；向用户提供 Compare 链接、PR 标题、说明、文档检查结果和“等待用户创建并合并 PR”状态。

## Plan Self-Review

- 设计目标分别由 Tasks 1-3 覆盖，Task 4 负责统一验收和 Git 交付。
- 文件职责与设计一致，没有新增 ADR、独立路线图或技术债文件。
- 没有业务代码、数据库或运行配置变更。
- 旧文档问题只进入 `PROJECT_STATUS.md` 技术债，不在本任务批量修正。
- Codex Memories 更新使用受支持的 ad-hoc note，不直接编辑主记忆文件。
- 所有修改、验证、提交和推送命令均给出明确文件范围和预期结果。
