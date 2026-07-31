# MySQL 初始化 UTF-8 修复设计

## 1. 背景与问题

本地干净环境使用 MySQL 8.0，并已经通过服务端参数声明：

```text
--character-set-server=utf8mb4
--collation-server=utf8mb4_unicode_ci
```

但是 MySQL Docker 首次启动执行 `/docker-entrypoint-initdb.d/*.sql` 时，
客户端会话仍可能使用 `latin1`。初始化脚本中的 UTF-8 中文字节因此被当作
Latin-1 字符读取，再转换为 UTF-8 存入数据库，最终形成双重编码乱码。

当前真实证据如下：

- `src/env/init/01-init-tables.sql` 和
  `src/env/init/03-init-hvac-schema.sql` 源文件包含正确 UTF-8 中文；
- MySQL 服务端字符集为 `utf8mb4`，但未显式指定字符集的客户端会话是
  `latin1`；
- `building.building_name` 中“试点大楼”实际存储为
  `C3A8C2AFE280A2...`，而正确 UTF-8 应以 `E8AF95E782B9...` 开始；
- 浏览器静态中文正常，来自 MySQL 的建筑名和测点名乱码。

因此问题属于 MySQL 首次初始化客户端字符集，不属于 Vue 显示转换或
Spring JDBC 连接配置。

## 2. 目标

1. MySQL 首次启动执行两份自动初始化脚本时，客户端连接明确使用
   `utf8mb4` 和 `utf8mb4_unicode_ci`。
2. 管理员昵称、角色名称、建筑名称、设备名称和测点名称等中文种子数据
   以正确 UTF-8 字节写入数据库。
3. 当前本地测试 MySQL 数据目录被安全重建，已有乱码数据不保留。
4. 自动化测试能够阻止初始化脚本再次遗漏客户端字符集声明。
5. 前端页面重新显示正确的后端中文名称，同时保持 19 测点和四项指标链路正常。

## 3. 非目标

- 不修改 Vue 前端代码来掩盖后端乱码。
- 不修改 Spring Boot 运行期 JDBC 字符集；当前连接已经显式使用 UTF-8。
- 不删除 TDengine、Redis 或 EMQX 的持久化数据。
- 不提供面向未知生产数据库的自动乱码转换脚本。
- 不遍历并猜测修复任意历史字段。生产数据迁移必须另行审计字段和数据状态。

## 4. 方案选择

### 4.1 采用方案：脚本声明字符集并重建本地 MySQL

在两份由 Docker 首次启动自动执行的 MySQL 脚本顶部加入：

```sql
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
```

修改文件：

- `src/env/init/01-init-tables.sql`
- `src/env/init/03-init-hvac-schema.sql`

该语句必须位于脚本首个业务 DDL 或 DML 之前，确保表注释和种子字符串都按
UTF-8 解释。两份脚本分别声明，不依赖 Docker entrypoint 是否复用同一个
MySQL 客户端会话。

随后只重建仓库当前环境的 `src/env/mysql-data`。TDengine、Redis 和 EMQX
数据目录不在删除范围内。

### 4.2 未采用：转换现有乱码字段

反向转换需要先证明每个值都经历了同一种 Latin-1 到 UTF-8 的错误转换。
如果字段同时包含正常值和乱码值，批量转换会破坏正常数据，也无法验证新的
干净安装路径。因此本次不采用。

### 4.3 未采用：只调整 MySQL 服务端参数

服务端已经使用 `utf8mb4`，但服务端字符集不能替代客户端会话字符集。
继续增加服务端参数不能直接约束 Docker entrypoint 执行 SQL 文件时如何
解释输入字节，因此不采用。

## 5. 自动化测试设计

新增一个专用的初始化脚本字符集合同测试，读取两份生产初始化脚本并验证：

1. 文件能够以 `UTF-8` 解码；
2. 包含精确语句
   `SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;`；
3. 字符集声明出现在第一个 `CREATE TABLE` 和第一个 `INSERT` 之前；
4. 源文件中的代表性中文种子仍然存在，例如“超级管理员”“试点大楼”和
   “冷冻水进水温度”。

测试只锁定初始化契约，不连接真实 MySQL，普通 `mvn test` 继续与外部资源隔离。

## 6. 本地数据重建与安全边界

重建前必须执行只读检查：

1. 解析仓库和 Compose 环境目录的绝对路径；
2. 确认目标精确等于当前仓库下的 `src/env/mysql-data`；
3. 确认目标不是仓库根目录、用户目录或其他 Docker 项目目录；
4. 确认当前使用的容器是 `iot-mysql`。

执行顺序：

1. 停止并移除当前 Compose 容器，释放 MySQL bind mount；
2. 仅删除 `src/env/mysql-data`；
3. 保留 `taos-data`、`redis-data`、EMQX 配置及其他目录；
4. 重新启动 Compose 服务；
5. 等待四个基础设施健康。

删除的本地 MySQL 测试数据不可恢复，但它是本次明确批准重建的目标。
主工作目录中未提交的 Controller 注释和 `outputs/` PPT 不属于目标路径，
不得修改。

## 7. 真实环境验证

MySQL 重建后执行以下只读验证：

- `@@character_set_server = utf8mb4`；
- 建筑 `BLD001` 的名称为“试点大楼”；
- “试点大楼”的十六进制为
  `E8AF95E782B9E5A4A7E6A5BC`；
- 管理员昵称为“超级管理员”；
- 四个角色名称为正确中文；
- `POINT001` 和 `POINT018` 的测点名称为正确中文；
- 相关字段不包含 `è¯`、`å` 等已知乱码片段。

随后启动当前后端和前端，发布 7 轮 19 测点模拟数据并验证：

- 快照返回 19 个不同测点，全部 `dataQuality=0`；
- 四项指标全部 `SUCCESS` 且有数值；
- 浏览器显示“试点大楼”和正确测点名称；
- 页面显示真实分钟数据已连接、完整率 100%；
- 浏览器控制台没有错误或警告。

## 8. Git 与交付边界

本修复使用独立分支 `fix/mysql-init-utf8`。提交只包含：

- 两份 MySQL 初始化脚本；
- 一个初始化字符集合同测试；
- 本设计文档和实施计划。

不包含：

- 前端分支的 9 个业务文件；
- 本地 MySQL、TDengine 或 Redis 数据目录；
- 主工作目录未提交的 Controller 注释；
- `outputs/` 下的 PPT。

测试通过后推送任务分支并提供独立 PR。该 PR 合并后，前端分支再合并最新
`main`，完成最终中文显示复验。
