package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.cache.*;
import com.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.platform.carbon.CarbonPlanContracts.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/carbon-plan-test.sql")
class CarbonPlanApiContractTest {
    private static final String BASE="/v1/carbon-management/buildings/BLD001/plan";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc;
    @MockBean TokenCacheService tokens;
    @MockBean BuildingScopeCacheService scopes;
    @MockBean BackendDutyService duties;
    @MockBean CarbonCalculationRepository formalResults;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void setup() {
        when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(duties.hasDuty(anyLong(),any())).thenReturn(true);
        // HTTP登录态另开事务读当前账号，独立测试审核人必须先提交，不能用未提交行冒充有效账号。
        actorWrite(()->{
            jdbc.update("INSERT INTO sys_user(id,username,password,status,del_flag) VALUES (910002,'plan-reviewer-test','not-a-login-password',1,0),(910003,'plan-activator-test','not-a-login-password',1,0)");
        });
    }
    @AfterEach void removeActors() { actorWrite(()->jdbc.update("DELETE FROM sys_user WHERE id IN (910002,910003)")); }
    private void actorWrite(Runnable work) {
        var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.executeWithoutResult(s->work.run());
    }
    @Test void lifecycleFreezesPlanAndRevisionRetainsOldVersionAndDutySeparation() throws Exception {
        Version version=create(null);
        version=save(version);
        version=action(version,"submit",1);
        doThrow(new com.platform.framework.exception.BusinessException(403,"职责分离拒绝")).when(duties).requireSeparation(1,1);
        mvc.perform(post(BASE+"/versions/"+version.versionId()+"/approve").header("Authorization",token(1,"PLATFORM_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Transition(version.revision(),"本人审核")))).andExpect(status().isForbidden());
        version=action(version,"approve",910002);version=action(version,"activate",910003);
        mvc.perform(get(BASE).param("year","2026").param("throughMonth","2026-02").header("Authorization",token(1,"PLATFORM_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.annualBaseline").value("160"))
                .andExpect(jsonPath("$.data.annualQuota").value("136.00")).andExpect(jsonPath("$.data.months.length()").value(12));
        mvc.perform(put(BASE+"/versions/"+version.versionId()).header("Authorization",token(1,"PLATFORM_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Save(version.revision(),CarbonPlanFixture.configuration(),"禁止修改生效")))).andExpect(status().isConflict());
        Version replacement=create(version.versionId());replacement=save(replacement);replacement=action(replacement,"submit",1);replacement=action(replacement,"approve",910002);action(replacement,"activate",910003);
        assertThat(jdbc.queryForObject("SELECT status FROM biz_carbon_plan_version WHERE version_id=?",String.class,version.versionId())).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_plan_version WHERE status='ACTIVE' AND building_id='BLD001' AND plan_year=2026",Integer.class)).isOne();
        verify(duties,atLeastOnce()).requireSeparation(1,910002);
    }
    @Test void rejectsAnonymousRoleBuildingDutyAndInvalidPeriodAndDoesNotInventMissingPlan() throws Exception {
        mvc.perform(get(BASE).param("year","2026")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).param("year","2026").header("Authorization",token(1,"THIRD_PARTY"))).andExpect(status().isForbidden());
        mvc.perform(get(BASE.replace("BLD001","BLD002")).param("year","2026").header("Authorization",token(1,"BUILDING_OWNER"))).andExpect(status().isForbidden());
        mvc.perform(get(BASE).param("year","2026").param("throughMonth","2025-12").header("Authorization",token(1,"PLATFORM_ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("year","2026").header("Authorization",token(1,"PLATFORM_ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NO_ACTIVE_PLAN")).andExpect(jsonPath("$.data.annualBaseline").isEmpty());
        when(duties.hasDuty(1,BackendDuty.CARBON_RULE_MAINTAIN)).thenReturn(false);
        mvc.perform(post(BASE+"/versions").header("Authorization",token(1,"PLATFORM_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Create(2026,null,"无维护职责")))).andExpect(status().isForbidden());
    }
    private Version create(String copied) throws Exception {
        var result=mvc.perform(post(BASE+"/versions").header("Authorization",token(1,"PLATFORM_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Create(2026,copied,"隔离自动化计划")))).andExpect(status().isOk()).andReturn();
        return json.treeToValue(json.readTree(result.getResponse().getContentAsString()).path("data"),Version.class);
    }
    private Version save(Version version) throws Exception {
        var result=mvc.perform(put(BASE+"/versions/"+version.versionId()).header("Authorization",token(1,"PLATFORM_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Save(version.revision(),CarbonPlanFixture.configuration(),"仅隔离自动化测试")))).andExpect(status().isOk()).andReturn();
        return json.treeToValue(json.readTree(result.getResponse().getContentAsString()).path("data"),Version.class);
    }
    private Version action(Version version,String action,long user) throws Exception {
        var result=mvc.perform(post(BASE+"/versions/"+version.versionId()+"/"+action).header("Authorization",token(user,"PLATFORM_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Transition(version.revision(),"自动化测试")))).andExpect(status().isOk()).andReturn();
        return json.treeToValue(json.readTree(result.getResponse().getContentAsString()).path("data"),Version.class);
    }
    private String token(long id,String role) { return "Bearer "+jwt.generateToken(id,"plan-test",List.of(role),1L); }
}
