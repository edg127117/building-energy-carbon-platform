package com.platform.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditRetentionApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM sys_audit_cleanup_run");
        jdbc.update("DELETE FROM sys_audit_evidence_hold");
        jdbc.update("DELETE FROM sys_audit_retention_policy");
        jdbc.update("DELETE FROM sys_security_audit_event");
        jdbc.update("DELETE FROM sys_sensitive_change_request");
        jdbc.update("DELETE FROM sys_user_backend_duty");
        for (BackendDuty duty : BackendDuty.values()) grant(duty);
    }

    @Test
    void holdReleaseAndRetentionPolicyUseSeparatedWriteContracts() throws Exception {
        String token = login();
        MvcResult holdResult = mockMvc.perform(post("/v1/audit-holds")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceModule":"SYSTEM_SECURITY","auditId":"AUDIT-001",
                                 "investigationId":"INV-API","reason":"待调查证据",
                                 "legalBasis":"内部调查单","reviewAt":"2027-01-01T00:00:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        String holdId = json(holdResult).path("data").path("holdId").asText();

        JsonNode release = executeChange(token, "RELEASE_AUDIT_EVIDENCE_HOLD", "release-hold-api",
                objectMapper.createObjectNode().put("holdId", holdId).put("reason", "调查已结案"));
        assertThat(release.path("status").asText()).isEqualTo("EXECUTED");
        mockMvc.perform(get("/v1/audit-holds").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.data[0].releaseRequestId").isString());

        var policy = objectMapper.createObjectNode().put("dataCategory", "SECURITY_EVENT")
                .put("sourceModule", "SYSTEM_SECURITY").put("retentionPeriod", "P6M")
                .put("cleanupEnabled", true).put("effectiveAt", "2026-08-27T00:00:00")
                .put("changeReason", "六个自然月安全事件保留");
        executeChange(token, "SET_AUDIT_RETENTION_POLICY", "retention-policy-api", policy);
        mockMvc.perform(get("/v1/audit-retention/policies")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].retentionPeriod").value("P6M"))
                .andExpect(jsonPath("$.data[0].policyVersion").value(1));
        mockMvc.perform(get("/v1/audit-retention/cleanup-runs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/audit-holds")).isTrue();
        assertThat(openApi.path("paths").has("/v1/audit-retention/policies")).isTrue();
        assertThat(openApi.path("paths").has("/v1/audit-retention/cleanup-runs")).isTrue();
    }

    private JsonNode executeChange(String token, String operationCode, String key, JsonNode command)
            throws Exception {
        var request = objectMapper.createObjectNode().put("operationCode", operationCode)
                .put("idempotencyKey", key).set("command", command);
        MvcResult created = mockMvc.perform(post("/v1/backoffice/change-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk()).andReturn();
        String requestId = json(created).path("data").path("requestId").asText();
        mockMvc.perform(post("/v1/backoffice/change-requests/{id}/submit", requestId)
                        .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(post("/v1/backoffice/change-requests/{id}/approve", requestId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"研发单人完整两步审批\"}"))
                .andExpect(status().isOk());
        return json(mockMvc.perform(post("/v1/backoffice/change-requests/{id}/execute", requestId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data").path("token").asText();
    }

    private void grant(BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,1,?,'ACTIVE',?,1,?)
                """, UUID.randomUUID().toString().replace("-", ""), duty.name(),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
