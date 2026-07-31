# MySQL 与 Redis 稳定命名卷迁移设计

## 1. 背景

本地 Compose 目前把 MySQL 和 Redis 数据目录相对挂载到：

```text
./mysql-data:/var/lib/mysql
./redis-data:/data
```

Compose 会按执行目录或 Compose 项目目录把相对路径解析为绝对宿主机路径。
现有容器是在短期 Git worktree 中创建的，因此 `docker inspect` 显示它们仍
引用已经清理的 `d9a9` worktree。容器尚未重建时仍可能保持健康，但宿主机
源目录已经不存在，后续停止或重建容器时不能再依赖这些路径恢复数据。

TDengine 已在前一任务中迁移到固定命名卷
`iot-platform-demo-tdengine-data`，本设计不再次操作 TDengine 数据。

## 2. 当前数据基线

迁移设计确认时的本地环境为：

- MySQL 数据库 `iot_platform` 有 21 张表，数据与索引约 1.06 MiB；
- MySQL 中的业务配置、用户、权限、建筑、设备、测点及数据质量任务数据
  必须完整保留；
- Redis 有 24 个键，均属于可重新生成的 HVAC 最新指标缓存；
- Redis AOF 仍可写入，但最近一次 RDB 后台保存失败；
- TDengine 使用 `iot-platform-demo-tdengine-data` 且状态健康；
- EMQX 状态健康。

迁移开始前必须重新采集一次基线。设计阶段的数据量只用于说明范围，不能
代替迁移时的实际校验结果。

## 3. 目标

本任务完成后：

1. MySQL `/var/lib/mysql` 使用固定 Docker 命名卷
   `iot-platform-demo-mysql-data`；
2. Redis `/data` 使用固定 Docker 命名卷
   `iot-platform-demo-redis-data`；
3. 两个数据卷均不依赖 Git 仓库、分支、worktree 或 Compose 项目名称；
4. MySQL 现有业务数据经逻辑备份、预恢复和一致性校验后完整保留；
5. Redis 旧缓存不迁移，由应用在新空卷上重新生成；
6. TDengine 与 EMQX 不停止、不删除、不重建；
7. 仓库测试能够阻止 MySQL 或 Redis 再次退回相对目录挂载。

固定命名卷解决的是同一 Docker 主机上的容器重建和 worktree 切换问题。
它不代替跨机器备份，也不会随 Git 仓库或镜像自动迁移到部署服务器。

## 4. 方案选择

### 4.1 采用：逻辑备份、预恢复验证、命名卷切换

先从仍在运行的 MySQL 执行 `mysqldump`，再将备份恢复到挂载最终命名卷的
临时 MySQL 容器。只有表清单、准确行数和中文数据校验全部通过，才允许
切换正式容器。

该方案在停止旧 MySQL 前已经证明备份可以恢复，也避免复制运行中的
InnoDB 物理文件。

### 4.2 不采用：直接复制 `/var/lib/mysql`

运行中复制 MySQL 数据文件可能得到不一致的 InnoDB 文件集。当前旧宿主机
源路径也已经不存在，因此不能把物理目录复制当作可靠迁移来源。

### 4.3 不采用：迁移到固定 Windows 宿主目录

固定宿主目录虽然便于直接查看文件，但仍受盘符、权限、Docker Desktop
文件共享和路径变化影响。显式命名的 Docker Volume 更适合本项目的本地
持久化底座。

## 5. Compose 配置

MySQL 和 Redis 服务改为：

```yaml
services:
  mysql:
    volumes:
      - mysql-data:/var/lib/mysql

  redis:
    volumes:
      - redis-data:/data

volumes:
  mysql-data:
    name: iot-platform-demo-mysql-data
  redis-data:
    name: iot-platform-demo-redis-data
  tdengine-data:
    name: iot-platform-demo-tdengine-data
```

MySQL 初始化 SQL 和 EMQX ACL 仍属于只读配置文件，不属于数据库数据卷。
真实迁移时必须把 Compose 项目目录显式解析到稳定的主项目
`src/env`，避免新容器的只读配置挂载再次指向短期 worktree。迁移命令只能
操作 `mysql` 或 `redis` 服务，不能对整套 Compose 执行 `down`。

配置附近必须保留直白的中文注释，说明固定卷名用于隔离 worktree 生命周期；
删除卷会删除本地持久化数据。

## 6. MySQL 备份与验证

### 6.1 备份位置

本次真实迁移备份保存在仓库外：

```text
D:\word\iot-platform-demo-runtime-backups\
  2026-07-31-mysql-redis-stable-volumes\
```

目录至少包含：

- `iot_platform.sql`：`iot_platform` 的完整逻辑备份；
- `source-table-counts.tsv`：源库每张表的准确行数；
- `source-tables.txt`：源库表清单；
- `SHA256SUMS.txt`：备份文件校验值；
- `migration-notes.txt`：源容器 ID、时间、镜像版本和验证结果。

备份目录不位于 Git 仓库内，不得暂存或推送。SQL 必须先写入容器内的临时
文件，再通过 `docker cp` 复制到 Windows，避免 Windows PowerShell 对原生
命令重定向时改变文件编码。

### 6.2 导出范围

备份使用 MySQL 8 自带的 `mysqldump`，导出数据库 `iot_platform`，包含：

- 表结构和数据；
- 触发器；
- 存储过程和函数；
- Event；
- `utf8mb4` 字符集信息；
- 二进制字段的十六进制表示。

MySQL 系统库不迁移。新容器仍由 Compose 的
`MYSQL_ROOT_PASSWORD` 和 `MYSQL_DATABASE` 初始化运行账号及数据库。

### 6.3 切换前预恢复

1. 确认 `iot-platform-demo-mysql-data` 不存在；若已经存在则立即停止，
   不得自动删除或覆盖；
2. 创建最终命名卷；
3. 启动不发布宿主机端口的临时 MySQL 8 容器，并挂载该最终命名卷；
4. 等待临时 MySQL 健康；
5. 将 `iot_platform.sql` 恢复到临时容器；
6. 对比源库和恢复库的表清单；
7. 对每张业务表执行准确的 `COUNT(*)` 并对比；
8. 查询代表性中文字段，确认不存在乱码；
9. 记录校验结果后停止并删除临时容器，但保留已经验证的命名卷。

任何一步失败都必须保留原 MySQL 容器继续运行，同时保留备份和诊断信息，
不得进入正式切换。

### 6.4 正式切换

预恢复验证通过后：

1. 再次确认 TDengine 和 EMQX 容器 ID；
2. 仅停止并删除旧 `iot-mysql`；
3. 使用修改后的 Compose 仅创建 `mysql` 服务；
4. 确认 `/var/lib/mysql` 的挂载类型为 `volume`，名称为
   `iot-platform-demo-mysql-data`；
5. 等待健康检查通过；
6. 再次对比表清单和准确行数；
7. 验证代表性中文数据、后端登录和依赖 MySQL 的 HVAC 查询；
8. 单独重启 MySQL，确认重启后数据仍然一致。

切换后的回滚依据是仓库外 SQL 备份和已经验证的命名卷。迁移任务不得删除
备份文件或自动删除任何失败卷。

## 7. Redis 重建

Redis 的 24 个现有键是可重新生成缓存，经用户确认不迁移。

处理顺序为：

1. 确认 `iot-platform-demo-redis-data` 不存在；若存在则停止并报告；
2. 仅停止并删除旧 `iot-redis`；
3. 使用修改后的 Compose 仅创建 `redis` 服务；
4. 确认 `/data` 挂载类型为 `volume`，名称为
   `iot-platform-demo-redis-data`；
5. 验证 `PING`、AOF 开启且持久化目录可写；
6. 启动后端并发送可控 HVAC 数据，使最新指标缓存重新生成；
7. 验证缓存键和 API 结果；
8. 单独重启 Redis，确认新缓存能够从 AOF 恢复。

Redis 重建失败不能触发 MySQL、TDengine 或 EMQX 重建。

## 8. 自动化测试

扩展 `DockerComposeConfigurationTest`，至少约束：

- MySQL 使用 `mysql-data:/var/lib/mysql`；
- Redis 使用 `redis-data:/data`；
- 顶层卷名分别为：
  - `iot-platform-demo-mysql-data`
  - `iot-platform-demo-redis-data`
  - `iot-platform-demo-tdengine-data`
- Compose 中不存在：
  - `./mysql-data:/var/lib/mysql`
  - `./redis-data:/data`
- 每个新增逻辑卷键只在服务挂载和顶层声明中各出现一次。

提交前执行：

1. 定向 Compose 契约测试；
2. `docker compose config`；
3. 完整 `mvn test`；
4. 真实 MySQL 备份、预恢复、切换和重启验证；
5. 真实 Redis 空卷重建、缓存再生成和重启验证；
6. 确认 TDengine 与 EMQX 容器 ID 没有变化；
7. 检查 Git 变更中不存在备份、数据目录、日志或用户主目录改动。

## 9. 错误处理与安全边界

- 当前迁移完成前禁止执行整套 `docker compose down`；
- 禁止使用 `docker compose down -v`；
- 禁止删除任何未精确确认名称和用途的 Docker Volume；
- 禁止在 MySQL 备份预恢复验证通过前停止旧 MySQL；
- 禁止把 SQL 备份写入或提交到 Git 仓库；
- 禁止触碰主项目现有未提交 Controller 修改和 `outputs/`；
- 禁止停止、删除或重建 TDengine 和 EMQX；
- 命名卷已存在、表行数不一致、中文校验失败或健康检查失败时立即停止；
- 所有破坏性命令执行前必须再次解析并打印精确容器名、卷名和备份路径。

## 10. 不包含的范围

本任务不包含：

- Java 生产业务代码、API、公式或数据库表结构修改；
- TDengine 数据迁移或重新初始化；
- EMQX 重建或持久化改造；
- Redis 旧缓存保留；
- 跨机器或生产服务器自动部署；
- Flyway/Liquibase 引入；
- 自动删除历史备份或 Docker Volume；
- 主工作目录中用户未提交内容的整理或提交。

## 11. 完成标准

任务只有同时满足以下条件才可交付 PR：

- Compose 与契约测试使用三个固定命名卷；
- MySQL 备份存在于仓库外并具有 SHA-256 校验值；
- MySQL 在切换前完成独立预恢复；
- 源库与新卷的表清单和每张表准确行数一致；
- 中文业务数据正常；
- Redis 以空命名卷重建并重新生成缓存；
- MySQL、Redis 分别重启后验证通过；
- TDengine 与 EMQX 未被重建；
- 完整自动化测试通过；
- Git 提交不包含数据、备份、日志或无关文件。
