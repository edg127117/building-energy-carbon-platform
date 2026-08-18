package com.platform.hvac.asset.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetManagementApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exposesStableAssetViewsReferencesAndOpenApi() throws Exception {
        String token = login("admin", "123456");

        mockMvc.perform(get("/v1/assets/buildings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].buildingId").isString())
                .andExpect(jsonPath("$.data.items[0].references.equipment").isNumber())
                .andExpect(jsonPath("$.data.items[0].allowedActions").isArray());

        mockMvc.perform(get("/v1/assets/equipment")
                        .param("status", "FUTURE_STATUS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        mockMvc.perform(delete("/v1/assets/buildings/BLD001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSET_REFERENCE_CONFLICT"));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(openApi.path("paths").has("/v1/assets/buildings")).isTrue();
        assertThat(openApi.path("paths").has("/v1/assets/equipment/{equipmentId}/points/{pointId}"))
                .isTrue();
        assertThat(openApi.path("components").path("schemas").has("AssetApiError")).isTrue();
    }

    @Test
    void returnsMachineCodesForAnonymousNonAdminValidationAndMissingAssets() throws Exception {
        mockMvc.perform(get("/v1/assets/buildings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ASSET_UNAUTHORIZED"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"asset_contract_owner","password":"123456","nickname":"资产契约业主"}
                                """))
                .andExpect(status().isOk());
        String ownerToken = login("asset_contract_owner", "123456");
        mockMvc.perform(get("/v1/assets/buildings")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ASSET_FORBIDDEN"));

        String adminToken = login("admin", "123456");
        mockMvc.perform(post("/v1/assets/buildings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ASSET_VALIDATION_FAILED"));

        mockMvc.perform(get("/v1/assets/equipment/NOT-FOUND")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ASSET_NOT_FOUND"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
