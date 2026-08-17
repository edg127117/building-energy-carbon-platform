package com.platform.iot.onboarding.api;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceOnboardingApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exposesVersionedOpenApiStablePageAndMachineErrorCode() throws Exception {
        String adminToken = login("admin", "123456");

        mockMvc.perform(get("/v1/device-products")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.items").isArray());

        mockMvc.perform(get("/v1/device-onboarding/pending/NOT-FOUND")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ONBOARDING_NOT_FOUND"));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(openApi.path("paths").has("/v1/device-products")).isTrue();
        assertThat(openApi.path("paths").has("/v1/device-onboarding/pending/{pendingId}/bind")).isTrue();
        assertThat(openApi.path("components").path("securitySchemes").has("bearerAuth")).isTrue();
        assertThat(openApi.path("components").path("schemas")
                .path("OnboardingApiError").path("properties").has("errorCode")).isTrue();
    }

    @Test
    void rejectsAnonymousAndNonAdminRequestsAtControllerBoundary() throws Exception {
        mockMvc.perform(get("/v1/device-products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ONBOARDING_UNAUTHORIZED"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"btest_owner","password":"123456","nickname":"B测试业主"}
                                """))
                .andExpect(status().isOk());
        String ownerToken = login("btest_owner", "123456");
        mockMvc.perform(get("/v1/device-products")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ONBOARDING_FORBIDDEN"));

        String adminToken = login("admin", "123456");
        mockMvc.perform(post("/v1/device-products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ONBOARDING_VALIDATION_FAILED"));
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
