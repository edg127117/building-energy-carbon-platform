package com.platform.energy.aggregation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
class EnergyAggregationApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void exposesGovernanceDtosOpenApiAndStableSecurityErrors() throws Exception {
        mockMvc.perform(get("/v1/energy-aggregation/meter-event-versions")
                        .param("buildingId", "BLD001").param("pointId", "POINT001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_AGGREGATION_UNAUTHORIZED"));

        String token = login("admin", "123456");
        mockMvc.perform(post("/v1/energy-aggregation/meter-event-versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(resetJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_AGGREGATION_FORBIDDEN"));

        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES ('API_AGGREGATION_MAINTAIN',1,'ENERGY_RULE_MAINTAIN','ACTIVE',CURRENT_TIMESTAMP,1)
                """);
        mockMvc.perform(post("/v1/energy-aggregation/meter-event-versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(resetJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventType").value("RESET"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.simulationFlag").value(true));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/energy-aggregation/simulations")).isTrue();
        assertThat(openApi.path("components").path("schemas")
                .has("EnergyAggregationApiError")).isTrue();
    }

    @Test
    void returnsAggregationValidationCodeForInvalidDto() throws Exception {
        String token = login("admin", "123456");
        mockMvc.perform(post("/v1/energy-aggregation/meter-event-versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("ENERGY_AGGREGATION_VALIDATION_FAILED"));
    }

    private String resetJson() {
        return """
                {"buildingId":"BLD001","meterPointId":"POINT001","eventType":"RESET",
                 "occurredAt":"2026-01-01T00:00:00Z","preEventReading":10,
                 "postEventReading":0,"sourceType":"SIMULATION",
                 "evidenceReference":"API研发模拟复位依据","simulationFlag":true}
                """;
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
