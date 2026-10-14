package com.platform.iot.daikin.control;

import com.platform.audit.SecurityAuditService;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.security.JwtUserPrincipal;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实控制 API、H2 事务和模拟厂家；验证 TEST 无角色流程与原请求历史。 */
class DaikinControlFastApiTest {
    ControlFixture f;
    MockMvc mvc;
    UsernamePasswordAuthenticationToken auth;
    BuildingScopeService scope;

    @BeforeEach void setup() throws Exception {
        f = new ControlFixture(); f.props.setTestOpenControls(true);
        f.jdbc.update("DELETE FROM biz_daikin_control_grant");
        f.jdbc.update("DELETE FROM sys_user_building");
        String migration = new org.springframework.core.io.ClassPathResource("db/migration/mysql/V03__init_hvac_schema.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int buildingDdl = migration.indexOf("CREATE TABLE IF NOT EXISTS `building`");
        f.jdbc.execute(migration.substring(buildingDdl, migration.indexOf(';', buildingDdl) + 1));
        f.jdbc.update("""
                INSERT INTO building(building_id,building_name,building_type,total_gfa,climate_zone,region_code)
                VALUES('building','已对接建筑','办公',1000,'夏热冬冷','320100'),
                      ('other-building','未对接建筑','办公',1000,'夏热冬冷','320100'),
                      ('deleted-building','已删除建筑','办公',1000,'夏热冬冷','320100')
                """);
        f.jdbc.update("UPDATE building SET del_flag=1 WHERE building_id='deleted-building'");
        scope=mock(BuildingScopeService.class);
        when(scope.getAccessibleBuildingIds(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(java.util.Set.of());
        var grant = new DaikinControlGrantService(f.access, f.policy, f.store, List.of(), f.clock);
        var recovery = new DaikinControlRecoveryService(f.access, f.store, f.policy,
                List.of(new DaikinControlGrantReader() {
                    public boolean supports(String source) { return true; }
                    public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) { return f.gateway.read(target); }
                }), f.clock);
        mvc = MockMvcBuilders.standaloneSetup(new DaikinControlController(f.service),
                        new DaikinControlGrantController(grant), new DaikinControlRecoveryController(recovery),
                        new DaikinControlBuildingController(f.jdbc,f.access,scope))
                .setControllerAdvice(new DaikinControlExceptionHandler(), new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
        auth = new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(10L,"test-user"),null,List.of());
    }

    @Test void noRoleUserAppliesTemperatureAndFirstReadEndsOperation() throws Exception {
        mvc.perform(get("/v1/hvac-control/device-grants/access").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOpenControls").value(true)).andExpect(jsonPath("$.data.canManage").value(true));
        var options = mvc.perform(get("/v1/hvac-control/devices/equipment/options").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canControl").value(true))
                .andExpect(jsonPath("$.data.testOpenControls").value(true)).andReturn();
        String revision=f.json.readTree(options.getResponse().getContentAsString()).path("data").path("revision").asText();
        var body=f.json.createObjectNode().put("action","TEMPERATURE").put("value","26")
                .put("idempotencyKey","fast-api-apply").put("revision",revision);
        var result=mvc.perform(post("/v1/hvac-control/devices/equipment/operations").principal(auth)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.verificationPolicy").value("TEST_FAST_V1")).andReturn();
        String id=f.json.readTree(result.getResponse().getContentAsString()).path("data").path("operationId").asText();
        f.service.reconcile();
        mvc.perform(get("/v1/hvac-control/operations/"+id).principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOUD_VERIFIED"))
                .andExpect(jsonPath("$.data.cloudStable").value(false)).andExpect(jsonPath("$.data.isolated").value(false));
        mvc.perform(get("/v1/hvac-control/devices/equipment/operations").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void unknownSendCanContinueWithoutWaitingOrReplaying() throws Exception {
        f.gateway.loseResponse=true;
        var options=f.service.options(10,"equipment");
        var op=f.service.submit(10,"equipment",new DaikinControlContracts.Submit(
                DaikinControlContracts.Action.TEMPERATURE,"26","api-unknown-send",options.revision()),"offline");
        mvc.perform(get("/v1/hvac-control/devices/equipment/recovery").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.simpleRecovery").value(true));
        mvc.perform(post("/v1/hvac-control/operations/"+op.operationId()+"/recovery/continue").principal(auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"按当前状态继续测试\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.reasonCode").value("TEST_CURRENT_STATE_RELEASED"));
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void openPermissionsStillRejectArbitraryWireFields() throws Exception {
        mvc.perform(post("/v1/hvac-control/devices/equipment/operations").principal(auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"vendorId\":\"arbitrary-device\",\"action\":\"ON_OFF\"}"))
                .andExpect(status().isBadRequest());
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void testBuildingListUsesFormalConnectionAndDoesNotWidenNormalScope() throws Exception {
        mvc.perform(get("/v1/hvac-control/buildings").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].buildingId").value("building"));
        f.props.setTestOpenControls(false);
        mvc.perform(get("/v1/hvac-control/buildings").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void closedControlsUseMigratedBuildingSchemaAndKeepBuildingScopeAndDeletionFiltering() throws Exception {
        f.props.setEnabled(false); f.props.setTestOpenControls(false);
        when(scope.getAccessibleBuildingIds(org.mockito.ArgumentMatchers.eq(10L),org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(java.util.Set.of("building"));
        mvc.perform(get("/v1/hvac-control/buildings").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].buildingName").value("已对接建筑"));
        when(scope.getAccessibleBuildingIds(org.mockito.ArgumentMatchers.eq(10L),org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(null);
        mvc.perform(get("/v1/hvac-control/buildings").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        assertThat(f.gateway.writes).hasValue(0);
    }
}
