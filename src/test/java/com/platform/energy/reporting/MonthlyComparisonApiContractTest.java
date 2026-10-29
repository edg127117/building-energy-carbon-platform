package com.platform.energy.reporting;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.carbon.MonthlyComparisonService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MonthlyComparisonApiContractTest {
    private static final String PATH = "/v1/energy-reporting/buildings/BLD001/monthly-comparison";
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean MonthlyComparisonService service;

    @BeforeEach void loginState() {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
    }

    @Test void requiresLoginAndAllowedRoleAndPreservesBuildingScopeFailure() throws Exception {
        mvc.perform(get(PATH).param("month", "2026-09-01")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("month", "2026-09-01").header("Authorization", bearer("THIRD_PARTY"))).andExpect(status().isForbidden());
        when(service.compare(eq(1L), anyCollection(), eq("BLD001"), any(), anyString()))
                .thenThrow(new BusinessException(403, "MONTHLY_SCOPE_FORBIDDEN", "建筑无权限"));
        mvc.perform(get(PATH).param("month", "2026-09-01").header("Authorization", bearer("BUILDING_OWNER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    @Test void returnsDecimalStringsAndNullPercentageWithoutRecalculatingInController() throws Exception {
        var zero = new MonthlyComparisonService.Change(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, "BASE_ZERO");
        var response = new MonthlyComparisonService.Response("BLD001", "Asia/Shanghai", null, null, null,
                List.of(new MonthlyComparisonService.Comparison("ELECTRICITY_KWH", "kWh", zero, zero)));
        when(service.compare(eq(1L), anyCollection(), eq("BLD001"), eq(LocalDate.of(2026, 9, 1)), eq("Asia/Shanghai"))).thenReturn(response);
        mvc.perform(get(PATH).param("month", "2026-09-01").header("Authorization", bearer("PLATFORM_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.buildingId").value("BLD001"))
                .andExpect(jsonPath("$.data.comparisons[0].monthOnMonth.currentValue").value("10"))
                .andExpect(jsonPath("$.data.comparisons[0].monthOnMonth.percentageChange").doesNotExist())
                .andExpect(jsonPath("$.data.comparisons[0].monthOnMonth.status").value("BASE_ZERO"));
        mvc.perform(get(PATH).param("month", "2026-09").header("Authorization", bearer("PLATFORM_ADMIN")))
                .andExpect(status().isBadRequest());
    }

    private String bearer(String role) { return "Bearer " + jwt.generateToken(1L, "monthly-reader", List.of(role), 1L); }
}
