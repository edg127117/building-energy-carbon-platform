package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageTestFixtures;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HvacQueryControllerFlowTest {

    private static final long FROM = 1_800_000_000_000L;
    private static final ResolutionContext DEFAULT_POLICY_CONTEXT =
            QualityUsageTestFixtures.systemDefaultContext();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private HvacMinuteRepository minuteRepository;
    @MockBean private QualityUsagePolicyResolver qualityUsagePolicyResolver;

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
                "hvac_owner_" + suffix, "BUILDING_OWNER", "BLD001");
        managerToken = createAndLogin(
                "hvac_manager_" + suffix, "ENERGY_MANAGER", "BLD001");
        outsideOwnerToken = createAndLogin(
                "hvac_outside_" + suffix, "BUILDING_OWNER", "BLD002");
        thirdPartyToken = createAndLogin(
                "hvac_third_" + suffix, "THIRD_PARTY", "BLD001");
    }

    @BeforeEach
    void resetTdengineMock() {
        reset(minuteRepository, qualityUsagePolicyResolver);
        when(qualityUsagePolicyResolver.runtimeContext())
                .thenReturn(DEFAULT_POLICY_CONTEXT);
        when(qualityUsagePolicyResolver.historyContext(
                anySet(), anyString(), anyLong(), anyLong()))
                .thenReturn(DEFAULT_POLICY_CONTEXT);
        when(qualityUsagePolicyResolver.resolve(
                any(ResolutionContext.class), anyString(), anyString(), anyLong(), anyInt()))
                .thenAnswer(invocation -> QualityUsageTestFixtures.systemDefaultResolver().resolve(
                        invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4)));
    }

    @Test
    void snapshotRequiresAuthentication() throws Exception {
        mockMvc.perform(hvacGet("/BLD001/snapshot"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicatedApiPrefixIsNotExposed() throws Exception {
        mockMvc.perform(get("/api/api/hvac/buildings/BLD001/snapshot")
                        .contextPath("/api")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void allowedRolesCanReadAuthorizedBuilding() throws Exception {
        for (String token : List.of(adminToken, ownerToken, managerToken)) {
            mockMvc.perform(hvacGet("/BLD001/snapshot")
                            .header(auth(), bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.buildingId").value("BLD001"))
                    .andExpect(jsonPath("$.data.points.length()").value(19))
                    .andExpect(jsonPath("$.data.points[0].status").value("NO_DATA"));
        }
    }

    @Test
    void buildingRoleCannotReadOutsideScope() throws Exception {
        mockMvc.perform(hvacGet("/BLD001/snapshot")
                        .header(auth(), bearer(outsideOwnerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void thirdPartyCannotUseInternalHvacApi() throws Exception {
        mockMvc.perform(hvacGet("/BLD001/snapshot")
                        .header(auth(), bearer(thirdPartyToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyReturnsRequestedSeriesAndAutomaticResolution() throws Exception {
        long to = FROM + 2L * 24 * 60 * 60 * 1_000;
        when(minuteRepository.findHistory(
                List.of("POINT002", "POINT001"), FROM, to, 1))
                .thenReturn(List.of(
                        row("POINT001", FROM, 12.3),
                        row("POINT002", FROM + 300_000L, 13.4)));

        mockMvc.perform(hvacGet("/BLD001/history")
                        .header(auth(), bearer(ownerToken))
                        .param("pointIds", "POINT002,POINT001")
                        .param("from", Long.toString(FROM))
                        .param("to", Long.toString(to)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionMinutes").value(5))
                .andExpect(jsonPath("$.data.series[0].pointId").value("POINT002"))
                .andExpect(jsonPath("$.data.series[0].records[1].average")
                        .value(13.4))
                .andExpect(jsonPath("$.data.series[1].pointId").value("POINT001"))
                .andExpect(jsonPath("$.data.series[1].records[0].time")
                        .value(FROM));
    }

    @Test
    void historyRejectsBadPointCountAndTimeRange() throws Exception {
        mockMvc.perform(hvacGet("/BLD001/history")
                        .header(auth(), bearer(adminToken))
                        .param("pointIds",
                                "POINT001,POINT002,POINT003,POINT004,POINT005,"
                                        + "POINT006,POINT007,POINT008,POINT009")
                        .param("from", Long.toString(FROM))
                        .param("to", Long.toString(FROM + 60_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(hvacGet("/BLD001/history")
                        .header(auth(), bearer(adminToken))
                        .param("pointIds", "POINT001")
                        .param("from", Long.toString(FROM))
                        .param("to", Long.toString(FROM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(hvacGet("/BLD001/history")
                        .header(auth(), bearer(adminToken))
                        .param("pointIds", "POINT001")
                        .param("from", "not-a-millisecond-timestamp")
                        .param("to", Long.toString(FROM + 60_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void missingBuildingReturns404() throws Exception {
        mockMvc.perform(hvacGet("/UNKNOWN/snapshot")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void tdengineFailureReturnsSanitized503() throws Exception {
        when(minuteRepository.findHistory(anyList(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException(
                        "jdbc:TAOS sql secret"));

        MvcResult result = mockMvc.perform(
                        hvacGet("/BLD001/history")
                                .header(auth(), bearer(adminToken))
                                .param("pointIds", "POINT001")
                                .param("from", Long.toString(FROM))
                                .param("to", Long.toString(FROM + 60_000L)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("jdbc").doesNotContain("secret");
    }

    private String createAndLogin(
            String username, String role, String buildingId) throws Exception {
        mockMvc.perform(post("/system/users")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s",
                                  "password":"123456",
                                  "nickname":"HVAC API 测试",
                                  "roleKeys":["%s"],
                                  "buildingIds":["%s"]
                                }
                                """.formatted(username, role, buildingId)))
                .andExpect(status().isOk());
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

    private HvacMinuteQueryRow row(String pointId, long time, double average) {
        return new HvacMinuteQueryRow(
                pointId, time, average, average - 0.5, average + 0.5,
                30L, 0);
    }

    private MockHttpServletRequestBuilder hvacGet(String path) {
        // 外部 URI 包含 /api，contextPath 会在进入 Controller 映射前被 Spring MVC 剥离。
        return get("/api/hvac/buildings" + path).contextPath("/api");
    }

    private String auth() {
        return "Authorization";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
