package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.cache.BuildingScopeCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CarbonProjectApiContractTest {
    private static final String OPTIONS = "/v1/carbon-projects/buildings/%s/options";
    private static final String VERSIONS = "/v1/carbon-projects/buildings/%s/versions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired @Qualifier("mysqlJdbcTemplate") private JdbcTemplate jdbc;
    @MockBean private TokenCacheService tokenCacheService;
    @MockBean private BuildingScopeCacheService buildingScopeCacheService;
    @MockBean private BackendDutyService backendDutyService;

    @BeforeEach
    void isolateSecurityDependencies() {
        when(tokenCacheService.validateActiveToken(anyLong(), anyString()))
                .thenReturn(TokenValidationResult.ACTIVE);
        when(backendDutyService.hasDuty(eq(1L), any(BackendDuty.class))).thenReturn(true);
    }

    @Test
    void corruptedStoredConfigurationFailsClosedWithoutBreakingPageContract() throws Exception {
        String token=bearer("PLATFORM_ADMIN");
        var created=mockMvc.perform(post(VERSIONS.formatted("BLD001")).header("Authorization",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"存储损坏拒绝路径\"}"))
                .andExpect(status().isOk()).andReturn();
        String id=json(created).path("data").path("versionId").asText();
        jdbc.update("UPDATE biz_carbon_project_version SET configuration_json='{}' WHERE version_id=?",id);
        mockMvc.perform(get(VERSIONS.formatted("BLD001")).header("Authorization",token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARBON_DEPENDENCY_UNAVAILABLE"));
        mockMvc.perform(get(VERSIONS.formatted("BLD001")+"/"+id+"/readiness").header("Authorization",token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARBON_DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void rejectsAnonymousUnauthorizedRoleAndUnauthorizedBuilding() throws Exception {
        mockMvc.perform(get(OPTIONS.formatted("BLD001")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("CARBON_UNAUTHORIZED"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(OPTIONS.formatted("BLD001"))
                        .header("Authorization", bearer("THIRD_PARTY")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CARBON_FORBIDDEN"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(OPTIONS.formatted("BLD002"))
                        .header("Authorization", bearer("ENERGY_MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void exposesOnlyFixedScopeOneAndScopeTwoPathsWithReservedWaterAndGreenEnergy() throws Exception {
        MvcResult result = mockMvc.perform(get(OPTIONS.formatted("BLD001"))
                        .header("Authorization", bearer("PLATFORM_ADMIN")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode options = json(result).path("data");
        assertThat(options.size()).isEqualTo(5);
        assertOption(options, 0, "STATIONARY_COMBUSTION", "ENABLED", "SCOPE_1");
        assertOption(options, 1, "PURCHASED_ELECTRICITY", "ENABLED", "SCOPE_2");
        assertOption(options, 2, "PURCHASED_HEAT", "ENABLED", "SCOPE_2");
        assertOption(options, 3, "WATER", "RESERVED", "SCOPE_3");
        assertOption(options, 4, "GREEN_ENERGY", "RESERVED", null);
    }

    @Test
    void validatesRequiredCreateAndSaveFieldsBeforeChangingVersionState() throws Exception {
        String token = bearer("PLATFORM_ADMIN");

        mockMvc.perform(post(VERSIONS.formatted("BLD001"))
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(put((VERSIONS + "/version-1").formatted("BLD001"))
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"修订项目边界\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void mapsSecondOpenVersionToStableConflict() throws Exception {
        String token = bearer("PLATFORM_ADMIN");
        String request = "{\"reason\":\"建立项目能源与碳边界\"}";

        mockMvc.perform(post(VERSIONS.formatted("BLD001"))
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(post(VERSIONS.formatted("BLD001"))
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.errorCode").value("CARBON_VERSION_CONFLICT"));
    }

    @Test
    void configurationGuideUsesAuthorizedSavedVersionAndKeepsCalculationUnchecked() throws Exception {
        String token = bearer("PLATFORM_ADMIN");
        var created = mockMvc.perform(post(VERSIONS.formatted("BLD001"))
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"建立引导检查草稿\"}"))
                .andExpect(status().isOk()).andReturn();
        String id = json(created).path("data").path("versionId").asText();
        String path = VERSIONS.formatted("BLD001") + "/" + id + "/guide";
        mockMvc.perform(get(path).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.calculationStatus").value("NOT_CHECKED"))
                .andExpect(jsonPath("$.data.steps.length()").value(5))
                .andExpect(jsonPath("$.data.steps[0].state").value("MISSING"));
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(VERSIONS.formatted("BLD002") + "/" + id + "/guide")
                        .header("Authorization", bearer("ENERGY_MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerGuideKeepsRelationHistoryRestrictedWithoutRejectingWholeGuide() throws Exception {
        String classification = "{\"relationVersionId\":\"RELATION-RESTRICTED\",\"schemes\":[],\"nodes\":[],\"bindings\":[],\"boundaryRules\":[]}";
        jdbc.update("INSERT INTO biz_energy_classification_version(version_id,building_id,version_no,status,configuration_json,reason,created_by,created_at) VALUES ('GUIDE-CLASS','BLD001',1,'DRAFT',?,'隔离权限用例',1,0)", classification);
        String project = "{\"classificationVersionId\":\"GUIDE-CLASS\",\"energies\":[{\"energyItemCode\":\"WATER\",\"pathway\":\"WATER\",\"capability\":\"RESERVED\"}],\"exclusions\":[]}";
        jdbc.update("INSERT INTO biz_carbon_project_version(version_id,building_id,version_no,status,configuration_json,reason,created_by,created_at) VALUES ('GUIDE-PROJECT','BLD001',1,'DRAFT',?,'隔离权限用例',1,0)", project);
        when(buildingScopeCacheService.get(1L)).thenReturn(java.util.Set.of("BLD001"));
        mockMvc.perform(get(VERSIONS.formatted("BLD001") + "/GUIDE-PROJECT/guide")
                        .header("Authorization", bearer("BUILDING_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].state").value("NOT_CHECKED"))
                .andExpect(jsonPath("$.data.steps[0].issues[0].code").value("RELATION_HISTORY_RESTRICTED"))
                .andExpect(jsonPath("$.data.calculationStatus").value("NOT_CHECKED"));
    }

    private String bearer(String role) {
        return "Bearer " + jwtService.generateToken(1L, "contract-user", List.of(role), 1L);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static void assertOption(JsonNode options, int index, String code,
                                     String capability, String scopeType) {
        JsonNode option = options.get(index);
        assertThat(option.path("code").asText()).isEqualTo(code);
        assertThat(option.path("capability").asText()).isEqualTo(capability);
        if (scopeType == null) {
            assertThat(option.get("scopeType").isNull()).isTrue();
        } else {
            assertThat(option.path("scopeType").asText()).isEqualTo(scopeType);
        }
    }
}
