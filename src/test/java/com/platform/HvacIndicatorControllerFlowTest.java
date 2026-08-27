package com.platform;

import com.platform.support.TestUserFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.IndicatorLatestCacheService;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HvacIndicatorControllerFlowTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private static final String INDICATOR_ID = "INDICATOR_WCR_COP_B1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestUserFixture userFixture;
    @MockBean private IndicatorLatestCacheService cache;
    @MockBean private IndicatorMinuteRepository indicatorRepository;
    @MockBean private HvacMinuteRepository minuteRepository;
    @MockBean private HvacFormulaEngine formulaEngine;

    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private String outsideOwnerToken;
    private String thirdPartyToken;

    @BeforeAll
    void createRoleUsers() throws Exception {
        adminToken = login("admin", "123456");
        String suffix = Long.toUnsignedString(System.nanoTime());
        ownerToken = createAndLogin(
                "indicator_owner_" + suffix, "BUILDING_OWNER", "BLD001");
        managerToken = createAndLogin(
                "indicator_manager_" + suffix, "ENERGY_MANAGER", "BLD001");
        outsideOwnerToken = createAndLogin(
                "indicator_outside_" + suffix, "BUILDING_OWNER", "BLD002");
        thirdPartyToken = createAndLogin(
                "indicator_third_" + suffix, "THIRD_PARTY", "BLD001");
    }

    @BeforeEach
    void resetReadBoundaries() {
        reset(cache, indicatorRepository, minuteRepository, formulaEngine);
    }

    @Test
    void allFourEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/latest"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/trends")
                        .param("indicatorIds", INDICATOR_ID)
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(apiGet(
                        "/api/hvac/indicators/{indicatorId}/history",
                        INDICATOR_ID)
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(apiGet(
                        "/api/hvac/indicators/{indicatorId}/calculations/{minuteStart}",
                        INDICATOR_ID, MINUTE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerManagerAndAdminCanUseAllFourEndpoints() throws Exception {
        for (String token : List.of(ownerToken, managerToken, adminToken)) {
            mockMvc.perform(apiGet(
                            "/api/hvac/buildings/BLD001/indicators/latest")
                            .header(auth(), bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.buildingId").value("BLD001"))
                    .andExpect(jsonPath("$.data.indicators.length()").value(4))
                    .andExpect(jsonPath("$.data.indicators[0].status").exists());

            mockMvc.perform(apiGet(
                            "/api/hvac/buildings/BLD001/indicators/trends")
                            .header(auth(), bearer(token))
                            .param("indicatorIds", INDICATOR_ID)
                            .param("from", Long.toString(MINUTE))
                            .param("to", Long.toString(MINUTE + 60_000L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.buildingId").value("BLD001"))
                    .andExpect(jsonPath("$.data.resolutionMinutes").value(1))
                    .andExpect(jsonPath("$.data.series[0].indicatorId")
                            .value(INDICATOR_ID))
                    .andExpect(jsonPath("$.data.series[0].records.length()").value(0));

            mockMvc.perform(apiGet(
                            "/api/hvac/indicators/{indicatorId}/history",
                            INDICATOR_ID)
                            .header(auth(), bearer(token))
                            .param("from", Long.toString(MINUTE))
                            .param("to", Long.toString(MINUTE + 60_000L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.indicatorId")
                            .value(INDICATOR_ID))
                    .andExpect(jsonPath("$.data.records.length()").value(0));

            mockMvc.perform(apiGet(
                            "/api/hvac/indicators/{indicatorId}/calculations/{minuteStart}",
                            INDICATOR_ID, MINUTE)
                            .header(auth(), bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.indicatorId")
                            .value(INDICATOR_ID))
                    .andExpect(jsonPath("$.data.status").value("NO_DATA"));
        }
    }

    @Test
    void outOfScopeBuildingRoleIsForbiddenOnAllFourEndpoints() throws Exception {
        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/latest")
                        .header(auth(), bearer(outsideOwnerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/trends")
                        .header(auth(), bearer(outsideOwnerToken))
                        .param("indicatorIds", INDICATOR_ID)
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isForbidden());
        mockMvc.perform(apiGet(
                        "/api/hvac/indicators/{indicatorId}/history",
                        INDICATOR_ID)
                        .header(auth(), bearer(outsideOwnerToken))
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isForbidden());
        mockMvc.perform(apiGet(
                        "/api/hvac/indicators/{indicatorId}/calculations/{minuteStart}",
                        INDICATOR_ID, MINUTE)
                        .header(auth(), bearer(outsideOwnerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void thirdPartyIsForbiddenOnAllFourInternalEndpoints() throws Exception {
        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/latest")
                        .header(auth(), bearer(thirdPartyToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/trends")
                        .header(auth(), bearer(thirdPartyToken))
                        .param("indicatorIds", INDICATOR_ID)
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isForbidden());
        mockMvc.perform(apiGet(
                        "/api/hvac/indicators/{indicatorId}/history",
                        INDICATOR_ID)
                        .header(auth(), bearer(thirdPartyToken))
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isForbidden());
        mockMvc.perform(apiGet(
                        "/api/hvac/indicators/{indicatorId}/calculations/{minuteStart}",
                        INDICATOR_ID, MINUTE)
                        .header(auth(), bearer(thirdPartyToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void trendEndpointRejectsInvalidBatchAndRangeParameters() throws Exception {
        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/trends")
                        .header(auth(), bearer(adminToken))
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/trends")
                        .header(auth(), bearer(adminToken))
                        .param("indicatorIds", "I1,I2,I3,I4,I5")
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(MINUTE + 60_000L)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(apiGet(
                        "/api/hvac/buildings/BLD001/indicators/trends")
                        .header(auth(), bearer(adminToken))
                        .param("indicatorIds", INDICATOR_ID)
                        .param("from", Long.toString(MINUTE))
                        .param("to", Long.toString(
                                MINUTE + java.time.Duration.ofDays(31).toMillis() + 1)))
                .andExpect(status().isBadRequest());
    }

    private String createAndLogin(
            String username, String role, String buildingId) throws Exception {
        userFixture.createActiveUser(username, "123456", role, buildingId);
        return login(username, "123456");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString());
        String token = response.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String auth() {
        return "Authorization";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private MockHttpServletRequestBuilder apiGet(
            String uriTemplate, Object... uriVariables) {
        return get(uriTemplate, uriVariables).contextPath("/api");
    }
}
