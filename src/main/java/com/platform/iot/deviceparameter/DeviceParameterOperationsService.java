package com.platform.iot.deviceparameter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.web.PageResponse;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.AuditEntry;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.RecalculationJob;
import com.platform.iot.deviceparameter.DeviceParameterModels.RecalculationStatus;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.AuditView;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.RecalculationView;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.FormulaResultRevisionView;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 参数追溯重算运维和脱敏审计查询；所有入口同时校验角色与建筑范围。 */
public class DeviceParameterOperationsService {
    private static final Set<String> RECALC_STATUSES = Set.of(
            "WAITING", "RUNNING", "FAILED", "SUCCEEDED");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterAuthorization authorization;
    private final ObjectMapper objectMapper;
    private final IndicatorMinuteRepository indicatorRepository;

    @Transactional
    public PageResponse<RecalculationView> listRecalculations(
            long userId, Collection<String> roles, String buildingId,
            String status, int page, int size) {
        authorization.requireReader(roles);
        authorization.checkBuilding(userId, roles, buildingId);
        validatePage(page, size);
        String normalizedStatus = normalizeStatus(status);
        List<RecalculationView> items = repository.listRecalculationJobs(
                        buildingId, normalizedStatus, (page - 1) * size, size).stream()
                .map(this::recalculationView).toList();
        PageResponse<RecalculationView> result = new PageResponse<>(page, size,
                repository.countRecalculationJobs(buildingId, normalizedStatus), items);
        repository.audit(id(), buildingId, "USER", userId, "READ_RECALCULATIONS",
                "BUILDING", buildingId, null, null, "count=" + items.size(),
                "SUCCESS", null, null, null);
        return result;
    }

    @Transactional
    public RecalculationView resumeFailed(
            long userId, Collection<String> roles, String jobId) {
        authorization.requireReviewer(roles);
        RecalculationJob job = repository.findRecalculationJob(jobId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "参数重算任务不存在"));
        authorization.checkBuilding(userId, roles, job.buildingId());
        if (repository.resumeFailedRecalculationJob(jobId) != 1) {
            throw DeviceParameterErrors.error(409,
                    DeviceParameterErrors.VERSION_CONFLICT, "只有 FAILED 参数重算任务可以恢复");
        }
        repository.updateTimelineRecalculationStatus(
                job.timelineRevisionId(), RecalculationStatus.PENDING_RECALC);
        repository.audit(id(), job.buildingId(), "USER", userId,
                "RESUME_RECALCULATION", "RECALCULATION_JOB", jobId,
                job.timelineRevisionId(), "FAILED", "WAITING", "SUCCESS", null,
                null, null);
        return recalculationView(repository.findRecalculationJob(jobId).orElseThrow());
    }

    @Transactional
    public PageResponse<AuditView> listAudits(
            long userId, Collection<String> roles, String buildingId, int page, int size) {
        authorization.requireReader(roles);
        if (buildingId == null || buildingId.isBlank()) {
            authorization.requireGlobalConfigurator(roles);
            buildingId = null;
        } else {
            authorization.checkBuilding(userId, roles, buildingId);
        }
        validatePage(page, size);
        List<AuditView> items = repository.listAudits(
                        buildingId, (page - 1) * size, size).stream()
                .map(DeviceParameterOperationsService::auditView).toList();
        PageResponse<AuditView> result = new PageResponse<>(
                page, size, repository.countAudits(buildingId), items);
        repository.audit(id(), buildingId, "USER", userId, "READ_AUDITS",
                "AUDIT_SCOPE", buildingId == null ? "GLOBAL" : buildingId, null,
                null, "count=" + items.size(), "SUCCESS", null, null, null);
        return result;
    }

    @Transactional
    public FormulaResultRevisionView formulaResultAt(
            long userId, Collection<String> roles, String indicatorId,
            long minuteStart, Long knowledgeAt) {
        authorization.requireReader(roles);
        long knowledge = knowledgeAt == null ? System.currentTimeMillis() : knowledgeAt;
        if (minuteStart < 0 || knowledge < 0) {
            throw DeviceParameterSupport.invalid("指标分钟和认知时间必须为非负毫秒时间戳");
        }
        var revision = indicatorRepository.findResultRevisionAt(
                        indicatorId, minuteStart, knowledge)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "指定认知时间没有公式结果修订"));
        authorization.checkBuilding(userId, roles, revision.buildingId());
        FormulaResultRevisionView result = new FormulaResultRevisionView(
                revision.resultRevisionId(), revision.attemptId(),
                revision.indicatorId(), revision.indicatorCode(), revision.buildingId(),
                revision.equipId(), revision.minuteStart(), revision.value(),
                revision.dataQuality(), revision.formulaVersion(),
                revision.parameterEvidenceJson(), revision.calculatedAt());
        repository.audit(id(), revision.buildingId(), "USER", userId,
                "READ_FORMULA_RESULT_REVISION", "INDICATOR", indicatorId,
                revision.resultRevisionId(), null, "minute=" + minuteStart,
                "SUCCESS", null, null, null);
        return result;
    }

    private RecalculationView recalculationView(RecalculationJob job) {
        List<String> indicators;
        try {
            indicators = objectMapper.readValue(job.indicatorIdsJson(), STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("参数重算任务指标快照损坏", exception);
        }
        return new RecalculationView(job.jobId(), job.timelineRevisionId(), job.buildingId(),
                job.equipmentId(), indicators, job.fromMinute(), job.toMinute(), job.status(),
                job.cursorMinute(), job.retryCount(), job.lastError(),
                "FAILED".equals(job.status()) ? List.of("RESUME") : List.of());
    }

    private static AuditView auditView(AuditEntry value) {
        return new AuditView(value.auditId(), value.buildingId(), value.actorType(),
                value.actionType(), value.objectType(), value.objectId(), value.versionId(),
                value.result(), value.reasonCode(), value.operationTime());
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw DeviceParameterSupport.invalid("分页参数必须满足 page>=1 且 1<=size<=100");
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!RECALC_STATUSES.contains(normalized)) {
            throw DeviceParameterSupport.invalid("参数重算状态不受支持");
        }
        return normalized;
    }
}
