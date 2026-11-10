package com.platform.energy.reporting;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.carbon.AnnualOverviewService;
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
class AnnualOverviewApiContractTest {
    private static final String PATH = "/v1/energy-reporting/buildings/BLD001/annual-overview";
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean AnnualOverviewService service;

    @BeforeEach void loginState() {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
    }

    @Test void requiresLoginAndAllowedRoleAndPreservesBuildingScopeFailure() throws Exception {
        mvc.perform(get(PATH).param("year", "2026").param("mode", "MONTHLY_YTD").param("throughMonth", "9")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("year", "2026").param("mode", "MONTHLY_YTD").param("throughMonth", "9").header("Authorization", bearer("THIRD_PARTY"))).andExpect(status().isForbidden());
        when(service.overview(eq(1L), anyCollection(), eq("BLD001"), eq(2026), eq("MONTHLY_YTD"), eq(9), anyString()))
                .thenThrow(new BusinessException(403, "ANNUAL_SCOPE_FORBIDDEN", "建筑无权限"));
        mvc.perform(get(PATH).param("year", "2026").param("mode", "MONTHLY_YTD").param("throughMonth", "9").header("Authorization", bearer("BUILDING_OWNER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    @Test void rejectsMalformedQueryAndSerializesFrozenDecimalStrings() throws Exception {
        var metric=new AnnualOverviewService.Metric("TOTAL_EMISSION","tCO2e",BigDecimal.TEN,"AVAILABLE",List.of(),List.of());
        var period=new AnnualOverviewService.Period(LocalDate.of(2026,1,1),LocalDate.of(2026,10,1),null,null,List.of(metric),null,"AREA_GAP",List.of());
        var response=new AnnualOverviewService.Response("BLD001",2026,"MONTHLY_YTD",9,"Asia/Shanghai","v1","hash",period,period,List.of());
        when(service.overview(eq(1L),anyCollection(),eq("BLD001"),eq(2026),eq("MONTHLY_YTD"),eq(9),eq("Asia/Shanghai"))).thenReturn(response);
        mvc.perform(get(PATH).param("year","2026").param("mode","MONTHLY_YTD").param("throughMonth","9").header("Authorization",bearer("BUILDING_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current.metrics[0].value").value("10"));
        mvc.perform(get(PATH).param("year","invalid").param("mode","MONTHLY_YTD").header("Authorization",bearer("BUILDING_OWNER")))
                .andExpect(status().isBadRequest());
    }

    private String bearer(String role) { return "Bearer " + jwt.generateToken(1L, "monthly-reader", List.of(role), 1L); }
}
