# 最小双数据源与测试隔离设计

> **文档状态：历史任务设计记录**
>
> 本文保留任务当时确认的设计、假设和取舍，部分内容可能已被后续提交替代。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

## 目标

以最小改动明确 MySQL 与 TDengine 的连接边界，确保 MySQL 字符集修复不会误用 TDengine，并使普通自动化测试不再主动初始化或连接 TDengine。

## 范围

本次只调整数据源 Bean、启动开关和测试配置，不修改 MQTT、遥测写入、分钟聚合、权限、接口、数据库结构或前端功能。

## 方案

### 明确两个 JdbcTemplate

- MySQL 主数据源继续使用 `dataSource`。
- 在 `MysqlConfig` 中显式创建 `mysqlJdbcTemplate`，并标记为主 JdbcTemplate。
- TDengine 继续使用现有的 `taosDataSource` 和 `taosJdbcTemplate`。
- `DatabaseCharsetFix` 改为构造器注入，并使用 `@Qualifier("mysqlJdbcTemplate")`。

运行时连接关系固定为：

```text
dataSource → mysqlJdbcTemplate → MySQL 业务操作和字符集修复
taosDataSource → taosJdbcTemplate → TDengine 时序操作和初始化
```

### 为启动任务增加开关

- `DatabaseCharsetFix` 使用 `database.charset-fix.enabled` 控制，默认开启。
- TDengine 初始化 Runner 使用 `tdengine.initialization-enabled` 控制，默认开启。
- 不关闭 TDengine 仓储 Bean，避免影响现有依赖关系。

### 测试环境隔离

`application-test.yml` 中配置：

```yaml
database:
  charset-fix:
    enabled: false

tdengine:
  initialization-enabled: false
```

普通测试继续使用 H2 作为 MySQL 替代，不执行 MySQL 字符集 DDL，也不执行 TDengine 建库、建表和迁移 Runner。

为了避免 TDengine Hikari 连接池在没有 SQL 操作时预热连接，`taosDataSource` 设置：

```java
config.setMinimumIdle(0);
config.setInitializationFailTimeout(-1);
```

真实 TDengine 仓储测试仍通过显式 Mock 或单独集成环境执行，不把本机 `127.0.0.1:6041` 作为普通测试的隐式依赖。

## 错误处理

- 正常环境中 MySQL 字符集修复失败仍只记录警告，不阻断应用启动，保持现有行为。
- TDengine 初始化失败仍降级为警告，不改变当前容错策略。
- 测试环境通过配置关闭启动行为，不依赖异常捕获实现“跳过”。

## 验证标准

1. `mvn test` 的 47 个后端测试全部通过。
2. 测试日志不再出现 `DatabaseCharsetFix` 连接 TDengine 6041。
3. 测试日志不再出现 TDengine 初始化 Runner 的建库、建表重试。
4. `DatabaseCharsetFix` 的依赖明确为 `mysqlJdbcTemplate`。
5. `taosJdbcTemplate` 仍被所有 TDengine Repository 正常使用。
6. 默认生产配置继续开启 MySQL 字符集修复和 TDengine 初始化。
7. 不修改业务接口、数据库表结构和前端行为。

## 非目标

- 不进行模块化重构。
- 不拆分微服务。
- 不修改 COP、MQTT、分钟聚合或 WebSocket。
- 不新增 Testcontainers。
- 不重写数据库迁移体系。
