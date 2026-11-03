package com.platform.carbon.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.carbon.HourlyCarbonService;
import com.platform.carbon.HourlyCarbonContracts;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HourlyCarbonApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.platform.security.JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean HourlyCarbonService service;
    @BeforeEach void setup() {when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);}
    private org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return request->{request.addHeader("Authorization","Bearer "+jwt.generateToken(1L,"isolated",List.of(role),1L));return request;};
    }
    private HourlyCarbonContracts.Request request() {
        return new HourlyCarbonContracts.Request("BLD001",LocalDate.of(2026,1,1),Instant.parse("2026-01-02T00:00:00Z"),"hourly-key","RAW_CUMULATIVE","SIMULATED","test-manifest","FORMAL");
    }
    @Test void commandRequiresAuthenticationRoleAndValidatedSourceDeclaration() throws Exception {
        mvc.perform(post("/v1/carbon-management/hourly-calculations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/carbon-management/hourly-calculations").with(role("BUILDING_OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request()))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/carbon-management/hourly-calculations").with(role("PLATFORM_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("CARBON_VALIDATION_FAILED"));
        mvc.perform(post("/v1/carbon-management/hourly-calculations").with(role("ENERGY_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request()))).andExpect(status().isOk());
        verify(service).calculate(eq(1L),eq(Set.of("ENERGY_MANAGER")),eq(request()));
    }
    @Test void queryIsReadOnlyAndEvidenceHasSeparateRoute() throws Exception {
        mvc.perform(get("/v1/carbon-management/buildings/BLD001/hourly").with(role("BUILDING_OWNER")).param("date","2026-01-01").param("revision","2"))
                .andExpect(status().isOk());
        verify(service).query(1L,Set.of("BUILDING_OWNER"),"BLD001",LocalDate.of(2026,1,1),2);
        mvc.perform(get("/v1/carbon-management/hourly-calculations/batch/evidence").with(role("ENERGY_MANAGER"))).andExpect(status().isOk());
        verify(service).evidence(1L,Set.of("ENERGY_MANAGER"),"batch");
        verify(service,never()).calculate(anyLong(),anyCollection(),any());
        mvc.perform(get("/v1/carbon-management/buildings/BLD001/hourly").with(role("PLATFORM_ADMIN")).param("date","wrong"))
                .andExpect(status().isBadRequest());
    }
    @Test void openApiNamesAndSecurityRemainIndependentOfMonthlyContracts() throws Exception {
        var response=mvc.perform(get("/v3/api-docs").with(role("PLATFORM_ADMIN"))).andExpect(status().isOk()).andReturn();
        var root=json.readTree(response.getResponse().getContentAsString());
        var path=root.path("paths").path("/v1/carbon-management/hourly-calculations").path("post");
        assertThat(path.path("security")).isNotEmpty();
        assertThat(root.path("components").path("schemas").path("HourlyCarbonCalculationRequest").path("properties").has("calculationAsOf")).isTrue();
        assertThat(root.path("components").path("schemas").path("HourlyCarbonPoint").path("properties").has("roundingPolicyVersionIds")).isTrue();
        assertThat(root.path("paths").has("/v1/carbon-management/buildings/{buildingId}/hourly")).isTrue();
    }
}
