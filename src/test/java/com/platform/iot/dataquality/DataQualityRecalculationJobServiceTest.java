package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BuildingService;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.dto.DataQualityFillDtos;
import com.platform.iot.dataquality.model.dto.DataQualityRecalculationDtos;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataQualityRecalculationJobServiceTest {

    private static final long TO = 1_800_003_600_000L;
    private static final long FROM = TO - 3_600_000L;
    private static final List<String> ADMIN = List.of("PLATFORM_ADMIN");

    private RecalculationJobRepository repository;
    private FillTaskRepository fillTaskRepository;
    private BuildingService buildingService;
    private BizDataPointService pointService;
    private DataQualityFillTaskService fillTaskService;
    private DataQualityRecalculationJobService service;

    @BeforeEach
    void setUp() {
        repository = mock(RecalculationJobRepository.class);
        fillTaskRepository = mock(FillTaskRepository.class);
        buildingService = mock(BuildingService.class);
        pointService = mock(BizDataPointService.class);
        fillTaskService = mock(DataQualityFillTaskService.class);
        service = new DataQualityRecalculationJobService(
                repository, fillTaskRepository, buildingService, pointService,
                fillTaskService, new ObjectMapper());
    }

    @Test
    void submitsCanonicalRangeUnderBuildingLockAndReturnsFreshJob() {
        when(pointService.listByIds(List.of("POINT001", "POINT002")))
                .thenReturn(List.of(
                        point("POINT001", "BLD001"),
                        point("POINT002", "BLD001")));
        when(repository.findOverlappingForUpdate(
                "BLD001", local(FROM), local(TO))).thenReturn(List.of());
        when(repository.insert(any())).thenAnswer(invocation -> {
            BizDataQualityRecalcJob job = invocation.getArgument(0);
            job.setJobId("JOB1");
            job.setQ0Count(0);
            job.setQ1Count(0);
            job.setQ2Count(0);
            job.setMissingCount(0);
            job.setVoidedCount(0);
            job.setReplacedCount(0);
            return job;
        });

        var response = service.submitRange(
                7L, ADMIN,
                new DataQualityRecalculationDtos.RecalculateRequest(
                        " BLD001 ", List.of("POINT002", " POINT001 ", "POINT002"),
                        FROM, TO, " 修正测点绑定 "),
                TO);

        assertThat(response.jobId()).isEqualTo("JOB1");
        assertThat(response.pointIds()).containsExactly("POINT001", "POINT002");
        assertThat(response.createTime()).isEqualTo(TO);
        assertThat(response.updateTime()).isEqualTo(TO);
        verify(buildingService).lockExistingForUpdate("BLD001");
        verify(repository).findOverlappingForUpdate(
                "BLD001", local(FROM), local(TO));
    }

    @Test
    void identicalActiveAndSucceededReuseWhileFailedResumesSameJob() {
        BizDataQualityRecalcJob waiting = job("JOB1", RecalculationJobStatus.WAITING);
        when(repository.findByIdempotencyKey(any())).thenReturn(Optional.of(waiting));
        var request = request(List.of("POINT001"), FROM, TO);
        assertThat(service.submitRange(7L, ADMIN, request, TO).jobId())
                .isEqualTo("JOB1");

        BizDataQualityRecalcJob running =
                job("JOB_RUNNING", RecalculationJobStatus.RUNNING);
        when(repository.findByIdempotencyKey(any()))
                .thenReturn(Optional.of(running));
        assertThat(service.submitRange(7L, ADMIN, request, TO).jobId())
                .isEqualTo("JOB_RUNNING");

        BizDataQualityRecalcJob succeeded =
                job("JOB_SUCCEEDED", RecalculationJobStatus.SUCCEEDED);
        when(repository.findByIdempotencyKey(any()))
                .thenReturn(Optional.of(succeeded));
        assertThat(service.submitRange(7L, ADMIN, request, TO).jobId())
                .isEqualTo("JOB_SUCCEEDED");

        BizDataQualityRecalcJob failed = job("JOB2", RecalculationJobStatus.FAILED);
        when(repository.findByIdempotencyKey(any()))
                .thenReturn(Optional.of(failed), Optional.of(failed));
        when(repository.findOverlappingForUpdate(
                "BLD001", local(FROM), local(TO))).thenReturn(List.of());
        when(pointService.listByIds(List.of("POINT001")))
                .thenReturn(List.of(point("POINT001", "BLD001")));
        assertThat(service.submitRange(7L, ADMIN, request, TO).jobId())
                .isEqualTo("JOB2");
        verify(repository).resumeFailed("JOB2");
    }

    @Test
    void identicalVoidJobReusesBeforeHistoricalPointValidation() {
        BizDataQualityRecalcJob succeeded =
                job("JOB_VOID", RecalculationJobStatus.SUCCEEDED);
        succeeded.setJobType(RecalculationJobType.VOID_AND_RECALCULATE);
        succeeded.setSupersedesTaskId("TASK001");
        when(repository.findByIdempotencyKey(any()))
                .thenReturn(Optional.of(succeeded));

        var response = service.submitVoid(
                7L, ADMIN, "TASK001", "异常配置", TO);

        assertThat(response.jobId()).isEqualTo("JOB_VOID");
        verifyNoInteractions(fillTaskRepository, pointService, buildingService);
    }

    @Test
    void rechecksIdempotencyAfterBuildingLockAndReusesConcurrentJob() {
        BizDataQualityRecalcJob concurrent =
                job("JOB_CONCURRENT", RecalculationJobStatus.WAITING);
        when(repository.findByIdempotencyKey(any()))
                .thenReturn(Optional.empty());
        when(repository.findByIdempotencyKeyForUpdate(any()))
                .thenReturn(Optional.of(concurrent));
        when(pointService.listByIds(List.of("POINT001")))
                .thenReturn(List.of(point("POINT001", "BLD001")));

        var response = service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), FROM, TO), TO);

        assertThat(response.jobId()).isEqualTo("JOB_CONCURRENT");
        verify(buildingService).lockExistingForUpdate("BLD001");
        verify(repository).findByIdempotencyKeyForUpdate(any());
        verify(repository, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void rejectsIntersectingActiveJobButAllowsDisjointPoints() {
        when(pointService.listByIds(List.of("POINT001")))
                .thenReturn(List.of(point("POINT001", "BLD001")));
        BizDataQualityRecalcJob overlap = job(
                "OTHER", RecalculationJobStatus.RUNNING);
        overlap.setPointIdsJson("[\"POINT001\",\"POINT009\"]");
        when(repository.findOverlappingForUpdate(
                "BLD001", local(FROM), local(TO)))
                .thenReturn(List.of(overlap));
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), FROM, TO), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void allowsAnActiveJobWithDisjointPointsInTheSameTimeRange() {
        when(pointService.listByIds(List.of("POINT001")))
                .thenReturn(List.of(point("POINT001", "BLD001")));
        BizDataQualityRecalcJob disjoint =
                job("OTHER", RecalculationJobStatus.RUNNING);
        disjoint.setPointIdsJson("[\"POINT009\"]");
        when(repository.findOverlappingForUpdate(
                "BLD001", local(FROM), local(TO)))
                .thenReturn(List.of(disjoint));
        when(repository.insert(any())).thenAnswer(invocation -> {
            BizDataQualityRecalcJob candidate = invocation.getArgument(0);
            candidate.setJobId("JOB_NEW");
            return candidate;
        });

        var response = service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), FROM, TO), TO);

        assertThat(response.jobId()).isEqualTo("JOB_NEW");
    }

    @Test
    void validatesRolePointsPeriodCompletionAndReason() {
        assertThatThrownBy(() -> service.submitRange(
                7L, List.of("ENERGY_MANAGER"),
                request(List.of("POINT001"), FROM, TO), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), FROM + 1, TO), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), FROM, TO + 60_000L), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), TO, FROM), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN,
                new DataQualityRecalculationDtos.RecalculateRequest(
                        "BLD001", List.of("POINT001"), FROM, TO,
                        "x".repeat(501)),
                TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
        List<String> tooManyPoints = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "P" + index)
                .toList();
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN, request(tooManyPoints, FROM, TO), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));

        when(pointService.listByIds(List.of("POINT001"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), FROM, TO), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(404));

        when(pointService.listByIds(List.of("POINT001")))
                .thenReturn(List.of(point("POINT001", "BLD002")));
        assertThatThrownBy(() -> service.submitRange(
                7L, ADMIN, request(List.of("POINT001"), FROM, TO), TO))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
    }

    @Test
    void detailReturnsJobAndChildFillTaskAuditViews() {
        BizDataQualityRecalcJob job =
                job("JOB1", RecalculationJobStatus.RUNNING);
        BizDataQualityFillTask child = new BizDataQualityFillTask();
        child.setTaskId("CHILD1");
        DataQualityFillDtos.Response childView =
                mock(DataQualityFillDtos.Response.class);
        when(repository.findById("JOB1")).thenReturn(Optional.of(job));
        when(fillTaskRepository.findByRecalculationJobId("JOB1"))
                .thenReturn(List.of(child));
        when(fillTaskService.toResponse(child)).thenReturn(childView);

        DataQualityRecalculationDtos.Detail detail =
                service.detail(ADMIN, "JOB1");

        assertThat(detail.job().jobId()).isEqualTo("JOB1");
        assertThat(detail.childTasks()).containsExactly(childView);
        verify(fillTaskRepository).findByRecalculationJobId("JOB1");
    }

    @Test
    void detailSanitizesInternalJobErrorAndRejectsBrokenRequiredFields() {
        BizDataQualityRecalcJob job =
                job("JOB1", RecalculationJobStatus.FAILED);
        job.setLastError(
                "JDBC SELECT * FROM biz_data_quality_recalc_job "
                        + "jdbc:mysql://internal-host/iot");
        when(repository.findById("JOB1")).thenReturn(Optional.of(job));
        when(fillTaskRepository.findByRecalculationJobId("JOB1"))
                .thenReturn(List.of());

        DataQualityRecalculationDtos.Detail detail =
                service.detail(ADMIN, "JOB1");

        assertThat(detail.job().lastError())
                .isEqualTo("人工重算任务执行失败，请查看服务端日志")
                .doesNotContain("JDBC", "SELECT", "internal-host");

        job.setOperatorId(null);
        assertThatThrownBy(() -> service.detail(ADMIN, "JOB1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(409));
    }

    private DataQualityRecalculationDtos.RecalculateRequest request(
            List<String> points, long from, long to) {
        return new DataQualityRecalculationDtos.RecalculateRequest(
                "BLD001", points, from, to, "修正测点绑定");
    }

    private BizDataPoint point(String id, String buildingId) {
        BizDataPoint point = new BizDataPoint();
        point.setPointId(id);
        point.setBuildingId(buildingId);
        return point;
    }

    private BizDataQualityRecalcJob job(
            String id, RecalculationJobStatus status) {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId(id);
        job.setIdempotencyKey("KEY_" + id);
        job.setJobType(RecalculationJobType.RANGE_RECALCULATE);
        job.setBuildingId("BLD001");
        job.setPointIdsJson("[\"POINT001\"]");
        job.setFromMinute(local(FROM));
        job.setToMinute(local(TO));
        job.setReason("修正测点绑定");
        job.setOperatorId(7L);
        job.setStatus(status);
        job.setPhase(RecalculationJobPhase.RECALCULATING);
        job.setCursorMinute(local(FROM));
        job.setQ0Count(0);
        job.setQ1Count(0);
        job.setQ2Count(0);
        job.setMissingCount(0);
        job.setVoidedCount(0);
        job.setReplacedCount(0);
        job.setCreateTime(local(TO));
        job.setUpdateTime(local(TO));
        return job;
    }

    private LocalDateTime local(long value) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(value), ZoneId.of("Asia/Shanghai"));
    }
}
