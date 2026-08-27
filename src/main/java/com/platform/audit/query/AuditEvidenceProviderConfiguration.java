package com.platform.audit.query;

import com.platform.audit.AuditSummarySanitizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.platform.audit.query.JdbcAuditEvidenceProvider.Definition;

@Configuration
/** 显式登记可查询审计源；固定定义防止通过请求参数访问任意表或任意 SQL。 */
public class AuditEvidenceProviderConfiguration {
    @Bean
    AuditEvidenceProvider systemSecurityAuditProvider(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AuditSummarySanitizer sanitizer) {
        return provider(jdbc, sanitizer, new Definition(
                "SYSTEM_SECURITY", "sys_security_audit_event", "audit_id", "building_id",
                "actor_type", "operator_id", "action_type", "object_type", "object_id",
                "version_id", "review_request_id", "before_summary", "after_summary", "result",
                "reason_code", "trace_id", "operation_time", "environment_mode", "self_approval_dev_mode"));
    }

    @Bean
    AuditEvidenceProvider collectionAuditProvider(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AuditSummarySanitizer sanitizer) {
        return provider(jdbc, sanitizer, domain("COLLECTION", "biz_collection_config_audit_log",
                "CASE WHEN actor_type='USER' THEN 'USER' ELSE 'MIGRATION' END"));
    }

    @Bean
    AuditEvidenceProvider qualityUsageAuditProvider(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AuditSummarySanitizer sanitizer) {
        return provider(jdbc, sanitizer, domain("QUALITY_USAGE", "biz_quality_usage_audit_log",
                "CASE WHEN actor_type='USER' THEN 'USER' ELSE 'MIGRATION' END"));
    }

    @Bean
    AuditEvidenceProvider deviceParameterAuditProvider(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AuditSummarySanitizer sanitizer) {
        return provider(jdbc, sanitizer, domain("DEVICE_PARAMETER", "biz_device_parameter_audit_log",
                "CASE WHEN actor_type='USER' THEN 'USER' WHEN actor_type='DEVICE' THEN 'DEVICE' "
                        + "WHEN actor_type='SYSTEM_RECALCULATION' THEN 'SYSTEM' ELSE 'MIGRATION' END"));
    }

    @Bean
    AuditEvidenceProvider onboardingAuditProvider(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AuditSummarySanitizer sanitizer) {
        return provider(jdbc, sanitizer, domain("ONBOARDING", "biz_onboarding_audit_log", "actor_type"));
    }

    @Bean
    AuditEvidenceProvider relationAuditProvider(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AuditSummarySanitizer sanitizer) {
        return provider(jdbc, sanitizer, new Definition(
                "RELATION", "biz_relation_audit_log", "audit_id", "building_id", "actor_type",
                "operator_id", "action_type", "object_type", "object_id", "version_id", "request_id",
                "before_state", "COALESCE(summary,after_state)", "result", "NULL", "trace_id",
                "operation_time", "environment_mode", "self_approval_dev_mode"));
    }

    private static Definition domain(String module, String table, String actorExpression) {
        return new Definition(module, table, "audit_id", "building_id", actorExpression, "operator_id",
                "action_type", "object_type", "object_id", "version_id", "review_request_id",
                "before_summary", "after_summary", "result", "reason_code", "trace_id",
                "operation_time", "environment_mode", "self_approval_dev_mode");
    }

    private static AuditEvidenceProvider provider(
            JdbcTemplate jdbc, AuditSummarySanitizer sanitizer, Definition definition) {
        return new JdbcAuditEvidenceProvider(jdbc, sanitizer, definition);
    }
}
