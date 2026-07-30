package com.platform;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.DataQualityRecalculationJobService;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.dto.DataQualityRecalculationDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步人工重算接口的平台管理员权限、请求校验和路由流程测试。
 */
@SpringBootTest(properties = {
        "data-quality.enabled=true",
        "data-quality.recalculation-enabled=true",
        "data-quality.recalculation-scan-delay-ms=3600000",
        "data-quality.reconciliation-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataQualityRecalculationControllerFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private DataQualityRecalculationJobService service;

    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = login("admin", "123456");
        String username = "recalc_owner_"
                + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/system/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"重算测试账号",
                                 "roleKeys":["BUILDING_OWNER"],"buildingIds":["BLD001"]}
                                """.formatted(username)))
                .andExpect(status().isOk());
        ownerToken = login(username, "123456");
        // 系统采用单账号单 Token；其他并行流程测试可能也会登录 admin，
        // 在正式发起接口断言前刷新一次，避免测试之间相互覆盖白名单。
        adminToken = login("admin", "123456");
        when(service.submitRange(
                anyLong(), any(), any(), anyLong())).thenReturn(response());
        when(service.submitVoid(
                anyLong(), any(), eq("TASK001"), eq("异常配置"), anyLong()))
                .thenReturn(response());
        Page<DataQualityRecalculationDtos.Response> page = new Page<>(1, 20);
        page.setRecords(List.of(response()));
        page.setTotal(1);
        when(service.page(any(), anyInt(), anyInt(),
                any(), any(), any(), any(), any())).thenReturn(page);
        when(service.detail(any(), eq("JOB1"))).thenReturn(
                new DataQualityRecalculationDtos.Detail(
                        response(), List.of()));
    }

    @Test
    void adminSubmitsAndQueriesWhileOwnerIsForbidden() throws Exception {
        String body = """
                {"buildingId":"BLD001","pointIds":["POINT001"],
                 "fromInclusive":1800000000000,"toExclusive":1800000060000,
                 "reason":"修正测点绑定"}
                """;
        mockMvc.perform(post("/iot/data-quality/recalculate")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value("JOB1"))
                .andExpect(jsonPath("$.data.status").value("WAITING"));
        mockMvc.perform(post(
                                "/iot/data-quality/fill-tasks/TASK001/void-and-recalculate")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"异常配置\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/iot/data-quality/recalculation-jobs")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/iot/data-quality/recalculation-jobs/JOB1")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.jobId").value("JOB1"))
                .andExpect(jsonPath("$.data.childTasks").isArray());

        mockMvc.perform(post("/iot/data-quality/recalculate")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/iot/data-quality/recalculation-jobs")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/iot/data-quality/recalculation-jobs/JOB1")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidRequestsAre400BeforeService() throws Exception {
        mockMvc.perform(post("/iot/data-quality/recalculate")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buildingId":"BLD001","pointIds":[],
                                 "fromInclusive":1,"toExclusive":2,"reason":""}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                                "/iot/data-quality/fill-tasks/TASK001/void-and-recalculate")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private DataQualityRecalculationDtos.Response response() {
        return new DataQualityRecalculationDtos.Response(
                "JOB1", RecalculationJobType.RANGE_RECALCULATE,
                "BLD001", List.of("POINT001"),
                1_800_000_000_000L, 1_800_000_060_000L,
                null, "修正测点绑定", 1L,
                RecalculationJobStatus.WAITING,
                RecalculationJobPhase.RECALCULATING,
                1_800_000_000_000L,
                0, 0, 0, 0, 0, 0,
                null, null, null,
                1_800_000_060_000L, 1_800_000_060_000L);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(
                result.getResponse().getContentAsString());
        return json.path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
