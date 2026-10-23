package com.platform.iot.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.activity.EnergyActivityDataContracts.*;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 + 正常登录 JWT + 真实职责/范围/审核事务；活动快照为隔离夹具，不代表现场能源核算。 */
@SpringBootTest(properties = {"audit-governance.allow-self-approval=false",
        "spring.datasource.url=jdbc:h2:mem:d01_authenticated;MODE=MySQL;DB_CLOSE_DELAY=0;DATABASE_TO_UPPER=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class D01AuthenticatedAcceptanceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RawTelemetryLedger ledger;
    @Autowired BuildingScopeService scope;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockBean EnergyActivityDataService activity;
    static final long TIME = 1767225600000L;
    static final String CORRECTIONS = "/v1/energy-aggregation/correction-versions";
    String admin;
    String reviewer;
    String owner;

    @BeforeTransaction void prepareAccounts() {
        // 登录态服务用新事务读取已提交账号；夹具仅提交到本类独立 H2，关闭上下文即销毁。
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=901",Integer.class) == 0) {
            jdbc.update("INSERT INTO sys_user(id,username,password,nickname,status,del_flag) VALUES (901,'d01_reviewer','123456','D01审核夹具',1,0),(902,'d01_owner','123456','D01负责人夹具',1,0)");
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (901,11),(902,10)");
        }
    }

    @BeforeEach void setup() throws Exception {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (901,'BLD001'),(902,'BLD001')");
        scope.evict(901L); scope.evict(902L);
        duty(1, "ENERGY_RULE_MAINTAIN");
        admin = login("admin"); reviewer = login("d01_reviewer"); owner = login("d01_owner");
        var raw = new RawActivityDataView("POINT001","D01_TOTAL","kWh","ELECTRICITY","GRID_PURCHASED",
                "CUMULATIVE","CONFIRMED",0,"MQTT_STANDARD_V1","D01","D01",100,TIME,TIME+1000,
                "Q0",false,"SYSTEM_DEFAULT_Q0_ONLY",null,0);
        when(activity.aggregationSnapshot(anyLong(),anyCollection(),eq("BLD001"),eq("POINT001"),eq(TIME),eq(TIME+1),anyLong()))
                .thenReturn(new AggregationActivitySnapshot("BLD001","POINT001","kWh","CUMULATIVE","CONFIRMED",0,
                        TIME,TIME+1,TIME+1000,TIME+1000,"POINT_ID_EVENT_TIME","APPEND_ONLY",List.of(raw)));
        ledger.reserve(event(), () -> null);
        ledger.storeFrozen(event(), () -> null);
    }

    @Test void approvalRequiresNormalLoginDutyScopeSeparationAndRevisionBeforeEffectiveValueChanges() throws Exception {
        mvc.perform(post(CORRECTIONS + "/missing/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0,\"reviewComment\":\"审核\"}")).andExpect(status().isUnauthorized());
        JsonNode pending = create(120, null);
        String id = pending.path("correctionVersionId").asText();
        mvc.perform(get(CORRECTIONS).header("Authorization", "Bearer " + reviewer)
                .param("buildingId","BLD001").param("pointId","POINT001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].correctionVersionId").value(id));
        approve(id, owner, 0).andExpect(status().isForbidden());
        approve(id, reviewer, 0).andExpect(status().isForbidden());
        approve(id, admin, 0).andExpect(status().isForbidden());
        duty(1,"ENERGY_RULE_REVIEW");
        approve(id, admin, 0).andExpect(status().isForbidden()); // 不为超级管理员放开自审。
        duty(901,"ENERGY_RULE_REVIEW");
        jdbc.update("DELETE FROM sys_user_building WHERE user_id=901"); refreshScope(901L);
        approve(id, reviewer, 0).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (901,'BLD001')"); refreshScope(901L);
        approve(id, reviewer, 99).andExpect(status().isConflict());
        assertEffective(100);
        mvc.perform(post(CORRECTIONS + "/" + id + "/approve").header("Authorization","Bearer " + reviewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":0,\"reviewComment\":\" \"}"))
                .andExpect(status().isBadRequest());
        approve(id, reviewer, 0).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertEffective(120);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task WHERE point_id='POINT001'",Integer.class)).isEqualTo(1);
        approve(id, reviewer, 0).andExpect(status().isConflict());

        // 恢复原始值仍创建新版本并走同一个审核入口，不能直接抹去已批准更正。
        JsonNode restore = create(100, pending.path("correctionId").asText());
        assertEffective(120);
        approve(restore.path("correctionVersionId").asText(), reviewer, 0).andExpect(status().isOk());
        assertEffective(100);
        assertThat(ledger.find("POINT001",TIME).snapshotJson()).contains("100.0");
    }

    @Test void retryUsesAuthenticatedOwnerAndRechecksBuildingAndFailedState() throws Exception {
        jdbc.update("UPDATE biz_telemetry_raw_intent SET storage_state='FAILED',retry_count=10,last_error='D01_TEST_FAILURE' WHERE point_id='POINT001'");
        String endpoint = "/v1/telemetry-conflicts/recovery/retry";
        String body = "{\"kind\":\"RAW\",\"id\":\"POINT001@" + TIME + "\"}";
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/telemetry-conflicts/recovery").param("buildingId","BLD001"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/telemetry-conflicts/recovery").header("Authorization","Bearer " + owner).param("buildingId","BLD002"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/telemetry-conflicts/recovery").header("Authorization","Bearer " + owner).param("buildingId","BLD001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].kind").value("RAW"));
        send(endpoint,owner,body).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO biz_telemetry_conflict_owner(building_id,user_id,assigned_by,assigned_at_ms) VALUES ('BLD001',902,1,0)");
        send(endpoint,reviewer,body).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM sys_user_building WHERE user_id=902"); refreshScope(902L);
        send(endpoint,owner,body).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT storage_state FROM biz_telemetry_raw_intent WHERE point_id='POINT001'",String.class)).isEqualTo("FAILED");
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (902,'BLD001')"); refreshScope(902L);
        send(endpoint,owner,body).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT storage_state FROM biz_telemetry_raw_intent WHERE point_id='POINT001'",String.class)).isEqualTo("PENDING");
        send(endpoint,owner,body).andExpect(status().isConflict());
    }

    private JsonNode create(int value, String correctionId) throws Exception {
        var request = json.createObjectNode().put("buildingId","BLD001").put("meterPointId","POINT001")
                .put("originalFactIdentity","POINT001@" + TIME).put("originalValue",100).put("correctedValue",value)
                .put("correctionReason","D01隔离回归").put("sourceType","MANUAL").put("evidenceReference","D01合成证据");
        if (correctionId != null) request.put("correctionId",correctionId);
        return json.readTree(send(CORRECTIONS,admin,request.toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private ResultActions approve(String id, String token, int revision) throws Exception {
        return send(CORRECTIONS + "/" + id + "/approve",token,
                "{\"expectedRevision\":" + revision + ",\"reviewComment\":\"D01核实通过\"}");
    }
    private ResultActions send(String path, String token, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization","Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String login(String username) throws Exception {
        return json.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
    }
    private void duty(long user, String duty) {
        jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)","D01_"+user+duty,user,duty);
    }
    private void refreshScope(long user) {
        // 多个 MockMvc 请求共用测试事务，直接改 JDBC 夹具后清除其 MyBatis 一级缓存。
        sqlSession.clearCache();
        scope.evict(user);
    }
    private void assertEffective(double expected) {
        assertThat(ledger.effective(List.of(event())).getFirst().value()).isEqualTo(expected);
    }
    private RawTelemetryEvent event() {
        return new RawTelemetryEvent("POINT001","D01_TOTAL","MQTT_STANDARD_V1","D01","D01","BLD001",null,"D01","D01","WCR",null,"PPE",100,TIME,TIME+1000,0,1,false);
    }
}
