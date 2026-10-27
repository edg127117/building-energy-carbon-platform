package com.platform.energy.parameters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:parameter_packages;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@org.springframework.test.context.jdbc.Sql("classpath:energy-reporting-carbon-schema-test.sql")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EnergyParameterPackageApiContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void exposesPrecisionHubeiCandidatesAndCreatesOnlyPendingCarbonVersions() throws Exception {
        mockMvc.perform(get("/v1/energy-parameter-packages/catalog"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_PARAMETER_PACKAGE_UNAUTHORIZED"));

        String token = login("admin", "123456");
        JsonNode catalog = data(mockMvc.perform(get("/v1/energy-parameter-packages/catalog")
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn());
        JsonNode b01 = packageByCode(catalog.path("packages"), "B01");
        JsonNode b02 = packageByCode(catalog.path("packages"), "B02");
        JsonNode standardCoal = itemByCode(b01.path("items"), "STANDARD_COAL_LHV");
        JsonNode hubei2023 = itemByCode(b02.path("items"), "HUBEI_ELECTRICITY_FACTOR_2023");
        assertThat(standardCoal.path("sourceValue").isTextual()).isTrue();
        assertThat(standardCoal.path("sourceValue").asText()).isEqualTo("29307.6");
        assertThat(hubei2023.path("normalizedValue").isTextual()).isTrue();
        assertThat(hubei2023.path("normalizedValue").asText()).isEqualTo("404.4");
        assertThat(hubei2023.path("requiredInputs").toString())
                .contains("420000").contains("2023").contains("1139517");

        mockMvc.perform(post("/v1/energy-parameter-packages/preview")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(hubei2023Request("PARAMETERS-HUBEI-INVALID")
                                .replace("MEE_HUBEI_2023", "MEE-HUBEI-2023")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyForImport").value(false))
                .andExpect(jsonPath("$.data.issues[?(@.code == 'SOURCE_CODE_INVALID')]").exists());

        ensureElectricityRuleOptions();
        grant("CARBON_RULE_MAINTAIN");
        String request = hubei2023Request("PARAMETERS-HUBEI-2023");
        mockMvc.perform(post("/v1/energy-parameter-packages/preview")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyForImport").value(true))
                .andExpect(jsonPath("$.data.items[0].normalizedValue").value("404.4"));

        mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completionStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.awaitingIndependentReview").value(true))
                .andExpect(jsonPath("$.data.created[1].itemCode").value("HUBEI_ELECTRICITY_FACTOR_2023"))
                .andExpect(jsonPath("$.data.created[1].status").value("PENDING_REVIEW"));
    }

    @Test
    void reportsRawCoalIdentityGapAndKeepsPartialImportIdempotent() throws Exception {
        String token = login("admin", "123456");
        mockMvc.perform(post("/v1/energy-parameter-packages/preview")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(rawCoalRequest(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyForImport").value(false))
                .andExpect(jsonPath("$.data.issues[?(@.code == 'RAW_COAL_ENERGY_ITEM_REQUIRED')]").exists());
        for (String substitute : List.of("SMOKE_COAL", "NATURAL_GAS")) {
            mockMvc.perform(post("/v1/energy-parameter-packages/preview")
                            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                            .content(rawCoalRequest(substitute)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.readyForImport").value(false))
                    .andExpect(jsonPath("$.data.issues[?(@.code == 'RAW_COAL_ENERGY_ITEM_IDENTITY_INVALID')]").exists());
        }

        int standardCoalVersionsBefore = countStandardCoalVersions();

        String energyDuty = grant("ENERGY_RULE_MAINTAIN");
        jdbc.update("""
                UPDATE biz_measurement_unit_version
                SET status='APPROVED',approved_by=1,approved_at=CURRENT_TIMESTAMP,config_revision=1
                WHERE version_id IN ('EUV_GJ_1','EUV_TCE_1')
                """);
        String request = standardCoalRequest("PARAMETERS-B01-STANDARD", "标准煤正式资料定位");
        JsonNode created = data(mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completionStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.awaitingIndependentReview").value(true))
                .andExpect(jsonPath("$.data.created[0].status").value("PENDING_EXPERT"))
                .andReturn());
        String versionId = created.path("created").get(0).path("versionId").asText();
        assertThat(versionId).isNotBlank();
        assertThat(standardCoalVersionStatus("SCLV_STANDARD_1")).isEqualTo("PENDING_EXPERT");
        assertThat(countStandardCoalVersions()).isEqualTo(standardCoalVersionsBefore + 1);

        jdbc.update("UPDATE sys_user_backend_duty SET status='REVOKED' WHERE assignment_id=?", energyDuty);
        mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_PARAMETER_PACKAGE_FORBIDDEN"));
        jdbc.update("UPDATE sys_user_backend_duty SET status='ACTIVE' WHERE assignment_id=?", energyDuty);
        JsonNode replayed = data(mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn());
        assertThat(replayed.path("created").get(0).path("versionId").asText()).isEqualTo(versionId);
        assertThat(countStandardCoalVersions()).isEqualTo(standardCoalVersionsBefore + 1);

        mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(standardCoalRequest("PARAMETERS-B01-STANDARD", "不同的证据定位")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ENERGY_PARAMETER_PACKAGE_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void permitsOnlyDocumentedDefaultFactorToOmitDataYearAndStillRequiresBoundedAccountingYear() throws Exception {
        String token = login("admin", "123456");
        ensureElectricityRuleOptions();
        grant("CARBON_RULE_MAINTAIN");
        var request = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(hubei2023Request("DEFAULT-UNDATED"));
        request.set("itemCodes", objectMapper.valueToTree(List.of("ELECTRICITY_FACTOR")));
        request.put("effectiveFrom", "2022-01-01T00:00:00");
        request.put("effectiveTo", "2023-01-01T00:00:00");
        var electricity = (com.fasterxml.jackson.databind.node.ObjectNode) request.path("electricity");
        electricity.put("applicabilityLevel", "NATIONAL");
        electricity.putNull("regionCode");
        electricity.putNull("dataYear");
        electricity.put("accountingYear", 2022);
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) request.path("source");
        source.put("sourceCode", "MEE_2023_43");
        source.put("sourceName", "旧全国默认电力因子");
        source.put("documentReference", "环办气候函〔2023〕43号");
        source.put("sourceDocumentVersion", "2023年通知");
        source.put("publicationYear", 2023);
        source.put("applicabilityNote", "已确认的2021/2022核算年度全国默认值；来源未声明统计数据年");
        mockMvc.perform(post("/v1/energy-parameter-packages/preview").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.readyForImport").value(true));
        var imported = data(mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk()).andReturn());
        String version = imported.path("created").get(1).path("versionId").asText();
        assertThat(jdbc.queryForObject("SELECT data_year FROM biz_carbon_factor_version WHERE factor_version_id=?", Integer.class, version)).isNull();
        assertThat(jdbc.queryForObject("SELECT accounting_year FROM biz_carbon_factor_version WHERE factor_version_id=?", Integer.class, version)).isEqualTo(2022);

        request.put("idempotencyKey", "DEFAULT-MISSING-YEAR");
        electricity.putNull("accountingYear");
        assertPreviewIssue(token, request.toString(), "ELECTRICITY_YEAR_REQUIRED");
        electricity.put("accountingYear", 2023);
        assertPreviewIssue(token, request.toString(), "ELECTRICITY_YEAR_REQUIRED");
        electricity.put("accountingYear", 2022);
        request.putNull("effectiveTo");
        assertPreviewIssue(token, request.toString(), "ELECTRICITY_EFFECTIVE_PERIOD_REQUIRED");
        request.put("effectiveTo", "2023-01-01T00:00:00");
        electricity.put("accountingYear", -1);
        electricity.put("dataYear", 2021);
        assertPreviewIssue(token, request.toString(), "ELECTRICITY_YEAR_INVALID");
        var hubei = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(hubei2023Request("HUBEI-MISSING-DATA-YEAR"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) hubei.path("electricity")).putNull("dataYear");
        assertPreviewIssue(token, hubei.toString(), "ELECTRICITY_YEAR_REQUIRED");
    }

    @Test
    void exposesOfficialElectricityFactorPrecisionAndDoesNotSkipFormulaReview() throws Exception {
        String token = login("admin", "123456");
        JsonNode catalog = data(mockMvc.perform(get("/v1/energy-parameter-packages/catalog")
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn());
        JsonNode definition = itemByCode(packageByCode(catalog.path("packages"), "B01").path("items"),
                "ELECTRICITY_CALORIFIC_TCE_PARAMETER");
        assertThat(definition.path("sourceValue").asText()).isEqualTo("0.1229");
        assertThat(definition.path("sourceUnit").asText()).isEqualTo("KGCE/KWH");
        assertThat(definition.path("normalizedValue").asText()).isEqualTo("0.0001229");
        assertThat(definition.path("normalizedUnit").asText()).isEqualTo("TCE_PER_KWH");
        grant("ENERGY_RULE_MAINTAIN");
        jdbc.update("UPDATE biz_measurement_unit_version SET status='APPROVED',approved_by=1,approved_at=CURRENT_TIMESTAMP,config_revision=1 WHERE version_id IN ('EUV_KWH_1','EUV_TCE_1')");
        String request = """
                {"packageCode":"B01","itemCodes":["ELECTRICITY_CALORIFIC_TCE_FORMULA"],
                 "source":{"sourceCode":"NBS_ELECTRICITY_2025","sourceName":"企业电力消费当量折标系数",
                 "publisher":"国家统计局能源统计司","documentReference":"国家统计局能源统计司咨询答复",
                 "sourceDocumentVersion":"2025-12-22答复","publicationYear":2025,
                 "applicabilityNote":"电力消费当量折标；项目采用独立记录，不替换历史",
                 "evidenceReference":"https://www.stats.gov.cn/hd/lyzx/zxgk/202601/t20260109_1962280.html",
                 "energySourceType":"STANDARD"},"effectiveFrom":"2026-01-01T00:00:00",
                 "effectiveTo":"2027-01-01T00:00:00","idempotencyKey":"OFFICIAL-ELECTRICITY-API"}
                """;
        JsonNode imported = data(mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.awaitingIndependentReview").value(true))
                .andExpect(jsonPath("$.data.created[0].status").value("PENDING_EXPERT")).andReturn());
        String versionId = imported.path("created").get(0).path("versionId").asText();
        JsonNode replayed = data(mockMvc.perform(post("/v1/energy-parameter-packages/import")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn());
        assertThat(replayed.path("created").get(0).path("versionId").asText()).isEqualTo(versionId);
        assertPreviewIssue(token, request.replace("ELECTRICITY_CALORIFIC_TCE_FORMULA", "ELECTRICITY_CALORIFIC_TCE_PARAMETER"),
                "ELECTRICITY_CALORIFIC_FORMULA_NOT_UNIQUE");
    }

    private void assertPreviewIssue(String token, String body, String code) throws Exception {
        mockMvc.perform(post("/v1/energy-parameter-packages/preview").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.readyForImport").value(false))
                .andExpect(jsonPath("$.data.issues[?(@.code == '" + code + "')]").exists());
    }

    private void ensureElectricityRuleOptions() {
        Integer formulaCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_formula_version
                WHERE formula_code='PURCHASED_ELECTRICITY_LOCATION_CO2'
                """, Integer.class);
        if (formulaCount != null && formulaCount == 0) {
            jdbc.update("""
                    INSERT INTO biz_carbon_formula_version
                    (formula_version_id,formula_code,version_no,algorithm_code,result_basis,gas_coverage,
                     usage_nature,status,effective_from)
                    VALUES ('CFV_ELECTRICITY_CO2_V1','PURCHASED_ELECTRICITY_LOCATION_CO2',1,
                    'PURCHASED_ELECTRICITY_LOCATION_CO2_V1','GAS_MASS','CO2_ONLY_ELECTRICITY',
                    'FORMAL','ACTIVE','2000-01-01 00:00:00')
                    """);
        }
        Integer roundingCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_rounding_policy_version
                WHERE rounding_policy_version_id='CRP_DECIMAL128_V1'
                """, Integer.class);
        if (roundingCount != null && roundingCount == 0) {
            jdbc.update("""
                    INSERT INTO biz_carbon_rounding_policy_version
                    (rounding_policy_version_id,policy_code,version_no,calculation_context,total_scale,
                     intensity_scale,share_scale,rounding_mode,status)
                    VALUES ('CRP_DECIMAL128_V1','CARBON_RESULT_ROUNDING',1,'DECIMAL128',6,6,12,'HALF_UP','ACTIVE')
                    """);
        }
    }

    private int countStandardCoalVersions() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_standard_coal_lhv_version v
                JOIN biz_standard_coal_lhv i ON i.lhv_id=v.lhv_id
                WHERE i.lhv_code='STANDARD_COAL_LHV'
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private String standardCoalVersionStatus(String versionId) {
        return jdbc.queryForObject("SELECT status FROM biz_standard_coal_lhv_version WHERE version_id=?",
                String.class, versionId);
    }

    private String grant(String duty) {
        String assignmentId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)
                """, assignmentId, 1L, duty);
        return assignmentId;
    }

    private String hubei2023Request(String key) {
        return """
                {"packageCode":"B02","itemCodes":["HUBEI_ELECTRICITY_FACTOR_2023"],
                 "source":{"sourceCode":"MEE_HUBEI_2023","sourceName":"湖北省电力平均二氧化碳排放因子",
                 "publisher":"生态环境部","documentReference":"https://www.mee.gov.cn/xxgk2018/xxgk/xxgk01/202512/t20251231_1139517.html",
                 "sourceDocumentVersion":"2025-12-31 公告","publicationYear":2025,
                 "applicabilityNote":"湖北省，位置法电力因子；资料数据年 2023，核算年由请求明确。",
                 "evidenceReference":"生态环境部公告正文中湖北 2023 年数据。"},
                 "effectiveFrom":"2024-01-01T00:00:00","effectiveTo":"2025-01-01T00:00:00",
                 "electricity":{"applicabilityLevel":"PROVINCE","regionCode":"420000","dataYear":2023,"accountingYear":2024},
                 "idempotencyKey":"%s"}
                """.formatted(key);
    }

    private String rawCoalRequest(String rawCoalEnergyItemCode) {
        String dependencies = rawCoalEnergyItemCode == null ? "{}"
                : "{\"rawCoalEnergyItemCode\":\"%s\"}".formatted(rawCoalEnergyItemCode);
        return """
                {"packageCode":"B01","itemCodes":["RAW_COAL_DIRECT_TCE_PARAMETER"],
                 "source":{"sourceCode":"GB_T_2589","sourceName":"综合能耗计算通则","publisher":"国家标准化管理部门",
                 "documentReference":"GB/T 2589","sourceDocumentVersion":"现行版","publicationYear":2020,
                 "applicabilityNote":"固定燃烧折标","evidenceReference":"用户确认资料","energySourceType":"STANDARD"},
                 "effectiveFrom":"2024-01-01T00:00:00","dependencies":%s,"idempotencyKey":"PREVIEW-NOT-USED"}
                """.formatted(dependencies);
    }

    private String standardCoalRequest(String key, String evidence) {
        return """
                {"packageCode":"B01","itemCodes":["STANDARD_COAL_LHV"],
                 "source":{"sourceCode":"GB_T_2589","sourceName":"综合能耗计算通则","publisher":"国家标准化管理部门",
                 "documentReference":"GB/T 2589","sourceDocumentVersion":"现行版","publicationYear":2020,
                 "applicabilityNote":"标准煤低位热值","evidenceReference":"%s","energySourceType":"STANDARD"},
                 "effectiveFrom":"2024-01-01T00:00:00","idempotencyKey":"%s"}
                """.formatted(evidence, key);
    }

    private JsonNode packageByCode(JsonNode packages, String code) {
        for (JsonNode value : packages) {
            if (code.equals(value.path("packageCode").asText())) return value;
        }
        throw new AssertionError("Missing package: " + code);
    }

    private JsonNode itemByCode(JsonNode items, String code) {
        for (JsonNode value : items) {
            if (code.equals(value.path("itemCode").asText())) return value;
        }
        throw new AssertionError("Missing item: " + code);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        return data(result).path("token").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
