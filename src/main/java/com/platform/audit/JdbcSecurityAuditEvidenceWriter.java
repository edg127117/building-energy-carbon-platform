package com.platform.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
/** 系统安全审计的 MySQL 首版实现；接口允许后续把高频安全事件替换到独立存储。 */
public class JdbcSecurityAuditEvidenceWriter implements AuditEvidenceWriter {
    private final JdbcTemplate jdbcTemplate;
    private final AuditSummarySanitizer sanitizer;

    @Override
    public void append(AuditEvidence evidence) {
        LocalDateTime operationTime = evidence.operationTime() == null
                ? LocalDateTime.now() : evidence.operationTime();
        jdbcTemplate.update("""
                INSERT INTO sys_security_audit_event
                (audit_id,source_module,building_id,actor_type,operator_id,action_type,object_type,object_id,
                 version_id,review_request_id,before_summary,after_summary,result,reason_code,trace_id,
                 operation_time,environment_mode,self_approval_dev_mode)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id(), required(evidence.sourceModule()), evidence.buildingId(), required(evidence.actorType()),
                evidence.operatorId(), required(evidence.actionType()), required(evidence.objectType()),
                required(evidence.objectId()), evidence.versionId(), evidence.reviewRequestId(),
                sanitizer.sanitize(evidence.beforeSummary()), sanitizer.sanitize(evidence.afterSummary()),
                required(evidence.result()), evidence.reasonCode(), required(evidence.traceId()),
                Timestamp.valueOf(operationTime), evidence.environmentMode().name(), evidence.selfApprovalDevMode());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("审计必填字段不能为空");
        }
        return value;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
