package com.platform.iot.qualityusage.governance.api;

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

/** 只验证治理 HTTP 契约与 OpenAPI 路由，不依赖前端或真实外部资源。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QualityUsageGovernanceApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exposesVersionedRoutesStablePagesAndOpenApi() throws Exception {
        String token = login("admin", "123456");
        mockMvc.perform(get("/v1/quality-usage/policies/active")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items").isArray());
        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/quality-usage/change-sets/{changeSetId}/direct-publish"))
                .isTrue();
        assertThat(openApi.path("paths").has("/v1/quality-usage/review-requests/{requestId}/approve"))
                .isTrue();
    }

    @Test
    void oldDirectPublicationIsStablyRejectedAndApprovalRequiresComment() throws Exception {
        String token = login("admin", "123456");
        mockMvc.perform(post("/v1/quality-usage/change-sets/NOT_FOUND/direct-publish")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "api-direct-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"绕过审核发布\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.traceId").isString());

        mockMvc.perform(post("/v1/quality-usage/review-requests/NOT_FOUND/approve")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "api-blank-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isString());
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
