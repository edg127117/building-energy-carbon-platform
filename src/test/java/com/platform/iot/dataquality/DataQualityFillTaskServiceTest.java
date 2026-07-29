package com.platform.iot.dataquality;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataQualityFillTaskServiceTest {

    private FillTaskRepository repository;
    private BuildingScopeService scopeService;
    private DataQualityRecoveryService recoveryService;
    private FillTaskEvidenceCodec codec;
    private DataQualityFillTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(FillTaskRepository.class);
        scopeService = mock(BuildingScopeService.class);
        recoveryService = mock(DataQualityRecoveryService.class);
        codec = new FillTaskEvidenceCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        service = new DataQualityFillTaskService(
                repository, codec, scopeService, recoveryService);
    }

    @Test
    void pageDelegatesBuildingScopeAndAllFiltersToMySqlPagination() {
        BizDataQualityFillTask task = task(FillApplyStatus.APPLIED);
        Page<BizDataQualityFillTask> page = new Page<>(2, 10);
        page.setRecords(List.of(task));
        page.setTotal(1);
        when(scopeService.getAccessibleBuildingIds(
                7L, List.of("BUILDING_OWNER"))).thenReturn(Set.of("BLD001"));
        when(repository.findPage(
                eq(2), eq(10), eq(false), eq(Set.of("BLD001")),
                eq("BLD001"), eq("POINT001"), eq(FillSourceType.INTERPOLATION),
                eq(1), eq(FillApplyStatus.APPLIED), any(), any())).thenReturn(page);

        var result = service.page(
                7L, List.of("BUILDING_OWNER"), 2, 10, "BLD001", "POINT001",
                FillSourceType.INTERPOLATION, 1, FillApplyStatus.APPLIED,
                1_800_000L, 2_100_000L);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords().getFirst().evidence())
                .isInstanceOf(FillTaskEvidence.Interpolation.class);
        verify(scopeService).checkAccess(
                7L, List.of("BUILDING_OWNER"), "BLD001");
    }

    @Test
    void pageKeepsEmptyBuildingScopeRestrictedInsteadOfTreatingItAsAdmin() {
        Page<BizDataQualityFillTask> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(scopeService.getAccessibleBuildingIds(
                7L, List.of("BUILDING_OWNER"))).thenReturn(Set.of());
        when(repository.findPage(
                eq(1), eq(10), eq(false), eq(Set.of()),
                eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null))).thenReturn(page);

        var result = service.page(
                7L, List.of("BUILDING_OWNER"), 1, 10,
                null, null, null, null, null, null, null);

        assertThat(result.getRecords()).isEmpty();
        verify(repository).findPage(
                eq(1), eq(10), eq(false), eq(Set.of()),
                eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null));
    }

    @Test
    void detailReturnsFixedBusinessSummaryInsteadOfInternalLastError() {
        BizDataQualityFillTask failed = task(FillApplyStatus.FAILED);
        String internalError = """
                JDBC SQL SELECT * FROM minute_data
                jdbc:mysql://mysql-internal:3306/iot
                    at com.mysql.cj.jdbc.ClientPreparedStatement.execute
                """;
        failed.setLastError(internalError);
        when(repository.findAuditById("TASK001")).thenReturn(Optional.of(failed));

        var response = service.detail(
                7L, List.of("BUILDING_OWNER"), "TASK001");

        assertThat(response.lastError())
                .isEqualTo("补全任务执行失败，请联系管理员处理")
                .doesNotContain("JDBC", "SQL", "mysql-internal", "ClientPreparedStatement");
    }

    @Test
    void detailRejectsThirdPartyAndDamagedEvidence() {
        assertThatThrownBy(() -> service.detail(
                7L, List.of("THIRD_PARTY"), "TASK001"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(403));

        BizDataQualityFillTask damaged = task(FillApplyStatus.APPLIED);
        damaged.setEvidenceJson("{broken");
        when(repository.findAuditById("TASK001")).thenReturn(Optional.of(damaged));
        assertThatThrownBy(() -> service.detail(
                7L, List.of("BUILDING_OWNER"), "TASK001"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void retryRequiresAdminAndFailedTaskAndReusesSameTaskRecovery() {
        BizDataQualityFillTask applied = task(FillApplyStatus.APPLIED);
        when(repository.findAuditById("TASK001")).thenReturn(Optional.of(applied));
        assertThatThrownBy(() -> service.retry(
                List.of("PLATFORM_ADMIN"), "TASK001", 2_100_000L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(recoveryService, never()).recoverTask(any(), anyLong());

        BizDataQualityFillTask failed = task(FillApplyStatus.FAILED);
        when(repository.findAuditById("TASK001")).thenReturn(Optional.of(failed));
        service.retry(List.of("PLATFORM_ADMIN"), "TASK001", 2_100_000L);
        verify(recoveryService).recoverTask("TASK001", 2_100_000L);

        assertThatThrownBy(() -> service.retry(
                List.of("ENERGY_MANAGER"), "TASK001", 2_100_000L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(403));
    }

    @Test
    void retryMapsMissingAndTdFailureTo404And503() {
        when(repository.findAuditById("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry(
                List.of("PLATFORM_ADMIN"), "MISSING", 2_100_000L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(404));

        BizDataQualityFillTask failed = task(FillApplyStatus.FAILED);
        when(repository.findAuditById("TASK001")).thenReturn(Optional.of(failed));
        org.mockito.Mockito.doThrow(new RuntimeException("TD down"))
                .when(recoveryService).recoverTask("TASK001", 2_100_000L);
        assertThatThrownBy(() -> service.retry(
                List.of("PLATFORM_ADMIN"), "TASK001", 2_100_000L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(503));
    }

    private BizDataQualityFillTask task(FillApplyStatus status) {
        FillTaskEvidence.Interpolation evidence = new FillTaskEvidence.Interpolation(
                1_800_000L, 10D, 1_980_000L, 13D, "LINEAR_V1");
        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setTaskId("TASK001");
        task.setIdempotencyKey("Q1:POINT001:1800000:1980000:LINEAR_V1");
        task.setBuildingId("BLD001");
        task.setPointId("POINT001");
        task.setStartMinute(LocalDateTime.of(1970, 1, 1, 8, 31));
        task.setEndMinute(LocalDateTime.of(1970, 1, 1, 8, 33));
        task.setMinuteCount(2);
        task.setDataQuality(1);
        task.setSourceType(FillSourceType.INTERPOLATION);
        task.setAlgorithmVersion("LINEAR_V1");
        task.setEvidenceJson(codec.encode(FillSourceType.INTERPOLATION, evidence));
        task.setApplyStatus(status);
        task.setAppliedCount(status == FillApplyStatus.APPLIED ? 2 : 0);
        task.setFailedCount(status == FillApplyStatus.FAILED ? 2 : 0);
        task.setReplacedCount(0);
        task.setVoidedCount(0);
        task.setRetryCount(0);
        task.setGeneratedAt(LocalDateTime.of(1970, 1, 1, 8, 35));
        return task;
    }
}
