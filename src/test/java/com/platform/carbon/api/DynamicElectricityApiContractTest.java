package com.platform.carbon.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.carbon.DynamicElectricityService;
import com.platform.carbon.DynamicElectricityContracts.*;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DynamicElectricityApiContractTest {
    private static final String BASE = "/v1/carbon-management/buildings/B/dynamic-electricity";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.platform.security.JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean DynamicElectricityService service;
    @MockBean com.platform.carbon.DynamicElectricityLiveService live;
    @MockBean com.platform.carbon.PeriodElectricityQueryService prepared;

    @BeforeEach void setup() {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + jwt.generateToken(1L, "isolated", List.of(role), 1L));
            return request;
        };
    }
    private ImportRequest request() {
        return new ImportRequest("key", "isolated fixture", "420000", "NO_EXTERNAL_HYDRO", "SIMULATED", "kgCO2/kWh",
                List.of(new Observation("*", Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T01:00:00Z"), new BigDecimal("0.523456789012345678"))));
    }

    @Test void deviceIntervalRequiresAuthenticationRoleAndValidValues() throws Exception {
        var request=new DeviceIntervalRequest(new com.platform.iot.identity.DeviceIdentityKey("SERIAL","M1"),"METER","P",
                Instant.parse("2025-01-01T00:00:00Z"),Instant.parse("2025-01-01T00:15:00Z"),1,
                new BigDecimal("12.345"),new BigDecimal("0.54321"),"SIMULATED","isolated device");
        String payload=json.writeValueAsString(request);
        mvc.perform(post(BASE+"/intervals").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE+"/intervals").with(role("BUILDING_OWNER")).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/intervals").with(role("ENERGY_MANAGER")).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
        verify(live).ingest(1L,Set.of("ENERGY_MANAGER"),"B",request);
        clearInvocations(live);
        mvc.perform(post(BASE+"/intervals").with(role("ENERGY_MANAGER")).contentType(MediaType.APPLICATION_JSON).content(payload.replace("12.345","-1"))).andExpect(status().isBadRequest());
        verifyNoInteractions(live);
    }

    @Test void rejectsAnonymousReadersAndOwnerWrites() throws Exception {
        mvc.perform(get(BASE).param("periodType", "YEAR").param("period", "2025")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/factors").with(role("BUILDING_OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request()))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void validatesUnitAndPreservesExactDecimals() throws Exception {
        var accepted = request();
        mvc.perform(post(BASE + "/factors").with(role("ENERGY_MANAGER")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(accepted))).andExpect(status().isOk());
        verify(service).ingest(1L, Set.of("ENERGY_MANAGER"), "B", accepted);
        clearInvocations(service);
        String invalid = json.writeValueAsString(accepted).replace("kgCO2/kWh", "kgCO2e/kWh");
        mvc.perform(post(BASE + "/factors").with(role("ENERGY_MANAGER")).contentType(MediaType.APPLICATION_JSON)
                .content(invalid)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void queryAndEvidenceNeverInvokeCalculation() throws Exception {
        when(prepared.dynamic(anyLong(), anyCollection(), anyString(), anyString(), anyString()))
                .thenReturn(new View("B", "YEAR", "2025", "Asia/Shanghai", "tCO2", "kgCO2/kWh", "MONITORING", List.of()));
        mvc.perform(get(BASE).with(role("BUILDING_OWNER")).param("periodType", "YEAR").param("period", "2025"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.factorUnit").value("kgCO2/kWh"));
        mvc.perform(get(BASE + "/hourly").with(role("BUILDING_OWNER")).param("date", "2025-01-01"))
                .andExpect(status().isOk());
        verify(prepared).dynamic(1L, Set.of("BUILDING_OWNER"), "B", "DAY", "2025-01-01");
        mvc.perform(get(BASE + "/results/id/evidence").with(role("BUILDING_OWNER"))).andExpect(status().isOk());
        verify(service).evidence(1L, Set.of("BUILDING_OWNER"), "B", "id");
        verify(service, never()).calculate(anyLong(), anyCollection(), anyString(), any());
    }

    @Test void openApiHasIndependentSchemaAndSecurity() throws Exception {
        var result = mvc.perform(get("/v3/api-docs").with(role("PLATFORM_ADMIN"))).andExpect(status().isOk()).andReturn();
        var root = json.readTree(result.getResponse().getContentAsString());
        var operation = root.path("paths").path("/v1/carbon-management/buildings/{buildingId}/dynamic-electricity/factors").path("post");
        assertThat(operation.path("security")).isNotEmpty();
        assertThat(root.path("components").path("schemas").path("DynamicElectricityImportRequest").path("properties").has("observations")).isTrue();
    }
}
