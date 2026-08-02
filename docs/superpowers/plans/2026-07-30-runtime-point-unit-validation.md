# Runtime Point Unit Validation Implementation Plan

> **文档状态：历史任务实施计划**
>
> 本文保留任务当时计划的步骤、命令和验收方式，部分内容可能已被后续提交替代。
> 文中的复选框表示原计划步骤，不代表当前完成状态；执行任何命令前必须重新核验。
> 判断当前状态前，请先查看 [历史文档目录](../README.md)、
> [项目指南](../../../PROJECT_GUIDE.md) 和 [项目状态](../../../PROJECT_STATUS.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在测点新增和修改的运行时写入边界拒绝无单位的在线计算模拟量，并保证拒绝时不写 MySQL、不刷新测点配置快照。

**Architecture:** 在现有 `BizDataPointServiceImpl` 中保留关系校验和身份字段锁定，新增一个只负责计算测点单位契约的私有校验方法。修改流程先用旧记录补全单位规则依赖的缺省字段并锁定身份字段，再校验最终对象；校验通过后才调用 MyBatis-Plus 持久化和 `MySqlDataPointConfigProvider.refreshAll()`。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus 3.5.5、JUnit 5、Mockito、AssertJ、Maven

## Global Constraints

- 规则只适用于 `status=ONLINE`、`dataType=ANALOG`、`isForCalc=1` 的最终测点状态。
- `unit` 为 `null`、空字符串或纯空白时抛出 `BusinessException(400, "参与计算的在线模拟量必须配置单位")`。
- 非计算、非在线或非模拟量测点不受本规则限制。
- 校验失败时不得调用 `insert`、`updateById` 或 `refreshAll`。
- 更新必须锁定现有身份字段，并用现有值补全请求未提供的 `status`、`isForCalc` 和 `unit`。
- 不修改数据库表结构、公式、典型值审批状态机、现有 `409` 语义或代码生成器。
- 新增和修改的业务职责、写库前阻断原因使用直白中文注释说明。
- 只暂存本计划列出的文件，不修改、删除或提交任何旧工作树的 `outputs/`。

---

### Task 1: 用 Service 单元测试锁定运行时单位契约

**Files:**
- Create: `src/test/java/com/platform/hvac/service/BizDataPointServiceImplTest.java`

**Interfaces:**
- Consumes: `BizDataPointServiceImpl.add(BizDataPoint)`、`BizDataPointServiceImpl.update(BizDataPoint)`。
- Produces: 对 400 错误、最终状态合并、身份字段锁定、允许场景和失败零副作用的回归契约。

- [ ] **Step 1: 创建 Mock 隔离的 Service 测试夹具**

```java
@ExtendWith(MockitoExtension.class)
class BizDataPointServiceImplTest {

    @Mock private BizDataPointMapper dataPointMapper;
    @Mock private MySqlDataPointConfigProvider configProvider;
    @Mock private BizEquipmentMapper equipmentMapper;
    @Mock private BizSystemGroupMapper systemGroupMapper;
    @Mock private BizPointNamingRuleMapper namingRuleMapper;
    @Mock private PointCodeNamingValidator namingValidator;

    private BizDataPointServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BizDataPointServiceImpl(
                configProvider, equipmentMapper, systemGroupMapper,
                namingRuleMapper, namingValidator);
        ReflectionTestUtils.setField(service, "baseMapper", dataPointMapper);
    }
}
```

- [ ] **Step 2: 增加新增非法单位测试**

使用 `@NullSource` 和 `@ValueSource(strings = {"", " ", " \t "})` 调用 `add`，
断言异常代码为 400、消息准确，并验证：

```java
verify(dataPointMapper, never()).insert(any());
verify(configProvider, never()).refreshAll();
```

- [ ] **Step 3: 增加更新最终状态测试**

构造旧记录 `OFFLINE + ANALOG + isForCalc=1 + unit=" "`，更新请求只传
`pointId` 和 `status=ONLINE`。断言合并旧单位后的最终对象被拒绝，并验证：

```java
verify(dataPointMapper, never()).updateById(any());
verify(configProvider, never()).refreshAll();
```

- [ ] **Step 4: 增加成功和豁免场景测试**

完整覆盖：

```text
合法单位新增成功
合法单位修改成功
isForCalc=0 且单位空白时新增成功
status=OFFLINE 且单位空白时新增成功
dataType=DIGITAL 且单位空白时新增成功
更新请求试图把 DIGITAL 改为 ANALOG 时仍使用旧 DIGITAL 身份
```

成功路径分别验证 Mapper 写入一次且 `refreshAll()` 一次；身份锁定测试用
`ArgumentCaptor<BizDataPoint>` 断言交给 `updateById` 的 `dataType` 仍为
`DIGITAL`。

- [ ] **Step 5: 运行测试并确认生产代码尚未满足契约**

Run:

```powershell
.\mvnw.cmd -Dtest=BizDataPointServiceImplTest test
```

Expected: 新增的非法单位断言或零副作用验证失败，证明测试能够捕获当前缺陷。

### Task 2: 在持久化前校验最终测点状态

**Files:**
- Modify: `src/main/java/com/platform/hvac/service/impl/BizDataPointServiceImpl.java`
- Test: `src/test/java/com/platform/hvac/service/BizDataPointServiceImplTest.java`

**Interfaces:**
- Consumes: Task 1 锁定的单位契约。
- Produces: `validateCalculationPointUnit(BizDataPoint)` 和更新最终状态合并逻辑。

- [ ] **Step 1: 更新类级用途注释**

将类级注释改为说明该 Service 是测点写入业务边界，负责关系、身份和计算单位
契约；通用生成 CRUD 不能整体覆盖该实现。

- [ ] **Step 2: 在 add 写库前调用单位校验**

```java
point.setPointId(null);
validateRelationships(point);
validateCalculationPointUnit(point);
this.save(point);
configProvider.refreshAll();
```

- [ ] **Step 3: 提取更新最终状态准备方法**

```java
private void prepareFinalStateForUpdate(
        BizDataPoint existing, BizDataPoint point) {
    point.setPointCode(existing.getPointCode());
    point.setBuildingId(existing.getBuildingId());
    point.setSystemGroupId(existing.getSystemGroupId());
    point.setEquipId(existing.getEquipId());
    point.setNamingRuleId(existing.getNamingRuleId());
    point.setFamilyCode(existing.getFamilyCode());
    point.setComponentCode(existing.getComponentCode());
    point.setSuffixCode(existing.getSuffixCode());
    point.setDataType(existing.getDataType());
    if (point.getStatus() == null) {
        point.setStatus(existing.getStatus());
    }
    if (point.getIsForCalc() == null) {
        point.setIsForCalc(existing.getIsForCalc());
    }
    if (point.getUnit() == null) {
        point.setUnit(existing.getUnit());
    }
}
```

方法附近用中文解释：MyBatis-Plus 默认忽略空更新字段，先补全单位规则字段才能
校验数据库真正会得到的最终状态。

- [ ] **Step 4: 在 update 写库前校验最终对象**

```java
BizDataPoint existing = this.getById(point.getPointId());
if (existing == null) {
    throw new BusinessException(404, "测点不存在");
}
prepareFinalStateForUpdate(existing, point);
validateRelationships(point);
validateCalculationPointUnit(point);
this.updateById(point);
configProvider.refreshAll();
```

- [ ] **Step 5: 实现单位契约校验**

```java
private void validateCalculationPointUnit(BizDataPoint point) {
    boolean onlineCalculationAnalog =
            "ONLINE".equalsIgnoreCase(point.getStatus())
                    && "ANALOG".equalsIgnoreCase(point.getDataType())
                    && Integer.valueOf(1).equals(point.getIsForCalc());
    if (onlineCalculationAnalog
            && (point.getUnit() == null || point.getUnit().isBlank())) {
        // 写库后再发现缺少单位会让公式和质量链路读取到含义不完整的计算输入。
        throw new BusinessException(400, "参与计算的在线模拟量必须配置单位");
    }
}
```

- [ ] **Step 6: 运行定向测试并确认通过**

Run:

```powershell
.\mvnw.cmd -Dtest=BizDataPointServiceImplTest test
```

Expected: `BizDataPointServiceImplTest` 全部通过，失败、错误和跳过均为 0。

- [ ] **Step 7: 执行完整回归**

Run:

```powershell
.\mvnw.cmd test
```

Expected: 全部测试通过，无失败、错误或跳过项；测试配置不连接真实外部资源。

- [ ] **Step 8: 检查注释、范围和暂存内容**

Run:

```powershell
git diff --check
git diff -- src/main/java/com/platform/hvac/service/impl/BizDataPointServiceImpl.java `
  src/test/java/com/platform/hvac/service/BizDataPointServiceImplTest.java
git status --short --branch
```

Expected: 只有设计、计划、Service 和 Service 测试属于本任务；中文注释解释写库前
阻断原因；没有 `outputs/` 或其他无关文件。

- [ ] **Step 9: 提交实现**

```powershell
git add -- src/main/java/com/platform/hvac/service/impl/BizDataPointServiceImpl.java `
  src/test/java/com/platform/hvac/service/BizDataPointServiceImplTest.java
git diff --cached --name-only
git diff --cached --check
git commit -m "fix(hvac): validate runtime point units"
```

Expected: 实现提交仅包含 Service 和对应单元测试。

### Task 3: 推送任务分支并交付 PR 材料

**Files:**
- Verify only: all task files

**Interfaces:**
- Consumes: Task 1 和 Task 2 的提交与测试证据。
- Produces: 远程任务分支以及用户可直接创建 PR 的完整材料。

- [ ] **Step 1: 检查提交边界和远程冲突**

Run:

```powershell
git status --short --branch
git log --oneline origin/main..HEAD
git diff --name-only origin/main...HEAD
git merge-tree (git merge-base HEAD origin/main) HEAD origin/main
```

Expected: 工作区干净，提交和文件仅属于本任务，合并预检无冲突。

- [ ] **Step 2: 推送任务分支**

Run:

```powershell
git push -u origin fix/runtime-point-unit-validation
```

Expected: 远程分支创建成功，本地分支跟踪同名远程分支。

- [ ] **Step 3: 向用户交付 PR 材料**

提供：

```text
Base: main
Compare: fix/runtime-point-unit-validation
Compare URL:
https://github.com/edg127117/iot-platform-demo/compare/main...fix/runtime-point-unit-validation?expand=1
```

PR 说明明确列出运行时单位契约、最终状态合并、失败零副作用、身份字段锁定、代码
生成器不在本次范围、定向测试和完整回归的实际数量，并声明当前等待用户创建和合并
PR。
