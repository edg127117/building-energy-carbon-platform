package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reading_diagnostics_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClassificationApiContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ClassificationRepository repository;
    @Autowired private javax.sql.DataSource dataSource;

    @org.junit.jupiter.api.BeforeEach
    void prepareManualReadingTables() throws Exception {
        var charset = java.nio.charset.StandardCharsets.UTF_8;
        String ddl = new org.springframework.core.io.ClassPathResource("db/migration/mysql/V75__mysql_energy_classification.sql")
                .getContentAsString(charset).replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ByteArrayResource(ddl.getBytes(charset)),
                new org.springframework.core.io.ClassPathResource("energy-reporting-carbon-schema-test.sql")).execute(dataSource);
    }

    @Test
    void readingHistoryKeepsPreciseReadOnlyHintsBehindExistingAuthenticationAndBuildingScope() throws Exception {
        var from=java.time.Instant.parse("2026-01-01T00:00:01Z");
        var to=from.plusSeconds(3600);
        var value=new java.math.BigDecimal("9007199254740993.00000001");
        var reading=new ClassificationContracts.Reading("diagnostic-point",from,to,"ELECTRICITY","kWh",
                "CUMULATIVE",value,value,null,java.math.BigDecimal.ONE,false,"CONFIRMED","synthetic ledger","EXACT",null);
        repository.insertBatch("diagnostic-batch","BLD001","diagnostic-key","hash","isolated API fixture",1);
        repository.insertReading("diagnostic-fact","diagnostic-batch","BLD001",reading,json.writeValueAsString(reading),java.math.BigDecimal.ZERO,1);
        repository.approveBatch("BLD001","diagnostic-batch",0,2);
        String base="/v1/energy-classification/buildings/BLD001/readings";
        mvc.perform(get(base).param("startInclusive",from.toString()).param("endExclusive",to.toString()))
                .andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String bearer="Bearer "+json.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
        // 查询窗口在记录内部，提示必须保留来源边界，不能把整段读数裁剪为新的事实。
        var response=mvc.perform(get(base).header("Authorization",bearer).param("startInclusive",from.plusSeconds(60).toString())
                        .param("endExclusive",to.minusSeconds(60).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].normalizedQuantity").isString())
                .andExpect(jsonPath("$.data[0].batchStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].unchangedReading.counterValue").value(value.toPlainString()))
                .andExpect(jsonPath("$.data[0].unchangedReading.startInclusive").value(from.toString()))
                .andExpect(jsonPath("$.data[0].unchangedReading.endExclusive").value(to.toString()))
                .andExpect(jsonPath("$.data[0].unchangedReading.intervalCount").value(1))
                .andExpect(jsonPath("$.data[0].unchangedReading.sources[0].sourceReference").value("synthetic ledger")).andReturn();
        assertThat(new java.math.BigDecimal(json.readTree(response.getResponse().getContentAsString())
                .path("data").get(0).path("normalizedQuantity").asText())).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        mvc.perform(get(base).header("Authorization",bearer).param("startInclusive",to.toString()).param("endExclusive",from.toString()))
                .andExpect(status().is4xxClientError());
        mvc.perform(get(base.replace("BLD001","NO_SUCH_BUILDING")).header("Authorization",bearer)
                        .param("startInclusive",from.toString()).param("endExclusive",to.toString()))
                .andExpect(status().is4xxClientError());
        assertThat(repository.batch("BLD001","diagnostic-batch",false).status()).isEqualTo("APPROVED");
    }

    @Test void configurationCoveragePreservesValidRawPreviewWithoutCreatingBusinessRecords() throws Exception {
        var login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String bearer = "Bearer " + json.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
        var from = java.time.Instant.parse("2024-12-31T16:00:00Z");
        var to = java.time.Instant.parse("2025-01-31T16:00:00Z");
        var reading = new ClassificationContracts.Reading("POINT004", from, to, "ELECTRICITY", "kWh",
                "PERIOD_TOTAL", null, null, java.math.BigDecimal.TEN, java.math.BigDecimal.ONE, true,
                "CONFIRMED", "隔离历史数据夹具", "EXACT", null);
        var data = new ClassificationContracts.ImportRequest("coverage-only", java.util.List.of(reading), "只读检查");
        var request = new com.platform.energy.history.HistoricalConfigurationContracts.Request(data, null,
                "Asia/Shanghai", "DIRECT_TCE_FACTOR", "CALORIFIC_EQUIVALENT", "PURCHASED_ELECTRICITY", "GLOBAL");
        mvc.perform(post("/v1/energy-classification/buildings/BLD001/readings/preview")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PREVIEW"));
        mvc.perform(post("/v1/energy-history/buildings/BLD001/configuration-coverage")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.formatStatus").value("PREVIEW"))
                .andExpect(jsonPath("$.data.configurationStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.data.executionStatus").value("NOT_RUN"));
        assertThat(repository.batch("BLD001", "coverage-only", true)).isNull();
        mvc.perform(post("/v1/energy-history/buildings/NO_SUCH_BUILDING/configuration-coverage")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().is4xxClientError());
    }

    @Test void authenticationValidationAndOpenApiUseActualHttpContracts() throws Exception {
        String base="/v1/energy-classification/buildings/BLD001";
        mvc.perform(post(base+"/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        var response=mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String token=json.readTree(response.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(post(base+"/query").header("Authorization","Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var docs=mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn();
        var specification=json.readTree(docs.getResponse().getContentAsString());
        var paths=specification.path("paths");
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/query")).isTrue();
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/readings/batches/{id}/approve")).isTrue();
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/versions/{id}/withdraw")).isTrue();
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/versions/{id}/approve-historical")).isTrue();
        assertThat(paths.has("/v1/energy-history/buildings/{buildingId}/configuration-coverage")).isTrue();
        assertThat(paths.has("/v1/carbon-projects/buildings/{buildingId}/versions/{id}/activate-historical")).isTrue();
        String coverageReference=paths.path("/v1/energy-history/buildings/{buildingId}/configuration-coverage")
                .path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText();
        var coverageProperties=specification.path("components").path("schemas")
                .path(coverageReference.substring(coverageReference.lastIndexOf('/')+1)).path("properties");
        assertThat(coverageProperties.has("data")).isTrue();
        assertThat(coverageProperties.has("timezoneId")).isTrue();
        assertThat(coverageProperties.has("method")).isTrue();
        mvc.perform(post("/v1/energy-history/buildings/BLD001/configuration-coverage")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/energy-history/buildings/BLD001/configuration-coverage")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
