package com.platform;

import com.platform.support.TestUserFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 典型值管理 API 的四角色和建筑范围流程测试。
 *
 * <p>测试使用 H2 验证权限和分页 SQL，确保普通角色的建筑过滤发生在数据库查询阶段，
 * 管理接口不会把其他建筑的配置或数据库实体直接返回给调用方。</p>
 */
@SpringBootTest(properties = {
        "data-quality.enabled=true",
        "data-quality.typical-config-refresh-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataQualityTypicalValueControllerFlowTest {

    private static final long VALID_FROM = Instant.parse("2026-07-29T00:00:00Z").toEpochMilli();
    private static final long VALID_TO = Instant.parse("2026-07-30T00:00:00Z").toEpochMilli();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestUserFixture userFixture;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private String thirdPartyToken;

    @BeforeEach
    void setUpRolesAndBuildingScopes() throws Exception {
        jdbcTemplate.update("DELETE FROM biz_point_typical_value_config");
        adminToken = login("admin", "123456");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ownerToken = createUserAndLogin(
                "typical_owner_" + suffix, "BUILDING_OWNER", "BLD001");
        managerToken = createUserAndLogin(
                "typical_manager_" + suffix, "ENERGY_MANAGER", "BLD001");
        thirdPartyToken = createUserAndLogin(
                "typical_third_" + suffix, "THIRD_PARTY", "BLD001");
        jdbcTemplate.update("UPDATE biz_data_point SET is_for_calc = 1 WHERE point_id = 'POINT020'");
    }

    @Test
    void shouldEnforceFourRolesAndBuildingScopedStablePagination() throws Exception {
        String firstConfigId = create(managerToken, "POINT001", "10.5", VALID_FROM, VALID_TO);
        String secondBuildingConfigId =
                create(adminToken, "POINT020", "11.5", VALID_FROM, VALID_TO);

        JsonNode ownerPage = json(mockMvc.perform(get("/iot/data-quality/typical-values")
                        .header(auth(), bearer(ownerToken))
                        .param("pageNum", "1")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(ownerPage.path("total").asLong()).isEqualTo(1);
        assertThat(ownerPage.path("records")).hasSize(1);
        assertThat(ownerPage.path("records").get(0).path("buildingId").asText())
                .isEqualTo("BLD001");
        assertThat(ownerPage.path("records").get(0).has("serialVersionUID")).isFalse();

        mockMvc.perform(post("/iot/data-quality/typical-values")
                        .header(auth(), bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("POINT001", "12.0", VALID_FROM, VALID_TO)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/iot/data-quality/typical-values")
                        .header(auth(), bearer(thirdPartyToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("POINT001", "12.0", VALID_FROM, VALID_TO)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/iot/data-quality/typical-values")
                        .header(auth(), bearer(thirdPartyToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/iot/data-quality/typical-values")
                        .header(auth(), bearer(managerToken))
                        .param("buildingId", "BLD002"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                                "/iot/data-quality/typical-values/{configId}",
                                secondBuildingConfigId)
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(
                                "/iot/data-quality/typical-values/{configId}",
                                secondBuildingConfigId)
                        .header(auth(), bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typicalValue":11.6,"sourceDescription":"越权修改",
                                 "reason":"不应泄露状态","validFrom":%d,"validTo":%d}
                                """.formatted(VALID_FROM, VALID_TO)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                                "/iot/data-quality/typical-values/{configId}/submit",
                                secondBuildingConfigId)
                        .header(auth(), bearer(managerToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/iot/data-quality/typical-values/{configId}", firstConfigId)
                        .header(auth(), bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typicalValue":10.6,"sourceDescription":"复核后的历史样本",
                                 "reason":"传感器长时缺失","validFrom":%d,"validTo":%d}
                                """.formatted(VALID_FROM, VALID_TO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.typicalValue").value(10.6));
        mockMvc.perform(post("/iot/data-quality/typical-values")
                        .header(auth(), bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("POINT020", "12.0", VALID_FROM, VALID_TO)))
                .andExpect(status().isForbidden());

        JsonNode adminPage = json(mockMvc.perform(get("/iot/data-quality/typical-values")
                        .header(auth(), bearer(adminToken))
                        .param("pageNum", "1")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(adminPage.path("total").asLong()).isEqualTo(2);
        assertThat(adminPage.path("records")).hasSize(1);
        JsonNode repeatedAdminPage = json(mockMvc.perform(get("/iot/data-quality/typical-values")
                        .header(auth(), bearer(adminToken))
                        .param("pageNum", "1")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(repeatedAdminPage.path("records").get(0).path("configId").asText())
                .isEqualTo(adminPage.path("records").get(0).path("configId").asText());

        JsonNode detail = json(mockMvc.perform(get(
                                "/iot/data-quality/typical-values/{configId}", firstConfigId)
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(detail.path("configId").asText()).isEqualTo(firstConfigId);
        assertThat(detail.path("validFrom").asLong()).isEqualTo(VALID_FROM);
    }

    @Test
    void shouldValidateTimeAndEnforceApprovalStateMachine() throws Exception {
        mockMvc.perform(get("/iot/data-quality/typical-values")
                        .header(auth(), bearer(managerToken))
                        .param("pageNum", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/iot/data-quality/typical-values")
                        .header(auth(), bearer(managerToken))
                        .param("buildingId", "B".repeat(33)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/iot/data-quality/typical-values")
                        .header(auth(), bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("POINT001", "10.5", VALID_TO, VALID_FROM)))
                .andExpect(status().isBadRequest());

        String approvedId = create(managerToken, "POINT001", "10.5", VALID_FROM, VALID_TO);
        submit(managerToken, approvedId);
        review(adminToken, approvedId, "approve", "{\"comment\":\"依据完整\"}", 200);

        String overlapId = create(
                managerToken, "POINT001", "10.8", VALID_FROM + 60_000, VALID_TO + 60_000);
        submit(managerToken, overlapId);
        review(adminToken, overlapId, "approve", "{\"comment\":\"复核\"}", 409);

        String draftId = create(managerToken, "POINT001", "9.5", VALID_TO, VALID_TO + 86_400_000);
        review(adminToken, draftId, "approve", "{\"comment\":\"状态不符\"}", 409);

        String selfCreated = create(
                adminToken, "POINT001", "9.0", VALID_TO + 86_400_000, VALID_TO + 172_800_000);
        submit(adminToken, selfCreated);
        review(adminToken, selfCreated, "approve", "{\"comment\":\"自审\"}", 409);

        String rejectedId = create(
                managerToken, "POINT001", "8.5", VALID_TO + 172_800_000, VALID_TO + 259_200_000);
        submit(managerToken, rejectedId);
        review(adminToken, rejectedId, "reject", "{\"comment\":\"来源依据不足\"}", 200);

        mockMvc.perform(post(
                                "/iot/data-quality/typical-values/{configId}/disable", approvedId)
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"批准了新的修订版本\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    private String createUserAndLogin(String username, String role, String buildingId)
            throws Exception {
        userFixture.createActiveUser(username, "123456", role, buildingId);
        return login(username, "123456");
    }

    private String create(
            String token, String pointId, String value, long validFrom, long validTo)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/iot/data-quality/typical-values")
                        .header(auth(), bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(pointId, value, validFrom, validTo)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("data").path("configId").asText();
    }

    private void submit(String token, String configId) throws Exception {
        mockMvc.perform(post("/iot/data-quality/typical-values/{configId}/submit", configId)
                        .header(auth(), bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    private void review(
            String token, String configId, String action, String body, int expectedStatus)
            throws Exception {
        mockMvc.perform(post(
                                "/iot/data-quality/typical-values/{configId}/{action}",
                                configId,
                                action)
                        .header(auth(), bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private String createBody(
            String pointId, String value, long validFrom, long validTo) {
        return """
                {"pointId":"%s","typicalValue":%s,
                 "sourceDescription":"经校准历史样本","reason":"传感器长时缺失",
                 "validFrom":%d,"validTo":%d}
                """.formatted(pointId, value, validFrom, validTo);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        String token = json(result).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String auth() {
        return "Authorization";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
