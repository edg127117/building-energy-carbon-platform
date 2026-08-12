# HVAC Recalculation Scheduler Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让人工重算可靠领取执行，并使迟到冷却塔数据在有界并发下完整生成 Q0 和 `TOWER_EFF_V1`。

**Architecture:** 显式注册业务 `taskScheduler`，把人工任务的 MySQL 扫描/领取与 TDengine 分块执行拆开；迟到修正使用独立有界执行器。TDengine REST 驱动配置连接和读取超时，持久化状态机与低频证据补偿继续作为恢复边界。

**Tech Stack:** Java 21、Spring Boot 3.2.4、Spring Scheduling/Async、MyBatis-Plus、HikariCP、TDengine REST JDBC、JUnit 5、Mockito、AssertJ。

## Global Constraints

- 只修改 `iot-platform-demo` 的调度、迟到分钟补偿、人工重算、公式下游回归测试和对应文档。
- 不修改 Gaia、MQTT 测点、`TOWER_EFF_V1` 公式、水泵效率、风系统效率或前端。
- 普通自动化测试不得连接真实 MySQL、TDengine、Redis 或 MQTT。
- 状态推进必须继续使用 MySQL 条件 SQL；不得用 JVM 锁替代多实例领取边界。
- 注释按高风险并发、补偿、多数据源和时间语义检查。

---

### Task 1: 固定业务调度器选择

**Files:**
- Modify: `src/main/java/com/platform/config/AsyncConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/platform/config/AsyncConfigTest.java`

**Interfaces:**
- Produces: 名称为 `taskScheduler` 的通用业务调度器、`recalculationScanTaskScheduler` 专用扫描调度器；名称为 `lateRealCorrectionExecutor` 和 `recalculationJobExecutor` 的 `AsyncTaskExecutor`。

- [x] **Step 1: 写失败测试**

验证四个 Bean 名称、线程名前缀、有限池大小、人工工作零内存队列及测试关闭时不连接外部资源。

- [x] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd test "-Dtest=AsyncConfigTest"`

Expected: 因 Bean 或配置尚不存在而失败。

- [x] **Step 3: 实现最小配置**

在 `AsyncConfig` 中注册显式业务调度器和两个有界执行器；配置值从 `DataQualityProperties` 读取，所有执行器设置稳定线程名前缀和明确关闭策略。

- [x] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd test "-Dtest=AsyncConfigTest"`

Expected: `BUILD SUCCESS`。

### Task 2: 拆分人工领取和分块执行

**Files:**
- Modify: `src/main/java/com/platform/iot/dataquality/DataQualityRecalculationScheduler.java`
- Modify: `src/main/java/com/platform/iot/dataquality/RecalculationJobRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/MySqlRecalculationJobRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityRecalcJobMapper.java`
- Test: `src/test/java/com/platform/iot/dataquality/DataQualityRecalculationSchedulerTest.java`
- Test: `src/test/java/com/platform/iot/dataquality/MySqlRecalculationJobRepositoryTest.java`

**Interfaces:**
- Consumes: `recalculationJobExecutor`。
- Produces: `releaseClaim(jobId, expectedCursor, claimedAt)` 条件回退入口；扫描线程只领取和提交，工作线程执行原有阶段逻辑。

- [x] **Step 1: 写失败测试**

覆盖工作异步提交、阻塞工作不阻塞下一轮扫描、队列拒绝后条件回退、并发领取失败不执行和工作异常进入 `FAILED`。

- [x] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd test "-Dtest=DataQualityRecalculationSchedulerTest,MySqlRecalculationJobRepositoryTest"`

Expected: 新异步/回退断言失败。

- [x] **Step 3: 实现条件回退与工作分离**

领取成功后通过 `recalculationJobExecutor.execute` 提交 `jobId`、游标与领取时间快照；提交拒绝时执行条件 SQL：

```sql
UPDATE biz_data_quality_recalc_job
SET status = 'WAITING', update_time = CURRENT_TIMESTAMP(3)
WHERE job_id = #{jobId}
  AND status = 'RUNNING'
  AND cursor_minute = #{expectedCursor}
  AND update_time = #{claimedAt}
```

工作方法保留作废阶段、重算分块及失败收口语义。

- [x] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd test "-Dtest=DataQualityRecalculationSchedulerTest,MySqlRecalculationJobRepositoryTest"`

Expected: `BUILD SUCCESS`。

### Task 3: 限制迟到修正并发并配置 TDengine 超时

**Files:**
- Modify: `src/main/java/com/platform/config/DataQualityProperties.java`
- Modify: `src/main/java/com/platform/iot/dataquality/LateRealMinuteCorrectionService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `server.env.example`
- Test: `src/test/java/com/platform/config/AsyncConfigTest.java`
- Test: `src/test/java/com/platform/iot/dataquality/LateRealMinuteCorrectionServiceTest.java`

**Interfaces:**
- Consumes: `lateRealCorrectionExecutor`。
- Produces: 可配置的并发、队列、TDengine HTTP 连接和读取超时。

- [x] **Step 1: 写失败测试**

验证 `@Async("lateRealCorrectionExecutor")`、配置最小值校验、有限执行器拒绝行为及迟到修正异常不会删除原始证据。

- [x] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd test "-Dtest=AsyncConfigTest,LateRealMinuteCorrectionServiceTest"`

Expected: 执行器名称或配置断言失败。

- [x] **Step 3: 实现有界执行器与超时**

默认迟到并发为 8、队列容量为 1000；人工工作并发为 2，使用零容量直接交接，线程满时退回 MySQL `WAITING`。TDengine URL 增加 `httpConnectTimeout` 和 `httpSocketTimeout`，默认分别为 5000ms 和 30000ms，并允许环境变量覆盖。

- [x] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd test "-Dtest=AsyncConfigTest,LateRealMinuteCorrectionServiceTest"`

Expected: `BUILD SUCCESS`。

### Task 4: 补充完整链路回归并验证

**Files:**
- Modify: `src/test/java/com/platform/DataQualityRecalculationAcceptanceTest.java`
- Modify: `src/test/java/com/platform/iot/dataquality/LateRealMinuteCorrectionServiceTest.java`
- Add: `src/test/java/com/platform/iot/dataquality/DataQualityRecalculationSchedulingIntegrationTest.java`
- Add: `src/test/java/com/platform/iot/dataquality/RecalculationJobClaimSqlIntegrationTest.java`
- Modify if current status changes: `PROJECT_STATUS.md`

**Interfaces:**
- Consumes: 新调度与执行器边界。
- Produces: 三测点迟到 Q0 到 `TOWER_EFF_V1` 的回归证据。

- [x] **Step 1: 增加回归用例**

构造 POINT008/009/010 同分钟迟到原始事件，断言三条 Q0、READY 事件、公式值和容差；同时验证调度异常状态恢复。

- [x] **Step 2: 运行相关测试**

Run: `.\mvnw.cmd test "-Dtest=DataQualityRecalculationAcceptanceTest,DataQualityConditionalConfigurationTest,DataQualityRecalculationSchedulerTest,MySqlRecalculationJobRepositoryTest,LateRealMinuteCorrectionServiceTest,HvacFormulaEngineTest"`

Expected: `BUILD SUCCESS`。

- [x] **Step 3: 运行完整后端测试**

Run: `.\mvnw.cmd test`

Expected: `BUILD SUCCESS`。

- [x] **Step 4: 运行差异和仓库检查**

Run: `git diff --check`

Expected: 无输出且退出码为 0。

- [ ] **Step 5: 专用本地端到端验收**

在后端、Docker 基础设施和 Gaia 1.1 模拟器均运行、且提供有效 BLD001 JWT 时执行用户提供的 PowerShell 脚本。

Expected: `CENTRAL_HVAC_TOWER_EFFICIENCY_VERIFIED`。若缺少 Token 或模拟器运行环境，必须标记未执行，不得用自动化测试替代。
