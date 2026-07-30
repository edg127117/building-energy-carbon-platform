# 一键启动脚本 Compose 路径兼容设计

## 目标

修复 `start-server.sh` 引用仓库根目录下不存在的 `docker-compose.yml`，使脚本能够使用实际位于 `src/env/docker-compose.yml` 的基础设施编排文件。

## 方案比较

1. 只修改现有命令中的文件路径。改动最少，但继续依赖旧版 `docker-compose` 命令。
2. 定义唯一的 Compose 文件路径，启动前检查文件是否存在，优先使用新版 `docker compose`，不可用时回退旧版 `docker-compose`。
3. 把 Compose 文件复制或移动到仓库根目录。会产生重复配置或影响现有相对卷路径，不采用。

本次选择方案 2，在保持修改范围很小的同时兼容新旧 Docker Compose 安装方式。

## 行为设计

- `COMPOSE_FILE` 固定为 `$SCRIPT_DIR/src/env/docker-compose.yml`。
- 启动前检查该文件；不存在时输出明确错误并退出。
- 如果 `docker compose version` 可用，使用 `docker compose`。
- 否则如果 `docker-compose version` 可用，使用 `docker-compose`。
- 两者都不可用时输出明确错误并退出。
- 保留现有 Docker 守护进程检查、MySQL 等待、JAR 启动及健康检查流程。
- 不移动 Compose 文件，不修改数据库、端口或服务配置。

## 验证

- 使用 Bash 语法检查确认脚本可解析。
- 使用临时命令替身分别覆盖新版、旧版和均不存在三条分支，确认最终调用的 Compose 文件路径正确。
- 执行相关项目测试，确认脚本修改没有影响后端构建。
