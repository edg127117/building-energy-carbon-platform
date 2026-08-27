package com.platform.iot.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.iot.onboarding.mapper.BizOnboardingAuditLogMapper;
import com.platform.iot.onboarding.model.entity.BizOnboardingAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
/** 在业务事务内写入设备接入操作的脱敏前后摘要。 */
public class OnboardingAuditService {
    private final BizOnboardingAuditLogMapper mapper;
    private final ObjectMapper objectMapper;
    private final AuditGovernanceProperties auditProperties;

    public void record(
            Long operatorId,
            String buildingId,
            String action,
            String objectType,
            String objectId,
            Map<String, ?> before,
            Map<String, ?> after) {
        BizOnboardingAuditLog log = new BizOnboardingAuditLog();
        log.setBuildingId(buildingId);
        log.setActorType("USER");
        log.setOperatorId(operatorId);
        log.setActionType(action);
        log.setObjectType(objectType);
        log.setObjectId(objectId);
        log.setBeforeSummary(toSummary(before));
        log.setAfterSummary(toSummary(after));
        log.setResult("SUCCESS");
        log.setTraceId(TraceContext.current());
        log.setEnvironmentMode(auditProperties.getEnvironmentMode().name());
        log.setSelfApprovalDevMode(false);
        log.setOperationTime(LocalDateTime.now());
        mapper.insert(log);
    }

    private String toSummary(Map<String, ?> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > 1000) {
                throw OnboardingErrors.error(
                        500, OnboardingErrors.VALIDATION_FAILED, "审计摘要超出安全上限");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw OnboardingErrors.error(
                    500, OnboardingErrors.VALIDATION_FAILED, "审计摘要生成失败");
        }
    }
}
