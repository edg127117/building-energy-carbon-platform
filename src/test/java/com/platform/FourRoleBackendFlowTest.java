package com.platform;

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

import java.util.ArrayList;
import java.util.List;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FourRoleBackendFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    private final List<BackendDuty> insertedDuties = new ArrayList<>();

    @BeforeEach
    void grantBuildingAccessReviewerDuty() {
        insertedDuties.clear();
        grantDuty(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grantDuty(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
    }

    @AfterEach
    void removeBuildingAccessReviewerDuty() {
        for (BackendDuty duty : insertedDuties) {
            jdbcTemplate.update("DELETE FROM sys_user_backend_duty WHERE user_id=1 AND duty_key=?", duty.name());
        }
    }

    @Test
    void should_enforce_role_menu_building_request_and_open_api_boundaries() throws Exception {
        String adminToken = login("admin", "123456");

        mockMvc.perform(delete("/system/users/1").header(auth(), bearer(adminToken)))
                .andExpect(status().isConflict());

        String ownerName = "owner_scope_flow";
        register(ownerName);
        String ownerToken = login(ownerName, "123456");

        JsonNode ownerMenu = json(getWithToken("/menu/current", ownerToken)).path("data");
        assertThat(flattenMenuIds(ownerMenu)).contains(100L, 101L)
                .doesNotContain(200L, 210L, 211L, 212L, 220L, 223L, 240L, 241L);

        JsonNode emptyBuildings = json(getWithToken("/building/list", ownerToken))
                .path("data").path("records");
        assertThat(emptyBuildings).isEmpty();

        mockMvc.perform(get("/system/users").header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());

        MvcResult submitted = mockMvc.perform(post("/building-access/requests")
                        .header(auth(), bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buildingId":"BLD001","reason":"负责一号楼的能效管理"}
                                """))
                .andExpect(status().isOk()).andReturn();
        long requestId = json(submitted).path("data").path("id").asLong();
        assertThat(requestId).isPositive();

        mockMvc.perform(put("/system/building-access/requests/{id}/approve", requestId)
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"资料核验通过\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE source_module='BUILDING_ACCESS' AND review_request_id=?
                  AND action_type='APPROVE_BUILDING_ACCESS'
                """, Integer.class, Long.toString(requestId))).isEqualTo(1);

        JsonNode approvedBuildings = json(getWithToken("/building/list", ownerToken))
                .path("data").path("records");
        assertThat(approvedBuildings).hasSize(1);
        assertThat(approvedBuildings.get(0).path("buildingId").asText()).isEqualTo("BLD001");

        var openingCommand = objectMapper.createObjectNode()
                .put("username", "third_open_flow").put("nickname", "第三方接口账号");
        openingCommand.set("roleKeys", objectMapper.createArrayNode().add("THIRD_PARTY"));
        openingCommand.set("buildingIds", objectMapper.createArrayNode().add("BLD001"));
        JsonNode opening = executeChange(adminToken, "OPEN_USER_ACCOUNT", "four-role-third-opening", openingCommand);
        mockMvc.perform(post("/auth/password/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("token", opening.path("oneTimeToken").asText())
                                .put("password", "123456").toString()))
                .andExpect(status().isOk());
        Long thirdId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username='third_open_flow'", Long.class);
        JsonNode thirdView = json(getWithToken("/system/users/" + thirdId, adminToken)).path("data");
        assertThat(thirdView.path("password").isMissingNode()).isTrue();
        assertThat(thirdView.path("roles").toString()).contains("THIRD_PARTY");

        String thirdToken = login("third_open_flow", "123456");
        assertThat(json(getWithToken("/menu/current", thirdToken)).path("data")).isEmpty();

        mockMvc.perform(get("/building/list").header(auth(), bearer(thirdToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/open-api/buildings/BLD001").header(auth(), bearer(thirdToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/open-api/buildings/BLD002").header(auth(), bearer(thirdToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/" + "device" + "/list")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/" + "telemetry" + "/history")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/" + "control" + "/issue")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"建筑业主"}
                                """.formatted(username)))
                .andExpect(status().isOk());
    }

    private JsonNode executeChange(String token, String operationCode, String idempotencyKey,
                                   JsonNode command) throws Exception {
        var body = objectMapper.createObjectNode().put("operationCode", operationCode)
                .put("idempotencyKey", idempotencyKey).set("command", command);
        String requestId = json(mockMvc.perform(post("/v1/backoffice/change-requests")
                        .header(auth(), bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())).andExpect(status().isOk()).andReturn())
                .path("data").path("requestId").asText();
        mockMvc.perform(post("/v1/backoffice/change-requests/{id}/submit", requestId)
                .header(auth(), bearer(token))).andExpect(status().isOk());
        mockMvc.perform(post("/v1/backoffice/change-requests/{id}/approve", requestId)
                        .header(auth(), bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"研发单人完整审批\"}"))
                .andExpect(status().isOk());
        return json(mockMvc.perform(post("/v1/backoffice/change-requests/{id}/execute", requestId)
                .header(auth(), bearer(token))).andExpect(status().isOk()).andReturn()).path("data");
    }

    private void grantDuty(BackendDuty duty) {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_user_backend_duty
                WHERE user_id=1 AND duty_key=? AND status='ACTIVE'
                """, Integer.class, duty.name());
        if (existing != null && existing == 0) {
            LocalDateTime now = LocalDateTime.now().minusMinutes(1);
            jdbcTemplate.update("""
                    INSERT INTO sys_user_backend_duty
                    (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                    VALUES (?,?,?,'ACTIVE',?,?,?)
                    """, UUID.randomUUID().toString().replace("-", ""), 1L, duty.name(),
                    Timestamp.valueOf(now), 1L, Timestamp.valueOf(now));
            insertedDuties.add(duty);
        }
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        String token = json(result).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private MvcResult getWithToken(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header(auth(), bearer(token)))
                .andExpect(status().isOk()).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> flattenMenuIds(JsonNode nodes) {
        List<Long> ids = new ArrayList<>();
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                ids.add(node.path("id").asLong());
                ids.addAll(flattenMenuIds(node.path("children")));
            }
        }
        return ids;
    }

    private String auth() { return "Authorization"; }
    private String bearer(String token) { return "Bearer " + token; }
}
