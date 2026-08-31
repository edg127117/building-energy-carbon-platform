package com.platform.iot.energymetadata.api;

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
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EnergyPointProfileApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exposesDtoOptionsContextAndOpenApiWithoutChangingCollectionPeriod() throws Exception {
        String token = login("admin", "123456");
        mockMvc.perform(post("/v1/energy-point-profiles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buildingId":"BLD001","pointId":"POINT004",
                                 "energyType":"ELECTRICITY","energySubtype":"GRID_PURCHASED",
                                 "valueSemantics":"INSTANTANEOUS","reportingPeriod":"MONTH",
                                 "annualSummary":true,"confirmationStatus":"CONFIRMED",
                                 "evidenceReference":"API契约测试依据"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").value("kW"))
                .andExpect(jsonPath("$.data.configRevision").value(0));
        mockMvc.perform(get("/v1/energy-point-profiles")
                        .header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].pointId").value("POINT004"));
        mockMvc.perform(get("/v1/energy-point-profiles/options")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.energyTypes").isArray())
                .andExpect(jsonPath("$.data.electricitySubtypes").isArray());
        mockMvc.perform(get("/v1/energy-point-profiles/collection-context")
                        .header("Authorization", "Bearer " + token)
                        .param("sourceId", "SOURCE_MQTT_FREEZE_V1")
                        .param("aliasId", "ALIAS004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportingPeriod").value("MONTH"))
                .andExpect(jsonPath("$.data.expectedIntervalSeconds").value(60))
                .andExpect(jsonPath("$.data.allowedDelaySeconds").value(30));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/energy-point-profiles/collection-context")).isTrue();
        assertThat(openApi.path("components").path("schemas").has("EnergyMetadataApiError")).isTrue();
    }

    @Test
    void returnsStableAnonymousRoleAndValidationCodes() throws Exception {
        mockMvc.perform(get("/v1/energy-point-profiles/options"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_METADATA_UNAUTHORIZED"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"energy_profile_owner","password":"123456","nickname":"只读用户"}
                                """))
                .andExpect(status().isOk());
        String ownerToken = login("energy_profile_owner", "123456");
        mockMvc.perform(post("/v1/energy-point-profiles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buildingId":"BLD001","pointId":"POINT004",
                                 "energyType":"ELECTRICITY","energySubtype":"GRID_PURCHASED",
                                 "valueSemantics":"INSTANTANEOUS","reportingPeriod":"MONTH",
                                 "annualSummary":true,"confirmationStatus":"CONFIRMED",
                                 "evidenceReference":"角色拒绝测试"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_METADATA_FORBIDDEN"));

        String adminToken = login("admin", "123456");
        mockMvc.perform(post("/v1/energy-point-profiles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_METADATA_VALIDATION_FAILED"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
