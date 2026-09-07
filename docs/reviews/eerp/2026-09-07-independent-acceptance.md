# EERp 后端独立软件验收

验收日期：2026-09-07。结论：**目标数据库公共链路通过；整体仍为部分通过，不能宣布 A01–A17 全部完成。** 发现一项 P2 设计契约缺口，另有未覆盖边界。保持研发活动候选，不替代专业确认、现场验收或用户正式版本批准。

## 验收对象与环境

- 固定对象：PR #44 合并提交 `3772a979f9f0d4d0d246fe9de57fd253f536322f`。独立工作树初始干净；本次 `git fetch origin` 后 `HEAD` 与 `origin/main` 均为该提交，没有后续生产代码差异。
- 任务分支：`test/eerp-independent-acceptance`；仓库预检返回 `TASK_PREFLIGHT_OK`。预检脚本不接受默认 `codex/` 前缀，故采用脚本允许的测试前缀。
- 依据：[已批准设计 A01–A17](../../designs/2026-09-07-eerp-water-cooled-station-design.md)、固定代码和本次原始测试结果；不采用实现任务的通过结论代替复验。
- Java 21、Maven Wrapper 3.9.9、Docker Desktop Linux 引擎；缓存镜像 `mysql:8.4`（镜像短 ID `b3b90af2a655`）、`tdengine/tdengine:3.2.3.0`（`ae60a3a45dee`）。
- 容器和网络均为本次随机命名；仅回环随机端口，无业务存储挂载。每轮目标引擎测试从一次性空 `iot_platform` 开始，实际执行 35 个迁移至 V43，包含 V42。TDengine 测试库随机创建并回收，完整年度用 `KEEP 3650` 保存历史合成样例。
- 审查按 `code-comment-quality` 核对计算、单位、质量、状态、事务和追溯边界；生产源码及迁移相对固定基线无变化。新增内容仅为独立测试和验收证据。

## 已执行证据

| 验证 | 命令 | 本次结果 |
| --- | --- | --- |
| 原始后端完整回归及打包 | `./mvnw.cmd --batch-mode --no-transfer-progress verify` | 932 项，失败 0、错误 0、跳过 42；BUILD SUCCESS |
| 原有目标引擎入口 | `./mvnw.cmd --batch-mode --no-transfer-progress -Dtest=EerpMysqlTdengineIntegrationTest test` | 1 项通过，无跳过；空库迁移、原生数值、注入写失败、恢复与重复发布 |
| 独立目标数据库公共链路 | `./mvnw.cmd --batch-mode --no-transfer-progress -Dtest=EerpIndependentTargetEngineTest test` | 1 项通过，无跳过；原始事实真实读写、365 个封账周期、来源切换、年度与固定证据恢复 |
| 新增边界及直接覆盖对象回归 | `./mvnw.cmd --batch-mode --no-transfer-progress -Dtest=EerpIndependentBoundaryTest,EerpServiceTest,EerpAnnualCoreTest,CoolingComputationCoreTest,EerpApiContractTest,NativeQuantityAggregationServiceTest,CalculationPointReadServiceTest test` | 52 项通过，失败/错误/跳过均0；包含3项新增独立测试 |

原始结果分别见 [基线逐类汇总](evidence/baseline-test-results.txt)、[原入口 Surefire 报告](evidence/native-target-report.txt)、[独立入口 Surefire 报告](evidence/independent-target-report.txt)、[最终定向汇总](evidence/final-focused-results.txt)。完整日志及原始 XML 在本次工作树被忽略的 `target/acceptance` 中保留；入库证据不包含机器路径、凭据或完整环境转储。实施文档的历史 922/40 数字不作为本次结果。

独立目标链路使用真正的 `TdengineEnergyActivityDataReader → CalculationPointReadService → EerpPeriodCalculator → NativeQuantityAggregationService → NativePeriodSnapshotService → EerpService`，MySQL 持久化配置、任务和原生量索引，TDengine 保存原始事实与数值结果。资产、关系、职责、审核审计和已审核计量事件来源仍为明确测试替身，不代表这些上游模块已经完成目标引擎联合验收。

可复算样例：2025 UTC 全年，前 181 天选累计表，每日冷量差 12,000 kWh、电量差 2,400 kWh；后 184 天选流量温差，显式合成 `rho=1000、cp=3.6、flow=100、supply=7、return=12`，得到 500 kW × 24 h = 12,000 kWh/日。全年 `4,380,000 / 876,000 = 5`，365 个周期全部封账，严格比较为 `GUIDANCE_ONLY`。年度汇总不再扫描原始点。恢复样例在数值写失败后删除专用原始流量表，重建服务实例，确认输入摘要和证据不变、无原始读取，并从保存的三路事实和参数手算 500 kW、12,000 kWh；没有使用新的现场参数。

## A01–A17 核对

“通过”限于表中明确的测试层；“部分”表示该编号仍缺联合证据，不能用核心或替身测试填充目标引擎完成状态。

| 编号 | 判定 | 当前证据及边界 |
| --- | --- | --- |
| A01 | 通过（软件） | 原始 `EerpAnnualCoreTest` 完整自然年 5,000,000/1,000,000=5；独立真实存储公共链路另以 4,380,000/876,000 验证全年完整性与等于5 |
| A02 | 部分 | 累计锚点、复位、回绕/换表/修正由原生聚合及既有算法回归覆盖；目标引擎公共链路只验证正常差值，未联合真实事件治理服务 |
| A03 | 通过（软件） | 原始核心及服务测试重跑 1000/4.18/100/7/12 一小时约580.555556；短样例不用于全年评价 |
| A04 | 通过（软件） | 独立边界测试同时改变流量及温差：半小时500kW加半小时2000kW=1250kWh，区别于均值相乘1125kWh |
| A05 | 部分 | 原始测试覆盖保持到期、逐路maxGap、拒绝段不跨越；独立测试增加时差999/1000/1001ms；未穷尽所有规则组合的目标数据库验证 |
| A06 | 通过（算法/服务） | 显式零值、负流量、反向温差、参数缺失/越界、缺测和质量拒绝不补零；目标引擎未逐项重复 |
| A07 | 部分 | 配置覆盖、总分层级、历史测量对象的拒绝逻辑及部分反例已核对；真实水路和共用设备归属依赖专业输入，未作现场验证 |
| A08 | 部分 | 独立数据库链路精确切换累计表/流量温差并完成全年；跨配置区间、重叠和缺口有服务测试；关系版本切换未联合真实关系治理服务 |
| A09 | 部分 | 缺必需设备、缺口/重叠、零分母及不完整输入有测试，已有量与原因保留；未结束年度和时区/周期错配检查已读代码，缺对应独立反例执行证据 |
| A10 | 部分 | 原始核心覆盖闰年、纽约DST、毫秒时长；目标公共链路覆盖UTC年/月切换与半开日边界，未运行目标引擎闰年/DST组合 |
| A11 | 部分／P2缺口 | 未舍入4/5交叉乘比较与边界回归通过；年度结果缺展示值与舍入版本，见F01 |
| A12 | 通过（软件） | 新增真实质量解析器经过公共累计链路验证默认Q0、默认拒绝Q1/Q2、显式许可Q1/Q2、空集合和禁用场景；策略持久化治理本身仍用固定测试快照 |
| A13 | 部分 | 配置状态、修订、动态职责撤销、建筑权限、审核动作及审计失败回滚回归通过；HTTP有真实安全/OpenAPI测试，未逐动作完成真实职责存储与目标引擎联合验证 |
| A14 | 部分 | 幂等冲突、旧租约拒绝、本机阻塞槽位回归；独立数据库写失败和服务实例重建恢复通过。未实施JVM强杀、双进程竞争及MySQL死锁恢复实验 |
| A15 | 部分 | 独立数据库样例从保存原始输入/参数手算并验证固定stage恢复、年度引用保留；带计量事件修正及所有来源选择变化的独立重放尚未穷尽 |
| A16 | 通过（明确边界） | 两种冷量来源经公共读取、聚合、原生量发布和封账进入年度；TDengine tce列无非空值；原始完整回归覆盖既有tce/碳测试。上游资产/关系/审核并非全真实装配 |
| A17 | 部分 | 点数32/33、事实20000/20001、证据容量、周期跨度、并发槽位等回归；未完成全硬上限、超时及生产等价长期容量矩阵 |

## 失败与未覆盖项

**F01 — P2：年度输出没有固定展示舍入契约。** 设计第3.3节要求保存原值、展示值和舍入版本，第8节也要求固定结果表达规则；[AnnualResult](../../../src/main/java/com/platform/energy/efficiency/EerpContracts.java)只有一个 `eerp`，无展示值、精度/模式或舍入版本。[EerpAnnualCore](../../../src/main/java/com/platform/energy/efficiency/EerpAnnualCore.java)以 `DECIMAL128` 计算比值并正确使用交叉乘比较阈值，但返回中没有补齐这些字段；年度追溯证据同样未固定该表达规则。这是代码/设计审查发现，现有通过测试不能证明该要求满足。影响是不同消费者无法按同一保存版本重建展示值，**不代表已经观察到阈值误判**。本任务保留缺口，未修改生产实现。

其余未覆盖项见矩阵：真实治理装配、所有计量事件与关系切换联合重放、JVM强杀与跨进程并发、完整极限容量矩阵、专业样例和现场验收。原始读取没有可审计的来源性质字段，结果保留 `UNKNOWN`；本次测试来源由夹具证明为合成，不能把该字段解释为真实采样已验收。

环境阻塞：无持续阻塞。初始沙箱限制影响共享Git写入和Maven缓存/联网，通过授权执行解决；WSL内Docker入口只是提示脚本，实际使用Windows客户端。未绕过Hook或必需检查。

## 复现与交付边界

1. 在固定提交的干净任务分支执行仓库预检和上述普通回归；补充测试文件取自本验收分支，生产文件保持固定基线。
2. 创建专用随机网络、MySQL 8.4与TDengine 3.2.3.0容器，只绑定回环；每个目标入口使用新空MySQL数据库，名称为 `iot_platform`。
3. 通过进程环境传入 `EERP_IT_ISOLATED=true`、`EERP_IT_MYSQL_URL/USER/PASSWORD` 和 `EERP_IT_TDENGINE_URL/USER/PASSWORD`。URL采用MySQL JDBC及 `jdbc:TAOS-RS`，时区UTC。原入口另设 `EERP_IT_ENABLED=true`；独立入口另设 `EERP_ACCEPTANCE_ENABLED=true`。不要将这些启用标志带入普通回归。
4. 运行表中对应定向命令。独立入口自行断言空库、迁移和365个逐日封账结果；所有数值和专业参数均显式合成。
5. 保存Surefire报告及命令日志后，只回收本次创建的容器、匿名卷和网络。不得清理既有业务资源。本次已完成清理并按随机名称查询确认容器/网络均为空，临时凭据文件已删除，输出 `EERP_ACCEPTANCE_RESOURCES_CLEANED`。

本次不调整正式基线、不替用户批准版本、不修改冻结设计、不修改生产实现以使测试通过。
