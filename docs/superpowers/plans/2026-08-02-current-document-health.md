# 当前文档健康与历史任务文档治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为全部历史任务文档建立可追踪的状态目录和文件级警告，并依据当前代码证据修正六份面向当前读者的文档。

**Architecture:** 使用 `docs/superpowers/README.md` 维护任务配对、命名差异和替代关系，使用每个 spec/plan 顶部的统一警告保护直接打开旧文件的场景。当前事实仍由代码、`AGENTS.md`、`PROJECT_GUIDE.md` 和 `PROJECT_STATUS.md` 承载，历史正文只加状态说明、不批量改写。

**Tech Stack:** Markdown、Git、PowerShell、ripgrep、Maven Wrapper（只在需要验证 MQTT 契约时运行定向测试）

## Global Constraints

- 最终基线为 26 份 specs、27 份 plans，共 53 份 Markdown 历史任务文档和 27 个逻辑任务。
- 不删除、移动、合并或改写历史 specs/plans 的正文。
- 每份历史文件只增加统一警告；确认存在替代关系时才增加一条“已知替代”说明。
- 六份当前文档只修正确认有代码、配置、测试或已合并决策证据的问题。
- 不修改业务代码、数据库、接口、运行配置或外部资源。
- 不提交真实密码、个人电脑绝对路径、临时文件或一次性治理脚本。
- 所有 Git 暂存都显式列出文件，不使用 `git add .`。

---

## 文件结构与职责

- Create: `docs/superpowers/README.md`：历史任务中央目录、配对关系、状态和替代链。
- Modify: `docs/superpowers/specs/*.md`：26 份设计记录顶部增加设计类历史警告。
- Modify: `docs/superpowers/plans/*.md`：27 份实施计划顶部增加计划类历史警告。
- Modify: `PROJECT_GUIDE.md`：稳定入口、文档类型和使用优先级。
- Modify: `PROJECT_STATUS.md`：当前状态基准和文档健康清单。
- Modify: `docs/设计冻结书-V1.0-19测点.md`：保留设计基线，分离当前能力、历史计划和未来路线图。
- Modify: `docs/MQTT-硬件数据对接说明.md`：与现有 MQTT 配置及运行契约对齐。
- Modify: `docs/HVAC控制能力设计备忘.md`：明确当前未实现、仅保存未来控制约束。
- Modify: `docs/development/java21.md`：改成跨机器的 Java 21 环境说明。

### Task 1: 建立中央目录并标记全部历史文件

**Files:**
- Create: `docs/superpowers/README.md`
- Modify: `docs/superpowers/specs/*.md`（26 个现有 Markdown 文件）
- Modify: `docs/superpowers/plans/*.md`（包含本计划在内的 27 个 Markdown 文件）

**Interfaces:**
- Consumes: `AGENTS.md`、`PROJECT_GUIDE.md`、`PROJECT_STATUS.md` 提供的当前事实入口，以及设计稿中确认的配对和替代关系。
- Produces: 27 行逻辑任务目录；每个文件唯一出现一次；全部 53 个文件具有与目录相连的文件级状态标识。

- [ ] **Step 1: 重新枚举文件并锁定治理基线**

Run:

```powershell
$specs = @(rg --files docs/superpowers/specs -g '*.md')
$plans = @(rg --files docs/superpowers/plans -g '*.md')
"specs=$($specs.Count) plans=$($plans.Count) total=$($specs.Count + $plans.Count)"
```

Expected: `specs=26 plans=27 total=53`。数量不符时先找出新增、删除或遗漏文件，不继续生成目录。

- [ ] **Step 2: 新增中央目录的固定说明和表格**

Create `docs/superpowers/README.md`，开头必须包含：

```markdown
# 历史任务设计与实施计划目录

本目录中的 specs 和 plans 保存任务发生时的设计依据与实施步骤，不是当前项目状态入口。
判断当前行为时，依次核对当前代码与测试、[固定工作规则](../../AGENTS.md)、
[项目指南](../../PROJECT_GUIDE.md) 和 [项目状态](../../PROJECT_STATUS.md)。

计划中的复选框只记录原计划步骤，不代表当前完成状态；执行旧命令前必须重新核验。

| 任务主题 | 设计记录 | 实施计划 | 关系状态 | 当前使用说明 |
| --- | --- | --- | --- | --- |
```

表格覆盖 27 个逻辑任务，固定使用 `成对历史记录`、`名称不一致`、`仅实施计划`、`被后续任务替代`、`待核验` 五种状态。明确记录：

- `building-scoped-equipment-point-identity-design` 与 `industrial-asset-point-identity-implementation` 为名称不一致的一对；
- `rbac-code-comments-plan` 为仅实施计划；
- 旧电表并行链被 `legacy-meter-demo-decommission` 替代；
- `/ws/dashboard` 被 `/ws/hvac` 替代；
- `COP 尚未实现` 被 `hvac-core-formula-engine` 替代；
- 临时 Docker 卷和 hostname 被稳定卷与稳定 hostname 任务替代；
- 前端假数据状态以当前 `PROJECT_STATUS.md` 为准。

- [ ] **Step 3: 给 26 份 specs 添加统一警告**

在每个 spec 的首个标题之后插入一次：

```markdown
> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。
```

使用 `apply_patch` 修改文件；先搜索相同标记，已经存在时不得重复插入。

- [ ] **Step 4: 给 27 份 plans 添加统一警告**

在每个 plan 的首个标题之后插入一次：

```markdown
> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。
```

使用 `apply_patch` 修改文件；先搜索相同标记，已经存在时不得重复插入。

- [ ] **Step 5: 给已确认过期描述增加最小替代说明**

只在包含下列已确认旧现状的文件警告后增加一行，不修改其历史正文：

```markdown
> **已知替代：** 本文中的旧电表并行链、旧 WebSocket 路径、COP 未实现状态或临时容器存储描述，已由中央目录列出的后续任务替代。
```

Run this evidence scan before deciding the exact affected files:

```powershell
rg -n "保留.*电表|旧电表|COP.*尚未|尚未.*COP|/ws/dashboard|临时.*卷|临时.*hostname|匿名卷" docs/superpowers/specs docs/superpowers/plans
```

Expected: 每个匹配项都在 README 的替代说明中有归属；不能确认替代关系的条目标为 `待核验`，不擅自称为错误。

- [ ] **Step 6: 验证目录覆盖和警告唯一性**

Run:

```powershell
$specs = @(rg --files docs/superpowers/specs -g '*.md')
$plans = @(rg --files docs/superpowers/plans -g '*.md')
$specBannerCount = (rg -l "文档状态：历史任务设计记录" docs/superpowers/specs -g '*.md').Count
$planBannerCount = (rg -l "文档状态：历史任务实施计划" docs/superpowers/plans -g '*.md').Count
"specs=$($specs.Count)/$specBannerCount plans=$($plans.Count)/$planBannerCount"
```

Expected: `specs=26/26 plans=27/27`，并且：

```powershell
rg -n "文档状态：历史任务设计记录" docs/superpowers/specs -g '*.md'
rg -n "文档状态：历史任务实施计划" docs/superpowers/plans -g '*.md'
```

每个文件仅出现一次对应警告。

- [ ] **Step 7: 提交中央目录和历史标识**

```powershell
git add -- docs/superpowers/README.md docs/superpowers/specs docs/superpowers/plans
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(project): classify historical task records"
```

Expected: 暂存内容只包含中央目录、26 份 specs 和 27 份 plans；提交成功。

### Task 2: 修正项目统一入口和设计冻结书

**Files:**
- Modify: `PROJECT_GUIDE.md`
- Modify: `PROJECT_STATUS.md`
- Modify: `docs/设计冻结书-V1.0-19测点.md`

**Interfaces:**
- Consumes: Task 1 的 `docs/superpowers/README.md`，以及当前代码、测试和已合并决策。
- Produces: 清晰的文档优先级、动态状态入口、文档健康表，以及不再冒充当前进度的 V1 设计基线。

- [ ] **Step 1: 更新项目指南的文档导航**

在 `PROJECT_GUIDE.md` 第 7 章明确：

- `AGENTS.md` 是固定工作规则；
- `PROJECT_GUIDE.md` 是稳定项目指南；
- `PROJECT_STATUS.md` 是当前状态入口；
- `docs/设计冻结书-V1.0-19测点.md` 是 V1 设计基线，不是进度表；
- `docs/superpowers/README.md` 是历史任务目录，specs/plans 不单独证明当前实现。

不得把 `PROJECT_STATUS.md` 的完成清单复制到指南中。

- [ ] **Step 2: 修正项目状态基准并增加文档健康表**

把 `PROJECT_STATUS.md` 中固定提交号：

```markdown
- 代码基线：`main` 提交 `e1fc110`。
```

替换为：

```markdown
- 代码基线：以本文件所在 Git 版本及其已合并代码、测试为准，不在文档中固定易过期的提交号。
```

新增 `文档健康` 表，逐项记录 `PROJECT_GUIDE.md`、`PROJECT_STATUS.md`、设计冻结书、MQTT 对接说明、HVAC 控制备忘和 Java 21 指南的核验状态、证据入口及剩余问题。已经修复的设计冻结书问题从“已确认技术债”和“下一步”中移除；未完成的业务功能保持不变。

- [ ] **Step 3: 分离设计冻结书的设计、现状、历史计划和未来设想**

在 `docs/设计冻结书-V1.0-19测点.md` 标题后增加：

```markdown
> **文档定位：V1 业务与技术设计基线**
>
> 本文保留已冻结的范围、数据、公式和安全边界；当前完成状态以
> [项目状态](../PROJECT_STATUS.md) 为准。第十二章是原始排期，附录 C 是未来扩展讨论，均不代表当前已经实现或仍待实施。
```

将第 11 章改为“当前能力概览”，只保留有现有代码和配置支持的能力，并链接 `PROJECT_STATUS.md`；删除“本期需新增”式的当前待办措辞。将第 12 章标题改为“原始实施计划（历史记录）”，在表格前说明它不是当前待办。附录 C 开头明确多实例、读写分离、Kafka、Nginx 和分布式锁均为未来扩展讨论，不代表当前实现。

- [ ] **Step 4: 核对入口文档之间没有互相冲突**

Run:

```powershell
rg -n "e1fc110|本期需新增|实施计划（一个月）|已经实现.*Kafka|已经实现.*Nginx" PROJECT_GUIDE.md PROJECT_STATUS.md docs/设计冻结书-V1.0-19测点.md
```

Expected: 不再出现固定旧提交号和“本期需新增”；原始一个月计划只能以明确历史标题出现；未来组件不得被描述为已实现。

### Task 3: 按当前实现核验三个专题说明

**Files:**
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Modify: `docs/HVAC控制能力设计备忘.md`
- Modify: `docs/development/java21.md`

**Interfaces:**
- Consumes: `src/main/resources/application.yml`、`MqttConfig`、MQTT 载荷与质量校验实现、WebSocket 端点、`pom.xml`、Maven Wrapper、Java 检查脚本和 CI 工作流。
- Produces: 与当前契约一致的 MQTT 说明、明确未实现状态的控制约束、跨机器可使用的 Java 21 指南。

- [ ] **Step 1: 收集 MQTT 与 WebSocket 的当前证据**

Run:

```powershell
rg -n "device/data/up|MQTT_FREEZE_V1|setQos|qos|ServerEndpoint|ws/hvac|payload|max.*payload|ACK|19" src/main/java src/main/resources src/test
```

Expected: 能确认上行主题、可信来源系统、QoS 1、`/ws/hvac` 端点，以及载荷和质量规则所在代码或测试；没有证据的字段不改写。

- [ ] **Step 2: 修正 MQTT 对接说明**

逐项核对主题、QoS、ACK、最大载荷、可信来源系统、19 个别名、HTTP 路径和 WebSocket 路径。保留已正确内容，把 `change-me` 明确标成“仅为非生产示例占位值，部署时必须由环境变量覆盖”，不得写入真实凭据。

- [ ] **Step 3: 证明当前没有 HVAC 控制下行实现并修正备忘**

Run:

```powershell
rg -n -i "control|command|downlink|下行|控制指令|指令状态" src/main/java web/src
```

Expected: 只有“当前不支持控制”的注释、展示文字或未来约束，没有控制 Controller、Service、下行主题、指令状态机和协议 Adapter。随后在 `docs/HVAC控制能力设计备忘.md` 顶部明确“未来安全约束，当前未实现”，并链接可验证的当前入口；若搜索发现真实控制实现，停止修改并把冲突记录到 `PROJECT_STATUS.md`。

- [ ] **Step 4: 用仓库版本证据重写 Java 21 环境说明**

Run:

```powershell
rg -n "<java.version>|distributionUrl|wrapperVersion|21|mvnw" pom.xml .mvn/wrapper/maven-wrapper.properties scripts/check-java21.ps1 .github/workflows/backend-ci.yml
```

Expected: Java 21、Maven Wrapper 3.9.9 和当前 CI 验证命令均有仓库证据。

删除 `F:\jdk21`、本机 Oracle javapath 顺序和管理员机器级 PATH 修改脚本，改为跨机器示例：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\scripts\check-java21.ps1
.\mvnw.cmd -version
```

同时提供 Linux/macOS 当前 shell 的 `JAVA_HOME` 示例；不承诺替用户永久修改系统环境变量。

- [ ] **Step 5: 提交六份当前文档的证据驱动修正**

```powershell
git add -- PROJECT_GUIDE.md PROJECT_STATUS.md docs/设计冻结书-V1.0-19测点.md docs/MQTT-硬件数据对接说明.md docs/HVAC控制能力设计备忘.md docs/development/java21.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs(project): align current documentation with implementation"
```

Expected: 暂存内容严格为六份当前文档；提交成功。

### Task 4: 全量验证、修正遗漏并交付

**Files:**
- Verify: `docs/superpowers/README.md`
- Verify: `docs/superpowers/specs/*.md`
- Verify: `docs/superpowers/plans/*.md`
- Verify: 六份当前文档

**Interfaces:**
- Consumes: Tasks 1-3 的全部文档变更。
- Produces: 覆盖完整、链接有效、无敏感信息、无无关改动的可推送任务分支。

- [ ] **Step 1: 验证文件数量、警告和 README 覆盖**

Run:

```powershell
$specs = @(rg --files docs/superpowers/specs -g '*.md')
$plans = @(rg --files docs/superpowers/plans -g '*.md')
$specBannerCount = @(rg -l "文档状态：历史任务设计记录" docs/superpowers/specs -g '*.md').Count
$planBannerCount = @(rg -l "文档状态：历史任务实施计划" docs/superpowers/plans -g '*.md').Count
"specs=$($specs.Count)/$specBannerCount plans=$($plans.Count)/$planBannerCount total=$($specs.Count + $plans.Count)"
```

Expected: `specs=26/26 plans=27/27 total=53`。逐文件 basename 搜索 README，确保每个文件恰好有一个目录链接。

```powershell
$readme = Get-Content -Raw -Encoding UTF8 docs/superpowers/README.md
$allHistoryFiles = @($specs) + @($plans)
$badCoverage = @($allHistoryFiles | Where-Object {
    $name = [IO.Path]::GetFileName($_)
    ([regex]::Matches($readme, [regex]::Escape($name))).Count -ne 1
})
if ($badCoverage.Count -gt 0) {
    $badCoverage
    throw "README coverage is not exactly once per file"
}
"README coverage=$($allHistoryFiles.Count)/$($allHistoryFiles.Count)"
```

Expected: `README coverage=53/53`。

- [ ] **Step 2: 验证 Markdown 相对链接目标存在**

对本任务修改文件提取不含 URL、锚点和图片的 Markdown 链接，按来源文件目录解析目标。Expected: 不存在缺失目标；带锚点的链接至少验证文件部分存在。

```powershell
$markdownFiles = @(git diff --name-only main...HEAD -- '*.md')
$missingLinks = @()
foreach ($file in $markdownFiles) {
    $content = Get-Content -Raw -Encoding UTF8 $file
    $sourceDir = Split-Path -Parent $file
    if (-not $sourceDir) { $sourceDir = '.' }
    foreach ($match in [regex]::Matches($content, '(?!!)\[[^\]]*\]\(([^)]+)\)')) {
        $target = ($match.Groups[1].Value -split '#', 2)[0].Trim('<', '>')
        if (-not $target -or $target -match '^(https?://|mailto:)') { continue }
        $resolved = Join-Path $sourceDir $target
        if (-not (Test-Path -LiteralPath $resolved)) {
            $missingLinks += "$file -> $target"
        }
    }
}
if ($missingLinks.Count -gt 0) {
    $missingLinks
    throw "Markdown link target does not exist"
}
"Markdown links valid in $($markdownFiles.Count) changed files"
```

- [ ] **Step 3: 扫描个人路径、敏感信息和过期现状**

Run:

```powershell
rg -n 'F:\\jdk21|C:\\Users\\|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|(?i)(password|token|secret)\s*[:=]\s*[^`$<{ ]+|/ws/dashboard|COP 尚未实现|保留旧电表' PROJECT_GUIDE.md PROJECT_STATUS.md docs
```

Expected: 没有个人绝对路径和真实凭据；`change-me` 只能存在于明确的非生产示例；过期词若仍存在，只能位于带历史警告的正文并由中央目录标记替代。

- [ ] **Step 4: 检查差异范围和格式**

Run:

```powershell
git status --short
git diff main...HEAD --name-only
git diff --check
```

Expected: 只包含本计划列出的文档；无空白错误、业务代码、配置、生成文件或临时脚本。

- [ ] **Step 5: 必要时运行 MQTT 契约定向测试**

只有 MQTT 文档变更依赖测试中的具体契约时运行仓库已有对应测试：

```powershell
.\mvnw.cmd -Dtest='*Mqtt*Test,*Ingestion*Test' test
```

Expected: 所有匹配的定向测试通过；若仓库不存在匹配测试，记录“未执行，使用静态代码与配置核验”，不得声称测试通过。

- [ ] **Step 6: 推送分支并准备中文 PR 材料**

```powershell
git status --short --branch
git log --oneline origin/main..HEAD
git push -u origin docs/current-document-health
```

Expected: 工作区干净，远程任务分支推送成功。PR Base 为 `main`，Compare 为 `docs/current-document-health`；PR 说明列出 53 份历史文件治理、六份当前文档修正、所有验证命令、测试结果和未执行项，并明确不包含业务代码与运行配置。
