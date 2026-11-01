package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDutyService;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static com.platform.carbon.CarbonIntensityContracts.*;
import static com.platform.carbon.CarbonIntensityMatcherTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@Sql("/carbon-intensity-schema-test.sql")
class CarbonIntensityCatalogApiTest {
    @Autowired MockMvc mvc; @Autowired JwtService jwt; @Autowired ObjectMapper json;
    @Autowired CarbonIntensityCatalogService catalog;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc;
    @MockBean StoredCarbonIntensityReader actuals;
    @MockBean CarbonIntensityYtdReader ytd;
    @MockBean TokenCacheService tokens; @MockBean BackendDutyService duties;
    @BeforeEach void login() { when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE); }
    @Test void savesUnknownTimeWithoutInventingDatesAndRejectsStaleVersionWhileRetainingHistory() throws Exception {
        var input=input(null,null,"PENDING");
        var a=catalog.save(1,List.of("PLATFORM_ADMIN"),input);
        var b=catalog.save(1,List.of("PLATFORM_ADMIN"),input(a.catalogId(),a.versionId(),"KNOWN"));
        assertThat(b.revision()).isEqualTo(2);
        assertThat(catalog.list()).extracting(Version::versionId).contains(a.versionId(),b.versionId());
        assertThatThrownBy(()->catalog.save(1,List.of("PLATFORM_ADMIN"),input(a.catalogId(),a.versionId(),"KNOWN"))).hasMessageContaining("已更新");
        mvc.perform(get("/v1/carbon-intensity/catalog").header("Authorization",bearer("BUILDING_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].entries[0].constraintValue").value("56.71"));
    }
    @Test void requiresLoginRoleAndExistingMaintainDutyAndDoesNotGrantOwnersWrites() throws Exception {
        mvc.perform(get("/v1/carbon-intensity/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/carbon-intensity/catalog").contentType("application/json").content(json.writeValueAsString(input(null,null,"PENDING"))).header("Authorization",bearer("BUILDING_OWNER"))).andExpect(status().isForbidden());
        doThrow(CarbonIntensityCatalogService.error(403,"FORBIDDEN","缺少维护职责")).when(duties).requireDuty(anyLong(),any());
        mvc.perform(post("/v1/carbon-intensity/catalog").contentType("application/json").content(json.writeValueAsString(input(null,null,"PENDING"))).header("Authorization",bearer("PLATFORM_ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void invalidKnownTimeCannotEnterCatalog() {
        assertThatThrownBy(()->catalog.save(1,List.of("PLATFORM_ADMIN"),new Save(null,null,"标准","版本","test://ref",null,List.of(rule("45","办公","KNOWN",null,null,"56.71"))))).hasMessageContaining("起始日");
        assertThat(catalog.list()).isEmpty();
    }
    @Test void savingDirectoryOnceIsEnoughForAnnualAndYtdWithoutAnyProjectSelection() throws Exception {
        jdbc.update("UPDATE building SET region_code='450103',building_type='办公' WHERE building_id='BLD001'");
        var source=catalog.save(1,List.of("PLATFORM_ADMIN"),input(null,null,"PENDING"));
        when(actuals.read(anyString(),anyString(),any(),any(),any())).thenReturn(actual("28.355","CO2_ONLY"));
        when(ytd.read(anyLong(),anyCollection(),anyString(),anyInt(),anyInt(),anyString())).thenReturn(actual("28.355","CO2_ONLY"));
        for(String mode:List.of("FORMAL_YEAR","MONTHLY_YTD")) {
            var request=get("/v1/carbon-intensity/buildings/BLD001").param("mode",mode).param("year","2026");
            if("MONTHLY_YTD".equals(mode)) request.param("month","9");
            mvc.perform(request.header("Authorization",bearer("PLATFORM_ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.matchStatus").value("APPLICABILITY_TIME_UNCLEAR"))
                    .andExpect(jsonPath("$.data.percentage").value("50.000000"))
                    .andExpect(jsonPath("$.data.percentageStatus").value("AVAILABLE"))
                    .andExpect(jsonPath("$.data.comparisonStatus").value("NOT_COMPARABLE"))
                    .andExpect(jsonPath("$.data.reference.versionId").value(source.versionId()))
                    .andExpect(jsonPath("$.data.reference.rule.applicableFrom").isEmpty())
                    .andExpect(jsonPath("$.data.reference.rule.projectUse").doesNotExist());
        }
        assertThat(catalog.list()).hasSize(1);
    }
    private Save input(String id,String expected,String status) { return new Save(id,expected,"测试标准","2026版","test://source",START,List.of(rule("4501","办公",status,"KNOWN".equals(status)?START:null,null,"56.71"))); }
    private String bearer(String role) { return "Bearer "+jwt.generateToken(1L,"reader",List.of(role),1L); }
}
