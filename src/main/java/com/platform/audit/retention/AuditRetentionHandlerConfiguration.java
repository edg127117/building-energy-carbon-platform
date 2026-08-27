package com.platform.audit.retention;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.platform.audit.retention.JdbcAuditRetentionHandler.Definition;

@Configuration
/** 固定登记可清理审计源及其未决审批保护条件，禁止请求选择任意业务表。 */
public class AuditRetentionHandlerConfiguration {
    @Bean
    AuditRetentionHandler systemSecurityRetentionHandler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return handler(jdbc, definition("SYSTEM_SECURITY", "sys_security_audit_event", "review_request_id",
                "sys_sensitive_change_request", "request_id", "status IN ('DRAFT','PENDING_REVIEW','APPROVED')"));
    }

    @Bean
    AuditRetentionHandler collectionRetentionHandler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return handler(jdbc, definition("COLLECTION", "biz_collection_config_audit_log", "review_request_id",
                "biz_collection_review_request", "request_id", "status='PENDING'"));
    }

    @Bean
    AuditRetentionHandler qualityUsageRetentionHandler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return handler(jdbc, definition("QUALITY_USAGE", "biz_quality_usage_audit_log", "review_request_id",
                "biz_quality_usage_review_request", "request_id", "status='PENDING'"));
    }

    @Bean
    AuditRetentionHandler deviceParameterRetentionHandler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return handler(jdbc, definition("DEVICE_PARAMETER", "biz_device_parameter_audit_log", "review_request_id",
                "biz_device_parameter_review_request", "request_id", "status='PENDING'"));
    }

    @Bean
    AuditRetentionHandler onboardingRetentionHandler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return handler(jdbc, definition("ONBOARDING", "biz_onboarding_audit_log", "review_request_id",
                "sys_sensitive_change_request", "request_id", "status IN ('DRAFT','PENDING_REVIEW','APPROVED')"));
    }

    @Bean
    AuditRetentionHandler relationRetentionHandler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return handler(jdbc, definition("RELATION", "biz_relation_audit_log", "request_id",
                "biz_relation_review_request", "request_id", "status='PENDING'"));
    }

    private static Definition definition(
            String module, String table, String reviewColumn,
            String requestTable, String requestIdColumn, String pendingPredicate) {
        String pending = "EXISTS (SELECT 1 FROM " + requestTable + " p WHERE p." + requestIdColumn
                + "=" + table + "." + reviewColumn + " AND p." + pendingPredicate + ")";
        return new Definition(module, table, "audit_id", "building_id", "action_type",
                "object_type", "object_id", "operation_time", pending);
    }

    private static AuditRetentionHandler handler(JdbcTemplate jdbc, Definition definition) {
        return new JdbcAuditRetentionHandler(jdbc, definition);
    }
}
