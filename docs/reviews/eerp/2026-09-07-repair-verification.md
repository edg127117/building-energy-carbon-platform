# EERp 验收缺口修复与复验

日期：2026-09-07。年度展示契约 F01 已修复；补充下列软件证据。整体保持活动候选，不宣布 A01–A17 全部通过。

## 修复范围

基于独立验收提交 `af295bac41f40a0337924b1c4dfc10d756701e77`，承接生产基线 `3772a979f9f0d4d0d246fe9de57fd253f536322f`；任务分支 `fix/eerp-acceptance-gaps` 已通过 `TASK_PREFLIGHT_OK`。[原验收报告](2026-09-07-independent-acceptance.md)保持冻结。

- `AnnualResult` 保留 `eerp`，增加 `displayEerp`、`roundingVersion`、`displayScale`、`roundingMode`，随原有 stage JSON 固定保存。
- 展示值直接由年度冷量除电量按两位小数 `HALF_UP` 生成，版本为 `EERP_DISPLAY_2DP_HALF_UP_V1`，避免先做 DECIMAL128 再做展示舍入的双重舍入。
- 严格 4/5 阈值仍用未舍入总量交叉乘比较；不可计算时展示值为空。旧快照缺失展示字段时读取为空，不补写、不改历史摘要。
- 生产修改仅限年度计算与结果契约；其余为测试和当前文档更新。

## 本次验证

命令统一使用 `./mvnw.cmd --batch-mode --no-transfer-progress`。定向测试使用 `-Dtest=<表中测试类> test`。

| 范围与测试类 | 结果 | 实际覆盖 |
| --- | --- | --- |
| `EerpAnnualCoreTest,EerpAnnualCompatibilityTest,EerpServiceTest,EerpApiContractTest,EerpIndependentBoundaryTest` | 27 项通过，无跳过 | 展示与严格阈值、双重舍入反例、旧 JSON 不回写、持久化查询、OpenAPI；未结束年度和建筑/时区/版本错配 |
| `EerpGovernanceAcceptanceTest` | 3 项通过，无跳过 | 真实 Spring 关系初始化/审核/生效、EERp 配置审核；真实已审核 RESET 事件 100→110、5→15 得冷量20；真实发布策略拒绝Q1并核对策略版本；职责与建筑范围撤销、审计 |
| `EerpProcessRecoveryTest,EerpResourceBoundaryTest` | 5 项通过，无跳过 | 子 JVM 强杀、竞争拒绝、租约到期后恢复、固定 stage 不变、旧租约拒绝；真实 MySQL 1213 死锁后显式重试；容量与超时边界 |
| `EerpIndependentTargetEngineTest` | 1 项通过，无跳过 | 2024 `America/New_York` 闰年/DST，两种来源切换、367个封账周期、年度输出持久化及固定证据恢复 |
| 完整 `verify` | 947 项，失败0、错误0、跳过44；BUILD SUCCESS | 本次完整后端回归及打包，外部资源入口按环境开关跳过，目标测试结果单独列示 |

证据见 [本次定向结果](evidence/repair-focused-results.txt)、[完整回归逐类结果](evidence/repair-full-results.txt)。完整日志及原始 Surefire 输出保留在忽略目录 `target/eerp-repair`；入库仅保留不含机器路径及凭据的摘要。

治理首轮 3 项中有 1 项因测试复用幂等键被正确拒绝；已将夹具键按建筑隔离，重跑全部通过。未放宽生产幂等校验，原失败日志保留。

## 证据层级与复现

- 治理测试使用实际 Spring 服务与 H2；资产和用户为合成 SQL 夹具，原始事件与数值存储为替身。它证明治理装配与拒绝行为，不证明 MySQL 全治理联调或现场设备接入。
- 进程测试使用 MySQL 8.4 与 TDengine 3.2.3.0 的实际任务、租约和数值存储；上游资产/职责/关系使用明确替身。第一个 JVM 在首个数值可见后被强杀，第二个竞争者被拒绝，恢复进程等待真实租约到期；另用外部事务构造 InnoDB 等待环，断言错误1213并显式重试成功。
- 年度测试真实读取 TDengine 原始合成事件，经过公共计算与原生量持久化进入年度。366个自然日中25小时日拆为24+1小时任务，得到367个周期；全年 `4,392,000 / 878,400 = 5`，展示 `5.00`、评价 `GUIDANCE_ONLY`。上游治理仍为替身。
- 容量测试覆盖年度5000/5001条门禁（5000条随后因输入不存在被拒绝，并非5000条成功年度性能测试）、8/16 MiB证据精确字节与+1字节、5秒超时不发布后重试、六项配置硬上限校验；不等于长期容量压测。
- 目标测试须使用一次性空 MySQL 库和随机 TDengine 库，沿用原验收报告的 `EERP_IT_ISOLATED` 与连接环境变量。进程入口加 `EERP_PROCESS_IT_ENABLED=true`；年度入口加 `EERP_ACCEPTANCE_ENABLED=true`、`EERP_ACCEPTANCE_YEAR=2024`、`EERP_ACCEPTANCE_TIMEZONE=America/New_York`。普通回归不启用这些标志。
- 本轮专用容器、匿名卷和网络已回收，临时凭据已删除，并按随机名称核对无残留：`EERP_REPAIR_RESOURCES_CLEANED`。

## 剩余边界

独立只读复核在 F01 指定范围内未发现生产逻辑缺陷，核对了直接舍入、严格阈值、stage 持久化、旧 JSON 与定向日志；复核者未重复执行测试。年度专门的“新任务前序引用与旧年度展示不变”重算用例尚未独立覆盖，不能由现有周期重算测试替代。

A11/F01 的软件契约缺口已修复；A09、A10、A14补充了原来缺失的执行证据。A02/A08/A12/A13/A15仍不能泛化为全部事件、全部关系版本切换和目标引擎全治理联合验收。A17尚缺生产等价长期容量矩阵；专业参数、正式标准适用性、真实设备与现场验收仍未完成。
