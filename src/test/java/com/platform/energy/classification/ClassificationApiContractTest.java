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

    @Test
    void readingHistoryKeepsPreciseReadOnlyHintsBehindExistingAuthenticationAndBuildingScope() throws Exception {
        // 测试基表已含分类版本；复用DDL补齐专用内存库的人工读数表，此处不作为迁移验收。
        var charset=java.nio.charset.StandardCharsets.UTF_8;
        String ddl=new org.springframework.core.io.ClassPathResource("db/migration/mysql/V75__mysql_energy_classification.sql")
                .getContentAsString(charset).replace("CREATE TABLE ","CREATE TABLE IF NOT EXISTS ");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ByteArrayResource(ddl.getBytes(charset))).execute(dataSource);
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
        var paths=json.readTree(docs.getResponse().getContentAsString()).path("paths");
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/query")).isTrue();
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/readings/batches/{id}/approve")).isTrue();
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/versions/{id}/withdraw")).isTrue();
    }
}
