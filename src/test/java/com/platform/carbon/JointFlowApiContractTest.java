package com.platform.carbon;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JointFlowApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean JointFlowService service;
    @MockBean JointFlowConfigurationService configurations;
    @MockBean CarbonDisplayQueryService display;
    private static final String PATH="/v1/carbon-management/joint-flow/query";
    private static final String BODY="""
        {"buildingId":"BLD001","periodType":"MONTH","period":"2024-01"}
        """;
    private String bearer(String role) { return "Bearer "+jwt.generateToken(1L,"joint-flow-reader",List.of(role),1L); }
    @BeforeEach void session() { when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE); }
    @Test void summaryRoutesKeepLoginRoleAndScopeChecksAndExposePreparationContract() throws Exception {
        String path="/v1/carbon-management/joint-flow/summary-query";
        mvc.perform(post(path).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType("application/json").content(BODY).header("Authorization",bearer("THIRD_PARTY"))).andExpect(status().isForbidden());
        when(display.jointFlow(eq(1L),anyCollection(),any())).thenReturn(new CarbonDisplayQueryContracts.View(CarbonDisplayQueryContracts.CONTRACT,"JOINT_FLOW","BLD001","query","source","PREPARING",null,1));
        mvc.perform(post(path).contentType("application/json").content(BODY).header("Authorization",bearer("BUILDING_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PREPARING"))
                .andExpect(jsonPath("$.data.contractVersion").value("CARBON_DISPLAY_QUERY_V1")).andExpect(jsonPath("$.data.data").isEmpty());
        when(display.jointFlow(eq(1L),anyCollection(),any())).thenThrow(new com.platform.framework.exception.BusinessException(403,"scope"));
        mvc.perform(post(path).contentType("application/json").content(BODY).header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isForbidden());
        verify(service,never()).query(anyLong(),anyCollection(),any());verify(service,never()).preview(anyLong(),anyCollection(),any());
    }
    @Test void intensitySummaryUsesOriginalParametersAndDoesNotGrantNewWriteCapability() throws Exception {
        String path="/v1/carbon-intensity/buildings/BLD001/summary?mode=MONTHLY_YTD&year=2026&month=10";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization",bearer("THIRD_PARTY"))).andExpect(status().isForbidden());
        when(display.intensity(eq(1L),anyCollection(),eq("BLD001"),eq("MONTHLY_YTD"),eq(2026),eq(10),eq("Asia/Shanghai")))
                .thenReturn(new CarbonDisplayQueryContracts.View(CarbonDisplayQueryContracts.CONTRACT,"CARBON_INTENSITY","BLD001","query","source","READY",java.util.Map.of("actual",java.util.Map.of("value","4.791448")),0));
        mvc.perform(get(path).header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("CARBON_INTENSITY")).andExpect(jsonPath("$.data.data.actual.value").value("4.791448"));
        when(display.intensity(anyLong(),anyCollection(),anyString(),anyString(),anyInt(),any(),anyString())).thenThrow(new com.platform.framework.exception.BusinessException(403,"scope"));
        mvc.perform(get(path).header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isForbidden());
    }
    @Test void configurationSelectionRequiresExplicitVersionAndPreviewUsesSeparateEndpoint() throws Exception {
        String selection="/v1/carbon-management/joint-flow/buildings/BLD001/configurations/selection";
        mvc.perform(get(selection)).andExpect(status().isUnauthorized());
        mvc.perform(put(selection).header("Authorization",bearer("ENERGY_MANAGER")).contentType("application/json")
                .content("{\"expectedRevision\":0,\"versionId\":null,\"reason\":\"不得隐式回切\"}")).andExpect(status().isBadRequest());
        when(configurations.select(eq(1L),anyCollection(),eq("BLD001"),any())).thenReturn(new JointFlowConfigurationContracts.Selection("BLD001","V2",1));
        mvc.perform(put(selection).header("Authorization",bearer("ENERGY_MANAGER")).contentType("application/json")
                .content("{\"expectedRevision\":0,\"versionId\":\"V2\",\"reason\":\"明确切换\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versionId").value("V2"));
        mvc.perform(post("/v1/carbon-management/joint-flow/preview").header("Authorization",bearer("ENERGY_MANAGER")).contentType("application/json").content(BODY)).andExpect(status().isOk());
        verify(service).preview(eq(1L),anyCollection(),any());
    }
    @Test void checksLoginRoleBuildingScopeAndInputWithoutWritePermissions() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType("application/json").content(BODY).header("Authorization",bearer("THIRD_PARTY"))).andExpect(status().isForbidden());
        when(service.query(eq(1L),anyCollection(),any())).thenThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"建筑无权限"));
        mvc.perform(post(PATH).contentType("application/json").content(BODY).header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType("application/json").content(BODY.replace("MONTH","DAY")).header("Authorization",bearer("ENERGY_MANAGER"))).andExpect(status().isBadRequest());
    }
    @Test void preservesDecimalStringsAndClassificationBasisInResponse() throws Exception {
        var graph=new JointFlowContracts.Graph("READY",null,"tCO2e",new BigDecimal("0.123456789123456789"),BigDecimal.ZERO,List.of(),List.of());
        var view=new JointFlowContracts.View("BLD001","MONTH","2024-01",null,"Asia/Shanghai",null,null,"READY","CONFIGURED_VIEW","V2","hash","version",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),graph,null,List.of(),new JointFlowContracts.Coverage(0,0,List.of()));
        when(service.query(eq(1L),anyCollection(),any())).thenReturn(view);
        mvc.perform(post(PATH).contentType("application/json").content(BODY).header("Authorization",bearer("BUILDING_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.carbon.total").value("0.123456789123456789"))
                .andExpect(jsonPath("$.data.classificationBasis").value("CONFIGURED_VIEW"))
                .andExpect(jsonPath("$.data.configurationVersionId").value("V2"));
    }
}
