package com.platform.carbon;

import com.platform.audit.BackendDutyService;
import com.platform.energy.classification.ClassificationRepository;
import com.platform.cache.*;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 安全过滤器和真实新Controller契约；外部令牌缓存与职责服务为隔离替身。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConfigurationWorkflowApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean BuildingScopeCacheService scopes;
    @MockBean BackendDutyService duties;
    @MockBean CarbonRecalculationRepository recalculations;
    @MockBean ClassificationRepository classificationStorage;
    @BeforeEach void prepare(){
        when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(classificationStorage.buildingExists("BLD001")).thenReturn(true);
    }
    @Test void newConfigurationRoutesRetainAuthenticationRoleAndBuildingScope() throws Exception {
        String staged="/v1/relation-models/BLD001/versions/unknown/staged-assets";
        mvc.perform(get(staged)).andExpect(status().isUnauthorized());
        mvc.perform(get(staged).header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/energy-classification/buildings/BLD002/readings/workbook/template").header("Authorization",bearer("ENERGY_MANAGER"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/energy-periods/buildings/BLD001/manual-months/context").param("pointId","POINT001").param("month","2026-08-01").header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/carbon-projects/buildings/BLD001/versions/unknown/work-order/commands").contentType("application/json")
                .content("{\"fingerprint\":\"f\",\"domain\":\"PROJECT\",\"action\":\"SUBMIT\",\"reason\":\"依据\",\"idempotencyKey\":\"key\"}")
                .header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isForbidden());
    }
    @Test void factorRulesAreAuthoritativeReadOnlyOptionsAndWorkbookTemplateIsBinary() throws Exception {
        mvc.perform(get("/v1/carbon-management/rule-options").header("Authorization",bearer("PLATFORM_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.formulas").isArray()).andExpect(jsonPath("$.data.roundingPolicies").isArray());
        mvc.perform(get("/v1/energy-classification/buildings/BLD001/readings/workbook/template").header("Authorization",bearer("PLATFORM_ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
    @Test void configurationListRoutesRequireLoginAndBuildingScope() throws Exception {
        for (String path : List.of("/v1/energy-boundary-summaries/policies",
                "/v1/carbon-management/recalculations")) {
            mvc.perform(get(path).param("buildingId", "BLD001"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(path).param("buildingId", "BLD002")
                    .header("Authorization", bearer("ENERGY_MANAGER")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path).param("buildingId", "BLD001")
                    .header("Authorization", bearer("PLATFORM_ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        }
    }
    @Test void readingBatchDetailKeepsAuthenticationAndBuildingBoundary() throws Exception {
        String route = "/v1/energy-classification/buildings/BLD001/readings/batches/unknown";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route.replace("BLD001", "BLD002"))
                .header("Authorization", bearer("ENERGY_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(route).header("Authorization", bearer("PLATFORM_ADMIN")))
                .andExpect(status().isNotFound());
    }
    private String bearer(String role){return "Bearer "+jwt.generateToken(1L,"admin",List.of(role),1L);}
}
