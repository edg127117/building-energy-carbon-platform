package com.platform.energy.activity;

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
class EnergyActivityDataApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EnergyActivityPointCatalog pointCatalog;

    @Test
    void pointCatalogPreservesMissingProfileForFailClosedGate() {
        var points = pointCatalog.find("BLD001", java.util.List.of("POINT004"));

        assertThat(points).singleElement().satisfies(point -> {
            assertThat(point.pointId()).isEqualTo("POINT004");
            assertThat(point.unit()).isEqualTo("kW");
            assertThat(point.profileId()).isNull();
        });
    }

    @Test
    void publishesRawEventContractAndStableFrameworkErrorCodes() throws Exception {
        mockMvc.perform(get("/v1/energy-activity-data/raw-events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_ACTIVITY_UNAUTHORIZED"));

        String token = login("admin", "123456");
        mockMvc.perform(get("/v1/energy-activity-data/raw-events")
                        .header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD001")
                        .param("pointIds", "POINT001")
                        .param("fromInclusive", "60000")
                        .param("toExclusive", "120000")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("ENERGY_ACTIVITY_VALIDATION_FAILED"));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths")
                .has("/v1/energy-activity-data/raw-events")).isTrue();
        assertThat(openApi.path("components").path("schemas")
                .has("EnergyActivityApiError")).isTrue();
        assertThat(openApi.path("components").path("schemas")
                .has("RawActivityDataPage")).isTrue();
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
