package com.platform.energy.catalog.api;

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
class EnergyCatalogApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void exposesCatalogDtosOpenApiAndStableSecurityErrors() throws Exception {
        mockMvc.perform(get("/v1/energy-catalog/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_CATALOG_UNAUTHORIZED"));

        String token = login("admin", "123456");
        mockMvc.perform(post("/v1/energy-catalog/item-versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson("API_TEST_FUEL")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_CATALOG_FORBIDDEN"));

        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES ('API_ENERGY_MAINTAIN',1,'ENERGY_CATALOG_MAINTAIN','ACTIVE',CURRENT_TIMESTAMP,1)
                """);
        mockMvc.perform(post("/v1/energy-catalog/item-versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson("API_TEST_FUEL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemCode").value("API_TEST_FUEL"))
                .andExpect(jsonPath("$.data.status").value("PENDING_EXPERT"))
                .andExpect(jsonPath("$.data.configRevision").value(0));
        mockMvc.perform(get("/v1/energy-catalog/options")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dimensionCodes").isArray())
                .andExpect(jsonPath("$.data.usageScopes").isArray());

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/energy-catalog/point-bindings/effective")).isTrue();
        assertThat(openApi.path("components").path("schemas").has("EnergyCatalogApiError")).isTrue();
    }

    @Test
    void returnsCatalogValidationCodeForInvalidDto() throws Exception {
        String token = login("admin", "123456");
        mockMvc.perform(post("/v1/energy-catalog/item-versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_CATALOG_VALIDATION_FAILED"));
    }

    private String itemJson(String code) {
        return """
                {"itemCode":"%s","itemName":"API测试燃料","compatibleCategory":"FUEL",
                 "usageScopes":["STATIONARY_COMBUSTION"],"sourceType":"MANUAL",
                 "sourceReference":"API研发模拟依据","effectiveFrom":"2026-01-01T00:00:00"}
                """.formatted(code);
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
