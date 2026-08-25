# ADR-0001：使用 Flyway 统一治理 MySQL 结构迁移

- 状态：已接受
- 日期：2026-08-25
- 范围：MySQL 结构初始化与版本升级

## 背景

此前 MySQL SQL 同时承担 Docker 首次初始化和人工增量升级，应用本身不记录已执行
版本。新库、已有研发库和未来生产库可能因此走不同路径，重复执行、漏执行及脚本顺序
都缺少可查询证据。本次只治理数据库迁移机制，不改变遥测业务契约、应用 ACK 语义或
查询 API。

## 决策

1. 应用通过 Flyway 执行 `classpath:db/migration/mysql` 下的版本脚本；构建从
   `src/env/init/V*.sql` 生成该 classpath 资源。
2. Docker Compose 只创建空的 `iot_platform` 数据库，不再把 SQL 挂载到
   `/docker-entrypoint-initdb.d`。应用启动是 MySQL 结构迁移的唯一入口。
3. 新库从 V1 开始自动迁移。迁移成功后版本、校验和及执行结果记录在
   `flyway_schema_history`。
4. 无 Flyway 历史表的非空旧库默认拒绝自动接管，`baseline-on-migrate` 默认关闭。
   接管前必须备份并核验至少 V1、V3 基础结构已经存在，确认其余既有结构可由后续幂等
   脚本兼容；随后仅在该次部署显式设置
   `MYSQL_FLYWAY_BASELINE_ON_MIGRATE=true` 和
   `MYSQL_FLYWAY_BASELINE_VERSION=3`。成功后撤销这两个部署覆盖项。
5. 已在任一环境成功执行的版本脚本禁止修改或复用版本号；后续变更必须增加更高版本。
   Flyway `clean` 始终关闭。
6. TDengine SQL 不纳入本 Flyway 版本链，仍由独立初始化与验证路径管理。

## 受控接管检查

- 取得可恢复备份，并在副本演练恢复；
- 核验当前库、字符集、关键基础表以及 V1/V3 所需列；
- 检查现有表、列、索引和约束与待执行 V8 及后续脚本是否冲突；
- 在隔离副本完成 baseline、迁移、应用启动和关键查询验证；
- 正式执行后查询 `flyway_schema_history`，确认所有记录成功，再撤销一次性开关。

禁止仅为绕过启动失败而开启 baseline。结构不满足时应先停止部署并修复迁移方案。

## 影响

- 新环境和升级环境获得一致、可审计的迁移顺序；迁移失败会阻止应用带着不完整结构启动。
- 旧库首次接管增加一次备份、结构核验和演练步骤，这是避免把未知结构静默标记为已迁移
  的必要成本。
- MySQL 与 TDengine 仍是两个独立存储边界；Flyway 成功不代表 TDengine、Broker、真实
  设备或生产网络已经验收。
