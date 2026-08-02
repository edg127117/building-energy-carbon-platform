# Start Server Compose Path Compatibility Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复一键启动脚本的 Compose 文件路径，并兼容新版 `docker compose` 与旧版 `docker-compose`。

**Architecture:** 只修改 `start-server.sh`，集中定义实际 Compose 文件路径，并在启动基础设施前选择可用的 Compose 命令。脚本继续使用现有 Docker 守护进程检查、MySQL 等待、JAR 启动和健康检查流程。

**Tech Stack:** Bash、Docker Compose V2/V1、PowerShell 静态验证

## Global Constraints

- Compose 文件固定使用 `$SCRIPT_DIR/src/env/docker-compose.yml`。
- 优先使用 `docker compose`，不可用时回退 `docker-compose`。
- 不移动或复制 Compose 文件。
- 不修改数据库、端口、服务或应用配置。
- 不包含未跟踪的 `outputs/` 目录。

---

### Task 1: 修复 Compose 文件定位和命令兼容

**Files:**
- Modify: `start-server.sh:8-39`

**Interfaces:**
- Consumes: `SCRIPT_DIR`，以及系统中的 `docker` 或 `docker-compose` 命令。
- Produces: `COMPOSE_FILE` 绝对路径和 `COMPOSE_COMMAND` Bash 数组，用于启动 `src/env/docker-compose.yml`。

- [ ] **Step 1: 验证当前脚本没有引用实际 Compose 路径**

Run:

```powershell
if (Select-String -Quiet -Path start-server.sh -SimpleMatch '$SCRIPT_DIR/src/env/docker-compose.yml') { exit 0 } else { exit 1 }
```

Expected: FAIL，退出码为 1。

- [ ] **Step 2: 实现最小兼容逻辑**

在 `SCRIPT_DIR` 后增加：

```bash
COMPOSE_FILE="$SCRIPT_DIR/src/env/docker-compose.yml"
```

在 Docker 守护进程检查之后增加：

```bash
if [ ! -f "$COMPOSE_FILE" ]; then
    echo "[ERROR] 找不到 Docker Compose 文件: $COMPOSE_FILE"
    exit 1
fi

if docker compose version > /dev/null 2>&1; then
    COMPOSE_COMMAND=(docker compose)
elif command -v docker-compose > /dev/null 2>&1; then
    COMPOSE_COMMAND=(docker-compose)
else
    echo "[ERROR] 未找到 Docker Compose，请安装 docker compose 或 docker-compose"
    exit 1
fi
```

把基础设施启动命令改为：

```bash
"${COMPOSE_COMMAND[@]}" -f "$COMPOSE_FILE" up -d
```

- [ ] **Step 3: 验证路径和新旧命令分支已写入**

Run:

```powershell
$text = Get-Content -Raw start-server.sh
if ($text -notmatch [regex]::Escape('COMPOSE_FILE="$SCRIPT_DIR/src/env/docker-compose.yml"')) { throw 'Compose 路径未修复' }
if ($text -notmatch [regex]::Escape('docker compose version')) { throw '缺少 Compose V2 检查' }
if ($text -notmatch [regex]::Escape('command -v docker-compose')) { throw '缺少 Compose V1 回退' }
if ($text -notmatch [regex]::Escape('"${COMPOSE_COMMAND[@]}" -f "$COMPOSE_FILE" up -d')) { throw '启动命令未使用兼容数组' }
```

Expected: PASS，退出码为 0。

- [ ] **Step 4: 执行 Bash 语法检查**

Run:

```bash
bash -n start-server.sh
```

Expected: PASS，退出码为 0，无输出。

- [ ] **Step 5: 执行后端回归测试**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`，全部测试失败数和错误数均为 0。

- [ ] **Step 6: 检查并提交唯一任务文件**

Run:

```powershell
git diff --check
git add -- start-server.sh
git diff --cached --name-only
git commit -m "fix(env): locate docker compose configuration"
```

Expected: 暂存内容只有 `start-server.sh`，提交成功。
