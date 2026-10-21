package com.platform.carbon;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CarbonClassificationReportApiContractTest {
    private static final String CALCULATIONS = "/v1/carbon-management/calculations";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockBean private TokenCacheService tokenCacheService;
    @MockBean private CarbonCalculationService calculationService;
    @MockBean private CarbonClassificationReportService reportService;

    @BeforeEach
    void isolateLoginState() {
        when(tokenCacheService.validateActiveToken(anyLong(), anyString()))
                .thenReturn(TokenValidationResult.ACTIVE);
    }

    @Test
    void protectsCalculationListByLoginRoleAndBuildingScope() throws Exception {
        when(calculationService.list(eq(1L), eq(Set.of("ENERGY_MANAGER")), eq("BLD002")))
                .thenThrow(forbiddenBuilding());
        when(calculationService.list(eq(1L), eq(Set.of("PLATFORM_ADMIN")), eq("BLD001")))
                .thenReturn(List.of());

        mockMvc.perform(get(CALCULATIONS).param("buildingId", "BLD001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("CARBON_UNAUTHORIZED"));

        mockMvc.perform(get(CALCULATIONS).param("buildingId", "BLD001")
                        .header("Authorization", bearer("THIRD_PARTY")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CARBON_FORBIDDEN"));

        mockMvc.perform(get(CALCULATIONS).param("buildingId", "BLD002")
                        .header("Authorization", bearer("ENERGY_MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.errorCode").value("CARBON_FORBIDDEN"));
        verify(calculationService).list(1L, Set.of("ENERGY_MANAGER"), "BLD002");

        mockMvc.perform(get(CALCULATIONS).param("buildingId", "BLD001")
                        .header("Authorization", bearer("PLATFORM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        verify(calculationService).list(1L, Set.of("PLATFORM_ADMIN"), "BLD001");
    }

    @Test
    void exposesClassificationReportRouteAndKeepsMissingBatchAsCarbonNotFound() throws Exception {
        when(reportService.report(eq(1L), eq(Set.of("PLATFORM_ADMIN")), eq("unknown-batch")))
                .thenThrow(new BusinessException(404, CarbonErrors.NOT_FOUND, "碳计算批次不存在"));

        mockMvc.perform(get(CALCULATIONS + "/unknown-batch/classification-report")
                        .header("Authorization", bearer("PLATFORM_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.errorCode").value("CARBON_NOT_FOUND"));
        verify(reportService).report(1L, Set.of("PLATFORM_ADMIN"), "unknown-batch");
    }

    private String bearer(String role) {
        return "Bearer " + jwtService.generateToken(1L, "contract-user", List.of(role), 1L);
    }

    private static BusinessException forbiddenBuilding() {
        return new BusinessException(403, CarbonErrors.FORBIDDEN, "无权访问该建筑");
    }
}
