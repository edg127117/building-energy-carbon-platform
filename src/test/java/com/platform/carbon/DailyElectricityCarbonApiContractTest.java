package com.platform.carbon;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.energy.classification.BuildingDailyElectricityReader;
import com.platform.energy.classification.ClassificationContracts.QueryView;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 Controller、授权与只读服务，隔离存储和现场来源。 */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class DailyElectricityCarbonApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired com.platform.security.JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean BuildingScopeService scopes;
    @MockBean BuildingDailyElectricityReader electricity;
    @MockBean HourlyCarbonRepository hourly;
    @MockBean DailyTrendSummaryRepository summaries;
    @MockBean DailyTrendSummaryCache resultCache;
    @BeforeEach void setup() {
        when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(electricity.read(anyLong(),anyCollection(),any(),any())).thenAnswer(c->{
            LocalDate date=c.getArgument(3);var from=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            return new QueryView("BLD001","cv","rv","KWH",from,from.plusSeconds(86400),new BigDecimal("12.3400"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"CALCULABLE","SOURCE_LABELLED","BOUNDARY_TOTAL",true,List.of(),List.of(),List.of(),List.of("reading"));
        });
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return request->{request.addHeader("Authorization","Bearer "+jwt.generateToken(1L,"isolated",List.of(role),1L));return request;};
    }
    @Test void dailyRouteRequiresAuthenticationRoleAndBuildingScope() throws Exception {
        String path="/v1/energy-reporting/buildings/BLD001/daily-trends";
        mvc.perform(get(path).param("month","2024-02")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(role("THIRD_PARTY")).param("month","2024-02")).andExpect(status().isForbidden());
        doThrow(new com.platform.framework.exception.BusinessException(403,"无建筑权限")).when(scopes).checkAccess(eq(1L),anyCollection(),eq("BLD001"));
        mvc.perform(get(path).with(role("BUILDING_OWNER")).param("month","2024-02")).andExpect(status().isForbidden());
        verifyNoInteractions(electricity,hourly);
    }
    @Test void sendsDecimalStringsExactCalendarAndIndependentMissingCarbonWithoutCalculating() throws Exception {
        String path="/v1/energy-reporting/buildings/BLD001/daily-trends";
        mvc.perform(get(path).with(role("ENERGY_MANAGER")).param("month","2024-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days.length()").value(29))
                .andExpect(jsonPath("$.data.days[0].electricityKwh").value("12.3400"))
                .andExpect(jsonPath("$.data.days[0].startInclusive").value("2024-01-31T16:00:00Z"))
                .andExpect(jsonPath("$.data.days[28].endExclusive").value("2024-02-29T16:00:00Z"))
                .andExpect(jsonPath("$.data.days[0].staticCarbonStatus").value("HOURLY_RESULT_MISSING"))
                .andExpect(jsonPath("$.data.resultNature").value("MONITORING"));
        verify(hourly,never()).publish(anyLong(),any(),any(),any(),any());
        mvc.perform(get(path).with(role("ENERGY_MANAGER")).param("month","2024-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("DAILY_TREND_QUERY_INVALID"));
    }
    @Test void summaryRouteRetainsRoleAndBuildingBoundaryBeforeCacheRead() throws Exception {
        String path="/v1/energy-reporting/buildings/BLD001/daily-trends/summary";
        mvc.perform(get(path).param("month","2026-01")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(role("THIRD_PARTY")).param("month","2026-01")).andExpect(status().isForbidden());
        doThrow(new com.platform.framework.exception.BusinessException(403,"无建筑权限")).when(scopes).checkAccess(eq(1L),anyCollection(),eq("BLD001"));
        mvc.perform(get(path).with(role("BUILDING_OWNER")).param("month","2026-01")).andExpect(status().isForbidden());
        verifyNoInteractions(summaries,resultCache,electricity,hourly);
    }
    @Test void summaryFirstReadReturnsPreparationInsteadOfDoingInlineCalculation() throws Exception {
        when(summaries.sourceVersion(eq("BLD001"),any())).thenReturn("a".repeat(64));
        mvc.perform(get("/v1/energy-reporting/buildings/BLD001/daily-trends/summary").with(role("ENERGY_MANAGER")).param("month","2026-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PREPARING"))
                .andExpect(jsonPath("$.data.days.length()").value(0)).andExpect(jsonPath("$.data.retryAfterSeconds").value(2))
                .andExpect(jsonPath("$.data.contractVersion").value(DailyTrendSummaryContracts.CONTRACT));
        verify(summaries).request(eq("BLD001"),eq("2026-01"),eq(1L),eq("a".repeat(64)),anyLong());
        verifyNoInteractions(electricity,hourly,resultCache);
    }
}
