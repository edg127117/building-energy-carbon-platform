# HVAC Latest Snapshot Partition Fix Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 TDengine 3.2.3 批量最新分钟查询只返回一个测点的问题，使 HVAC 快照接口一次返回 19 个真实最新测点。

**Architecture:** 只替换 `TdengineHvacMinuteRepository.findLatestByPointIds()` 的 SQL 形状：使用 `LAST_ROW` 聚合读取每个完整测点身份的最后一行，保持 Repository 接口、Service 合并、DTO 和权限逻辑不变。先用 SQL 契约测试锁定兼容写法，再执行完整 Java 回归和真实 TDengine/API/前端联合验收。

**Tech Stack:** Java 21、Spring Boot 3.2.4、Spring JDBC、JUnit 5、Mockito、AssertJ、TDengine 3.2.3、Docker、Vue 3

## Global Constraints

- 后端分支必须从最新 `origin/main` 创建，且不得包含 `feature/hvac-v1-frontend-integration` 的提交。
- 只修改分钟 Repository、对应测试、设计文档和实施计划。
- 不修改 Controller、Service、DTO、权限、表结构、Redis、历史查询和前端生产代码。
- 普通自动化测试不得依赖真实 MySQL、TDengine、MQTT 或 Redis。
- 新 SQL 必须一次批量返回全部请求测点，禁止退化为逐测点查询。
- 多数据源边界保持不变：此查询只能通过 TDengine 专用 `JdbcTemplate` 执行。
- 修改后的中文注释必须解释 TDengine 3.2.3 的实际语义和采用 `LAST_ROW` 的原因。
- 完成自动化和真实环境验证前，不推送后端分支。

---

### Task 1: 用 SQL 契约测试固定 `LAST_ROW` 最新测点查询

**Files:**
- Modify: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java:355`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java:100`

**Interfaces:**
- Consumes: `HvacMinuteRepository.findLatestByPointIds(List<String> pointIds)`
- Produces: 保持返回 `List<HvacMinuteQueryRow>`；SQL 列别名继续满足现有 `mapQueryRow(ResultSet, int)`。

- [ ] **Step 1: 把旧 SQL 断言改成失败的 `LAST_ROW` 契约**

将 `readsLatestRowsForAllPointIdsInOnePartitionedQuery()` 的 SQL 断言改为：

```java
assertThat(sql.getValue())
        .contains("point_id IN ('POINT001','POINT002')")
        .contains("LAST_ROW(ts) AS bucket_time")
        .contains("LAST_ROW(avg_val) AS average_value")
        .contains("LAST_ROW(min_val) AS minimum_value")
        .contains("LAST_ROW(max_val) AS maximum_value")
        .contains("LAST_ROW(sample_count) AS sample_count")
        .contains("LAST_ROW(data_quality) AS data_quality")
        .contains("PARTITION BY point_id, point_code, building_id, system_group_id,")
        .contains("equip_id, equip_code, family_code, component_code,")
        .contains("suffix_code, is_for_calc")
        .doesNotContain("ORDER BY ts DESC")
        .doesNotContain("LIMIT 1");
```

保留现有结果映射断言和 `emptyQueryPointListDoesNotTouchTdengine()`，确保接口及空列表行为不变。

- [ ] **Step 2: 运行定向测试并确认旧实现失败**

Run:

```powershell
.\mvnw.cmd "-Dtest=TdengineHvacMinuteRepositoryTest" test
```

Expected: `readsLatestRowsForAllPointIdsInOnePartitionedQuery` 失败；失败信息显示 SQL 缺少 `LAST_ROW(ts) AS bucket_time`，并仍包含旧的 `ORDER BY ts DESC LIMIT 1`。

- [ ] **Step 3: 用 `LAST_ROW` 实现最小修复**

将 `findLatestByPointIds()` 中的注释和 SQL 替换为：

```java
// TDengine 3.2.3 的普通 LIMIT 会限制整批分区结果，并不是“每个分区一条”。
// 使用 LAST_ROW 按完整测点身份聚合，才能一次批量返回每个测点的最新分钟值。
String sql = """
        SELECT point_id,
               LAST_ROW(ts) AS bucket_time,
               LAST_ROW(avg_val) AS average_value,
               LAST_ROW(min_val) AS minimum_value,
               LAST_ROW(max_val) AS maximum_value,
               LAST_ROW(sample_count) AS sample_count,
               LAST_ROW(data_quality) AS data_quality
        FROM %s
        WHERE point_id IN (%s)
        PARTITION BY point_id, point_code, building_id, system_group_id,
                     equip_id, equip_code, family_code, component_code,
                     suffix_code, is_for_calc
        """.formatted(stable, pointIdIn(pointIds));
```

不要修改方法签名、空列表提前返回、SQL 值转义或 `mapQueryRow()`。

- [ ] **Step 4: 运行 Repository 与查询链路定向测试**

Run:

```powershell
.\mvnw.cmd "-Dtest=TdengineHvacMinuteRepositoryTest,HvacQueryServiceTest,HvacQueryControllerFlowTest" test
```

Expected: 三个测试类全部通过，0 failures、0 errors。

- [ ] **Step 5: 检查注释和改动边界**

Run:

```powershell
git diff --check
git diff --name-only
git diff -- src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java
```

Expected: 生产代码只修改一个 SQL 和相邻中文原因注释；测试只修改对应 SQL 契约，无格式错误和无关文件。

- [ ] **Step 6: 提交最小修复**

```powershell
git add -- src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(hvac): query latest rows for every point"
```

Expected: 暂存区只有 Repository 和对应测试，提交成功。

---

### Task 2: 完整回归与真实 TDengine 19/19 验收

**Files:**
- Verify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Verify: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`
- Verify only: `.scripts/simulate-hvac-19-points.mjs`
- Verify only: `web/src/pages/HvacDemoPage.vue`（切回已存在的前端任务分支进行联合验收，不产生后端分支改动）

**Interfaces:**
- Consumes: `/api/hvac/buildings/BLD001/snapshot`、`/api/hvac/buildings/BLD001/indicators/latest`
- Produces: 快照响应含 19 个测点且 19 个均为 `NORMAL`；四项指标均为 `SUCCESS`。

- [ ] **Step 1: 运行完整后端测试**

Run:

```powershell
.\mvnw.cmd test
```

Expected: 全部测试通过；记录 tests、failures、errors、skipped 数量。

- [ ] **Step 2: 构建真实验收 JAR**

Run:

```powershell
.\mvnw.cmd -DskipTests package
```

Expected: `BUILD SUCCESS`，生成 `target/iot-platform-demo-1.0-SNAPSHOT.jar`。

- [ ] **Step 3: 使用当前健康 Docker 环境启动后端**

仅为当前进程设置：

```powershell
$env:MYSQL_PORT = '13306'
$env:REDIS_PORT = '16379'
$env:MQTT_ENABLED = 'true'
java -jar target/iot-platform-demo-1.0-SNAPSHOT.jar
```

Expected: 管理员登录返回 JWT，Actuator 健康状态为 `UP`，不重置或删除现有测试数据。

- [ ] **Step 4: 发布完整 19 点 MQTT 数据**

Run:

```powershell
npm ci --prefix .scripts
node .scripts/simulate-hvac-19-points.mjs
```

Expected: 7 轮全部完成，共发布 133 条；TDengine 最新有效分钟包含 19 个测点。

- [ ] **Step 5: 验证快照和指标 API**

使用 `admin / 123456` 获取 JWT 后调用：

```text
GET /api/hvac/buildings/BLD001/snapshot
GET /api/hvac/buildings/BLD001/indicators/latest
```

Expected:

- snapshot `points.length = 19`；
- `status = NORMAL` 的测点数量为 19；
- 19 个测点编码无重复；
- latest `indicators.length = 4`；
- 四项指标状态全部为 `SUCCESS` 且值非空。

- [ ] **Step 6: 用前端任务分支完成浏览器联合验收**

在后端 JAR 继续运行时，暂时切换到
`feature/hvac-v1-frontend-integration` 启动 `web`，登录后检查
`/hvac-demo`；验收结束后切回 `fix/hvac-latest-snapshot-partition`。

Expected:

- 当前建筑为 `PILOT-001`；
- 固定测点为 19；
- 测点数据完整率为 100%；
- 全部 19 个槽位均显示真实值和 `NORMAL`；
- COP、冷却塔效率、水泵效率、风系统耗功值均显示“计算成功”；
- 页面无接口 500、无模拟随机值。

- [ ] **Step 7: 清理临时进程并核查 Git**

只停止本次启动的 Spring Boot、Vite 和 MQTT 模拟器进程，保留四个健康
Docker 容器。

Run:

```powershell
git switch fix/hvac-latest-snapshot-partition
git status --short --branch
git log --oneline origin/main..HEAD
git diff --check origin/main...HEAD
git diff --name-status origin/main...HEAD
```

Expected: 工作区干净；后端分支只包含设计、计划、Repository 和对应测试；不含前端提交、日志、数据目录和依赖目录。

- [ ] **Step 8: 推送并准备后端 PR 材料**

仅在以上验证全部通过后执行：

```powershell
git push -u origin fix/hvac-latest-snapshot-partition
```

Expected: 远程分支与本地 HEAD 一致。交付 Compare 链接、中文 PR 标题与说明、测试数量、真实环境 19/19 证据、冲突和无关文件检查，并等待用户创建和合并 PR。
