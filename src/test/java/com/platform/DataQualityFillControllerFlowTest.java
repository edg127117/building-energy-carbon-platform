package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.DataQualityRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 补全任务查询和 FAILED 重试接口的四角色、建筑范围与错误状态流程测试。
 *
 * <p>任务分页使用 H2 验证 SQL 范围隔离；Task 10 恢复服务使用 Mock 替换，避免普通
 * API 测试连接 TDengine。</p>
 */
@SpringBootTest(properties = {
        "data-quality.enabled=true",
        "data-quality.retry-delay-ms=3600000",
        "data-quality.reconciliation-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataQualityFillControllerFlowTest {

    private static final long FROM = Instant.parse("2026-07-29T00:00:00Z").toEpochMilli();
    private static final long TO = FROM + 3_600_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private DataQualityRecoveryService recoveryService;

    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private String thirdPartyToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM biz_data_quality_fill_task");
        adminToken = login("admin", "123456");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ownerToken = createUserAndLogin(
                "fill_owner_" + suffix, "BUILDING_OWNER", "BLD001");
        managerToken = createUserAndLogin(
                "fill_manager_" + suffix, "ENERGY_MANAGER", "BLD001");
        thirdPartyToken = createUserAndLogin(
                "fill_third_" + suffix, "THIRD_PARTY", "BLD001");
        insertTask("TASK001", "BLD001", "POINT001", "FAILED", validEvidence());
        insertTask("TASK002", "BLD002", "POINT020", "APPLIED", validEvidence());
    }

    @Test
    void shouldEnforceFourRolesAndMysqlScopedFilters() throws Exception {
        JsonNode ownerPage = json(mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(ownerToken))
                        .param("pageNum", "1")
                        .param("pageSize", "10")
                        .param("sourceType", "INTERPOLATION")
                        .param("dataQuality", "1")
                        .param("applyStatus", "FAILED")
                        .param("fromInclusive", Long.toString(FROM))
                        .param("toExclusive", Long.toString(TO)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(ownerPage.path("total").asLong()).isEqualTo(1);
        assertThat(ownerPage.path("records").get(0).path("taskId").asText())
                .isEqualTo("TASK001");
        assertThat(ownerPage.path("records").get(0).path("evidence")
                .path("algorithmVersion").asText()).isEqualTo("LINEAR_V1");

        mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(managerToken))
                        .param("buildingId", "BLD002"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/iot/data-quality/fill-tasks/TASK002")
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(thirdPartyToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void shouldRestrictRetryAndMap404409And400() throws Exception {
        mockMvc.perform(post("/iot/data-quality/fill-tasks/TASK001/retry")
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/iot/data-quality/fill-tasks/TASK001/retry")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("TASK001"));
        verify(recoveryService).recoverTask(
                org.mockito.ArgumentMatchers.eq("TASK001"),
                org.mockito.ArgumentMatchers.anyLong());

        mockMvc.perform(post("/iot/data-quality/fill-tasks/TASK002/retry")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/iot/data-quality/fill-tasks/MISSING/retry")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(adminToken))
                        .param("pageNum", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(adminToken))
                        .param("fromInclusive", Long.toString(TO))
                        .param("toExclusive", Long.toString(FROM)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn409ForDamagedEvidenceWithoutLeakingStack() throws Exception {
        insertTask("TASK_BAD", "BLD001", "POINT001", "APPLIED", "{broken");
        mockMvc.perform(get("/iot/data-quality/fill-tasks/TASK_BAD")
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.msg").value("补全任务证据已损坏，需人工修复"));
    }

    @Test
    void shouldReturnFixedLastErrorSummaryWithoutLeakingSqlOrJdbcDetails()
            throws Exception {
        String internalError = """
                JDBC SQL SELECT * FROM minute_data
                jdbc:mysql://mysql-internal:3306/iot
                    at com.mysql.cj.jdbc.ClientPreparedStatement.execute
                """;
        jdbcTemplate.update(
                "UPDATE biz_data_quality_fill_task SET last_error = ? WHERE task_id = ?",
                internalError, "TASK001");

        MvcResult result = mockMvc.perform(get("/iot/data-quality/fill-tasks/TASK001")
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastError")
                        .value("补全任务执行失败，请联系管理员处理"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "JDBC", "SELECT *", "mysql-internal",
                        "ClientPreparedStatement");
    }

    @Test
    void shouldExcludeTasksTouchingEitherBoundaryOfHalfOpenPeriod()
            throws Exception {
        insertTask(
                "TASK_END_EQUAL", "BLD001", "POINT_END", "APPLIED",
                validEvidence(), FROM - 180_000L, FROM);
        insertTask(
                "TASK_START_EQUAL", "BLD001", "POINT_START", "APPLIED",
                validEvidence(), TO, TO + 180_000L);

        JsonNode page = json(mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(ownerToken))
                        .param("fromInclusive", Long.toString(FROM))
                        .param("toExclusive", Long.toString(TO)))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(page.path("records").findValuesAsText("taskId"))
                .containsExactly("TASK001");
    }

    @Test
    void shouldRejectUnsupportedDataQualityFilters() throws Exception {
        mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(adminToken))
                        .param("dataQuality", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/iot/data-quality/fill-tasks")
                        .header(auth(), bearer(adminToken))
                        .param("dataQuality", "3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldCheckBuildingAccessBeforeDecodingDamagedEvidence()
            throws Exception {
        insertTask(
                "TASK_BAD_SCOPE", "BLD002", "POINT_BAD", "APPLIED", "{broken");

        mockMvc.perform(get("/iot/data-quality/fill-tasks/TASK_BAD_SCOPE")
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());
    }

    private void insertTask(
            String taskId, String buildingId, String pointId,
            String status, String evidence) {
        insertTask(
                taskId, buildingId, pointId, status, evidence,
                FROM, FROM + 180_000L);
    }

    private void insertTask(
            String taskId, String buildingId, String pointId,
            String status, String evidence, long startMinute, long endMinute) {
        jdbcTemplate.update("""
                INSERT INTO biz_data_quality_fill_task(
                  task_id,idempotency_key,building_id,point_id,start_minute,end_minute,
                  minute_count,data_quality,source_type,algorithm_version,evidence_json,
                  apply_status,applied_count,failed_count,replaced_count,voided_count,
                  retry_count,generated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                taskId, "KEY_" + taskId, buildingId, pointId,
                new Timestamp(startMinute), new Timestamp(endMinute),
                Math.toIntExact((endMinute - startMinute) / 60_000L),
                1, "INTERPOLATION", "LINEAR_V1", evidence, status,
                "APPLIED".equals(status)
                        ? Math.toIntExact((endMinute - startMinute) / 60_000L)
                        : 0,
                "FAILED".equals(status)
                        ? Math.toIntExact((endMinute - startMinute) / 60_000L)
                        : 0,
                0, 0, 0, new Timestamp(TO));
    }

    private String validEvidence() {
        return """
                {"leftMinute":%d,"leftValue":10.0,"rightMinute":%d,
                 "rightValue":13.0,"algorithmVersion":"LINEAR_V1"}
                """.formatted(FROM - 60_000L, FROM + 180_000L);
    }

    private String createUserAndLogin(String username, String role, String buildingId)
            throws Exception {
        mockMvc.perform(post("/system/users")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"补全任务测试账号",
                                 "roleKeys":["%s"],"buildingIds":["%s"]}
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
        return json(result).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String auth() {
        return "Authorization";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
