package com.platform.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SensitiveChangeApiContractTest {
    private static final String OPENED_USERNAME = "approved_third_party";
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareDuties() {
        cleanupOpenedAccount();
        jdbcTemplate.update("DELETE FROM sys_security_audit_event");
        jdbcTemplate.update("DELETE FROM sys_sensitive_change_request");
        jdbcTemplate.update("DELETE FROM sys_user_backend_duty");
        grant(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
    }

    @AfterEach
    void cleanup() {
        cleanupOpenedAccount();
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

    @Test
    void oldSystemSensitiveWriteEndpointsRequireApprovedChangeRequest() throws Exception {
        String token = login();
        assertReviewRequired(post("/system/users")
                .content("{\"username\":\"legacy_create\",\"password\":\"123456\"}"), token);
        assertReviewRequired(delete("/system/users/99"), token);
        assertReviewRequired(put("/system/users/99/restore").content("{}"), token);
        assertReviewRequired(put("/system/users/1/password").content("{}"), token);
        assertReviewRequired(put("/system/roles/10/menus")
                .content("{\"menuIds\":[100,101]}"), token);
        assertReviewRequired(put("/system/users/1/status")
                .content("{\"status\":1}"), token);
        assertReviewRequired(put("/system/users/1/roles")
                .content("{\"roleKeys\":[\"PLATFORM_ADMIN\"]}"), token);
        assertReviewRequired(put("/system/users/1/buildings")
                .content("{\"buildingIds\":[\"BLD001\"]}"), token);
        assertReviewRequired(delete("/system/users/1/buildings/BLD001"), token);
    }

    @Test
    void accountOpeningAndPasswordResetReturnTokenOnlyOnExecution() throws Exception {
        String adminToken = login();
        var openingCommand = objectMapper.createObjectNode()
                .put("username", OPENED_USERNAME).put("nickname", "审批开通账号");
        openingCommand.set("roleKeys", objectMapper.createArrayNode().add("THIRD_PARTY"));
        openingCommand.set("buildingIds", objectMapper.createArrayNode().add("BLD001"));
        JsonNode opening = executeChange(adminToken, "OPEN_USER_ACCOUNT", "open-account-api", openingCommand);
        String openingRequestId = opening.path("requestId").asText();
        String activationToken = opening.path("oneTimeToken").asText();
        assertThat(opening.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(opening.path("tokenPurpose").asText()).isEqualTo("ACTIVATION");
        assertThat(activationToken).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT command_json FROM sys_sensitive_change_request WHERE request_id=?",
                String.class, openingRequestId)).doesNotContain("password", activationToken);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM sys_password_setup_token WHERE source_request_id=?",
                String.class, openingRequestId)).isNotEqualTo(activationToken).hasSize(64);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE review_request_id=? AND action_type IN
                  ('CREATE_USER_ACCOUNT','GRANT_USER_FORMAL_ROLE','GRANT_USER_BUILDING_ACCESS')
                """, Integer.class, openingRequestId)).isEqualTo(3);

        mockMvc.perform(post("/auth/password/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("token", activationToken)
                                .put("password", "first-password").toString()))
                .andExpect(status().isOk());
        String openedToken = login(OPENED_USERNAME, "first-password");
        assertThat(openedToken).isNotBlank();
        mockMvc.perform(post("/auth/password/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("token", activationToken)
                                .put("password", "reused-password").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_SETUP_TOKEN_INVALID"));

        JsonNode reset = executeChange(adminToken, "ISSUE_PASSWORD_RESET_TOKEN", "reset-account-api",
                objectMapper.createObjectNode().put("userId", userId(OPENED_USERNAME)));
        String resetToken = reset.path("oneTimeToken").asText();
        assertThat(reset.path("tokenPurpose").asText()).isEqualTo("RESET");
        mockMvc.perform(post("/auth/password/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("token", resetToken)
                                .put("password", "second-password").toString()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"approved_third_party\",\"password\":\"first-password\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(login(OPENED_USERNAME, "second-password")).isNotBlank();
    }

    private JsonNode executeChange(String token, String operationCode, String idempotencyKey,
                                   JsonNode command) throws Exception {
        var request = objectMapper.createObjectNode().put("operationCode", operationCode)
                .put("idempotencyKey", idempotencyKey).set("command", command);
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
                        .content("{\"comment\":\"研发单人完整审批\"}"))
                .andExpect(status().isOk());
        return json(mockMvc.perform(post("/v1/backoffice/change-requests/{id}/execute", requestId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
    }

    private void assertReviewRequired(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                      String token) throws Exception {
        mockMvc.perform(request.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    private String login() throws Exception {
        return login("admin", "123456");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data").path("token").asText();
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    private void cleanupOpenedAccount() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE username=?", Long.class, OPENED_USERNAME);
        for (Long id : ids) {
            jdbcTemplate.update("DELETE FROM sys_password_setup_token WHERE user_id=?", id);
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id=?", id);
            jdbcTemplate.update("DELETE FROM sys_user_building WHERE user_id=?", id);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", id);
        }
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
