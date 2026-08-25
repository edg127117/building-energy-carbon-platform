package com.platform.iot.deviceparameter.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService.CandidateCommand;
import com.platform.iot.deviceparameter.DeviceParameterErrors;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.DeviceParameterModels.ValidationStatus;
import com.platform.iot.deviceparameter.DeviceParameterSupport;
import com.platform.iot.onboarding.PendingDeviceDiscovery;
import com.platform.iot.onboarding.PendingDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.invalid;

@Service
@RequiredArgsConstructor
/**
 * 标准设备参数报告的身份解析、待接入分流和整份原子候选写入边界。
 *
 * <p>未知身份只进入既有 MySQL 待绑定区。已绑定报告只有在硬件语义被显式启用后才处理；
 * 任一参数未映射或非法会回滚整份报告，避免在部分失败规则尚未确认时猜测可接受子集。</p>
 */
public class DeviceParameterReportHandler {
    private static final ZoneId PROJECT_ZONE = DeviceParameterSupport.PROJECT_ZONE;

    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterCandidateService candidateService;
    private final DeviceParameterIngestProperties properties;
    private final PendingDeviceRepository pendingRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReportResult handle(StandardDeviceParameterReport report) {
        validateEnvelope(report);
        EquipmentIdentity equipment = repository.findEquipmentByExternalIdentity(
                report.deviceIdentity().type(), report.deviceIdentity().value()).orElse(null);
        if (equipment == null) {
            discoverUnknown(report);
            return new ReportResult("PENDING_DEVICE", List.of());
        }
        if (!properties.isEnabled()
                || properties.getAllowedSemantics().stream().map(this::normalize)
                .noneMatch(normalize(report.reportSemantics())::equals)) {
            throw DeviceParameterErrors.error(409,
                    DeviceParameterErrors.MAPPING_NOT_FOUND,
                    "设备参数报文语义尚未由硬件合同启用");
        }
        String expectedProfile = repository.findExpectedProfileCode(
                        report.deviceIdentity().type(), report.deviceIdentity().value())
                .orElseThrow(() -> DeviceParameterErrors.error(409,
                        DeviceParameterErrors.MAPPING_NOT_FOUND,
                        "设备身份没有已确认参数档案契约"));
        if (!normalize(expectedProfile).equals(normalize(report.profileCode()))) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.MAPPING_NOT_FOUND,
                    "设备参数档案与已绑定身份不匹配");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (StandardDeviceParameterReport.Item item : report.parameters()) {
            var mapping = item.mappingVersionId() == null ? null
                    : repository.findMappingVersion(item.mappingVersionId()).orElse(null);
            if (mapping == null || !"ACTIVE".equals(mapping.status())
                    || !normalize(mapping.profileCode()).equals(normalize(report.profileCode()))
                    || !mapping.profileVersion().equals(Integer.toString(report.profileVersion()))
                    || !mapping.sourcePath().equals(item.sourcePath())) {
                throw DeviceParameterErrors.error(409, DeviceParameterErrors.MAPPING_NOT_FOUND,
                        "设备参数项没有匹配的已发布档案映射");
            }
            if (item.parameterCode() != null && !item.parameterCode().isBlank()) {
                var definition = repository.findDefinition(mapping.definitionId()).orElse(null);
                if (definition == null || !definition.parameterCode().equals(
                        normalize(item.parameterCode()))) {
                    throw DeviceParameterErrors.error(409,
                            DeviceParameterErrors.MAPPING_NOT_FOUND,
                            "设备参数项声明与映射目标不一致");
                }
            }
            Candidate candidate = candidateService.ingest(new CandidateCommand(
                    equipment, SourceType.DEVICE, report.reportId(),
                    Integer.toString(report.profileVersion()), item.sourcePath(), item.rawValue(),
                    item.rawUnit(), item.mappingVersionId(), item.parameterCode(),
                    report.reportedAt(), report.reportId() + '|' + item.sourcePath(), null));
            if (candidate.validationStatus() != ValidationStatus.READY) {
                throw DeviceParameterErrors.error(400,
                        DeviceParameterErrors.VALIDATION_FAILED,
                        "设备参数报告包含未映射或非法参数，整份报告未受理");
            }
            candidates.add(candidate);
        }
        return new ReportResult("CANDIDATES_PERSISTED", candidates);
    }

    private void validateEnvelope(StandardDeviceParameterReport report) {
        if (report == null || report.deviceIdentity() == null
                || report.standardVersion() == null || report.standardVersion().isBlank()
                || report.profileCode() == null || report.profileCode().isBlank()
                || report.profileVersion() < 1 || report.reportId() == null
                || report.reportId().isBlank() || report.reportSemantics() == null
                || report.reportSemantics().isBlank() || report.reportedAt() == null
                || report.receivedAt() == null || report.parameters().isEmpty()
                || report.parameters().size() > properties.getMaxItems()) {
            throw invalid("设备参数报告身份、版本、语义、时间或参数项不完整");
        }
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (StandardDeviceParameterReport.Item item : report.parameters()) {
            if (item == null || item.sourcePath() == null || item.sourcePath().isBlank()
                    || item.rawValue() == null || item.rawValue().isBlank()
                    || !paths.add(item.sourcePath())) {
                throw invalid("设备参数报告包含空字段路径、空值或重复参数项");
            }
        }
    }

    private void discoverUnknown(StandardDeviceParameterReport report) {
        try {
            String sample = objectMapper.writeValueAsString(report.parameters().stream()
                    .limit(64).map(item -> new PendingItem(item.sourcePath(), item.rawUnit())).toList());
            pendingRepository.upsertDiscovery(new PendingDeviceDiscovery(id(), report.deviceIdentity(),
                    report.profileCode(), report.profileVersion(), report.receivedAt(),
                    report.reportedAt().atZone(PROJECT_ZONE).toInstant().toEpochMilli(),
                    "DEVICE_PARAMETER_REPORTED_AT", sample, report.parameters().size() > 64));
        } catch (Exception exception) {
            throw DeviceParameterErrors.error(503, DeviceParameterErrors.VALIDATION_FAILED,
                    "未知设备参数报告暂时无法进入待接入区");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record ReportResult(String status, List<Candidate> candidates) {
        public ReportResult {
            candidates = List.copyOf(candidates);
        }
    }

    private record PendingItem(String sourcePath, String rawUnit) {
    }
}
