# 碳追溯与能源上游联动复验

两项补齐已通过软件隔离复验，碳管理仍为活动候选，不代表正式核算、生产容量或现场验收。

## 已补齐内容

- 计算固定原能源月封账快照及嵌套证据、活动水位、质量/例外、关系和汇总策略，以及完整因子组合/来源、匹配优先级、单位换算、公式、GWP、舍入、分母和请求上下文。
- 明细提供受建筑权限保护的追溯入口；按批次和明细共同定位，重建后校验完整证据摘要。小数使用精确十进制字符串；历史不足记录只标记 `LEGACY_PARTIAL`，不以当前事实回填。
- V42 将重复规则保存在批次共享证据中，与明细同一短事务提交。明细写入按行数/UTF-8 载荷分段；本进程最多两路结果写入在借连接前排队，等待仍受原截止时间限制。规则读取只在单次计算且查询条件相同时复用。
- 年/季碳计算读取上游真实发布的月度封账段；能源修正通过实际公共服务提交、审批、执行，产生持久化碳变化并触发候选重算。
- 已批准的电力平均口径、核算年/数据年对应、省级→区域→全国匹配、报告原因子/GWP 锁定规则保持不变。

## 本次证据

| 验证 | 结果 |
| --- | --- |
| 原有 MySQL/HTTP/JVM 重启与恢复组 | 28 项中的 27 项先通过；容量项超限后修复并单独复验通过 |
| 最终集中复验 | 22 项通过，0 失败、错误或跳过，包含迁移、容量、上游联动、API、规则复用、证据及电力回归 |
| MySQL 迁移 | 全链到 V42；另在既有 V41 隔离库成功升级；共享字段可空，不初始化业务因子/分母 |
| 真实能源公共服务联动 | 12 个月封账合计 1200 kWh / 0.60 tCO₂e；月度修正后 1220 kWh / 0.61 tCO₂e；旧证据保留、重复变化幂等、跨批次拒绝和整体回滚通过 |
| 追溯落库及查询 | 高精度、摘要校验、缺失共享引用、篡改拒绝、建筑授权、历史不足标记和多段写入回滚通过 |
| API 契约 | 修复计算/结果/追溯的 OpenAPI 成功响应模型后，字段、路径及未登录拒绝通过 |
| 完整 Java 21 门禁 | `verify` 通过并完成后端打包：882 项中 841 通过、41 按外部资源环境条件跳过，0 失败或错误 |

最终集中复验完成于 2026-09-07 12:58（Asia/Shanghai）。使用 Java 21、专用 MySQL 8.0.19、测试登录/数据边界；普通服务测试使用 H2/Mock。原始 Surefire XML、进程日志和容量 JSON 保留在本次隔离复验归档，仓库不提交运行数据。

容量覆盖 12/96/500 条快照与 1/4/8/16 并发，共 12 组合、192 次 HTTP 请求，全部成功。测试保持 JVM 384 MiB、HTTP 工作线程 8、数据库连接池 4、请求截止 20 秒及短事务 5 秒。最大响应 19.30 秒，发生在 500 条快照/16 并发；该组合余量较小，不据此承诺生产 SLA 或长期容量。

## 复验入口与边界

- [CarbonSoftwareAcceptanceTest](../../../src/test/java/com/platform/carbon/CarbonSoftwareAcceptanceTest.java)：显式设置 `CARBON_ACCEPTANCE_URL` 与 `CARBON_ACCEPTANCE_ISOLATED=true`，使用一次性专用 MySQL。
- [CarbonManagementMysqlIntegrationTest](../../../src/test/java/com/platform/carbon/CarbonManagementMysqlIntegrationTest.java)：`CARBON_MYSQL_IT_URL`、`CARBON_MYSQL_IT_ISOLATED=true`，要求空库。
- [CarbonUpstreamIntegrationAcceptanceTest](../../../src/test/java/com/platform/carbon/CarbonUpstreamIntegrationAcceptanceTest.java)：`CARBON_UPSTREAM_IT_URL`、`CARBON_UPSTREAM_IT_ISOLATED=true`，要求本机一次性数据库和未使用的验收建筑；账号使用对应 `USER/PASSWORD` 环境变量。
- Maven 入口：`.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=<测试类或测试类#方法>' test`；普通完整门禁使用 `verify`，不启用上述外部资源开关。

上游联动实际执行能源治理、月封账、计量边界汇总、修正、碳变化记录及重算服务；下层累计量输入、时序数值存储和关系服务使用明确的研发模拟替身。未验证正式上游结果、真实设备、现场网络、真实 TDengine 链路、专业核算样例或生产长期负载。旧冻结设计和历史复验报告未改写，本报告不代替独立正式版本验收与用户批准。
