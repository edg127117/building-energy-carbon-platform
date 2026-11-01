package com.platform.carbon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import com.platform.carbon.CarbonCalculationService;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CarbonManagementApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private TokenCacheService tokenCacheService;
    @MockBean private CarbonCalculationService calculationService;

    @BeforeEach void isolateLoginState() {
        // 契约测试保留 JWT 和角色校验；登录态不与其他进程共享 Redis 白名单。
        when(tokenCacheService.validateActiveToken(anyLong(), anyString()))
                .thenReturn(TokenValidationResult.ACTIVE);
    }

    @Test
    void exposesStableSecurityValidationAndOpenApiContracts() throws Exception {
        mockMvc.perform(post("/v1/carbon-management/calculations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("CARBON_UNAUTHORIZED"));

        String token = login();
        mockMvc.perform(post("/v1/carbon-management/calculations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CARBON_VALIDATION_FAILED"));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has("/v1/carbon-management/calculations")).isTrue();
        assertThat(openApi.path("paths").has(
                "/v1/carbon-management/recalculations/{batchId}/approve")).isTrue();
        assertThat(openApi.path("components").path("schemas").has("CarbonApiError")).isTrue();
        assertThat(openApi.path("paths").has(
                "/v1/carbon-management/electricity-factor-catalog/{entryCode}/import")).isTrue();
        assertThat(openApi.path("components").path("schemas").path("FactorVersionView")
                .path("properties").has("dataYear")).isTrue();
        assertThat(openApi.path("components").path("schemas").path("CalculationItemView")
                .path("properties").has("evidenceUrl")).isTrue();
        assertThat(openApi.path("paths").has(
                "/v1/carbon-management/calculations/{batchId}/items/{itemId}/evidence")).isTrue();
        mockMvc.perform(get("/v1/carbon-management/calculations/unknown/items/unknown/evidence"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/carbon-management/electricity-factor-catalog"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/carbon-management/electricity-factor-catalog")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(114));
        mockMvc.perform(post("/v1/carbon-management/electricity-factor-catalog/unknown/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposesBoundedMonthlyResultReadValidationAndAuthorizationContract() throws Exception {
        String path = "/v1/carbon-management/results/months";
        mockMvc.perform(get(path).param("buildingId", "BLD001").param("months", "2026-10"))
                .andExpect(status().isUnauthorized());

        String token = login();
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD001")
                        .param("months", "2026-10", "2026-09", "2025-10", "2024-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CARBON_VALIDATION_FAILED"));
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD001").param("months", "2026-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CARBON_VALIDATION_FAILED"));

        when(calculationService.monthResults(anyLong(), any(), anyString(), anyList())).thenReturn(List.of(
                new CarbonCalculationService.MonthlyCalculationResult("2026-10",
                        CarbonCalculationService.MonthlyCalculationState.MISSING,
                        "该自然月没有可读取的正式完整结果", List.of(), null)));
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD001").param("months", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].month").value("2026-10"))
                .andExpect(jsonPath("$.data[0].state").value("MISSING"))
                .andExpect(jsonPath("$.data[0].conflictBatchIds.length()").value(0));

        JsonNode openApi = json(mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertThat(openApi.path("paths").has(path)).isTrue();
        assertThat(openApi.path("components").path("schemas").path("MonthlyCalculationResultView")
                .path("properties").has("conflictBatchIds")).isTrue();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
