package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.support.TestUserFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PersonnelLifecycleTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestUserFixture userFixture;
    @Autowired private SensitiveChangeService sensitiveChangeService;

    @Test
    void should_logically_delete_restore_and_keep_deleted_username_reserved() throws Exception {
        String adminToken = login("admin", "123456");
        long userId = userFixture.createActiveUser(
                "lifecycle_user", "123456", "ENERGY_MANAGER", "BLD002");
        grantDuty(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grantDuty(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        execute("DELETE_USER_ACCOUNT", userId, "delete-lifecycle-user");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_user\",\"password\":\"123456\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult includeDeleted = mockMvc.perform(get("/system/users")
                        .header("Authorization", bearer(adminToken))
                        .param("includeDeleted", "true")
                        .param("keyword", "lifecycle_user"))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = json(includeDeleted).path("data").path("records");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).path("delFlag").asInt()).isEqualTo(1);
        assertThat(records.get(0).path("password").isMissingNode()).isTrue();

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_user\",\"password\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        execute("RESTORE_USER_ACCOUNT", userId, "restore-lifecycle-user");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_user\",\"password\":\"123456\"}"))
                .andExpect(status().isForbidden());
    }

    private void execute(String operationCode, long userId, String idempotencyKey) {
        var draft = sensitiveChangeService.createDraft(1L, operationCode,
                objectMapper.createObjectNode().put("userId", userId), idempotencyKey);
        sensitiveChangeService.submit(1L, draft.requestId());
        sensitiveChangeService.approve(1L, draft.requestId(), "研发单人完整审批");
        sensitiveChangeService.execute(1L, draft.requestId());
    }

    private void grantDuty(BackendDuty duty) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_user_backend_duty
                WHERE user_id=1 AND duty_key=? AND status='ACTIVE'
                """, Integer.class, duty.name());
        if (count != null && count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO sys_user_backend_duty
                    (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                    VALUES (REPLACE(CAST(RANDOM_UUID() AS VARCHAR),'-',''),1,?,'ACTIVE',CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)
                    """, duty.name());
        }
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        String token = json(result).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) { return "Bearer " + token; }
}
