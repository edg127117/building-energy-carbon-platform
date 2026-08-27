package com.platform.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.support.TestUserFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "audit-governance.export-directory=target/test-audit-exports",
        "audit-governance.export-cleanup-delay=PT1H"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditQueryExportApiContractTest {
    private static final String SCOPED_USERNAME = "audit_query_scoped_manager";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestUserFixture userFixture;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM sys_audit_export_job");
        jdbc.update("DELETE FROM sys_security_audit_event");
        jdbc.update("DELETE FROM biz_collection_config_audit_log");
        jdbc.update("DELETE FROM sys_user_backend_duty");
        grant(BackendDuty.AUDIT_EVIDENCE_VIEWER);
        grant(BackendDuty.AUDIT_EVIDENCE_EXPORTER);
        deleteExportFiles();
        insertCollectionAudit("00000000000000000000000000000001", "TRACE0001",
                LocalDateTime.now().minusMinutes(2), "password=should-not-leak");
        insertCollectionAudit("00000000000000000000000000000002", "TRACE0002",
                LocalDateTime.now().minusMinutes(1), "status=ENABLED");
    }

    @AfterEach
    void cleanup() throws Exception {
        userFixture.remove(SCOPED_USERNAME);
        jdbc.update("DELETE FROM sys_audit_export_job");
        jdbc.update("DELETE FROM biz_collection_config_audit_log");
        jdbc.update("DELETE FROM sys_user_backend_duty");
        deleteExportFiles();
    }

    @Test
    void queryUsesOpaqueCursorAndMasksSummary() throws Exception {
        String token = login();
        MvcResult first = mockMvc.perform(get("/v1/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("sourceModule", "COLLECTION")
                        .param("buildingId", "BLD001")
                        .param("from", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString())
                        .param("to", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1).toString())
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].auditId")
                        .value("00000000000000000000000000000002"))
                .andExpect(jsonPath("$.data.nextCursor").isString())
                .andReturn();
        String cursor = json(first).path("data").path("nextCursor").asText();

        mockMvc.perform(get("/v1/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("sourceModule", "COLLECTION")
                        .param("buildingId", "BLD001")
                        .param("from", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString())
                        .param("to", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1).toString())
                        .param("cursor", cursor).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].auditId")
                        .value("00000000000000000000000000000001"))
                .andExpect(jsonPath("$.data.items[0].beforeSummary").value("password=***"));
    }

    @Test
    void queryRequiresDynamicViewerDuty() throws Exception {
        String token = login();
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE duty_key='AUDIT_EVIDENCE_VIEWER'");
        mockMvc.perform(get("/v1/audit-events").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_DUTY_REQUIRED"));
    }

    @Test
    void queryNeverExceedsCurrentBuildingScope() throws Exception {
        long managerId = userFixture.createActiveUser(
                SCOPED_USERNAME, "123456", "ENERGY_MANAGER", "BLD001");
        grant(managerId, BackendDuty.AUDIT_EVIDENCE_VIEWER);
        insertCollectionAudit("00000000000000000000000000000003", "BLD002", "TRACE0003",
                LocalDateTime.now(), "status=ENABLED");
        String token = login(SCOPED_USERNAME, "123456");

        MvcResult result = mockMvc.perform(get("/v1/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("sourceModule", "COLLECTION")
                        .param("from", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString())
                        .param("to", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1).toString()))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode item : json(result).path("data").path("items")) {
            assertThat(item.path("buildingId").asText()).isEqualTo("BLD001");
        }

        mockMvc.perform(get("/v1/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD002"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUDIT_QUERY_FORBIDDEN"));
    }

    @Test
    void exportRequiresIndependentExporterDuty() throws Exception {
        String token = login();
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE duty_key='AUDIT_EVIDENCE_EXPORTER'");
        mockMvc.perform(post("/v1/audit-exports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"职责分离验证\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BACKOFFICE_DUTY_REQUIRED"));
    }

    @Test
    void createsDefaultRedactedExportAndAuditsDownload() throws Exception {
        String token = login();
        var request = objectMapper.createObjectNode()
                .put("purpose", "研发审计核查")
                .put("sourceModule", "COLLECTION")
                .put("buildingId", "BLD001")
                .put("from", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString())
                .put("to", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1).toString());
        MvcResult created = mockMvc.perform(post("/v1/audit-exports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        String exportId = json(created).path("data").path("exportId").asText();
        awaitCompleted(exportId);

        MvcResult download = mockMvc.perform(get("/v1/audit-exports/{id}/download", exportId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String csv = new String(download.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("password=***").doesNotContain("should-not-leak");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE action_type='AUDIT_EXPORT_DOWNLOAD' AND object_id=? AND result='SUCCESS'
                """, Integer.class, exportId)).isEqualTo(1);
    }

    private void awaitCompleted(String exportId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM sys_audit_export_job WHERE export_id=?", String.class, exportId);
            if ("COMPLETED".equals(status)) return;
            if ("FAILED".equals(status)) throw new AssertionError("审计导出异步生成失败");
            Thread.sleep(25);
        }
        throw new AssertionError("审计导出未在测试时限内完成");
    }

    private void insertCollectionAudit(String auditId, String traceId, LocalDateTime time, String before) {
        insertCollectionAudit(auditId, "BLD001", traceId, time, before);
    }

    private void insertCollectionAudit(
            String auditId, String buildingId, String traceId, LocalDateTime time, String before) {
        jdbc.update("""
                INSERT INTO biz_collection_config_audit_log
                (audit_id,building_id,actor_type,operator_id,action_type,object_type,object_id,
                 before_summary,after_summary,result,trace_id,environment_mode,self_approval_dev_mode,operation_time)
                VALUES (?,?,'USER',1,'UPDATE_SOURCE','DATA_SOURCE','SRC001',?,
                        'status=ENABLED','SUCCESS',?,'TEST',FALSE,?)
                """, auditId, buildingId, before, traceId, Timestamp.valueOf(time));
    }

    private void grant(BackendDuty duty) {
        grant(1L, duty);
    }

    private void grant(long userId, BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), userId, duty.name(),
                Timestamp.valueOf(now), 1L, Timestamp.valueOf(now));
    }

    private String login() throws Exception {
        return login("admin", "123456");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        return json(result).path("data").path("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void deleteExportFiles() throws Exception {
        Path directory = Path.of("target", "test-audit-exports");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
    }
}
