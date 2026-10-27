package com.platform.energy.reporting;

import com.fasterxml.jackson.databind.*;
import com.platform.audit.*;
import com.platform.cache.*;
import com.platform.security.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 实际HTTP契约与过滤器验证；Token缓存是隔离替身，业务来源、审核和封账使用真实服务及H2。 */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:reporting_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@org.springframework.test.context.jdbc.Sql("classpath:energy-reporting-carbon-schema-test.sql")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportingApiContractTest {
    static final String BASE="/v1/energy-reporting/buildings/BLD001";
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JwtService jwt;
    @Autowired @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc;
    @Autowired ReportingCarbonInput input;
    @MockBean TokenCacheService tokenCacheService;
    @MockBean BuildingScopeCacheService buildingScopeCacheService;
    @MockBean BackendDutyService backendDutyService;
    @org.springframework.test.context.transaction.BeforeTransaction void committedActors() {
        for(long id:List.of(2L,3L))jdbc.update("INSERT INTO sys_user(id,username,password,nickname,status,del_flag,activation_pending,security_version) VALUES (?,?,'test-only','隔离审核账号',1,0,0,1) ON DUPLICATE KEY UPDATE activation_pending=0,security_version=1",id,"reporting-test-"+id);
    }
    @BeforeEach void prepare() {
        when(tokenCacheService.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(buildingScopeCacheService.get(1L)).thenReturn(Set.of("BLD001"));
        when(backendDutyService.hasDuty(anyLong(),any(BackendDuty.class))).thenReturn(true);

        jdbc.update("UPDATE biz_energy_item_version SET status='APPROVED',approved_by=1,approved_at=CURRENT_TIMESTAMP WHERE version_id='EIV_ELECTRICITY_1'");
        jdbc.update("UPDATE biz_measurement_unit_version SET status='APPROVED',approved_by=1,approved_at=CURRENT_TIMESTAMP WHERE version_id='EUV_KWH_1'");
    }
    @Test void ownerCanCompleteBillInputWithoutTechnicalPointIdentityAndReviewerSealsIt() throws Exception {
        String source=postData(BASE+"/sources",2,"PLATFORM_ADMIN", """
            {"idempotencyKey":"source","configuration":{"name":"全楼实际电费账单","energyItemCode":"ELECTRICITY","unitCode":"KWH",
             "channel":"BILL","pathway":"PURCHASED_ELECTRICITY","purpose":"PRIMARY","coverage":"WHOLE_BUILDING","meterOverlap":"NO_METER",
             "timezoneId":"Asia/Shanghai","effectiveFrom":"2020-01-01","evidence":"独立样例合同及覆盖清单","reason":"初始化来源"}}
            """).path("sourceId").asText();
        postData(BASE+"/sources/"+source+"/submit",2,"PLATFORM_ADMIN","{\"expectedRevision\":0,\"reason\":\"提交\"}");
        postData(BASE+"/sources/"+source+"/approve",3,"PLATFORM_ADMIN","{\"expectedRevision\":1,\"reason\":\"审核\"}");
        var batch=postData(BASE+"/batches",1,"BUILDING_OWNER","{\"month\":\"2024-01-01\",\"reason\":\"月度填报\",\"idempotencyKey\":\"create\"}");
        String id=batch.path("batchId").asText();
        var saved=mvc.perform(put(BASE+"/batches/"+id).header("Authorization",token(1,"BUILDING_OWNER")).contentType(MediaType.APPLICATION_JSON).content("""
            {"expectedRevision":0,"reason":"填写实际用量","rows":[{"sourceId":"%s","sourceRevision":2,"quantity":"928127.123456789012",
            "valueStatus":"CONFIRMED","startDate":"2024-01-01","endDate":"2024-02-01","evidence":"账单凭证","dataNature":"NATIVE_QUANTITY"}]}
            """.formatted(source))).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].quantity").value("928127.123456789012")).andReturn();
        assertThat(json.readTree(saved.getResponse().getContentAsString()).path("data").path("rows").get(0).has("pointId")).isFalse();
        postData(BASE+"/batches/"+id+"/preview",1,"BUILDING_OWNER","{}");
        postData(BASE+"/batches/"+id+"/submit",1,"BUILDING_OWNER","{\"expectedRevision\":1,\"reason\":\"提交\",\"idempotencyKey\":\"submit\"}");
        mvc.perform(post(BASE+"/batches/"+id+"/approve").header("Authorization",token(1,"PLATFORM_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":2,\"reason\":\"自行审核应拒绝\",\"idempotencyKey\":\"self\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("REPORTING_REVIEW_SEPARATION"));
        postData(BASE+"/batches/"+id+"/approve",3,"PLATFORM_ADMIN","{\"expectedRevision\":2,\"reason\":\"独立核对\",\"idempotencyKey\":\"approve\"}");
        var sealed=postData(BASE+"/batches/"+id+"/seal",3,"PLATFORM_ADMIN","{\"expectedRevision\":3,\"reason\":\"正式封账\",\"idempotencyKey\":\"seal\"}");
        assertThat(sealed.path("snapshot").path("nativeStatus").asText()).isEqualTo("COMPLETE");
        assertThat(sealed.path("snapshot").path("tceStatus").asText()).isEqualTo("NOT_CALCULATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_dependency_change WHERE source_object_type='ENERGY_REPORTING_BATCH'",Integer.class)).isEqualTo(1);
    }
    @Test void httpContractRejectsAnonymousUnauthorizedBuildingAndInvalidBody() throws Exception {
        mvc.perform(get(BASE+"/context?month=2024-01-01")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("REPORTING_UNAUTHORIZED"));
        mvc.perform(get("/v1/energy-reporting/buildings/BLD002/context?month=2024-01-01").header("Authorization",token(1,"BUILDING_OWNER"))).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/context?month=2024-01-02").header("Authorization",token(1,"BUILDING_OWNER"))).andExpect(status().isBadRequest());
        mvc.perform(post(BASE+"/sources").header("Authorization",token(1,"BUILDING_OWNER")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE+"/batches").header("Authorization",token(1,"BUILDING_OWNER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"2024-01-01\",\"reason\":\"填报\",\"idempotencyKey\":\"owner\"}"))
                .andExpect(status().isOk());
        var docs=mvc.perform(get("/v3/api-docs").header("Authorization",token(1,"PLATFORM_ADMIN"))).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(docs.getResponse().getContentAsString()).path("paths").has("/v1/energy-reporting/buildings/{building}/batches")).isTrue();
    }
    @Test void refreshSourcesHttpCommandRemainsAnIdempotentOwnerDraftOperation() throws Exception {
        var batch=postData(BASE+"/batches",1,"BUILDING_OWNER","{\"month\":\"2024-01-01\",\"reason\":\"先建草稿\",\"idempotencyKey\":\"refresh-create\"}");
        String path=BASE+"/batches/"+batch.path("batchId").asText()+"/refresh-sources";
        String command="{\"expectedRevision\":0,\"reason\":\"更新来源\",\"idempotencyKey\":\"refresh-command\"}";
        var refreshed=postData(path,1,"BUILDING_OWNER",command);
        assertThat(refreshed.path("status").asText()).isEqualTo("DRAFT");assertThat(refreshed.path("revision").asLong()).isEqualTo(1);
        assertThat(postData(path,1,"BUILDING_OWNER",command)).isEqualTo(refreshed);
        mvc.perform(post(path).header("Authorization",token(2,"PLATFORM_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":1,\"reason\":\"不能改他人草稿\",\"idempotencyKey\":\"foreign-refresh\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path.replace("BLD001","BLD002")).header("Authorization",token(1,"BUILDING_OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content(command)).andExpect(status().isForbidden());
    }
    @Test void heatMaterialCanCreateCo2MassFactorWithoutClaimingCo2eDirectCoverage() throws Exception {
        var source=postData("/v1/carbon-management/factor-sources",2,"PLATFORM_ADMIN", """
            {"sourceCode":"HEAT_SAMPLE_SOURCE","sourceName":"已确认资料热力参数","publisher":"隔离验收来源",
             "documentReference":"测试来源版本","publicationYear":2024,"publishedOn":"2024-01-01","applicabilityNote":"专用隔离样例",
             "evidenceReference":"热力0.11tCO2/GJ资料","usageNature":"FORMAL"}
            """);
        var sourceId=source.path("sourceVersionId").asText();
        var factor=postData("/v1/carbon-management/factors",2,"PLATFORM_ADMIN", """
            {"factorCode":"HEAT_SAMPLE_CO2","scopeType":"SCOPE_2","energyItemCode":"HEAT","factorCategory":"PURCHASED_HEAT",
             "resultBasis":"GAS_MASS","gasCode":"CO2","gasCoverage":"CO2_ONLY_HEAT","sourceVersionId":"%s","applicabilityLevel":"NATIONAL",
             "inputUnitCode":"GJ","usageNature":"FORMAL","effectiveFrom":"2024-01-01T00:00:00","effectiveTo":"2025-01-01T00:00:00",
             "formulaVersionId":"CFV_HEAT_CO2_V1","roundingPolicyVersionId":"CRP_DECIMAL128_V1",
             "components":[{"componentType":"DIRECT_EMISSION_FACTOR","value":"110","unit":"KG_CO2/GJ","sourceVersionId":"%s","evidenceReference":"0.11tCO2/GJ归一"}]}
            """.formatted(sourceId,sourceId));
        assertThat(factor.path("gasCoverage").asText()).isEqualTo("CO2_ONLY_HEAT");
        assertThat(factor.path("status").asText()).isEqualTo("PENDING_REVIEW");
        String id=factor.path("factorVersionId").asText();
        postData("/v1/carbon-management/factors/"+id+"/review",3,"PLATFORM_ADMIN","{\"expectedRevision\":0,\"approved\":true,\"reviewComment\":\"独立核对来源及口径\"}");
        assertThat(postData("/v1/carbon-management/factors/"+id+"/activate",3,"PLATFORM_ADMIN","{\"expectedRevision\":1}").path("status").asText()).isEqualTo("ACTIVE");
    }

    private JsonNode postData(String path,long user,String role,String body)throws Exception {
        var reply=mvc.perform(post(path).header("Authorization",token(user,role)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        return json.readTree(reply.getResponse().getContentAsString()).path("data");
    }
    private String token(long user,String role){return "Bearer "+jwt.generateToken(user,"reporting-"+user,List.of(role),1L);}
}
