package com.platform.hvac.asset.ledger;

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
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** JWT、管理员权限、参数校验和真实 H2 配置事务的 HTTP 合约；不访问厂家或真实时序库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired LedgerService ledger;

    @Test
    @Transactional
    void queriesGroupedScopeAndPersistsTypeRuleWithRevisionAndAudit() throws Exception {
        String token = login("admin", "123456");
        String body = json.writeValueAsString(Map.of("professionalGroup", "HVAC", "operationApplicable", true,
                "evidence", "隔离测试确认类型映射", "expectedRevision", 0));
        mvc.perform(put("/v1/assets/equipment-types/WCR/ledger-display-rule")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(get("/v1/assets/ledger").param("buildingId", "BLD001").param("size", "1").param("professionalGroup", "HVAC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.groupCounts.ALL").value(4))
                .andExpect(jsonPath("$.data.groupCounts.UNCLASSIFIED").value(3))
                .andExpect(jsonPath("$.data.items[0].equipment.buildingId").value("BLD001"))
                .andExpect(jsonPath("$.data.items[0].operation.value").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].displayPoint.status").value("UNCONFIGURED"));
        mvc.perform(put("/v1/assets/equipment-types/WCR/ledger-display-rule")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ASSET_STATE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_config_audit WHERE target_id='WCR'", Integer.class)).isEqualTo(1);
        mvc.perform(get("/v1/assets/ledger/type-rules").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].typeCode").value("WCR"));
    }

    @Test
    @Transactional
    void classificationOverrideIsExplicitAndNullRestoresTypeInheritance() throws Exception {
        String token = login("admin", "123456");
        String path = "/v1/assets/equipment/EQUIP_PUMP_B1/ledger-classification";
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"professionalGroup\":\"WATER\",\"evidence\":\"隔离测试显式覆盖\",\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(get("/v1/assets/ledger").header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD001").param("professionalGroup", "WATER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].classificationSource").value("EQUIPMENT"))
                .andExpect(jsonPath("$.data.items[0].classificationRevision").value(1));
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"professionalGroup\":null,\"evidence\":\"恢复继承\",\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.professionalGroup").doesNotExist());
        mvc.perform(get("/v1/assets/ledger").header("Authorization", "Bearer " + token)
                        .param("buildingId", "BLD001").param("professionalGroup", "WATER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @Transactional
    void rejectsAnonymousNonAdminInvalidRulesAndWrongScope() throws Exception {
        mvc.perform(get("/v1/assets/ledger")).andExpect(status().isUnauthorized());
        // 登录在独立事务锁账号；测试账号也需独立提交，不能让未提交夹具制造伪登录失败。
        var fixture = new org.springframework.transaction.support.TransactionTemplate(transactions);
        fixture.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fixture.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO sys_user(id,username,password,nickname,status,del_flag) VALUES(9001,'ledger-reader',?,'隔离读者',1,0)", passwords.encode("123456"));
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(9001,11)");
        });
        try {
            String reader = login("ledger-reader", "123456");
            mvc.perform(get("/v1/assets/ledger").header("Authorization", "Bearer " + reader)).andExpect(status().isForbidden());
            mvc.perform(put("/v1/assets/equipment-types/WCR/ledger-display-rule").header("Authorization", "Bearer " + reader)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"professionalGroup\":\"HVAC\",\"operationApplicable\":true,\"evidence\":\"权限测试\",\"expectedRevision\":0}"))
                    .andExpect(status().isForbidden());
        } finally {
            fixture.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM sys_user_role WHERE user_id=9001");
                jdbc.update("DELETE FROM sys_user WHERE id=9001");
            });
        }
        String admin = login("admin", "123456");
        mvc.perform(get("/v1/assets/ledger").param("size", "101").header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/assets/ledger").param("professionalGroup", "FAKE").header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/assets/ledger").param("buildingId", "BLD001").param("spaceId", "SPACE002")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(put("/v1/assets/equipment-types/WCR/ledger-display-rule").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"professionalGroup\":\"HVAC\",\"operationApplicable\":true,\"evidence\":\"\",\"expectedRevision\":-1}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_type_rule", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_config_audit", Integer.class)).isZero();
    }

    @Test
    void auditFailureRollsBackTypeRuleThroughActualTransactionProxy() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.saveRule("WCR",
                new LedgerContracts.TypeRuleRequest("HVAC", true, "MAIN", "TWout", "隔离事务回滚测试", 0L),
                null, java.util.Set.of("PLATFORM_ADMIN")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_type_rule WHERE type_code='WCR'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_ledger_config_audit WHERE target_id='WCR'", Integer.class)).isZero();
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode result = json.readTree(response).path("data");
        return result.path("token").asText();
    }
}
