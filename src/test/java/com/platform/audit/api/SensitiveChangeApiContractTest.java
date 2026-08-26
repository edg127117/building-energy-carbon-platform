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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SensitiveChangeApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareDuties() {
        jdbcTemplate.update("DELETE FROM sys_security_audit_event");
        jdbcTemplate.update("DELETE FROM sys_sensitive_change_request");
        jdbcTemplate.update("DELETE FROM sys_user_backend_duty");
        grant(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
    }

    @Test
    void exposesCompleteDevelopmentTwoStepFlowAndServerTrace() throws Exception {
        String token = login();
        MvcResult created = mockMvc.perform(post("/v1/backoffice/change-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operationCode":"GRANT_BACKEND_DUTY","idempotencyKey":"api-grant-viewer",
                                 "command":{"userId":1,"dutyKey":"AUDIT_EVIDENCE_VIEWER"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        String requestId = json(created).path("data").path("requestId").asText();

        mockMvc.perform(post("/v1/backoffice/change-requests/{id}/submit", requestId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mockMvc.perform(post("/v1/backoffice/change-requests/{id}/approve", requestId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"研发单人完整两步审批\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.selfApprovalDevMode").value(true));
        mockMvc.perform(post("/v1/backoffice/change-requests/{id}/execute", requestId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXECUTED"));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/backoffice/change-requests/{requestId}/approve")).isTrue();
    }

    @Test
    void anonymousAndMissingDutyFailuresAreMaskedAndTraceable() throws Exception {
        MvcResult anonymous = mockMvc.perform(get("/v1/backoffice/change-requests/hidden"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_OPERATION_UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn();
        assertThat(json(anonymous).path("traceId").asText())
                .isEqualTo(anonymous.getResponse().getHeader("X-Trace-Id"));

        String token = login();
        jdbcTemplate.update("DELETE FROM sys_user_backend_duty WHERE duty_key=?",
                BackendDuty.BACKOFFICE_CHANGE_SUBMITTER.name());
        MvcResult denied = mockMvc.perform(post("/v1/backoffice/change-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operationCode":"GRANT_BACKEND_DUTY","idempotencyKey":"forbidden",
                                 "command":{"userId":1,"dutyKey":"AUDIT_EVIDENCE_VIEWER"}}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_DUTY_REQUIRED"))
                .andExpect(jsonPath("$.msg").value("无权限执行该操作"))
                .andReturn();
        String traceId = json(denied).path("traceId").asText();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE trace_id=? AND reason_code='BACKOFFICE_DUTY_REQUIRED' AND result='DENIED'
                """, Integer.class, traceId)).isEqualTo(1);
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data").path("token").asText();
    }

    private void grant(BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), 1L, duty.name(),
                Timestamp.valueOf(now), 1L, Timestamp.valueOf(now));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
