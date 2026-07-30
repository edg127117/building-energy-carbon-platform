#!/bin/bash
# =========================================================
# IoT 平台 - 服务器一键启动脚本
# 用法: chmod +x start-server.sh && ./start-server.sh
# =========================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/src/env/docker-compose.yml"
JAR_FILE="$SCRIPT_DIR/iot-platform-demo-1.0-SNAPSHOT.jar"
LOG_FILE="$SCRIPT_DIR/app.log"

echo "========================================"
echo "  能效碳效智慧管控平台 - 服务器启动脚本"
echo "========================================"

# 加载本机私有环境变量。server.env 已被 .gitignore 排除。
if [ -f "$SCRIPT_DIR/server.env" ]; then
    echo "加载 server.env 环境变量"
    set -a
    source "$SCRIPT_DIR/server.env"
    set +a
fi

# 1. 检查 JAR 是否存在
if [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] 找不到 $JAR_FILE"
    echo "请先将 JAR 文件放到当前目录"
    exit 1
fi

# 2. 检查 Docker 是否运行
if ! docker info > /dev/null 2>&1; then
    echo "[ERROR] Docker 未运行，请先启动 Docker"
    exit 1
fi

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

# 3. 启动基础设施容器
echo "[1/3] 启动 Docker 容器 (MySQL + EMQX + TDengine)..."
"${COMPOSE_COMMAND[@]}" -f "$COMPOSE_FILE" up -d

# 4. 等待 MySQL 就绪
echo "[2/3] 等待 MySQL 初始化完成..."
for i in $(seq 1 30); do
    if docker exec iot-mysql mysqladmin ping -uroot -p"${MYSQL_PASSWORD:-change-me}" --silent 2>/dev/null; then
        echo "       MySQL 已就绪"
        break
    fi
    echo "       等待中... ($i/30)"
    sleep 2
done

# 5. 启动 Spring Boot
echo "[3/3] 启动 Spring Boot 应用..."

nohup java -jar "$JAR_FILE" > "$LOG_FILE" 2>&1 &
PID=$!
echo "       应用 PID: $PID"
echo "       日志文件: $LOG_FILE"

# 6. 等待启动完成
echo "       等待应用启动..."
for i in $(seq 1 60); do
    if curl -s http://localhost:8081/api/actuator/health > /dev/null 2>&1; then
        echo ""
        echo "========================================"
        echo "  启动成功!"
        echo "========================================"
        echo "  API:     http://localhost:8081/api"
        echo "  Actuator:http://localhost:8081/api/actuator/health"
        echo "  日志:    tail -f $LOG_FILE"
        echo "  停止:    kill $PID"
        echo "========================================"
        exit 0
    fi
    sleep 2
done

echo ""
echo "[WARN] 应用可能仍在启动中，请查看日志: tail -f $LOG_FILE"
echo "       PID: $PID"
