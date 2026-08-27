package com.platform.relation.api;

import com.platform.support.TestUserFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 关系治理 HTTP 路由、OpenAPI 和四角色入口的稳定契约。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RelationGovernanceApiContractTest {
    private static final String BUILDING_ID = "BLD002";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestUserFixture userFixture;

    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private String thirdPartyToken;

    @BeforeAll
    void createRoleUsers() throws Exception {
        adminToken = login("admin", "123456");
        String suffix = Long.toUnsignedString(System.nanoTime());
        ownerToken = createAndLogin("relation_owner_" + suffix, "BUILDING_OWNER");
        managerToken = createAndLogin("relation_manager_" + suffix, "ENERGY_MANAGER");
        thirdPartyToken = createAndLogin("relation_third_" + suffix, "THIRD_PARTY");
    }

    @Test
    void exposesKeyManagementAndQueryRoutesWithRelationErrorSchema() throws Exception {
        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode paths = openApi.path("paths");
        assertThat(paths.has("/v1/relation-models/{buildingId}/initialize")).isTrue();
        assertThat(paths.has("/v1/relation-models/{buildingId}/versions/{versionId}")).isTrue();
        assertThat(paths.has("/v1/relation-models/{buildingId}/versions/{versionId}/diff")).isTrue();
        assertThat(paths.path("/v1/relation-models/versions/{versionId}/relations/{relationItemId}")
                .has("put")).isTrue();
        assertThat(paths.path(
                "/v1/relation-models/versions/{versionId}/metering/boundaries/{boundaryId}")
                .has("put")).isTrue();
        assertThat(paths.path(
                "/v1/relation-models/versions/{versionId}/metering/boundaries/{boundaryId}")
                .has("delete")).isTrue();
        assertThat(paths.path(
                "/v1/relation-models/versions/{versionId}/metering/assignments/{assignmentId}")
                .has("put")).isTrue();
        assertThat(paths.path(
                "/v1/relation-models/versions/{versionId}/metering/assignments/{assignmentId}")
                .has("delete")).isTrue();
        assertThat(paths.has("/v1/relation-models/versions/{versionId}/submit")).isTrue();
        assertThat(paths.has("/v1/relation-models/review-requests/{requestId}/approve")).isTrue();
        assertThat(paths.has("/v1/relation-models/versions/{versionId}/activate")).isTrue();
        assertThat(paths.has("/v1/relation-models/{buildingId}/effective/space-tree")).isTrue();
        assertThat(paths.has(
                "/v1/relation-models/{buildingId}/effective/nodes/{nodeType}/{nodeId}/context"))
                .isTrue();
        assertThat(paths.has(
                "/v1/relation-models/{buildingId}/versions/{versionId}/query/nodes/{nodeType}/{nodeId}/context"))
                .isTrue();
        assertThat(paths.has("/v1/relation-models/{buildingId}/effective/metering-boundaries"))
                .isTrue();
        assertThat(openApi.path("components").path("schemas").has("RelationApiError")).isTrue();
    }

    @Test
    void enforcesFourRoleEntrancesAndStableUnauthorizedForbiddenCodes() throws Exception {
        mockMvc.perform(get("/v1/relation-models").param("buildingId", BUILDING_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("RELATION_UNAUTHORIZED"));

        mockMvc.perform(post("/v1/relation-models/{buildingId}/initialize", BUILDING_ID)
                        .header("Authorization", bearer(managerToken))
                        .header("Idempotency-Key", "relation-api-init-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"API 角色入口初始化\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buildingId").value(BUILDING_ID))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(get("/v1/relation-models").param("buildingId", BUILDING_ID)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RELATION_NOT_FOUND"));

        mockMvc.perform(post("/v1/relation-models/review-requests/{requestId}/approve", "missing-review")
                        .header("Authorization", bearer(adminToken))
                        .header("Idempotency-Key", "relation-api-approve-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"确认管理员入口\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RELATION_NOT_FOUND"));

        mockMvc.perform(post("/v1/relation-models/{buildingId}/initialize", BUILDING_ID)
                        .header("Authorization", bearer(ownerToken))
                        .header("Idempotency-Key", "relation-api-owner-forbidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"业主不得初始化\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RELATION_FORBIDDEN"));

        mockMvc.perform(post("/v1/relation-models/review-requests/{requestId}/approve", "missing-review")
                        .header("Authorization", bearer(managerToken))
                        .header("Idempotency-Key", "relation-api-manager-forbidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"能源管理员不得审核\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RELATION_FORBIDDEN"));

        mockMvc.perform(get("/v1/relation-models").param("buildingId", BUILDING_ID)
                        .header("Authorization", bearer(thirdPartyToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RELATION_FORBIDDEN"));
    }

    private String createAndLogin(String username, String role) throws Exception {
        userFixture.createActiveUser(username, "123456", role, BUILDING_ID);
        return login(username, "123456");
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

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
