# HVAC Manual Data-Quality Recalculation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add auditable, asynchronous and resumable manual recalculation jobs that safely void bad fill tasks and rebuild historical minutes using Q0, Q1, Q2 or explicit missing results.

**Architecture:** MySQL stores one low-frequency recalculation job per administrator request and links generated Q1/Q2 fill tasks through `recalc_job_id`. A conditional scheduler claims one job chunk at a time, reads only a bounded 60-minute window plus interpolation context, writes TDengine through the existing quality-priority boundary, publishes authoritative `MANUAL_RECALCULATION` events, and advances a persistent cursor only after the entire chunk succeeds.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Spring Security, MyBatis-Plus 3.5.5, MySQL/H2, TDengine JDBC 3.2.7, JUnit 5, Mockito, MockMvc.

## Global Constraints

- Keep the V1 monolith; do not introduce a microservice or external job platform.
- MySQL stores job, configuration and audit state; TDengine stores raw events, formal minutes and formula results.
- Do not add a TDengine column or a MySQL per-minute recalculation table.
- Every time range uses `[fromInclusive,toExclusive)` and Unix milliseconds at API boundaries.
- Request point IDs are trimmed, de-duplicated, sorted and limited to 100.
- `toExclusive` cannot be later than the current completed minute.
- Process one target chunk of at most 60 minutes; each side may read at most `maxGapMinutes + 1` minutes of interpolation context.
- A chunk performs one batched raw-event query and one batched formal-minute query; do not add point-by-minute N+1 reads.
- Existing quality order remains `Q0 > Q1 > Q2`; manual execution cannot lower current quality.
- A Q1/Q2 fill task keeps one truthful source and evidence type; a mixed job is represented by multiple child tasks plus job counters.
- Fully identical requests reuse the same job. Different active jobs with overlapping building, point and time scopes return `409`.
- Both void-and-recalculate and range recalculation execute asynchronously.
- `outputs/` belongs to the user and must never be staged.
- Ordinary tests must not connect to real MySQL, TDengine, MQTT, Redis or third-party systems.
- Add direct Chinese comments for data-source selection, cross-database recovery, status transitions and unusual business rules.

---

### Task 1: Add the recalculation job schema, domain model and MySQL repository

**Files:**
- Create: `src/env/init/10-migrate-mysql-data-quality-recalculation.sql`
- Modify: `src/env/init/03-init-hvac-schema.sql`
- Modify: `src/test/resources/schema-test.sql`
- Modify: `src/main/java/com/platform/config/DataQualityProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Create: `src/main/java/com/platform/iot/dataquality/model/RecalculationJobType.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/RecalculationJobStatus.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/RecalculationJobPhase.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/RecalculationChunkStats.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/entity/BizDataQualityRecalcJob.java`
- Modify: `src/main/java/com/platform/iot/dataquality/model/entity/BizDataQualityFillTask.java`
- Create: `src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityRecalcJobMapper.java`
- Create: `src/main/java/com/platform/iot/dataquality/RecalculationJobRepository.java`
- Create: `src/main/java/com/platform/iot/dataquality/MySqlRecalculationJobRepository.java`
- Test: `src/test/java/com/platform/iot/dataquality/MySqlRecalculationJobRepositoryTest.java`
- Modify test: `src/test/java/com/platform/DatabaseInitializationTest.java`

**Interfaces:**
- Produces:

```java
public interface RecalculationJobRepository {
    BizDataQualityRecalcJob insert(BizDataQualityRecalcJob candidate);
    Optional<BizDataQualityRecalcJob> findById(String jobId);
    Optional<BizDataQualityRecalcJob> findByIdempotencyKey(String key);
    List<BizDataQualityRecalcJob> findOverlappingForUpdate(
            String buildingId, LocalDateTime from, LocalDateTime to);
    IPage<BizDataQualityRecalcJob> findPage(
            int pageNum, int pageSize, String buildingId,
            RecalculationJobType type, RecalculationJobStatus status,
            LocalDateTime from, LocalDateTime to);
    List<BizDataQualityRecalcJob> findClaimable(
            LocalDateTime staleBefore, int limit);
    boolean claim(String jobId, LocalDateTime staleBefore, LocalDateTime now);
    void resumeFailed(String jobId);
    void freezeVoidTargets(String jobId, String targetMinutesJson);
    void completeVoid(String jobId, int voidedCount, int replacedCount);
    void advanceChunk(
            String jobId, LocalDateTime expectedCursor,
            LocalDateTime nextCursor, RecalculationChunkStats stats,
            boolean finished, LocalDateTime at);
    void markFailed(
            String jobId, LocalDateTime expectedCursor, String error);
}
```

The mapper page contract is exact and stays on the MySQL side:

```java
IPage<BizDataQualityRecalcJob> selectPageFiltered(
        IPage<BizDataQualityRecalcJob> page,
        String buildingId,
        String jobType,
        String status,
        LocalDateTime fromInclusive,
        LocalDateTime toExclusive);
```

- `RecalculationChunkStats` is:

```java
public record RecalculationChunkStats(
        int q0Count,
        int q1Count,
        int q2Count,
        int missingCount) {
    public RecalculationChunkStats {
        if (q0Count < 0 || q1Count < 0
                || q2Count < 0 || missingCount < 0) {
            throw new IllegalArgumentException("重算计数不能为负数");
        }
    }
}
```

- `BizDataQualityRecalcJob` contains every column from the approved design,
  including `phase`, `cursorMinute`, and `voidTargetMinutesJson`.

- [ ] **Step 1: Write repository and schema contract tests**

Add tests that assert:

```java
assertThat(columns("BIZ_DATA_QUALITY_RECALC_JOB"))
        .contains("JOB_ID", "IDEMPOTENCY_KEY", "STATUS", "PHASE",
                "CURSOR_MINUTE", "VOID_TARGET_MINUTES_JSON",
                "Q0_COUNT", "Q1_COUNT", "Q2_COUNT", "MISSING_COUNT");
assertThat(columns("BIZ_DATA_QUALITY_FILL_TASK"))
        .contains("RECALC_JOB_ID");
```

Mock the mapper and verify `MySqlRecalculationJobRepository`:

```java
verify(mapper).claimAtomic(
        eq("JOB1"), eq(staleBefore), eq(now));
verify(mapper).advanceChunkAtomic(
        eq("JOB1"), eq(cursor), eq(nextCursor),
        eq(2), eq(3), eq(4), eq(1), eq(false), eq(now));
```

Also test:

- limit is `1..100`;
- error text is trimmed and capped at 1000 characters;
- only `FAILED` can be resumed;
- `advanceChunk` rejects a changed cursor;
- final advance writes `SUCCEEDED` and `finished_at`.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```powershell
.\mvnw.cmd '-Dtest=DatabaseInitializationTest,MySqlRecalculationJobRepositoryTest' test
```

Expected: compilation failure because the job entity, enums and repository do not exist.

- [ ] **Step 3: Add the MySQL and H2 schema**

Use the following table shape in the new migration and mirror it in the base
schema and H2 schema:

```sql
CREATE TABLE IF NOT EXISTS biz_data_quality_recalc_job (
    job_id VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    job_type VARCHAR(30) NOT NULL,
    building_id VARCHAR(32) NOT NULL,
    point_ids_json JSON NOT NULL,
    from_minute DATETIME(3) NOT NULL,
    to_minute DATETIME(3) NOT NULL,
    supersedes_task_id VARCHAR(32) DEFAULT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    phase VARCHAR(20) NOT NULL,
    cursor_minute DATETIME(3) NOT NULL,
    void_target_minutes_json JSON DEFAULT NULL,
    q0_count INT NOT NULL DEFAULT 0,
    q1_count INT NOT NULL DEFAULT 0,
    q2_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    voided_count INT NOT NULL DEFAULT 0,
    replaced_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) DEFAULT NULL,
    started_at DATETIME(3) DEFAULT NULL,
    finished_at DATETIME(3) DEFAULT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_id),
    UNIQUE KEY uk_recalc_idempotency (idempotency_key),
    KEY idx_recalc_status_cursor (status, update_time, job_id),
    KEY idx_recalc_building_range
        (building_id, status, from_minute, to_minute),
    KEY idx_recalc_supersedes (supersedes_task_id, create_time)
);

SET @ddl = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema=DATABASE()
          AND table_name='biz_data_quality_fill_task'
          AND column_name='recalc_job_id'),
    'SELECT 1',
    'ALTER TABLE `biz_data_quality_fill_task`
         ADD COLUMN `recalc_job_id` VARCHAR(32) DEFAULT NULL'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema=DATABASE()
          AND table_name='biz_data_quality_fill_task'
          AND index_name='idx_fill_recalc_job'),
    'SELECT 1',
    'ALTER TABLE `biz_data_quality_fill_task`
         ADD KEY `idx_fill_recalc_job` (`recalc_job_id`, `task_id`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

For H2, use `TEXT` for JSON columns and separate `CREATE INDEX` statements.
The fresh base schema directly declares the new column and index; only migration
`10` uses the `information_schema` guards above. Do not retroactively edit
migration `08`, because existing environments must receive this change in the
new ordered migration.

- [ ] **Step 4: Add domain types and repository implementation**

Use exact enum values:

```java
public enum RecalculationJobType {
    VOID_AND_RECALCULATE,
    RANGE_RECALCULATE
}

public enum RecalculationJobStatus {
    WAITING,
    RUNNING,
    SUCCEEDED,
    FAILED
}

public enum RecalculationJobPhase {
    VOIDING,
    RECALCULATING
}
```

Mapper status transitions must use conditional SQL. The critical claim and
advance predicates are:

```sql
UPDATE biz_data_quality_recalc_job
SET status='RUNNING',
    started_at=COALESCE(started_at, #{now}),
    last_error=NULL,
    update_time=#{now}
WHERE job_id=#{jobId}
  AND (
      status='WAITING'
      OR (status='RUNNING' AND update_time <= #{staleBefore})
  )
```

```sql
UPDATE biz_data_quality_recalc_job
SET cursor_minute=#{nextCursor},
    q0_count=q0_count+#{q0},
    q1_count=q1_count+#{q1},
    q2_count=q2_count+#{q2},
    missing_count=missing_count+#{missing},
    status=CASE WHEN #{finished}=TRUE THEN 'SUCCEEDED' ELSE 'WAITING' END,
    finished_at=CASE WHEN #{finished}=TRUE THEN #{at} ELSE NULL END,
    update_time=#{at}
WHERE job_id=#{jobId}
  AND status='RUNNING'
  AND phase='RECALCULATING'
  AND cursor_minute=#{expectedCursor}
```

Add to `DataQualityProperties`:

```java
/** 是否启用低频人工重算批次执行器；测试环境可关闭。 */
private boolean recalculationEnabled = true;

/** 人工重算执行器两轮扫描间隔，单位毫秒。 */
@Min(1)
private long recalculationScanDelayMs = 10_000L;

/** RUNNING 批次超过该时间未更新后允许其他实例重新领取。 */
@Min(1)
private long recalculationStaleMs = 120_000L;
```

Set `data-quality.recalculation-enabled: false` in `application-test.yml`.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=DatabaseInitializationTest,MySqlRecalculationJobRepositoryTest' test
```

Expected: `BUILD SUCCESS`, zero failures and zero skipped tests.

- [ ] **Step 6: Commit**

```powershell
git add -- src/env/init/10-migrate-mysql-data-quality-recalculation.sql src/env/init/03-init-hvac-schema.sql src/test/resources/schema-test.sql src/main/java/com/platform/config/DataQualityProperties.java src/main/resources/application.yml src/test/resources/application-test.yml src/main/java/com/platform/iot/dataquality/model/RecalculationJobType.java src/main/java/com/platform/iot/dataquality/model/RecalculationJobStatus.java src/main/java/com/platform/iot/dataquality/model/RecalculationJobPhase.java src/main/java/com/platform/iot/dataquality/model/RecalculationChunkStats.java src/main/java/com/platform/iot/dataquality/model/entity/BizDataQualityRecalcJob.java src/main/java/com/platform/iot/dataquality/model/entity/BizDataQualityFillTask.java src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityRecalcJobMapper.java src/main/java/com/platform/iot/dataquality/RecalculationJobRepository.java src/main/java/com/platform/iot/dataquality/MySqlRecalculationJobRepository.java src/test/java/com/platform/iot/dataquality/MySqlRecalculationJobRepositoryTest.java src/test/java/com/platform/DatabaseInitializationTest.java
git diff --cached --check
git commit -m "feat(data-quality): add recalculation job storage"
```

---

### Task 2: Complete fill-task query, detail and FAILED retry APIs

**Files:**
- Modify: `src/main/java/com/platform/iot/dataquality/FillTaskRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/dto/DataQualityFillDtos.java`
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityFillTaskService.java`
- Create: `src/main/java/com/platform/iot/dataquality/controller/DataQualityFillController.java`
- Create test: `src/test/java/com/platform/iot/dataquality/DataQualityFillTaskServiceTest.java`
- Create test: `src/test/java/com/platform/DataQualityFillControllerFlowTest.java`

**Interfaces:**
- Consumes: `DataQualityRecoveryService.recoverTask(String taskId, long now)`.
- Produces:

```java
IPage<DataQualityFillDtos.Response> page(
        Long userId, Collection<String> roles,
        int pageNum, int pageSize,
        String buildingId, String pointId,
        FillSourceType sourceType, Integer dataQuality,
        FillApplyStatus applyStatus,
        Long fromInclusive, Long toExclusive);
DataQualityFillDtos.Response detail(
        Long userId, Collection<String> roles, String taskId);
DataQualityFillDtos.Response retry(
        Collection<String> roles, String taskId, long now);
```

- [ ] **Step 1: Preserve and review the existing uncommitted foundation**

The worktree already contains the intended DTO, service and MySQL page query.
Do not discard it. Verify the SQL retains:

```sql
AND end_minute > #{fromInclusive}
AND start_minute < #{toExclusive}
ORDER BY generated_at DESC, task_id DESC
```

and that an ordinary user with an empty building scope produces `AND 1=0`.

- [ ] **Step 2: Write service and four-role controller tests**

Service tests must assert:

```java
assertThatThrownBy(() -> service.retry(
        List.of("PLATFORM_ADMIN"), "APPLIED_TASK", now))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(409);

verify(recoveryService).recoverTask("FAILED_TASK", now);
verify(repository, never()).getOrCreate(any());
```

Flow tests must cover:

- building owner and energy manager read only their authorized building;
- platform administrator reads all and retries FAILED;
- third party receives `403`;
- unauthorized `buildingId` and detail task receive `403`;
- page filters include point, source, quality, status and overlap range;
- broken evidence returns `409` without exposing JSON/JDBC details;
- task not found returns `404`;
- invalid page and period return `400`.

- [ ] **Step 3: Run the focused tests and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=DataQualityFillTaskServiceTest,DataQualityFillControllerFlowTest' test
```

Expected: compilation failure or `404` because the controller and tests are not complete.

- [ ] **Step 4: Implement the controller and finish validation**

Controller mappings:

```java
@RestController
@RequestMapping("/iot/data-quality/fill-tasks")
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "true")
public class DataQualityFillController {

    @GetMapping
    @PreAuthorize(
        "hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    Result<IPage<DataQualityFillDtos.Response>> page(
            Authentication authentication,
            int pageNum, int pageSize,
            String buildingId, String pointId,
            FillSourceType sourceType, Integer dataQuality,
            FillApplyStatus applyStatus,
            Long fromInclusive, Long toExclusive);

    @GetMapping("/{taskId}")
    @PreAuthorize(
        "hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    Result<DataQualityFillDtos.Response> detail(
            Authentication authentication, String taskId);

    @PostMapping("/{taskId}/retry")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    Result<DataQualityFillDtos.Response> retry(
            Authentication authentication, String taskId);
}
```

Keep `lastError` as a sanitized business summary. If stored evidence cannot be
decoded, return `409` with `"补全任务证据已损坏，需人工修复"` and never include the
raw JSON or stack trace.

- [ ] **Step 5: Run focused and existing role tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=DataQualityFillTaskServiceTest,DataQualityFillControllerFlowTest,DataQualityTypicalValueControllerFlowTest,FourRoleBackendFlowTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add -- src/main/java/com/platform/iot/dataquality/FillTaskRepository.java src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java src/main/java/com/platform/iot/dataquality/model/dto/DataQualityFillDtos.java src/main/java/com/platform/iot/dataquality/DataQualityFillTaskService.java src/main/java/com/platform/iot/dataquality/controller/DataQualityFillController.java src/test/java/com/platform/iot/dataquality/DataQualityFillTaskServiceTest.java src/test/java/com/platform/DataQualityFillControllerFlowTest.java
git diff --cached --check
git commit -m "feat(data-quality): expose fill task management API"
```

---

### Task 3: Implement idempotent job submission, overlap locking and job query APIs

**Files:**
- Modify: `src/main/java/com/platform/hvac/mapper/BuildingMapper.java`
- Modify: `src/main/java/com/platform/hvac/service/BuildingService.java`
- Modify: `src/main/java/com/platform/hvac/service/impl/BuildingServiceImpl.java`
- Create: `src/main/java/com/platform/iot/dataquality/RecalculationJobIdempotency.java`
- Create: `src/main/java/com/platform/iot/dataquality/model/dto/DataQualityRecalculationDtos.java`
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityRecalculationJobService.java`
- Create: `src/main/java/com/platform/iot/dataquality/controller/DataQualityRecalculationController.java`
- Test: `src/test/java/com/platform/iot/dataquality/DataQualityRecalculationJobServiceTest.java`
- Test: `src/test/java/com/platform/DataQualityRecalculationControllerFlowTest.java`

**Interfaces:**
- Produces:

```java
public final class RecalculationJobIdempotency {
    public static String voidJob(String oldTaskId);
    public static String rangeJob(
            long operatorId, String buildingId, List<String> pointIds,
            long fromInclusive, long toExclusive, String reason);
}
```

```java
public interface BuildingService {
    void lockExistingForUpdate(String buildingId);
}
```

```java
public class DataQualityRecalculationJobService {
    DataQualityRecalculationDtos.Response submitVoid(
            long operatorId, Collection<String> roles,
            String taskId, String reason, long now);
    DataQualityRecalculationDtos.Response submitRange(
            long operatorId, Collection<String> roles,
            DataQualityRecalculationDtos.RecalculateRequest request, long now);
    IPage<DataQualityRecalculationDtos.Response> page(
            Collection<String> roles,
            int pageNum, int pageSize,
            String buildingId,
            RecalculationJobType jobType,
            RecalculationJobStatus status,
            Long fromInclusive, Long toExclusive);
    DataQualityRecalculationDtos.Detail detail(
            Collection<String> roles, String jobId);
}
```

`DataQualityRecalculationDtos` defines the asynchronous request and response:

```java
public record RecalculateRequest(
        @NotBlank String buildingId,
        @NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
        @NotNull Long fromInclusive,
        @NotNull Long toExclusive,
        @NotBlank @Size(max = 500) String reason) {}

public record Response(
        String jobId,
        RecalculationJobType jobType,
        String buildingId,
        List<String> pointIds,
        long fromInclusive,
        long toExclusive,
        String supersedesTaskId,
        String reason,
        long operatorId,
        RecalculationJobStatus status,
        RecalculationJobPhase phase,
        long cursorMinute,
        int q0Count,
        int q1Count,
        int q2Count,
        int missingCount,
        int voidedCount,
        int replacedCount,
        String lastError,
        Long startedAt,
        Long finishedAt,
        long createTime,
        long updateTime) {}

public record Detail(
        Response job,
        List<DataQualityFillDtos.Response> childTasks) {}
```

The void request remains `DataQualityFillDtos.VoidAndRecalculateRequest`.
Remove its old synchronous `DataQualityFillDtos.RecalculationResult`, because
both POST routes now return `DataQualityRecalculationDtos.Response`.

- [ ] **Step 1: Write failing submission and query tests**

Cover:

```java
assertThat(service.submitRange(
        7L, ADMIN, request, now).jobId()).isEqualTo("JOB1");
verify(buildingService).lockExistingForUpdate("BLD001");
verify(repository).findOverlappingForUpdate(
        eq("BLD001"), eq(from), eq(to));
```

and:

- point IDs are trimmed, de-duplicated, sorted and capped at 100;
- every point exists and belongs to the request building;
- `from/to` are minute-aligned, `from<to`, and `to` is completed;
- blank or over-500-character reason is rejected;
- identical WAITING/RUNNING/SUCCEEDED returns the same job;
- identical FAILED invokes `resumeFailed` and returns the same job;
- different active job with intersecting point set and overlapping time returns `409`;
- same building with disjoint points or time is allowed;
- only platform admin can submit or query jobs.

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=DataQualityRecalculationJobServiceTest,DataQualityRecalculationControllerFlowTest' test
```

Expected: compilation failure because the submission service and controllers do not exist.

- [ ] **Step 3: Add the building row lock through the HVAC service boundary**

Add mapper SQL:

```java
@Select("""
        SELECT *
        FROM building
        WHERE building_id=#{buildingId}
          AND del_flag=0
        FOR UPDATE
        """)
Building selectExistingForUpdate(@Param("buildingId") String buildingId);
```

`BuildingServiceImpl.lockExistingForUpdate` throws `BusinessException(404,
"建筑不存在")` when the row is absent. The data-quality module calls the HVAC
service, never the HVAC mapper directly.

- [ ] **Step 4: Implement deterministic keys and transactional submission**

Canonical range JSON contains:

```json
{
  "operatorId": 7,
  "buildingId": "BLD001",
  "pointIds": ["POINT001", "POINT002"],
  "fromInclusive": 1800000000000,
  "toExclusive": 1800003600000,
  "reason": "修正测点绑定"
}
```

Hash UTF-8 bytes with SHA-256 and return `RANGE_RECALC:{lowerHex}`.

Submission order inside one MySQL transaction:

```text
validate role and request
→ return exact idempotent job when present
→ lock building row
→ query overlapping active jobs FOR UPDATE
→ compare point-set intersection in Java
→ insert WAITING job with cursor=from and phase
```

Void jobs start in `VOIDING`; range jobs start in `RECALCULATING`.

- [ ] **Step 5: Implement asynchronous POST and two job GET APIs**

Routes:

```text
POST /iot/data-quality/fill-tasks/{taskId}/void-and-recalculate
POST /iot/data-quality/recalculate
GET  /iot/data-quality/recalculation-jobs
GET  /iot/data-quality/recalculation-jobs/{jobId}
```

Both POST methods return the accepted job immediately. V1 job GET endpoints
use `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`.

Apply both `data-quality.enabled=true` and
`data-quality.recalculation-enabled=true` to the recalculation controller,
submission service, void service, chunk executor and scheduler. When either
switch is off, none of the recalculation-specific endpoints or executors are
registered; ordinary tests therefore cannot enqueue jobs that will never run.

`Detail` includes child fill tasks from:

```java
List<BizDataQualityFillTask> findByRecalculationJobId(String jobId);
```

Add that method to `FillTaskRepository` and MySQL mapper using
`ORDER BY generated_at, task_id`.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=DataQualityRecalculationJobServiceTest,DataQualityRecalculationControllerFlowTest,DataQualityFillControllerFlowTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```powershell
git add -- src/main/java/com/platform/hvac/mapper/BuildingMapper.java src/main/java/com/platform/hvac/service/BuildingService.java src/main/java/com/platform/hvac/service/impl/BuildingServiceImpl.java src/main/java/com/platform/iot/dataquality/RecalculationJobIdempotency.java src/main/java/com/platform/iot/dataquality/model/dto/DataQualityRecalculationDtos.java src/main/java/com/platform/iot/dataquality/DataQualityRecalculationJobService.java src/main/java/com/platform/iot/dataquality/controller/DataQualityRecalculationController.java src/main/java/com/platform/iot/dataquality/FillTaskRepository.java src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java src/test/java/com/platform/iot/dataquality/DataQualityRecalculationJobServiceTest.java src/test/java/com/platform/DataQualityRecalculationControllerFlowTest.java
git diff --cached --check
git commit -m "feat(data-quality): accept recalculation jobs"
```

---

### Task 4: Make old-task voiding ownership-safe and restart-safe

**Files:**
- Modify: `src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/FillTaskRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java`
- Modify: `src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java`
- Create: `src/main/java/com/platform/iot/dataquality/RecalculationVoidService.java`
- Test: `src/test/java/com/platform/iot/dataquality/RecalculationVoidServiceTest.java`
- Modify test: `src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java`
- Modify test: `src/test/java/com/platform/iot/dataquality/MySqlFillTaskRepositoryTest.java`

**Interfaces:**
- Changes:

```java
boolean HvacMinuteRepository.deleteIfOwnedByTask(
        String pointId, long minuteStart, String taskId);
```

- Adds:

```java
void FillTaskRepository.markVoidedExact(
        String taskId,
        long operatorId,
        String reason,
        LocalDateTime at,
        int minuteCount,
        int failedCount,
        int replacedCount,
        int voidedCount);
```

```java
public record VoidResult(int voidedCount, int replacedCount) {}

public class RecalculationVoidService {
    VoidResult voidOldTask(
            BizDataQualityRecalcJob job,
            BizDataQualityFillTask oldTask,
            long now);
}
```

- [ ] **Step 1: Write failure-first void tests**

Cover:

- only current rows with `qualityTaskId == oldTaskId` become frozen targets;
- Q0, Q1 and another task are preserved and counted as replaced;
- `deleteIfOwnedByTask=false` after a race counts the minute as replaced;
- a target absent on restart is counted as already voided;
- a target still owned on restart is retried;
- frozen target JSON is written before the first TD delete;
- old FAILED minutes remain `failed_count`;
- final old task counts satisfy:

```text
applied=0
minuteCount=voided+replaced+failed
status=VOIDED
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=RecalculationVoidServiceTest,TdengineHvacMinuteRepositoryTest,MySqlFillTaskRepositoryTest' test
```

Expected: compilation failure because delete does not return a result and exact void APIs do not exist.

- [ ] **Step 3: Return the actual conditional-delete result**

Keep the existing point-minute lock and ownership reread:

```java
return lockRegistry.withLocks(List.of(key), () -> {
    Optional<RawMinuteAggregate> current =
            findPointMinute(pointId, minuteStart);
    if (current.map(RawMinuteAggregate::qualityTaskId)
            .filter(taskId::equals).isEmpty()) {
        return false;
    }
    template.execute("DELETE FROM " + qualifiedChild(pointId)
            + " WHERE ts=" + quote(timestamp(minuteStart)));
    return true;
});
```

The returned `true` means this invocation passed the ownership check and issued
the delete. Existing Q0/Q1/Q2 quality-priority locking remains the concurrency
boundary.

- [ ] **Step 4: Implement frozen-target recovery**

First execution:

```text
read old task range
→ collect current old-owned minute starts
→ encode sorted list to void_target_minutes_json
→ persist it
→ delete each target conditionally
→ reread target range
→ classify absent=voided, other owner/higher quality=replaced
→ mark old task VOIDED exactly
→ complete job VOIDING phase
```

Restart execution decodes the frozen list instead of deriving a new one.
Sanitize TD failures to `"TDengine作废操作失败"` in the job error; log the stack
server-side.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=RecalculationVoidServiceTest,TdengineHvacMinuteRepositoryTest,MySqlFillTaskRepositoryTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add -- src/main/java/com/platform/iot/temporal/HvacMinuteRepository.java src/main/java/com/platform/iot/temporal/impl/TdengineHvacMinuteRepository.java src/main/java/com/platform/iot/dataquality/FillTaskRepository.java src/main/java/com/platform/iot/dataquality/MySqlFillTaskRepository.java src/main/java/com/platform/iot/dataquality/mapper/BizDataQualityFillTaskMapper.java src/main/java/com/platform/iot/dataquality/RecalculationVoidService.java src/test/java/com/platform/iot/dataquality/RecalculationVoidServiceTest.java src/test/java/com/platform/iot/temporal/TdengineHvacMinuteRepositoryTest.java src/test/java/com/platform/iot/dataquality/MySqlFillTaskRepositoryTest.java
git diff --cached --check
git commit -m "feat(data-quality): void owned fill minutes safely"
```

---

### Task 5: Add bounded manual Q0 re-aggregation

**Files:**
- Create: `src/main/java/com/platform/iot/aggregation/ManualRealMinuteAggregationService.java`
- Test: `src/test/java/com/platform/iot/aggregation/ManualRealMinuteAggregationServiceTest.java`

**Interfaces:**
- Consumes:
  - `HvacRawEventRepository.findWindow(long from, long to, boolean lateOnly)`;
  - `DataPointConfigProvider.findAll()`;
  - `HvacPointMinuteAggregator.aggregate(
    PointRuntimeConfig point, long minuteStart,
    List<RawTelemetryEvent> events, long finalizedAt)`.
- Produces:

```java
public class ManualRealMinuteAggregationService {
    List<RawMinuteAggregate> aggregate(
            Set<String> pointIds,
            long contextFromInclusive,
            long contextToExclusive,
            long finalizedAt);
}
```

- [ ] **Step 1: Write bounded aggregation tests**

Assert:

```java
verify(rawRepository).findWindow(contextFrom, contextTo, false);
verify(rawRepository, times(1))
        .findWindow(anyLong(), anyLong(), eq(false));
```

Cover:

- one raw query for the exact bounded context passed by the chunk orchestrator;
- events are grouped by point and natural minute;
- only requested, ONLINE, calculation, ANALOG points are aggregated;
- events outside the requested point set are ignored;
- empty evidence produces no Q0;
- old history is accepted without checking `late-real-correction-hours`;
- invalid range and non-minute alignment are rejected.

The aggregation service must not hard-code a 72-minute cap because
`maxGapMinutes` is configurable. The chunk orchestrator alone computes the
bounded context as `60 + 2 * (maxGapMinutes + 1)` minutes; with the default
five-minute gap, the performance contract expects at most 72 minutes.

- [ ] **Step 2: Run the test and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=ManualRealMinuteAggregationServiceTest' test
```

Expected: compilation failure because the service does not exist.

- [ ] **Step 3: Implement one-query grouping**

Use a key:

```java
private record PointMinute(String pointId, long minuteStart) {}
```

Calculate minute start with:

```java
long minuteStart = event.eventTime()
        - Math.floorMod(event.eventTime(), 60_000L);
```

Sort output by `minuteStart` then `pointId`. Do not publish events or write
TDengine from this component; it is a pure manual aggregation boundary.

- [ ] **Step 4: Run the focused test**

Run:

```powershell
.\mvnw.cmd '-Dtest=ManualRealMinuteAggregationServiceTest,HvacMinuteAggregationServiceTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/platform/iot/aggregation/ManualRealMinuteAggregationService.java src/test/java/com/platform/iot/aggregation/ManualRealMinuteAggregationServiceTest.java
git diff --cached --check
git commit -m "feat(data-quality): aggregate manual historical Q0"
```

---

### Task 6: Implement manual Q1/Q2 selection and one-chunk execution

**Files:**
- Modify: `src/main/java/com/platform/iot/dataquality/FillTaskIdempotency.java`
- Create: `src/main/java/com/platform/iot/dataquality/ManualQualitySelectionService.java`
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityRecalculationService.java`
- Test: `src/test/java/com/platform/iot/dataquality/ManualQualitySelectionServiceTest.java`
- Test: `src/test/java/com/platform/iot/dataquality/DataQualityRecalculationServiceTest.java`

**Interfaces:**
- Produces deterministic child keys:

```java
String FillTaskIdempotency.recalculationQ1(
        String jobId, String pointId,
        long leftMinute, long rightMinute,
        String algorithmVersion);

String FillTaskIdempotency.recalculationQ2(
        String jobId, String pointId,
        String configId, int configVersion,
        long hourStart);
```

Keep the existing `regeneration(...)` key method and its tests for legacy
single-task recovery compatibility. The two new job-scoped methods are
additional namespaces and must not change existing key output.

- Produces:

```java
public record ChunkSelection(
        List<RawMinuteAggregate> q1Rows,
        List<RawMinuteAggregate> q2Rows,
        List<BizDataQualityFillTask> childTasks) {}

public class ManualQualitySelectionService {
    ChunkSelection selectAndPersist(
            BizDataQualityRecalcJob job,
            Map<String, PointRuntimeConfig> points,
            long targetFrom,
            long targetTo,
            long contextFrom,
            long contextTo,
            long finalizedAt);
}
```

```java
public class DataQualityRecalculationService {
    void processClaimedJob(String jobId, long now);
}
```

- [ ] **Step 1: Write selection tests before implementation**

Create a two-point, six-minute fixture containing:

```text
P1 minute 0: raw Q0
P1 minute 1: missing → Q1
P1 minute 2: raw Q0
P1 minute 3: missing → approved Q2
P1 minute 4: missing → no config → missing
P2 minute 0: existing higher-quality Q0 → preserved
```

Assert:

- Q0 is written before Q1/Q2 selection;
- Q1 uses only current Q0 endpoints and `LinearMinuteInterpolator`;
- Q1 writes only target minutes even when endpoints are in context;
- Q2 uses the approved config valid at each minute;
- Q2 groups same point/config/hour under one child task;
- child tasks contain `recalcJobId=jobId`;
- void jobs also contain `supersedesTaskId=oldTaskId`;
- manual keys do not equal standard Q1/Q2 keys and cannot resurrect VOIDED tasks;
- existing Q0/Q1 is never downgraded;
- write results count and point-minute order are validated.

- [ ] **Step 2: Write one-chunk orchestration tests**

Mock one target chunk and assert:

```java
verify(manualAggregator).aggregate(
        pointIds, contextFrom, contextTo, now);
verify(minuteRepository).saveAllWithQualityPriority(q0Rows, null);
verify(minuteRepository, times(1)).findRange(
        pointIds, contextFrom, contextTo);
verify(jobRepository).advanceChunk(
        jobId, cursor, nextCursor, expectedStats, finished, at);
```

For each target minute verify exactly one event:

```java
assertThat(event.source())
        .isEqualTo(QualityEventSource.MANUAL_RECALCULATION);
assertThat(event.affectedPointIds()).containsExactlyInAnyOrderElementsOf(pointIds);
```

Cover a completely missing minute and verify the event still publishes.
Make the publisher throw on minute 30 and verify:

- `markFailed` is called with the unchanged cursor;
- later minutes and later chunks are not processed;
- `advanceChunk` is never called.

- [ ] **Step 3: Run tests and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=ManualQualitySelectionServiceTest,DataQualityRecalculationServiceTest' test
```

Expected: compilation failure because manual selection and orchestration do not exist.

- [ ] **Step 4: Implement manual child task creation**

Q1 candidate fields:

```java
task.setIdempotencyKey(FillTaskIdempotency.recalculationQ1(
        jobId, pointId, leftMinute, rightMinute,
        InterpolationFillService.ALGORITHM_VERSION));
task.setDataQuality(1);
task.setSourceType(FillSourceType.INTERPOLATION);
task.setRecalcJobId(jobId);
task.setSupersedesTaskId(job.getSupersedesTaskId());
```

Q2 candidate uses a truthful `FillTaskEvidence.Typical` snapshot and:

```java
task.setIdempotencyKey(FillTaskIdempotency.recalculationQ2(
        jobId, pointId, configId, version, hourStart));
task.setDataQuality(2);
task.setSourceType(FillSourceType.TYPICAL_VALUE);
task.setRecalcJobId(jobId);
```

Use `FillTaskRepository.getOrCreate` so a repeated chunk reuses child task IDs.
Batch write rows per child task with the job's optional old task ID as
`supersedesTaskId`. Record actual old-task replacements before advancing the
job. On write failure, call `recordFailure` for affected child minutes and let
the job fail without advancing.

Q1 task bounds are the full missing gap intersected with the job range, not
just the current chunk. Q2 task bounds are the approved config/hour segment
intersected with the job range. Therefore, a gap or hour split by a chunk
boundary reuses one truthful child task and later chunks extend its applied
evidence instead of colliding with a narrower task carrying the same key.

- [ ] **Step 5: Implement the bounded chunk**

Calculate:

```java
long chunkEnd = Math.min(cursor + 3_600_000L, jobTo);
long contextPadding =
        (properties.getInterpolation().getMaxGapMinutes() + 1L) * 60_000L;
long contextFrom = Math.max(jobFrom, cursor - contextPadding);
long contextTo = Math.min(jobTo, chunkEnd + contextPadding);
```

Execution order:

```text
manual Q0 aggregate for context
→ quality-priority Q0 batch write
→ one formal context read
→ Q1 plan/write for target
→ Q2 plan/write for remaining target
→ one target classification
→ one MANUAL_RECALCULATION event per target minute
→ atomic cursor/count advance
```

Count final current target rows, not write attempts:

- `dataQuality=0` → Q0;
- `dataQuality=1` → Q1;
- `dataQuality=2` → Q2;
- no row for requested point-minute → missing.

- [ ] **Step 6: Run focused regression**

Run:

```powershell
.\mvnw.cmd '-Dtest=ManualQualitySelectionServiceTest,DataQualityRecalculationServiceTest,HvacFormulaEngineTest,InterpolationFillServiceTest,TypicalValueFillServiceTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```powershell
git add -- src/main/java/com/platform/iot/dataquality/FillTaskIdempotency.java src/main/java/com/platform/iot/dataquality/ManualQualitySelectionService.java src/main/java/com/platform/iot/dataquality/DataQualityRecalculationService.java src/test/java/com/platform/iot/dataquality/ManualQualitySelectionServiceTest.java src/test/java/com/platform/iot/dataquality/DataQualityRecalculationServiceTest.java
git diff --cached --check
git commit -m "feat(data-quality): execute recalculation chunks"
```

---

### Task 7: Add the asynchronous claimant, stale recovery and conditional wiring

**Files:**
- Create: `src/main/java/com/platform/iot/dataquality/DataQualityRecalculationScheduler.java`
- Modify: `src/test/java/com/platform/iot/dataquality/DataQualityConditionalConfigurationTest.java`
- Create test: `src/test/java/com/platform/iot/dataquality/DataQualityRecalculationSchedulerTest.java`

**Interfaces:**
- Consumes:
  - `RecalculationJobRepository.findClaimable(...)`;
  - `RecalculationJobRepository.claim(...)`;
  - `RecalculationVoidService.voidOldTask(...)`;
  - `DataQualityRecalculationService.processClaimedJob(...)`.

- [ ] **Step 1: Write scheduler and condition tests**

Cover:

- both `data-quality.enabled=true` and
  `data-quality.recalculation-enabled=true` are required;
- disabled test context contains no scheduler, executor or job service;
- each scan handles at most ten jobs;
- only a successfully claimed job executes;
- VOIDING runs before recalculation;
- one job failure does not stop the next job;
- stale RUNNING is claimable, fresh RUNNING is not;
- a successful non-final chunk returns to WAITING and is processed in a later scan.

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=DataQualityRecalculationSchedulerTest,DataQualityConditionalConfigurationTest' test
```

Expected: compilation failure because the scheduler does not exist.

- [ ] **Step 3: Implement conditional scheduling**

Use:

```java
@Component
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "recalculation-enabled"},
        havingValue = "true")
public class DataQualityRecalculationScheduler {

    @Scheduled(
        fixedDelayString =
            "${data-quality.recalculation-scan-delay-ms:10000}",
        initialDelayString =
            "${data-quality.recalculation-scan-delay-ms:10000}")
    public void run();
}
```

Use one fixed `now` and `staleBefore` per scan. Catch per-job exceptions, call
`markFailed` with a sanitized business message, and continue. Never log the
reason, point JSON or raw evidence at INFO level.

- [ ] **Step 4: Run focused tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=DataQualityRecalculationSchedulerTest,DataQualityConditionalConfigurationTest,DataQualityRecalculationServiceTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/platform/iot/dataquality/DataQualityRecalculationScheduler.java src/test/java/com/platform/iot/dataquality/DataQualityConditionalConfigurationTest.java src/test/java/com/platform/iot/dataquality/DataQualityRecalculationSchedulerTest.java
git diff --cached --check
git commit -m "feat(data-quality): schedule resumable recalculation jobs"
```

---

### Task 8: Add cross-module acceptance, performance contracts and operator documentation

**Files:**
- Create test: `src/test/java/com/platform/DataQualityRecalculationAcceptanceTest.java`
- Create test: `src/test/java/com/platform/iot/dataquality/DataQualityRecalculationPerformanceContractTest.java`
- Modify: `.scripts/simulate-hvac-19-points.mjs`
- Modify: `docs/MQTT-硬件数据对接说明.md`
- Modify: `src/env/docker-compose.yml`

**Interfaces:**
- Verifies all APIs and services produced by Tasks 1-7.

- [ ] **Step 1: Write H2 + Fake TDengine acceptance tests**

Cover the complete asynchronous sequence:

```text
submit void job
→ receive WAITING jobId immediately
→ scheduler claims VOIDING
→ old owned minute deleted, upgraded minute preserved
→ phase becomes RECALCULATING
→ mixed Q0/Q1/Q2/missing chunk
→ MANUAL READY invalidates missing formula success
→ cursor reaches end
→ job becomes SUCCEEDED
→ child Q1/Q2 tasks are queryable from job detail
```

Add 61-minute and 120-minute fixtures and assert exactly two target chunks,
with no duplicated target boundary minute.

- [ ] **Step 2: Add performance contracts**

Mockito verification must prove:

```java
verify(rawRepository, times(chunkCount))
        .findWindow(anyLong(), anyLong(), eq(false));
verify(minuteRepository, times(chunkCount))
        .findRange(eq(pointIds), anyLong(), anyLong());
verify(recalcJobMapper, never())
        .selectPageFiltered(any(), any(), any(), any(), any(), any());
```

The last assertion belongs to an ordinary HVAC history/formula query test and
proves normal reads do not access the new job mapper.

- [ ] **Step 3: Run acceptance and performance tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=DataQualityRecalculationAcceptanceTest,DataQualityRecalculationPerformanceContractTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Extend the simulator and documentation**

Keep the existing optional historical event variables:

```text
HVAC_ONLY_POINT
HVAC_EVENT_TIME_MS
HVAC_VALUE_OVERRIDE
```

Document:

- migration `10` is a manual MySQL migration for persistent environments;
- two POST APIs return a job rather than waiting for recalculation;
- how to poll job detail until `SUCCEEDED` or `FAILED`;
- how FAILED resumes when the identical POST is repeated;
- Q0/Q1/Q2/missing and formula invalidation checks;
- Docker volume init scripts do not rerun for an existing volume.

Only update the compose comments; do not mount migrations `08`, `09` or `10`
into a MySQL initialization directory.

- [ ] **Step 5: Run the entire Maven regression**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`; record actual total tests, failures, errors and skipped tests.

- [ ] **Step 6: Check comments and change boundaries**

Run:

```powershell
git diff --check
git status --short
git diff --name-only origin/main...HEAD
```

Expected:

- `outputs/` remains untracked and unstaged;
- no frontend files, credentials or generated artifacts are included;
- new classes and cross-database rules have direct Chinese purpose comments;
- ordinary HVAC query and MQTT paths do not depend on the new job repository.

- [ ] **Step 7: Commit**

```powershell
git add -- src/test/java/com/platform/DataQualityRecalculationAcceptanceTest.java src/test/java/com/platform/iot/dataquality/DataQualityRecalculationPerformanceContractTest.java .scripts/simulate-hvac-19-points.mjs docs/MQTT-硬件数据对接说明.md src/env/docker-compose.yml
git diff --cached --check
git commit -m "test(data-quality): verify manual recalculation workflow"
```

---

### Task 9: Final branch verification, push and PR handoff

**Files:**
- No source changes expected.

- [ ] **Step 1: Inspect commits and workspace**

Run:

```powershell
git status --short --branch
git log --oneline origin/main..HEAD
git diff --stat origin/main...HEAD
```

Expected: only `outputs/` is untracked; every implementation stage has a focused commit.

- [ ] **Step 2: Run the final full regression**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`; report exact test totals and any skipped tests.

- [ ] **Step 3: Check remote movement and conflicts**

Run:

```powershell
git fetch --prune origin
$base = git merge-base HEAD origin/main
git merge-tree $base HEAD origin/main
```

Expected: no conflict markers. If `origin/main` moved, follow `AGENTS.md`;
never reset, force-push or overwrite local work.

- [ ] **Step 4: Run Docker smoke only when Docker is available**

Check:

```powershell
docker version
docker compose version
```

When available, follow the updated MQTT document and verify:

```text
MQTT → raw events → manual job → Q0/Q1/Q2/missing
→ TDengine → formula invalidation/recalculation
→ MySQL job/fill audit → Redis/API/WebSocket
```

When unavailable, report “not executed”; automated tests are not a substitute
for real TDengine/MySQL/MQTT/Redis behavior.

- [ ] **Step 5: Push the task branch**

Run:

```powershell
git push -u origin feature/hvac-data-quality-fill
```

Expected: remote branch updated without force.

- [ ] **Step 6: Deliver PR materials**

Provide:

- compare URL:
  `https://github.com/edg127117/iot-platform-demo/compare/main...feature/hvac-data-quality-fill?expand=1`;
- base `main`, compare `feature/hvac-data-quality-fill`;
- suggested title and complete PR body;
- exact test commands and totals;
- Docker smoke status;
- migration order `08` MySQL, `09` TDengine, `10` MySQL;
- conflict and unrelated-file checks;
- explicit statement that `outputs/` is not included.

Current state after handoff: **waiting for the user to create and merge the PR**.
