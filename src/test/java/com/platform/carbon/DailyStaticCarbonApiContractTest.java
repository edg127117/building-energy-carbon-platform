package com.platform.carbon;

import com.platform.cache.*;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller, authentication and application service; external data ports are test doubles. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class DailyStaticCarbonApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired com.platform.security.JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean BuildingScopeService scopes;
    @MockBean com.platform.audit.BackendDutyService duties;
    @MockBean DailyStaticCarbonRepository repository;
    @MockBean DailyStaticCarbonInput inputs;
    @MockBean HourlyCarbonRuleService rules;
    @MockBean DailyTrendSummaryRepository versions;
    static final LocalDate DATE=LocalDate.of(2026,9,1);
    static final Instant START=Instant.parse("2026-08-31T16:00:00Z");
    static final String REQUEST="""
            {"buildingId":"BLD001","date":"2026-09-01","calculationAsOf":"2026-09-01T16:00:00Z",
             "idempotencyKey":"day-key","declaredSourceNature":"SIMULATED","sourceReference":"reviewed DAY batch","factorUsageNature":"FORMAL"}
            """;
    @BeforeEach void setup() {
        when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(duties.hasDuty(1L,com.platform.audit.BackendDuty.CARBON_CALCULATION_RUN)).thenReturn(true);
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return request->{request.addHeader("Authorization","Bearer "+jwt.generateToken(1L,"isolated",List.of(role),1L));return request;};
    }
    static DailyStaticCarbonRepository.Stored stored() {
        var v=new DailyStaticCarbonContracts.View("BLD001",DATE,START,START.plusSeconds(86400),"Asia/Shanghai","DAY","KG_CO2E",
                "PURCHASED_ELECTRICITY_SCOPE_2","MONITORING","COMPLETE","REVIEWED_INTERVAL","CURRENT_APPROVED_AT_CALCULATION","SIMULATED","SOURCE_LABELLED_STATISTICS","FORMAL",
                "DAYBATCH",1,START.plusSeconds(86400),"a".repeat(64),"C","R","P",new BigDecimal("100.00"),new BigDecimal("56.00"),
                new BigDecimal("0.56"),"AVAILABLE","CO2_ONLY",List.of("F"),"b".repeat(64),"c".repeat(64));
        return new DailyStaticCarbonRepository.Stored(v,CarbonEvidence.object().put("periodType","DAY"));
    }
    @Test void calculateRequiresRunnerRoleAndBuildingScope() throws Exception {
        String path="/v1/carbon-management/daily-static-calculations";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(role("BUILDING_OWNER")).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
        doThrow(new com.platform.framework.exception.BusinessException(403,"denied")).when(scopes).checkAccess(eq(1L),anyCollection(),eq("BLD001"));
        mvc.perform(post(path).with(role("ENERGY_MANAGER")).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
        verify(scopes).checkAccess(eq(1L),anyCollection(),eq("BLD001"));
        verifyNoInteractions(repository,inputs,rules);
    }
    @Test void normalPostReplaysFrozenDayContractWithoutAnyHourFacts() throws Exception {
        when(repository.replay(eq("BLD001"),eq("day-key"),anyString())).thenReturn(stored());
        mvc.perform(post("/v1/carbon-management/daily-static-calculations").with(role("ENERGY_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.periodType").value("DAY"))
                .andExpect(jsonPath("$.data.electricityKwh").value("100.00"))
                .andExpect(jsonPath("$.data.declaredSourceNature").value("SIMULATED"))
                .andExpect(jsonPath("$.data.resultNature").value("MONITORING"))
                .andExpect(jsonPath("$.data.electricitySourceNature").value("SOURCE_LABELLED_STATISTICS"))
                .andExpect(jsonPath("$.data.points").doesNotExist());
        verifyNoInteractions(inputs,rules);
    }
    @Test void readAndEvidenceRoutesNeverStartCalculation() throws Exception {
        when(repository.byDate("BLD001",DATE,null)).thenReturn(stored());when(repository.byId("DAYBATCH")).thenReturn(stored());
        mvc.perform(get("/v1/carbon-management/buildings/BLD001/daily-static").with(role("BUILDING_OWNER")).param("date","2026-09-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.emissionKgCo2e").value("56.00"));
        mvc.perform(get("/v1/carbon-management/daily-static-calculations/DAYBATCH/evidence").with(role("BUILDING_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.periodType").value("DAY"));
        verifyNoInteractions(inputs,rules);verify(repository,never()).publish(anyLong(),any(),anyString(),any(),any(),any());
    }
    @Test void requestCannotClaimFormalResultOrInventRawHourlyInputs() throws Exception {
        mvc.perform(post("/v1/carbon-management/daily-static-calculations").with(role("ENERGY_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST.replace("SIMULATED","FORMAL")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository,inputs,rules);
    }
    @Test void managerRoleWithoutCalculationDutyIsStillForbidden() throws Exception {
        when(duties.hasDuty(1L,com.platform.audit.BackendDuty.CARBON_CALCULATION_RUN)).thenReturn(false);
        mvc.perform(post("/v1/carbon-management/daily-static-calculations").with(role("ENERGY_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CARBON_FORBIDDEN"));
        verifyNoInteractions(scopes,repository,inputs,rules);
    }

}
