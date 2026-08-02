# 本地基础设施端口可配置设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 背景

开发机已有自动启动的 Windows MySQL 和 Redis 服务，分别占用 `3306` 和
`6379`。项目 Docker Compose 当前固定发布相同端口，导致每次启动测试环境前
都必须停止本机服务。

## 目标

- 允许开发者覆盖 Docker MySQL 和 Redis 的宿主机端口。
- 容器内部继续使用标准端口 `3306` 和 `6379`。
- 项目默认行为保持不变，不影响没有端口冲突的开发者和部署环境。
- 后端与 Compose 复用同一组端口环境变量，避免两处配置不一致。
- 本机长期使用 MySQL `13306`、Redis `16379`，不停止现有 Windows 服务。

## 方案

Compose 端口映射改为：

```yaml
mysql:
  ports:
    - "${MYSQL_PORT:-3306}:3306"

redis:
  ports:
    - "${REDIS_PORT:-6379}:6379"
```

Spring Boot 已通过 `MYSQL_PORT` 和 `REDIS_PORT` 读取连接端口，因此无需修改
Java 代码或 `application.yml`。`server.env.example` 保留标准默认值，并补充
说明这些变量同时控制本机 Compose 发布端口和后端连接端口。

本机用户环境变量持久设置为：

```text
MYSQL_PORT=13306
REDIS_PORT=16379
```

新 PowerShell 会自动继承；当前已打开的进程在执行测试命令时显式传入相同值。

## 数据流

PowerShell 环境变量进入 Docker Compose，决定宿主机发布端口；同一环境变量
进入 Spring Boot，决定 JDBC 和 Lettuce 的连接端口。容器内部端口保持不变，
MySQL、Redis 镜像及其数据卷无需迁移。

## 错误处理

启动前检查 `13306` 和 `16379` 是否空闲。若仍有冲突，停止启动并报告占用
进程，不自动选择其他端口。环境变量缺失时回退到原有标准端口。
