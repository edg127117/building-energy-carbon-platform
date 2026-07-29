# HVAC Data Quality Fill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在现有 HVAC 真实分钟冻结和公式引擎之间增加质量补全层，实现质量 `2` 的即时典型值补全、质量 `1` 的五分钟内历史线性插值、迟到质量 `0` 的自动升级、来源追溯、典型值审批、失败恢复及管理 API，同时保持每分钟结果及时生成。

**Architecture:** 保持 V1 单体架构，在 `com.platform.iot.dataquality` 内建立独立业务边界。MySQL 保存典型值配置与补全任务，TDengine 的 `st_raw_minute` 继续保存全部分钟值并仅增加 `quality_task_id`。正常冻结事件先进入质量层，再发布统一的 `HvacMinuteQualityReadyEvent` 给公式引擎；跨 MySQL/TDengine 不做分布式事务，而使用确定性幂等键、质量优先级、任务状态和补偿调度保证最终一致。

**Tech Stack:** Java 21、Spring Boot 3、Spring Events、MyBatis-Plus、MySQL 8、TDengine、H2、JUnit 5、Mockito、AssertJ、Maven Wrapper。

**Global Constraints:**

- 严格遵守 `docs/superpowers/specs/2026-07-29-hvac-data-quality-fill-design.md`；质量优先级固定为 `0 > 1 > 2`。
- 五分钟是右侧真实端点到达后的历史回溯上限，不是首次结果等待时间。
- 首次冻结缺少真实值时，只能使用当分钟有效且已经批准的典型值；不得使用 `biz_data_point.default_value`、零值或未经审批的默认值。
- MySQL 与 TDengine 通过各自 Mapper/Repository 访问，禁止跨库联表和分布式事务。
- 普通自动化测试只使用 H2、Mock 或 Fake；不得连接真实 MySQL、TDengine、MQTT、Redis。
- 所有新增业务类和关键规则添加直白中文注释，说明职责、上下游和不满足规则的后果。
- 每个任务先写失败测试，再写最小实现；每个任务独立提交，禁止暂存用户的 `outputs/`。
- 完成每个任务后运行该任务列出的定向测试；生产代码全部完成后运行 `.\mvnw.cmd test`。

---

## Task 1: 建立配置、领域枚举和数据库结构

**Files:**

- Create: `src/main/java/com/platform/config/DataQualityProperties.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/TypicalValueStatus.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/FillApplyStatus.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/FillSourceType.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/QualityEventSource.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/entity/BizPointTypicalValueConfig.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/entity/BizDataQualityFillTask.java`
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Create: `src/env/init/08-migrate-mysql-data-quality-fill.sql`
- Modify: `src/env/init/04-init-tdengine-hvac.sql`
- Create: `src/env/init/09-migrate-tdengine-data-quality-fill.sql`
- Modify: `src/main/java/com/platform/config/TdengineConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `src/test/resources/schema-test.sql`
- Modify: `src/test/java/com/platform/config/TdengineHvacSchemaTest.java`
- Create: `src/test/java/com/platform/config/DataQualityPropertiesTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/DataQualitySchemaTest.java`

**Interfaces and data contracts:**

```java
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "data-quality")
public class DataQualityProperties {
    private boolean enabled = true;
    private Interpolation interpolation = new Interpolation();
    @Min(1)
    private int lateRealCorrectionHours = 24;
    @Min(1)
    private long typicalConfigRefreshMs = 60_000L;
    @Min(1)
    private long retryDelayMs = 600_000L;
    private boolean reconciliationEnabled = true;

    @Data
    public static class Interpolation {
        @Min(1)
        private int maxGapMinutes = 5;
    }
}
```

`BizPointTypicalValueConfig` 必须一一映射设计文档 6.1 的字段；`BizDataQualityFillTask` 必须一一映射 6.2 的字段。时间字段统一使用 `LocalDateTime`，ID 使用 `String`，操作者使用 `Long`，计数使用 `Integer`，典型值使用 `BigDecimal`。枚举值必须精确为：

```java
enum TypicalValueStatus { DRAFT, PENDING, APPROVED, REJECTED, DISABLED }
enum FillApplyStatus { WAITING, APPLIED, FAILED, REPLACED, VOIDED }
enum FillSourceType { INTERPOLATION, TYPICAL_VALUE }
enum QualityEventSource {
    NORMAL_FREEZE,
    TYPICAL_FILL,
    INTERPOLATION_CORRECTION,
    LATE_REAL_CORRECTION,
    MANUAL_RECALCULATION
}
```

### Step 1: 写数据库与配置失败测试

在 `DataQualitySchemaTest` 中读取 `schema-test.sql`，断言两张 MySQL 表、唯一键和查询索引存在，并断言补全任务表不含 `review_status`、`reviewer_id`、`review_comment`、`reviewed_at`。在 `TdengineHvacSchemaTest` 中新增断言：

```java
assertThat(allSql).contains(
        "quality_task_id nchar(32)",
        "add column quality_task_id nchar(32)");
```

在 `DataQualityPropertiesTest` 使用 `ApplicationContextRunner` 绑定全部六个配置项，并验证默认值为 `true/5/24/60000/600000/true`。

### Step 2: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityPropertiesTest,DataQualitySchemaTest,TdengineHvacSchemaTest test
```

Expected: 编译失败，因为 `DataQualityProperties` 和领域类型尚不存在；补齐类型后，结构断言仍因 SQL 缺表和 `quality_task_id` 缺列而失败。

### Step 3: 写最小结构实现

- 在 `03-init-hvac-schema.sql` 增加两张新表，字段、唯一键和索引严格按设计文档 6.1/6.2。
- `08-migrate-mysql-data-quality-fill.sql` 使用 `CREATE TABLE IF NOT EXISTS`，供已有 MySQL 环境手工执行；不修改既有数据。
- `04-init-tdengine-hvac.sql` 的 `st_raw_minute` 定义加入 `quality_task_id NCHAR(32)`。
- `09-migrate-tdengine-data-quality-fill.sql` 只执行对 `st_raw_minute` 的增量加列，并在文件注释中说明重复执行前需先 `DESCRIBE`。
- `TdengineConfig.initStRawMinute` 和 `ensureRawMinuteColumns` 同时加入 `quality_task_id`，保证新库和旧库都覆盖。
- `application.yml` 加入设计文档 13 节配置和中文说明；`application-test.yml` 设置 `data-quality.enabled=false`、`reconciliation-enabled=false`，阻止测试上下文注册后台任务。
- H2 表的 JSON 字段用 `TEXT`，MySQL 迁移使用 `JSON`；其余字段语义保持一致。

MySQL 新表 DDL 使用以下字段和索引；`03` 与 `08` 保持同构：

```sql
CREATE TABLE IF NOT EXISTS `biz_point_typical_value_config` (
    `config_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `typical_value` DECIMAL(12,4) NOT NULL,
    `unit` VARCHAR(20) NOT NULL,
    `source_description` VARCHAR(500) NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `valid_from` DATETIME(3) NOT NULL,
    `valid_to` DATETIME(3) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL,
    `version` INT NOT NULL,
    `created_by` BIGINT NOT NULL,
    `submitted_at` DATETIME(3) DEFAULT NULL,
    `reviewer_id` BIGINT DEFAULT NULL,
    `review_comment` VARCHAR(500) DEFAULT NULL,
    `reviewed_at` DATETIME(3) DEFAULT NULL,
    `disabled_by` BIGINT DEFAULT NULL,
    `disabled_reason` VARCHAR(500) DEFAULT NULL,
    `disabled_at` DATETIME(3) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`config_id`),
    UNIQUE KEY `uk_typical_point_version` (`point_id`, `version`),
    KEY `idx_typical_building_status` (`building_id`, `status`),
    KEY `idx_typical_effective`
        (`point_id`, `status`, `valid_from`, `valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测点典型值审批版本';

CREATE TABLE IF NOT EXISTS `biz_data_quality_fill_task` (
    `task_id` VARCHAR(32) NOT NULL,
    `idempotency_key` VARCHAR(160) NOT NULL,
    `building_id` VARCHAR(32) NOT NULL,
    `point_id` VARCHAR(32) NOT NULL,
    `start_minute` DATETIME(3) NOT NULL,
    `end_minute` DATETIME(3) NOT NULL,
    `minute_count` INT NOT NULL DEFAULT 0,
    `data_quality` TINYINT NOT NULL,
    `source_type` VARCHAR(30) NOT NULL,
    `algorithm_version` VARCHAR(32) NOT NULL,
    `evidence_json` JSON NOT NULL,
    `typical_config_id` VARCHAR(32) DEFAULT NULL,
    `typical_config_version` INT DEFAULT NULL,
    `apply_status` VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    `applied_count` INT NOT NULL DEFAULT 0,
    `failed_count` INT NOT NULL DEFAULT 0,
    `replaced_count` INT NOT NULL DEFAULT 0,
    `voided_count` INT NOT NULL DEFAULT 0,
    `failed_minutes_json` JSON DEFAULT NULL,
    `retry_count` INT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `generated_at` DATETIME(3) NOT NULL,
    `closed_at` DATETIME(3) DEFAULT NULL,
    `void_by` BIGINT DEFAULT NULL,
    `void_reason` VARCHAR(500) DEFAULT NULL,
    `void_at` DATETIME(3) DEFAULT NULL,
    `supersedes_task_id` VARCHAR(32) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`task_id`),
    CONSTRAINT `chk_fill_quality` CHECK (`data_quality` IN (1, 2)),
    UNIQUE KEY `uk_fill_idempotency` (`idempotency_key`),
    KEY `idx_fill_building_range`
        (`building_id`, `start_minute`, `end_minute`),
    KEY `idx_fill_point_range`
        (`point_id`, `start_minute`, `end_minute`),
    KEY `idx_fill_status_update` (`apply_status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量补全写入与追溯批次';
```

### Step 4: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityPropertiesTest,DataQualitySchemaTest,TdengineHvacSchemaTest test
```

Expected: `BUILD SUCCESS`，三个测试类全部通过。

### Step 5: 提交

```powershell
git add -- src/main/java/com/platform/config/DataQualityProperties.java src/main/java/com/platform/iot/dataquality/model src/env/init/03-init-hvac-schema.sql src/env/init/04-init-tdengine-hvac.sql src/env/init/08-migrate-mysql-data-quality-fill.sql src/env/init/09-migrate-tdengine-data-quality-fill.sql src/main/java/com/platform/config/TdengineConfig.java src/main/resources/application.yml src/test/resources/application-test.yml src/test/resources/schema-test.sql src/test/java/com/platform/config/TdengineHvacSchemaTest.java src/test/java/com/platform/config/DataQualityPropertiesTest.java src/test/java/com/platform/iot/dataquality/DataQualitySchemaTest.java
git diff --cached --check
git commit -m "feat(data-quality): add fill schema and configuration"
```

## Task 2: 实现典型值配置持久化、快照和审批状态机

**Files:**

- Create: `src/main/java/com/platform/iot/dataquality/mapper/BizPointTypicalValueConfigMapper.java`
- Create: `src/main/java/com/platform/iot/dataquality/TypicalValueConfigProvider.java`
- Create: `src/main/java/com/platform/iot/dataquality/MySqlTypicalValueConfigProvider.java`
- Create: `src/main/java/com/platform/iot/dataquality/TypicalValueConfigService.java`
- Create: `src/test/java/com/platform/iot/dataquality/MySqlTypicalValueConfigProviderTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/TypicalValueConfigServiceTest.java`
- Modify: `src/main/java/com/platform/hvac/mapper/BizDataPointMapper.java`

**Interfaces and locking rules:**

```java
public interface TypicalValueConfigProvider {
    Optional<BizPointTypicalValueConfig> findApproved(
            String pointId, long minuteStart);
    List<BizPointTypicalValueConfig> snapshot();
    void refresh();
}
```

Mapper 必须提供以下明确的 MySQL 操作，不允许 Service 拼 SQL：

```java
Optional<BizPointTypicalValueConfig> selectByIdForUpdate(String configId);
int selectMaxVersion(String pointId);
List<BizPointTypicalValueConfig> selectApprovedSnapshot();
boolean existsApprovedOverlap(
        String pointId, LocalDateTime validFrom, LocalDateTime validTo,
        String excludedConfigId);
```

`BizDataPointMapper` 增加 `selectByIdForUpdate(String pointId)`。创建版本和审批时先锁共同父测点行，再查询版本或重叠范围，避免两个不同配置行并发批准造成幻读。缓存使用 `AtomicReference<List<BizPointTypicalValueConfig>>` 整体替换；刷新失败保留上一份完整快照；应用启动后从未成功加载时 `findApproved` 返回空，禁止生成 Q2。

### Step 1: 写 Provider 失败测试

覆盖以下行为：

- 只加载 `APPROVED`；
- `[validFrom, validTo)` 起点命中、终点不命中；
- 多版本时选择当分钟唯一有效版本；
- 刷新异常后仍返回旧快照；
- 首次加载失败时返回空。

### Step 2: 写状态机失败测试

使用 Mock Mapper、`BuildingScopeService` 和当前用户身份，覆盖：

- 新建时按 `max(version)+1` 生成 DRAFT；
- 只有 DRAFT 可修改和提交；
- 平台管理员才能批准、拒绝、停用；
- 创建人不能批准自己创建的配置；
- 典型值必须落在 `BizDataPoint.minValue/maxValue` 范围；
- 批准时同测点有效期重叠返回 `BusinessException(409, "典型值有效期与已批准配置重叠")`；
- APPROVED 行不可直接修改；
- DISABLED 只影响后续匹配，不删除历史任务。

### Step 3: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=MySqlTypicalValueConfigProviderTest,TypicalValueConfigServiceTest test
```

Expected: 编译失败，因为 Mapper、Provider、Service 尚不存在。

### Step 4: 实现最小状态机

- `TypicalValueConfigService` 负责事务、建筑权限、状态流转和审批冲突检查。
- 复用 `BizDataPointMapper` 校验测点存在、属于目标建筑、`ONLINE`、`ANALOG`、`is_for_calc=1`，并冻结当前单位。
- 创建和修改不得接受客户端传入的 `status/version/createdBy/reviewerId`。
- `approve` 在同一个 MySQL 事务内：锁配置行 → 检查 PENDING → 禁止自审 → 锁共同父测点行 → 检查范围与重叠 → 更新 APPROVED。
- `reject` 要求非空审核意见；`disable` 要求非空停用原因。
- Provider 使用 `@Scheduled(fixedDelayString = "${data-quality.typical-config-refresh-ms:60000}")`，并通过 `@ConditionalOnProperty(prefix="data-quality", name="enabled", havingValue="true")` 注册。

### Step 5: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=MySqlTypicalValueConfigProviderTest,TypicalValueConfigServiceTest test
```

Expected: `BUILD SUCCESS`。

### Step 6: 提交

```powershell
git add -- src/main/java/com/platform/iot/dataquality/mapper/BizPointTypicalValueConfigMapper.java src/main/java/com/platform/iot/dataquality/TypicalValueConfigProvider.java src/main/java/com/platform/iot/dataquality/MySqlTypicalValueConfigProvider.java src/main/java/com/platform/iot/dataquality/TypicalValueConfigService.java src/main/java/com/platform/hvac/mapper/BizDataPointMapper.java src/test/java/com/platform/iot/dataquality/MySqlTypicalValueConfigProviderTest.java src/test/java/com/platform/iot/dataquality/TypicalValueConfigServiceTest.java
git diff --cached --check
git commit -m "feat(data-quality): add typical value approval workflow"
```

## Task 3: 暴露典型值配置分页 API 和建筑权限

**Files:**

- Create: `src/main/java/com/platform/iot/dataquality/model/dto/TypicalValueDtos.java`
- Create: `src/main/java/com/platform/iot/dataquality/controller/TypicalValueConfigController.java`
- Modify: `src/main/java/com/platform/iot/dataquality/TypicalValueConfigService.java`
- Modify: `src/main/java/com/platform/iot/dataquality/mapper/BizPointTypicalValueConfigMapper.java`
- Create: `src/test/java/com/platform/DataQualityTypicalValueControllerFlowTest.java`

**REST contracts:**

```java
record CreateRequest(
        @NotBlank String pointId,
        @NotNull BigDecimal typicalValue,
        @NotBlank @Size(max = 500) String sourceDescription,
        @NotBlank @Size(max = 500) String reason,
        @NotNull Long validFrom,
        Long validTo) {}

record UpdateRequest(
        @NotNull BigDecimal typicalValue,
        @NotBlank @Size(max = 500) String sourceDescription,
        @NotBlank @Size(max = 500) String reason,
        @NotNull Long validFrom,
        Long validTo) {}

record ReviewRequest(@NotBlank @Size(max = 500) String comment) {}
record DisableRequest(@NotBlank @Size(max = 500) String reason) {}
```

列表接受 `pageNum/pageSize/buildingId/pointId/status/validFrom/validTo`；API 时间统一使用 Unix 毫秒，Service 按 Asia/Shanghai 转换为 MySQL `LocalDateTime`，范围采用 `[validFrom, validTo)`。响应不得暴露数据库 Entity，必须返回独立 Response DTO。

### Step 1: 写四角色 API 失败测试

在 H2 流程测试中登录四类角色，验证：

- 建筑业主只读本建筑；
- 能效管理员只在授权建筑创建、修改和提交；
- 平台管理员可读全部并审批、拒绝、停用；
- 第三方开发角色全部拒绝；
- 越权建筑返回 403；
- 非法时间范围返回 400；
- 自审、状态冲突和有效期冲突返回 409；
- 列表分页稳定，响应不含其他建筑数据。

### Step 2: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityTypicalValueControllerFlowTest test
```

Expected: `404` 或编译失败，因为 API 尚不存在。

### Step 3: 实现 Controller 与分页查询

Controller 路径精确为：

```text
GET  /iot/data-quality/typical-values
GET  /iot/data-quality/typical-values/{configId}
POST /iot/data-quality/typical-values
PUT  /iot/data-quality/typical-values/{configId}
POST /iot/data-quality/typical-values/{configId}/submit
POST /iot/data-quality/typical-values/{configId}/approve
POST /iot/data-quality/typical-values/{configId}/reject
POST /iot/data-quality/typical-values/{configId}/disable
```

- Controller 只做 Bean Validation、身份入口和 Service 调用。
- Service 计算允许的建筑集合并传给 Mapper，Mapper 用 SQL 范围过滤，禁止全量读取后在 Java 过滤。
- 平台管理员审批接口使用 `ReviewRequest`；拒绝意见不可空；停用使用独立 `DisableRequest`。
- 整个 Controller 使用 `@ConditionalOnProperty(prefix="data-quality", name="enabled", havingValue="true")`。

### Step 4: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityTypicalValueControllerFlowTest,FourRoleBackendFlowTest test
```

Expected: `BUILD SUCCESS`，既有四角色流程保持通过。

### Step 5: 提交

```powershell
git add -- src/main/java/com/platform/iot/dataquality/model/dto/TypicalValueDtos.java src/main/java/com/platform/iot/dataquality/controller/TypicalValueConfigController.java src/main/java/com/platform/iot/dataquality/TypicalValueConfigService.java src/main/java/com/platform/iot/dataquality/mapper/BizPointTypicalValueConfigMapper.java src/test/java/com/platform/DataQualityTypicalValueControllerFlowTest.java
git diff --cached --check
git commit -m "feat(data-quality): expose typical value management API"
```

## Task 4: 建立补全任务持久化和确定性幂等键

**Files:**

- Create: `src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java`
- Create: `src/main/java/com/platform/iot/dataquality/FillTaskRepository.java`
- Create: `src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java`
- Create: `src/main/java/com/platform/iot/dataquality/FillTaskIdempotency.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/FillTaskEvidence.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/TaskReconciliation.java`
- Create: `src/main/java/com/platform/iot/dataquality/FillTaskEvidenceCodec.java`
- Create: `src/test/java/com/platform/iot/dataquality/MySqlFillTaskRepositoryTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/FillTaskIdempotencyTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/FillTaskEvidenceCodecTest.java`

**Contracts:**

```java
public interface FillTaskRepository {
    BizDataQualityFillTask getOrCreate(BizDataQualityFillTask candidate);
    Optional<BizDataQualityFillTask> findById(String taskId);
    Optional<BizDataQualityFillTask> findByIdForUpdate(String taskId);
    void markFirstApplied(String taskId);
    void recordFailure(String taskId, long minuteStart, String error);
    void reconcile(TaskReconciliation result);
    void markVoided(String taskId, long operatorId, String reason, LocalDateTime at);
}
```

任务证据使用强类型边界：

```java
public sealed interface FillTaskEvidence {
    record Typical(
            String configId,
            int version,
            BigDecimal value,
            String unit,
            long validFrom,
            Long validTo,
            long hourStart,
            String algorithmVersion,
            List<MinuteSegment> appliedSegments) implements FillTaskEvidence {}

    record Interpolation(
            long leftMinute,
            double leftValue,
            long rightMinute,
            double rightValue,
            String algorithmVersion) implements FillTaskEvidence {}

    record MinuteSegment(long fromInclusive, long toExclusive) {}
}

public record TaskReconciliation(
        String taskId,
        int minuteCount,
        int appliedCount,
        int failedCount,
        int replacedCount,
        int voidedCount,
        FillApplyStatus applyStatus,
        List<FillTaskEvidence.MinuteSegment> appliedSegments,
        LocalDateTime closedAt) {}
```

`FillTaskEvidenceCodec` 是 Entity 与 `evidence_json` 之间的唯一 Jackson 编解码边界，后续 Q1/Q2 和恢复服务不得直接拼 JSON。

幂等键格式固定为：

```text
Q1:{pointId}:{leftMinute}:{rightMinute}:{algorithmVersion}
Q2:{pointId}:{typicalConfigId}:{typicalConfigVersion}:{hourStart}
REGEN:{oldTaskId}:{pointId}:{startMinute}:{endMinute}
```

键生成器必须验证 point/config ID 不为空、分钟对齐到 60 秒、小时对齐到 60 分钟，并返回不超过 160 字符的字符串。`getOrCreate` 先按唯一键查询，插入冲突时重新查询同一行，不得生成重复任务。

### Step 1: 写失败测试

覆盖：

- 同一 Q1 连续缺口生成相同键；
- Q1 右端点或算法版本变化生成不同键；
- 同一 Q2 点位、配置版本和自然小时复用；
- 新小时或配置版本变化生成不同键；
- 同一作废任务和范围生成相同 REGEN 键，改变旧任务或范围生成不同键；
- 两次 `getOrCreate` 返回同一 task；
- Typical/Interpolation 证据 JSON 往返不丢字段，非法 JSON 和缺失必填证据拒绝；
- 第一条成功把 WAITING 改为 APPLIED，后续成功不更新计数；
- 失败立即累计失败分钟和错误；
- Repository 明确使用 MySQL Mapper，不引用 TDengine JdbcTemplate。

### Step 2: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=FillTaskIdempotencyTest,FillTaskEvidenceCodecTest,MySqlFillTaskRepositoryTest test
```

Expected: 编译失败，因为任务仓储尚不存在。

### Step 3: 实现仓储

- `failed_minutes_json` 在 Repository 内用 Jackson 序列化最多 60 个分钟错误项。
- `evidence_json` 只通过 `FillTaskEvidenceCodec` 读写；类型与 `source_type` 不匹配时拒绝执行。
- 更新状态与计数使用 Mapper 原子 SQL，不在 Service 中先读后写普通行。
- `markFirstApplied` 仅执行 `WHERE apply_status='WAITING'`。
- `recordFailure` 将状态置 FAILED、`retry_count` 不变、立即更新 `last_error/failed_minutes_json`。
- 重试计数只在真正发起重试时增加，普通冻结写入失败不冒充重试。

### Step 4: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=FillTaskIdempotencyTest,FillTaskEvidenceCodecTest,MySqlFillTaskRepositoryTest test
```

Expected: `BUILD SUCCESS`。

### Step 5: 提交

```powershell
git add -- src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java src/main/java/com/platform/iot/dataquality/FillTaskRepository.java src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java src/main/java/com/platform/iot/dataquality/FillTaskIdempotency.java src/main/java/com/platform/iot/dataquality/model/FillTaskEvidence.java src/main/java/com/platform/iot/dataquality/model/TaskReconciliation.java src/main/java/com/platform/iot/dataquality/FillTaskEvidenceCodec.java src/test/java/com/platform/iot/dataquality/MySqlFillTaskRepositoryTest.java src/test/java/com/platform/iot/dataquality/FillTaskIdempotencyTest.java src/test/java/com/platform/iot/dataquality/FillTaskEvidenceCodecTest.java
git diff --cached --check
git commit -m "feat(data-quality): add auditable fill task storage"
```

## Task 5: 扩展 TDengine 分钟模型并实现质量优先写入

**Files:**

- Modify: `src/main/java/com/platform/iot/temporal/model/RawMinuteAggregate.java`
- Create: `src/main/java/com/platform/iot/temporal/model/MinuteQualityWriteResult.java`
- Create: `src/main/java/com/platform/iot/dataquality/MinuteQualityLockRegistry.java`
- Modify: `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Modify: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`
- Modify: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`
- Modify: `src/test/java/com/platform/iot/formula/FormulaInputAssemblerTest.java`

**Model and repository changes:**

```java
public record RawMinuteAggregate(
        String pointId,
        String pointCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        String equipCode,
        String familyCode,
        String componentCode,
        String suffixCode,
        int isForCalc,
        long minuteStart,
        double averageValue,
        double minimumValue,
        double maximumValue,
        int sampleCount,
        int dataQuality,
        Long firstReceivedTime,
        Long lastReceivedTime,
        long finalizedAt,
        String qualityTaskId) {}
```

Repository 新增：

```java
Optional<RawMinuteAggregate> findPointMinute(String pointId, long minuteStart);
List<RawMinuteAggregate> findRange(
        Set<String> pointIds, long fromInclusive, long toExclusive);
List<RawMinuteAggregate> findByQualityTaskId(String qualityTaskId);
List<MinuteQualityWriteResult> saveAllWithQualityPriority(
        List<RawMinuteAggregate> aggregates, String supersedesTaskId);
void deleteIfOwnedByTask(String pointId, long minuteStart, String taskId);
```

`MinuteQualityWriteResult` 精确定义为：

```java
public record MinuteQualityWriteResult(
        String pointId,
        long minuteStart,
        Outcome outcome,
        Integer previousQuality,
        String previousTaskId) {
    public enum Outcome {
        INSERTED,
        UPGRADED,
        UPDATED_REAL,
        IDEMPOTENT,
        REJECTED_HIGHER_QUALITY,
        REJECTED_SAME_QUALITY
    }
}
```

### Step 1: 写失败测试

扩展现有 Repository 测试覆盖：

- Q0 的 `quality_task_id` 为 NULL；
- Q1/Q2 的 `sample_count=0`、接收时间 NULL、任务 ID 正确绑定；
- 不存在目标时写入；
- `Q2→Q1`、`Q2→Q0`、`Q1→Q0` 允许；
- Q0 拒绝被 Q1/Q2 覆盖；
- 新 Q0 与旧 Q0 值不同允许 `UPDATED_REAL`，支持同一分钟冲突真实事件重聚合；
- 相同 task 幂等成功；
- 同级不同 task 默认冲突，只有 `supersedesTaskId` 匹配旧 task 才允许；
- `deleteIfOwnedByTask` 不删除已被新任务或高质量替换的分钟；
- `findRange` 一次查询多个点位并使用半开区间；
- 5/30 分钟平均权重为 `sample_count > 0 ? sample_count : 1`，返回样本数仍只累加真实样本。

### Step 2: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=TdengineHvacMinuteRepositoryTest,HvacMinuteAggregationServiceTest,FormulaInputAssemblerTest test
```

Expected: 编译失败，因为 `RawMinuteAggregate` 构造参数和 Repository 契约已改变。

### Step 3: 修改模型映射和 SQL

- 所有 Q0 构造点显式传入已有接收时间和 `qualityTaskId=null`。
- TDengine ResultSet 映射必须用 `getTimestamp` 并允许 NULL，不得把 NULL 接收时间转换成 `0`。
- `MinuteQualityLockRegistry` 使用固定 256 个 `ReentrantLock` 条带，键为 pointId+minuteStart；不得永久保存每个分钟键。`saveAllWithQualityPriority` 在按 point+minute 排序取得锁后，一次批量读取当前行、比较质量并批量 upsert，最后逆序释放，禁止逐点查询和并发死锁。
- 真实 Q0 和生成 Q1/Q2 全部走同一优先级入口，避免 Q1/Q2 在并发交错时反向覆盖刚写入的 Q0。
- `HvacMinuteAggregationService` 使用批量优先级入口；只有结果为 INSERTED/UPGRADED/UPDATED_REAL/IDEMPOTENT 才进入冻结事件。
- 生成分钟不修改 `st_raw_event`。
- 5/30 分钟 SQL 使用：

```sql
SUM(avg_val * CASE WHEN sample_count > 0 THEN sample_count ELSE 1 END)
/ SUM(CASE WHEN sample_count > 0 THEN sample_count ELSE 1 END)
```

同时 `SUM(sample_count)` 原样返回真实样本数。

### Step 4: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=TdengineHvacMinuteRepositoryTest,HvacMinuteAggregationServiceTest,FormulaInputAssemblerTest,HvacQueryServiceTest test
```

Expected: `BUILD SUCCESS`。

### Step 5: 提交

```powershell
git add -- src/main/java/com/platform/iot/temporal/model/RawMinuteAggregate.java src/main/java/com/platform/iot/temporal/model/MinuteQualityWriteResult.java src/main/java/com/platform/iot/dataquality/MinuteQualityLockRegistry.java src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java src/test/java/com/platform/iot/formula/FormulaInputAssemblerTest.java
git diff --cached --check
git commit -m "feat(data-quality): enforce minute quality write priority"
```

## Task 6: 在冻结事件与公式之间建立质量完成边界并即时生成 Q2

**Files:**

- Modify: `src/main/java/com/platform/iot/quality/PointRuntimeConfig.java`
- Modify: `src/main/java/com/platform/iot/quality/MySqlDataPointConfigProvider.java`
- Modify: `src/test/java/com/platform/iot/quality/MySqlDataPointConfigProviderTest.java`
- Create: `src/main/java/com/platform/iot/dataquality/event/HvacMinuteQualityReadyEvent.java`
- Create: `src/main/java/com/platform/iot/dataquality/TypicalValueFillService.java`
- Create: `src/main/java/com/platform/iot/dataquality/HvacMinuteQualityCompletionService.java`
- Create: `src/main/java/com/platform/iot/dataquality/HvacMinuteQualityBypassListener.java`
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteBatchFrozenEvent.java`
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Modify: `src/main/java/com/platform/iot/formula/HvacFormulaEngine.java`
- Create: `src/test/java/com/platform/iot/dataquality/TypicalValueFillServiceTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/HvacMinuteQualityCompletionServiceTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/DataQualityConditionalConfigurationTest.java`
- Modify: `src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java`
- Modify: `src/test/java/com/platform/iot/formula/HvacFormulaEngineTest.java`

**Event contract:**

```java
public record HvacMinuteBatchFrozenEvent(
        long minuteStart,
        long finalizedAt,
        boolean recovery,
        Set<String> buildingIds,
        List<RawMinuteAggregate> aggregates) {
    public HvacMinuteBatchFrozenEvent {
        buildingIds = Set.copyOf(buildingIds);
        aggregates = List.copyOf(aggregates);
    }
}

public record HvacMinuteQualityReadyEvent(
        long minuteStart,
        long finalizedAt,
        QualityEventSource source,
        Set<String> buildingIds,
        List<RawMinuteAggregate> aggregates,
        Set<String> affectedPointIds) {
    public HvacMinuteQualityReadyEvent {
        buildingIds = Set.copyOf(buildingIds);
        aggregates = List.copyOf(aggregates);
        affectedPointIds = Set.copyOf(affectedPointIds);
    }
}
```

Frozen 的 `buildingIds` 来自本轮活动计算测点快照，因此即使整分钟没有一行 Q0，质量层仍知道要补哪些建筑。READY 的 `affectedPointIds` 在正常首次冻结时为空，表示计算 `buildingIds` 内全部活动指标；历史修正时只包含被替换或删除的点位，供后续任务做定向指标筛选。

`PointRuntimeConfig` 新增 `String dataType` 和 `String unit`，由 MySQL 快照提供；Q1/Q2 只处理 `ONLINE + ANALOG + isForCalc=1`。

### Step 1: 写事件边界和 Q2 失败测试

覆盖：

- 输入完整的 19 个 Q0 时不访问 `TypicalValueConfigProvider` 的 MySQL Mapper、不创建任务，直接发布一次 READY；
- 缺一个点且有 APPROVED 有效配置时，当分钟立即生成 Q2，不等待五分钟；
- Q2 值三个聚合字段相等，`sampleCount=0`，接收时间为 NULL，`qualityTaskId` 为小时任务；
- DRAFT/PENDING/REJECTED/DISABLED/过期配置不生成 Q2；
- 无合法典型值时仍发布 READY，公式看到明确缺失输入；
- 同点、同配置版本、同自然小时复用内存中的任务；新小时或新版本重新 `getOrCreate`；
- Q2 写 TDengine 失败时记录任务失败且不发布该生成值；
- 质量模块关闭时旁路监听器将 Frozen 转为 READY，现有 Q0 公式链路仍工作；
- 质量模块关闭且 Frozen 为空时旁路不发布 READY，保持原有“无真实分钟不计算”行为；
- 聚合分钟完全无真实行时也必须发布 Frozen，让质量层有机会生成 Q2。

### Step 2: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=TypicalValueFillServiceTest,HvacMinuteQualityCompletionServiceTest,DataQualityConditionalConfigurationTest,HvacMinuteAggregationServiceTest,HvacFormulaEngineTest test
```

Expected: 编译失败，因为 READY 事件和质量完成服务尚不存在。

### Step 3: 实现 Q2 即时补全

- `HvacMinuteAggregationService.processMinute` 在空聚合时不调用 TDengine 写入，但仍用活动计算点推导 `buildingIds` 并发布 `HvacMinuteBatchFrozenEvent`。
- `HvacMinuteQualityCompletionService` 监听 Frozen：
  1. 从内存测点快照取得目标活动计算点；
  2. 以事件 Q0 列表判断缺失点；
  3. 对每个缺失点调用 `TypicalValueFillService`;
  4. Q2 成功后合并进事件输入；
  5. 恢复事件若只带局部点，按建筑回读完整分钟；
  6. 发布一次 `NORMAL_FREEZE` 或 `TYPICAL_FILL` READY；
  7. 本任务在 READY 发布后返回；Task 7 会在这个返回点追加 Q1 历史修正，保证当前分钟公式先执行。
- Q2 任务证据固定包含 `configId/version/value/unit/validFrom/validTo/hourStart/algorithmVersion/appliedSegments`；创建时段列表为空，小时收口后由 Task 10 一次重建。
- Q2 算法版本固定常量 `TYPICAL_V1`。
- 复用 Task 5 的 `MinuteQualityLockRegistry`；Q2 不建立第二套锁。
- 小时任务本地缓存键为 point/config/version/hour；跨重启仍以 MySQL 唯一幂等键兜底。
- `HvacFormulaEngine` 不再监听 Frozen，只监听 READY。首次事件继续使用事件快照，恢复或修正事件回读完整分钟。
- `HvacMinuteQualityCompletionService` 和管理组件仅在 `data-quality.enabled=true` 注册；旁路监听器仅在 false 时注册，并只转发至少含一行 Q0 的 Frozen。

### Step 4: 添加性能断言

在完成服务测试中加入：

```java
verifyNoInteractions(fillTaskRepository); // 19 点 Q0 完整时
verify(eventPublisher, times(1))
        .publishEvent(any(HvacMinuteQualityReadyEvent.class));
```

对同一点连续 60 个缺失分钟，验证小时内任务只从仓储创建/取得一次；普通成功分钟不执行 60 次 MySQL 计数更新。

### Step 5: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=TypicalValueFillServiceTest,HvacMinuteQualityCompletionServiceTest,DataQualityConditionalConfigurationTest,HvacMinuteAggregationServiceTest,HvacFormulaEngineTest test
```

Expected: `BUILD SUCCESS`。

### Step 6: 提交

```powershell
git add -- src/main/java/com/platform/iot/quality/PointRuntimeConfig.java src/main/java/com/platform/iot/quality/MySqlDataPointConfigProvider.java src/main/java/com/platform/iot/dataquality/event/HvacMinuteQualityReadyEvent.java src/main/java/com/platform/iot/dataquality/TypicalValueFillService.java src/main/java/com/platform/iot/dataquality/HvacMinuteQualityCompletionService.java src/main/java/com/platform/iot/dataquality/HvacMinuteQualityBypassListener.java src/main/java/com/platform/iot/aggregation/HvacMinuteBatchFrozenEvent.java src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java src/main/java/com/platform/iot/formula/HvacFormulaEngine.java src/test/java/com/platform/iot/quality/MySqlDataPointConfigProviderTest.java src/test/java/com/platform/iot/dataquality/TypicalValueFillServiceTest.java src/test/java/com/platform/iot/dataquality/HvacMinuteQualityCompletionServiceTest.java src/test/java/com/platform/iot/dataquality/DataQualityConditionalConfigurationTest.java src/test/java/com/platform/iot/aggregation/HvacMinuteAggregationServiceTest.java src/test/java/com/platform/iot/formula/HvacFormulaEngineTest.java
git diff --cached --check
git commit -m "feat(data-quality): fill missing minutes from approved values"
```

## Task 7: 实现五分钟内 Q1 线性插值与 Q2→Q1 升级

**Files:**

- Create: `src/main/java/com/platform/iot/dataquality/LinearMinuteInterpolator.java`
- Create: `src/main/java/com/platform/iot/dataquality/InterpolationFillService.java`
- Modify: `src/main/java/com/platform/iot/dataquality/HvacMinuteQualityCompletionService.java`
- Create: `src/test/java/com/platform/iot/dataquality/LinearMinuteInterpolatorTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/InterpolationFillServiceTest.java`

**Interpolation contract:**

```java
public final class LinearMinuteInterpolator {
    public List<InterpolatedMinute> interpolate(
            long leftMinute,
            double leftValue,
            long rightMinute,
            double rightValue,
            int maxGapMinutes);
}

public record InterpolatedMinute(long minuteStart, double value) {}
```

若 `missingCount = (right-left)/60000 - 1` 不在 `1..maxGapMinutes`，直接返回空。每个值使用：

```text
leftValue + (rightValue-leftValue)
* (targetMinute-leftMinute)
/ (rightMinute-leftMinute)
```

### Step 1: 写纯算法失败测试

覆盖：

- 缺 1 分钟；
- 连续缺 5 分钟；
- 右端减去 6 分钟的查询边界能得到左端，避免少查一分钟；
- 6 个缺失分钟拒绝；
- 分钟未对齐、左右顺序非法拒绝；
- 相同端点值生成相同中间值。

### Step 2: 写业务服务失败测试

覆盖：

- 左右端点必须都是 Q0；
- 只允许 ONLINE、ANALOG、`isForCalc=1`；
- 每个插值结果通过点位量程校验；
- 缺口中 Q2 允许升级为 Q1，已有 Q0/Q1 不覆盖；
- 一个连续缺口只创建一个 Q1 任务；
- 任务证据保存左右端点时间、值、质量和算法版本 `LINEAR_V1`；
- 最多五行一次批量 TDengine 写入；
- 每个真正变化的历史分钟各发布一次 `INTERPOLATION_CORRECTION` READY；
- 同一右端点重复处理保持幂等；
- 将本批右端 Q0 点位收集成集合，按建筑和时间范围一次 `findRange` 读取候选分钟，不允许逐点或逐分钟 N+1。

### Step 3: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=LinearMinuteInterpolatorTest,InterpolationFillServiceTest test
```

Expected: 编译失败，因为插值算法和服务尚不存在。

### Step 4: 实现回溯升级

- READY 当前分钟发布完成后，将本批 Q0 点位交给 `InterpolationFillService`。
- 本批所有右端 Q0 共享一次 `[right-(maxGap+1)*60000, right+60000)` 批量查询；在内存按点分组后，各自倒序找最近 Q0 左端。
- 服务只对不存在或当前 Q2 的中间分钟生成候选；出现中间 Q0/Q1 时保留该行，不降级或同级覆盖。
- Q1 的 `avg/min/max` 都写插值结果，样本数 0、接收时间 NULL。
- 批量写入前按 point+minute 的固定排序获取条带锁，写完逆序释放，避免并发死锁。
- TDengine 成功的分钟才累计任务应用结果并发布 READY；失败分钟立即进入任务错误。
- 旧 Q2 任务不删除，小时收口或即时替换记账会增加其 `replaced_count`。

### Step 5: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=LinearMinuteInterpolatorTest,InterpolationFillServiceTest,HvacMinuteQualityCompletionServiceTest,HvacFormulaEngineTest test
```

Expected: `BUILD SUCCESS`。

### Step 6: 提交

```powershell
git add -- src/main/java/com/platform/iot/dataquality/LinearMinuteInterpolator.java src/main/java/com/platform/iot/dataquality/InterpolationFillService.java src/main/java/com/platform/iot/dataquality/HvacMinuteQualityCompletionService.java src/test/java/com/platform/iot/dataquality/LinearMinuteInterpolatorTest.java src/test/java/com/platform/iot/dataquality/InterpolationFillServiceTest.java
git diff --cached --check
git commit -m "feat(data-quality): interpolate short real data gaps"
```

## Task 8: 将迟到真实事件升级为 Q0 并限制自动修正窗口

**Files:**

- Create: `src/main/java/com/platform/iot/aggregation/HvacPointMinuteAggregator.java`
- Modify: `src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java`
- Create: `src/main/java/com/platform/iot/dataquality/event/HvacLateRealEventStoredEvent.java`
- Modify: `src/main/java/com/platform/iot/ingest/HvacIngestionService.java`
- Create: `src/main/java/com/platform/iot/dataquality/LateRealMinuteCorrectionService.java`
- Create: `src/test/java/com/platform/iot/aggregation/HvacPointMinuteAggregatorTest.java`
- Modify: `src/test/java/com/platform/iot/ingest/HvacIngestionServiceTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/LateRealMinuteCorrectionServiceTest.java`

**Late event contract:**

```java
public record HvacLateRealEventStoredEvent(
        String pointId,
        String buildingId,
        long minuteStart,
        long receivedAt) {}
```

### Step 1: 写失败测试

覆盖：

- INSERTED/CONFLICT_UPDATED 且 `late=true` 时发布迟到事件；
- DUPLICATE 不重复发布；
- 未超过 24 小时且目标为 Q1/Q2 时，重新读取该点该分钟全部原始事件（包括 late），聚合为 Q0 并覆盖；
- Q0 的样本数和接收时间来自真实事件，`qualityTaskId=null`；
- 目标已是 Q0 时幂等退出；
- 超过配置窗口时保留原始事件但不自动改正式分钟；
- Q0 写成功后更新旧任务替换计数并发布 `LATE_REAL_CORRECTION` READY；
- 迟到 Q0 自身 READY 完成后，也作为新的真实右端点调用 `InterpolationFillService`，可修正它之前五分钟内的缺口；
- 原始事件写失败、正式分钟写失败均不发布 READY；
- 同点同分钟并发只执行一次有效升级。

### Step 2: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=HvacPointMinuteAggregatorTest,HvacIngestionServiceTest,LateRealMinuteCorrectionServiceTest test
```

Expected: 编译失败，因为迟到事件与修正服务尚不存在。

### Step 3: 提取真实分钟纯聚合器

`HvacPointMinuteAggregator` 接收 `PointRuntimeConfig`、目标分钟、真实事件和 finalizedAt，返回 Q0 `RawMinuteAggregate`。正常冻结和迟到修正共用它，避免复制统计、质量和接收时间规则。它不访问数据库、不发布事件。

### Step 4: 实现迟到 Q0 修正

- `HvacIngestionService` 只有在原始事件 TDengine upsert 成功后发布迟到事件。
- `LateRealMinuteCorrectionService` 用 `@Async("virtualThreadExecutor")` 监听，防止历史修正阻塞 MQTT 接入返回。
- 自动窗口判断使用服务器接收时间与目标分钟比较，默认 24 小时；超过窗口记录日志和指标，等待管理 API 人工重算。
- 在 point+minute 锁内先读当前正式行，再查询原始事件并写 Q0；旧 taskId 在覆盖前保存，覆盖后用于替换计数。
- Q0 成功后回读目标建筑完整分钟并发布 READY。
- READY 返回后把该 Q0 交给 Task 7 的插值服务；先重算本分钟，再修正更早分钟，避免历史回溯阻塞当前修正结果。

### Step 5: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=HvacPointMinuteAggregatorTest,HvacIngestionServiceTest,LateRealMinuteCorrectionServiceTest,HvacMinuteAggregationServiceTest test
```

Expected: `BUILD SUCCESS`。

### Step 6: 提交

```powershell
git add -- src/main/java/com/platform/iot/aggregation/HvacPointMinuteAggregator.java src/main/java/com/platform/iot/aggregation/HvacMinuteAggregationService.java src/main/java/com/platform/iot/dataquality/event/HvacLateRealEventStoredEvent.java src/main/java/com/platform/iot/ingest/HvacIngestionService.java src/main/java/com/platform/iot/dataquality/LateRealMinuteCorrectionService.java src/test/java/com/platform/iot/aggregation/HvacPointMinuteAggregatorTest.java src/test/java/com/platform/iot/ingest/HvacIngestionServiceTest.java src/test/java/com/platform/iot/dataquality/LateRealMinuteCorrectionServiceTest.java
git diff --cached --check
git commit -m "feat(data-quality): upgrade late real minutes to quality zero"
```

## Task 9: 定向重算指标并清除已经失效的成功结果

**Files:**

- Modify: `src/main/java/com/platform/iot/formula/IndicatorFormula.java`
- Modify: `src/main/java/com/platform/iot/formula/ChillerCopFormula.java`
- Modify: `src/main/java/com/platform/iot/formula/CoolingTowerEfficiencyFormula.java`
- Modify: `src/main/java/com/platform/iot/formula/PumpEfficiencyFormula.java`
- Modify: `src/main/java/com/platform/iot/formula/AhuPowerEfficiencyFormula.java`
- Create: `src/main/java/com/platform/iot/formula/FormulaDependencyResolver.java`
- Modify: `src/main/java/com/platform/iot/formula/HvacFormulaEngine.java`
- Modify: `src/main/java/com/platform/iot/temporal/IndicatorMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java`
- Modify: `src/main/java/com/platform/cache/IndicatorLatestCacheService.java`
- Create: `src/test/java/com/platform/iot/formula/FormulaDependencyResolverTest.java`
- Modify: `src/test/java/com/platform/iot/formula/HvacFormulaEngineTest.java`
- Modify: `src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java`
- Modify: `src/test/java/com/platform/cache/IndicatorLatestCacheServiceTest.java`

**Dependency and invalidation contracts:**

```java
public interface IndicatorFormula {
    String indicatorCode();
    String formulaVersion();
    Set<String> requiredInputKeys();
    FormulaCalculation calculate(FormulaInputs inputs);
}
```

```java
void deleteSuccesses(Set<IndicatorMinuteKey> keys);

boolean setIfNotOlder(
        IndicatorLatestState state,
        boolean allowEqualMinuteSuccessInvalidation);
```

每个公式返回其全部必需语义键；冷却塔公式同时列出实测湿球键和干球温度/相对湿度替代输入键，Resolver 只负责缩小候选指标，不改变公式内部“实测优先、焓湿换算兜底”的判断。

### Step 1: 写依赖筛选失败测试

覆盖：

- 设备点只影响同建筑、同设备、需要该语义键的指标；
- 环境点影响同建筑内需要该环境键的指标；
- 不相关点位不触发指标重算；
- 正常首次 READY 的空 `affectedPointIds` 仍计算该建筑全部活动指标；
- 修正事件只计算 Resolver 返回的指标 ID。

### Step 2: 写旧成功删除失败测试

覆盖：

- 修正后公式成功：相同指标+分钟幂等覆盖，不删除新成功；
- 修正后公式变为 MISSING/INVALID/ENGINE_ERROR：先删除旧成功，再写异常；
- 初次 NORMAL_FREEZE 失败不执行无意义删除；
- 批量删除 SQL 只命中明确 `IndicatorMinuteKey`；
- 删除失败时不发布 Redis/WebSocket 新状态，异常向上抛给补偿层。
- 普通路径仍拒绝同一分钟 SUCCESS→失败；correction/manual 权威修正允许同一分钟 SUCCESS→失败；
- 两种缓存模式都绝不允许更早分钟覆盖更晚分钟。

### Step 3: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=FormulaDependencyResolverTest,HvacFormulaEngineTest,TdengineIndicatorMinuteRepositoryTest test
```

Expected: 编译失败，因为依赖键与批量删除契约尚不存在。

### Step 4: 实现定向重算和失效清理

- `FormulaDependencyResolver` 使用 `PointRuntimeConfig` 构造与 `FormulaInputAssembler` 一致的语义键，禁止再实现另一套键规则；如有必要，将键构造提取为包内 `FormulaInputKeyResolver` 并由两者共用。
- `HvacFormulaEngine` 对 correction/manual 来源使用 `affectedPointIds` 得到 `onlyIndicatorIds`。
- 对 correction/manual 计算失败的指标，先批量删除旧成功，再保存失败审计；成功结果继续按原时间戳 upsert。
- Redis/WebSocket 只在 TDengine 成功删除/保存后更新；旧历史成功被删除后，最新缓存若指向该分钟，调用 `setIfNotOlder(state, true)` 发布失败状态覆盖它。正常首次计算和普通恢复传 false。
- `HvacFormulaRecoveryService` 继续负责普通缺失成功的低频补算，不承担源分钟作废。

### Step 5: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=FormulaDependencyResolverTest,HvacFormulaEngineTest,TdengineIndicatorMinuteRepositoryTest,IndicatorLatestCacheServiceTest,HvacFormulaRecoveryServiceTest,CoolingTowerEfficiencyFormulaTest test
```

Expected: `BUILD SUCCESS`，干球温度+相对湿度换算回归仍通过。

### Step 6: 提交

```powershell
git add -- src/main/java/com/platform/iot/formula/IndicatorFormula.java src/main/java/com/platform/iot/formula/ChillerCopFormula.java src/main/java/com/platform/iot/formula/CoolingTowerEfficiencyFormula.java src/main/java/com/platform/iot/formula/PumpEfficiencyFormula.java src/main/java/com/platform/iot/formula/AhuPowerEfficiencyFormula.java src/main/java/com/platform/iot/formula/FormulaDependencyResolver.java src/main/java/com/platform/iot/formula/HvacFormulaEngine.java src/main/java/com/platform/iot/temporal/IndicatorMinuteRepository.java src/main/java/com/platform/iot/temporal/impl/TdengineIndicatorMinuteRepository.java src/main/java/com/platform/cache/IndicatorLatestCacheService.java src/test/java/com/platform/iot/formula/FormulaDependencyResolverTest.java src/test/java/com/platform/iot/formula/HvacFormulaEngineTest.java src/test/java/com/platform/iot/temporal/TdengineIndicatorMinuteRepositoryTest.java src/test/java/com/platform/cache/IndicatorLatestCacheServiceTest.java
git diff --cached --check
git commit -m "fix(formula): invalidate stale results after quality changes"
```

## Task 10: 实现失败重试、小时收口和跨库补偿

**Files:**

- Modify: `src/main/java/com/platform/iot/dataquality/FillTaskRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java`
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityRecoveryService.java`
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityRecoveryScheduler.java`
- Create: `src/main/java/com/platform/iot/dataquality/FillTaskReconciliationService.java`
- Create: `src/main/java/com/platform/iot/dataquality/FillTaskReconciliationScheduler.java`
- Create: `src/test/java/com/platform/iot/dataquality/DataQualityRecoveryServiceTest.java`
- Create: `src/test/java/com/platform/iot/dataquality/FillTaskReconciliationServiceTest.java`
- Modify: `src/test/java/com/platform/iot/dataquality/DataQualityConditionalConfigurationTest.java`

Task 4 已建立强类型 `FillTaskEvidence` 和唯一 JSON 编解码边界。本任务恢复执行时只反序列化该类型；反序列化失败必须把任务保留为 FAILED 并记录明确错误。

Repository 新增：

```java
List<BizDataQualityFillTask> findRetryable(
        LocalDateTime updatedBefore, int limit);
List<BizDataQualityFillTask> findTypicalTasksToClose(
        LocalDateTime hourEndedBefore, int limit);
void incrementRetry(String taskId);
void recordReplacements(Map<String, Integer> countsByOldTaskId);
```

### Step 1: 写恢复失败测试

覆盖：

- 未知 sourceType、非法 JSON、缺必需字段拒绝；
- MySQL 已建任务而 TDengine 失败：按原 taskId 重试；
- TDengine 已有相同 taskId：视为幂等成功并补记状态；
- 重试时目标已有更高质量：不覆盖并累计 replaced；
- Q2 技术重试使用任务生成时冻结的证据；配置后来停用不删除此前目标分钟的合法历史依据；
- Q1 重试前重新确认左右端点仍为相同 Q0；证据已失效时保留 FAILED 并要求人工重算；
- 任务仍失败时累计 retryCount 和 lastError；
- 失败任务不得发布 READY；
- 一次最多取固定 100 条，避免无界恢复。

### Step 2: 写小时收口失败测试

覆盖：

- 从 TDengine `quality_task_id` 查询实际分钟行；
- 将不连续分钟压缩成 `[start,end)` 段写回 evidence；
- `minuteCount` 只统计实际关联行，不把小时范围内未补全分钟算进去；
- 正确重建 applied/replaced/voided/failed 计数；
- 全部被替换时状态为 REPLACED；
- 第一次写入成功将 WAITING 改 APPLIED，后续分钟不逐条更新；
- 同一小时只执行一次核对更新；
- TDengine 不可用时保留原状态，下轮继续；
- reconciliation 开关关闭时不注册收口调度器。

### Step 3: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityRecoveryServiceTest,FillTaskReconciliationServiceTest,DataQualityConditionalConfigurationTest test
```

Expected: 编译失败，因为恢复和收口组件尚不存在。

### Step 4: 实现补偿调度

- `DataQualityRecoveryScheduler` 在 `data-quality.enabled=true` 时注册，周期使用 `retry-delay-ms`，Service 每轮只处理 100 条。
- `FillTaskReconciliationScheduler` 同时要求 `data-quality.enabled=true` 与 `reconciliation-enabled=true`。
- Q2 重试只处理任务 evidence 中记录的失败分钟，或尚未在 TDengine 发现相同 taskId 的分钟。
- Q1 重试以一个连续缺口为单位批量执行。
- 写入结果为 IDEMPOTENT 也可修复 MySQL 状态，但不得重复发布公式事件；只有本轮实际写入/升级的分钟发布 READY。
- 收口以自然小时结束作为边界，不扫描仍在进行中的小时。
- 所有调度异常按任务隔离，单个坏任务不得中止后续任务。

### Step 5: 添加写放大断言

使用 Mockito 验证同点连续 60 个 Q2 分钟：

```java
verify(fillTaskRepository, times(1)).markFirstApplied(taskId);
verify(fillTaskRepository, never()).reconcile(any()); // 分钟热路径
verify(fillTaskRepository, times(1)).reconcile(any()); // 小时收口
```

### Step 6: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityRecoveryServiceTest,FillTaskReconciliationServiceTest,DataQualityConditionalConfigurationTest test
```

Expected: `BUILD SUCCESS`。

### Step 7: 提交

```powershell
git add -- src/main/java/com/platform/iot/dataquality/FillTaskRepository.java src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java src/main/java/com/platform/iot/dataquality/DataQualityRecoveryService.java src/main/java/com/platform/iot/dataquality/DataQualityRecoveryScheduler.java src/main/java/com/platform/iot/dataquality/FillTaskReconciliationService.java src/main/java/com/platform/iot/dataquality/FillTaskReconciliationScheduler.java src/test/java/com/platform/iot/dataquality/DataQualityRecoveryServiceTest.java src/test/java/com/platform/iot/dataquality/FillTaskReconciliationServiceTest.java src/test/java/com/platform/iot/dataquality/DataQualityConditionalConfigurationTest.java
git diff --cached --check
git commit -m "feat(data-quality): recover and reconcile fill tasks"
```

## Task 11: 实现补全任务查询、重试、作废并重算和范围重算 API

**Files:**

- Create: `src/main/java/com/platform/iot/dataquality/model/dto/DataQualityFillDtos.java`
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityFillTaskService.java`
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityRecalculationService.java`
- Create: `src/main/java/com/platform/iot/dataquality/controller/DataQualityFillController.java`
- Modify: `src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java`
- Modify: `src/main/java/com/platform/iot/dataquality/FillTaskRepository.java`
- Create: `src/test/java/com/platform/iot/dataquality/DataQualityRecalculationServiceTest.java`
- Create: `src/test/java/com/platform/DataQualityFillControllerFlowTest.java`

**REST DTOs:**

```java
record VoidAndRecalculateRequest(
        @NotBlank @Size(max = 500) String reason) {}

record RecalculateRequest(
        @NotBlank String buildingId,
        List<String> pointIds,
        @NotNull Long fromInclusive,
        @NotNull Long toExclusive,
        @NotBlank @Size(max = 500) String reason) {}
```

列表 Query 包含 `pageNum/pageSize/buildingId/pointId/sourceType/dataQuality/applyStatus/fromInclusive/toExclusive`；Response 返回任务全部审计字段、解析后的 evidence 和实际分钟段，不返回内部堆栈。

### Step 1: 写 Service 失败测试

覆盖：

- retry 只允许 FAILED；其他状态返回 409；
- retry 调用 Task 10 的同任务恢复，不新建审批；
- void 原因必填，只有平台管理员可执行；
- 作废前逐行核验 TDengine 当前 `qualityTaskId`；
- 已被 Q0/Q1 或新任务替换的行不删除，只更新计数；
- 仍属于旧任务的行删除后将任务标记 VOIDED；
- 作废重算创建带 `supersedesTaskId` 的新任务，幂等键使用：

```text
REGEN:{oldTaskId}:{pointId}:{startMinute}:{endMinute}
```

- 作废后重新寻找真实 Q0、合法 Q1、合法 Q2；都没有时保留缺失并发布 MANUAL_RECALCULATION READY，使旧公式成功被删除；
- 指定范围重算按 60 分钟块批处理，不把整个历史范围一次加载进内存；
- 输入时间未按分钟对齐、`from>=to`、空原因返回 400；
- 点位不存在返回 404；TDengine 即时操作失败返回 503。

### Step 2: 写四角色 API 失败测试

覆盖：

- 建筑业主、能效管理员只读授权建筑补全记录；
- 平台管理员读取全部并执行 retry/void/recalculate；
- 第三方开发角色拒绝；
- 建筑越权 403；
- 列表分页、全部筛选字段和 `[from,to)`；
- task 不存在 404；
- 非法状态 409；
- 原因缺失 400；
- API 流程测试通过 `@MockBean` 隔离 TDengine，后台恢复/收口关闭。

### Step 3: 运行测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityRecalculationServiceTest,DataQualityFillControllerFlowTest test
```

Expected: `404` 或编译失败，因为任务管理 API 尚不存在。

### Step 4: 实现管理 API

Controller 路径精确为：

```text
GET  /iot/data-quality/fill-tasks
GET  /iot/data-quality/fill-tasks/{taskId}
POST /iot/data-quality/fill-tasks/{taskId}/retry
POST /iot/data-quality/fill-tasks/{taskId}/void-and-recalculate
POST /iot/data-quality/recalculate
```

- GET 使用角色注解和 `BuildingScopeService` 双重限制。
- retry/void/recalculate 只允许 `PLATFORM_ADMIN`。
- “作废并重算”是异常修正入口，不增加 `review_status`，不提供逐条批准/拒绝。
- 明确作废整批时才写 VOIDED；部分行已经替换时通过计数表达，不回滚高质量数据。
- 人工重算的 READY 来源固定为 `MANUAL_RECALCULATION`，确保公式旧成功失效处理开启。
- 列表 SQL 在 MySQL 侧分页和筛选，禁止全量查出后 Java 分页。

### Step 5: 运行定向测试

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityRecalculationServiceTest,DataQualityFillControllerFlowTest,DataQualityTypicalValueControllerFlowTest,FourRoleBackendFlowTest test
```

Expected: `BUILD SUCCESS`。

### Step 6: 提交

```powershell
git add -- src/main/java/com/platform/iot/dataquality/model/dto/DataQualityFillDtos.java src/main/java/com/platform/iot/dataquality/DataQualityFillTaskService.java src/main/java/com/platform/iot/dataquality/DataQualityRecalculationService.java src/main/java/com/platform/iot/dataquality/controller/DataQualityFillController.java src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java src/main/java/com/platform/iot/dataquality/FillTaskRepository.java src/test/java/com/platform/iot/dataquality/DataQualityRecalculationServiceTest.java src/test/java/com/platform/DataQualityFillControllerFlowTest.java
git diff --cached --check
git commit -m "feat(data-quality): expose fill recovery and recalculation API"
```

## Task 12: 完成性能回归、模拟器能力和真实冒烟说明

**Files:**

- Create: `src/test/java/com/platform/iot/dataquality/DataQualityPerformanceContractTest.java`
- Create: `src/test/java/com/platform/DataQualityBackendAcceptanceTest.java`
- Modify: `.scripts/simulate-hvac-19-points.mjs`
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Modify: `src/env/docker-compose.yml`

### Step 1: 写跨模块验收测试

`DataQualityBackendAcceptanceTest` 使用 H2 + Fake/Mock TDengine，覆盖完整业务顺序：

```text
Q0 缺点
→ 当分钟 Q2
→ 右端 Q0 到达后历史 Q1
→ 迟到真实事件后 Q0
→ 指标质量同步 2→1→0
→ 每个生成分钟可用 qualityTaskId 追溯
```

同时覆盖无典型值时的严格缺失和公式异常，证明系统没有套用 `default_value`。

`DataQualityPerformanceContractTest` 使用 Mockito 调用次数作为稳定性能契约：

- 完整 19 点 Q0：不创建补全任务，READY/公式各一次；
- 19 点插值候选：一次批量范围查询，禁止逐点逐分钟查询；
- 一个连续缺口：一个任务、一次批量写；
- 同点连续 60 分钟 Q2：同小时一个任务、成功热路径不更新 60 次 MySQL；
- 普通历史和公式查询：不访问新 MySQL Mapper，不跨库联表。

### Step 2: 运行验收测试并确认失败

Run:

```powershell
.\mvnw.cmd -Dtest=DataQualityPerformanceContractTest,DataQualityBackendAcceptanceTest test
```

Expected: 若前面任务有任何事件重复、N+1、写放大或质量升级断链，本组测试失败。

### Step 3: 扩展模拟器的定点历史事件能力

保留现有默认 19 点、10 秒一轮、70 秒行为，新增三个可选环境变量：

```text
HVAC_ONLY_POINT=PUMP1_Power
HVAC_EVENT_TIME_MS=目标旧分钟内的Unix毫秒
HVAC_VALUE_OVERRIDE=10.0
```

指定 `HVAC_ONLY_POINT` 时只发布该点一次；`HVAC_EVENT_TIME_MS` 必须是安全整数；`HVAC_VALUE_OVERRIDE` 必须是有限数字。这样真实冒烟可向已经生成 Q1/Q2 的旧分钟补发 Q0，而不伪造整批当前数据。

### Step 4: 更新迁移与冒烟文档

- `src/env/docker-compose.yml` 只更新注释：`08` 是已有 MySQL 手工迁移，`09` 是 TDengine 手工迁移，均不挂入 MySQL 初始化目录。
- `docs/MQTT-硬件数据对接说明.md` 先保留现有“无典型值时 MISSING_INPUT”验证，再增加：
  1. 能效管理员创建并提交 `PUMP1_Power` 的 9kW 典型值；
  2. 平台管理员批准；
  3. 缺点分钟冻结后查询 Q2、taskId、公式质量；
  4. 右端 Q0 冻结后查询旧分钟 Q1 和定向公式重算；
  5. 使用定点历史事件补发 10kW，查询 Q0、旧任务 replaced_count、指标 Q0；
  6. 检查 Redis/API/WebSocket 最新状态；
  7. 停用测试配置，避免持久卷污染后续无默认值验证。
- 文档明确持久 Docker 数据卷不会重跑 init SQL，旧环境必须先执行 `08` 和 `09`。

### Step 5: 运行全部自动化回归

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`，零失败、零错误、零跳过；交付时记录 Maven 实际 `Tests run` 总数。

### Step 6: 检查改动边界和注释

Run:

```powershell
git status --short
git diff --check
git diff --name-only origin/main...HEAD
```

Expected:

- 只包含本计划列出的 HVAC 数据质量、必要公式/TDengine联动、测试、脚本和文档；
- `outputs/` 仍为未跟踪且未暂存；
- 没有密钥、临时文件或前端代码；
- 新增类级注释、跨库补偿、质量优先级、配置开关和特殊缓存修正均有中文用途说明。

### Step 7: 可用时执行 Docker 真实冒烟

若本机 Docker Engine + Compose 可用，按文档执行：

```powershell
docker compose -f src/env/docker-compose.yml up -d
.\mvnw.cmd spring-boot:run
node .scripts/simulate-hvac-19-points.mjs
```

然后完成 Q2→Q1→Q0、四项指标、TDengine、MySQL 任务、Redis、API、WebSocket 检查。若 Docker 环境不可用，明确记录为“未执行”，不得写成通过；自动化测试通过不等于真实外部链路已确认。

### Step 8: 提交最终验收与文档

```powershell
git add -- src/test/java/com/platform/iot/dataquality/DataQualityPerformanceContractTest.java src/test/java/com/platform/DataQualityBackendAcceptanceTest.java .scripts/simulate-hvac-19-points.mjs docs/MQTT-硬件数据对接说明.md src/env/docker-compose.yml
git diff --cached --check
git commit -m "test(data-quality): verify quality upgrade workflow"
```

## Task 13: 分支级最终检查、推送和 PR 材料

### Step 1: 确认提交和工作区

Run:

```powershell
git status --short --branch
git log --oneline origin/main..HEAD
git diff --stat origin/main...HEAD
```

Expected: 任务提交边界清晰；除用户的 `outputs/` 外没有未提交文件。

### Step 2: 再次执行完整回归

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`；记录总测试数、失败数、错误数、跳过数。

### Step 3: 获取远程状态并检查冲突

Run:

```powershell
git fetch --prune origin
git merge-tree (git merge-base HEAD origin/main) HEAD origin/main
```

Expected: 不出现冲突标记。若 origin/main 前进，只报告并按 AGENTS.md 决定是否需要在任务分支合并最新 main；不得 reset 或强制变基。

### Step 4: 推送任务分支

Run:

```powershell
git push -u origin feature/hvac-data-quality-fill
```

Expected: 远程分支创建或更新成功。

### Step 5: 交付 PR 材料

Compare URL:

```text
https://github.com/edg127117/iot-platform-demo/compare/main...feature/hvac-data-quality-fill?expand=1
```

建议标题：

```text
feat(data-quality): add HVAC quality fill and correction workflow
```

PR 说明必须列出：

- Q1/Q2 生成、质量升级、来源追溯；
- 典型值审批和建筑权限；
- TDengine 增量列、MySQL 两张表和迁移步骤；
- 失败重试、小时收口、作废和人工重算；
- 公式旧成功失效删除及 Redis/WebSocket 修正；
- 不包含前端管理页面；
- 定向测试与完整回归的实际结果；
- Docker 真实冒烟是通过还是因环境不可用未执行；
- `outputs/` 未纳入提交；
- 当前与 `origin/main` 是否存在冲突。

交付后状态必须写为“等待用户创建并合并 PR”，不得自行创建或合并 PR。
