package com.platform.iot.collection.api;

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
class CollectionPolicyApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exposesStablePagesMachineCodesAndOpenApi() throws Exception {
        String adminToken = login("admin", "123456");
        mockMvc.perform(get("/v1/data-sources")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].runtimeApplyStatus").isString())
                .andExpect(jsonPath("$.data.items[0].allowedActions").isArray());
        mockMvc.perform(get("/v1/data-sources/NOT_FOUND")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COLLECTION_CONFIG_NOT_FOUND"));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/data-sources/{sourceId}/runtime-refresh")).isTrue();
        assertThat(openApi.path("paths").has(
                "/v1/collection-policies/{policyId}/versions/{versionId}/publish")).isTrue();
        assertThat(openApi.path("components").path("schemas").has("CollectionApiError")).isTrue();
    }

    @Test
    void returnsCollectionCodesForAnonymousRoleAndValidationFailures() throws Exception {
        mockMvc.perform(get("/v1/data-sources"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("COLLECTION_CONFIG_UNAUTHORIZED"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"collection_owner","password":"123456","nickname":"采集只读用户"}
                                """))
                .andExpect(status().isOk());
        String ownerToken = login("collection_owner", "123456");
        mockMvc.perform(post("/v1/data-sources")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceCode":"MQTT_OWNER_FORBIDDEN","sourceName":"越权来源",
                                 "buildingId":"BLD001","transportType":"MQTT","changeReason":"权限测试"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COLLECTION_CONFIG_FORBIDDEN"));

        String adminToken = login("admin", "123456");
        mockMvc.perform(post("/v1/data-sources")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COLLECTION_CONFIG_VALIDATION_FAILED"));
    }

    @Test
    void oldDirectPublicationPathsRequireDomainReviewAndRoleAloneCannotSubmit() throws Exception {
        String adminToken = login("admin", "123456");
        JsonNode source = json(mockMvc.perform(post("/v1/data-sources")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceCode":"MQTT_API_REVIEW_ONLY","sourceName":"审核收口来源",
                                 "buildingId":"BLD001","transportType":"MQTT","changeReason":"契约测试"}
                                """))
                .andExpect(status().isOk()).andReturn()).path("data");
        String sourceId = source.path("sourceId").asText();
        JsonNode alias = json(mockMvc.perform(post("/v1/data-sources/" + sourceId + "/aliases")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourcePointCode":"API_REVIEW_ONLY","pointId":"POINT001",
                                 "initialPolicy":{"expectedIntervalSeconds":75,"allowedDelaySeconds":12,
                                 "rawRetentionMode":"FIXED_DAYS","rawRetentionDays":30,
                                 "minuteRetentionMode":"LONG_TERM","enabled":true},
                                 "changeReason":"契约测试"}
                                """))
                .andExpect(status().isOk()).andReturn()).path("data");

        assertReviewRequired(post("/v1/data-sources/" + sourceId + "/enable")
                .content("{\"reason\":\"绕过审核首启\"}"), adminToken);
        assertReviewRequired(post("/v1/data-sources/" + sourceId + "/aliases/"
                + alias.path("aliasId").asText() + "/enable")
                .content("{\"reason\":\"绕过审核启用\"}"), adminToken);
        assertReviewRequired(post("/v1/collection-policies/" + alias.path("policyId").asText()
                + "/versions/" + alias.path("draftVersionId").asText() + "/publish")
                .content("{\"comment\":\"绕过审核发布\"}"), adminToken);

        mockMvc.perform(post("/v1/data-sources/" + sourceId + "/submit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"仅凭平台管理员角色提交\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_DUTY_REQUIRED"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    private void assertReviewRequired(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token) throws Exception {
        mockMvc.perform(request.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_REVIEW_REQUIRED"))
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
