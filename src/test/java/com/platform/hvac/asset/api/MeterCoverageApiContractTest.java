package com.platform.hvac.asset.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.asset.service.MeterCoverageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import static com.platform.hvac.asset.api.MeterCoverageContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 内存数据库契约验证，不访问现场设备、MQTT 或 TDengine。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MeterCoverageApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MeterCoverageService service;
    static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    String token;

    @BeforeEach
    void seed() throws Exception {
        add("MC_M", "表计", "ELECTRIC_METER", "BLD001");
        add("MC_M2", "另一块表", "ELECTRIC_METER", "BLD001");
        add("MC_A", "房间内机一", "INDOOR_UNIT", "BLD001");
        add("MC_B", "房间内机二", "INDOOR_UNIT", "BLD001");
        add("MC_C", "房间内机三", "INDOOR_UNIT", "BLD001");
        add("MC_OTHER", "别楼设备", "INDOOR_UNIT", "BLD002");
        var result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        token = json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    void add(String id, String name, String category, String building) {
        jdbc.update("""
                INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,
                building_id,space_id,system_group_id,del_flag)
                SELECT ?,?,?,type_code,?,?,space_id,system_group_id,0 FROM biz_equipment
                WHERE equip_id='EQUIP_WCR_B1'
                """, id, id, name, category, building);
    }

    SaveRequest request(long revision, List<String> ids) {
        return new SaveRequest(revision, null, "三台内机共同计量", ids, "现场负责人确认覆盖设备，安装位置待确认");
    }

    @Test
    void unclassifiedEquipmentCannotBecomeAMeterTarget() {
        jdbc.update("UPDATE biz_equipment SET equip_category='UNKNOWN' WHERE equip_id='MC_A'");
        assertThatThrownBy(() -> service.save("MC_M", request(0, List.of("MC_A")), 1L, ADMIN))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已明确分类");
    }

    @Test
    void productAndTypeCannotOverrideNonMeterCategory() throws Exception {
        jdbc.update("UPDATE biz_equipment SET type_code='ELECTRIC_METER_1P', product_id='PRODUCT_IDU_METER_1039', equip_name='电表 METER' WHERE equip_id='MC_A'");
        mvc.perform(get("/v1/assets/equipment/MC_A/meter-coverage")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE biz_equipment SET equip_name='任意新名称' WHERE equip_id='MC_M'");
        mvc.perform(get("/v1/assets/equipment/MC_M/meter-coverage")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void returnsUnconfiguredWithoutInferringInstallationFromAssetSpace() throws Exception {
        mvc.perform(get("/v1/assets/equipment/MC_M/meter-coverage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.installationSpaceId").isEmpty())
                .andExpect(jsonPath("$.data.targets").isEmpty());
        mvc.perform(put("/v1/assets/equipment/MC_M/meter-coverage")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(0, List.of("MC_A", "MC_B", "MC_C")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.targets.length()").value(3))
                .andExpect(jsonPath("$.data.effectiveAt").isString())
                .andExpect(jsonPath("$.data.aggregationPolicy").value("SEPARATE_ONLY"));
    }

    @Test
    void savesOneToManyAndPreservesSnapshotsAndAcquisitionIdentity() throws Exception {
        long start = System.currentTimeMillis();
        var first = service.save("MC_M", request(0, List.of("MC_A", "MC_B", "MC_C")), 1L, ADMIN);
        assertThat(first.targets()).hasSize(3);
        assertThat(first.effectiveAt().toEpochMilli()).isGreaterThanOrEqualTo(start);
        assertThat(first.quantityMode()).isEqualTo("GROUP_ONLY");
        assertThat(first.aggregationPolicy()).isEqualTo("SEPARATE_ONLY");
        assertThat(first.installationSpaceId()).isNull();
        assertThat(jdbc.queryForObject("SELECT equip_code FROM biz_equipment WHERE equip_id='MC_M'", String.class)).isEqualTo("MC_M");
        jdbc.update("UPDATE biz_equipment SET equip_name='已改名' WHERE equip_id='MC_A'");
        // 明确推进版本时间，避免测试依赖执行速度或等待。
        jdbc.update("UPDATE biz_meter_coverage_revision SET effective_at=effective_at-1000 WHERE meter_id='MC_M'");
        service.save("MC_M", request(1, List.of()), 1L, ADMIN);
        var history = service.history("MC_M", 1, 20, ADMIN);
        assertThat(history.total()).isEqualTo(2);
        assertThat(history.items().get(0).targets()).isEmpty();
        assertThat(history.items().get(1).targets()).extracting(Target::equipmentName).contains("房间内机一");
        mvc.perform(get("/v1/assets/meter-coverages").param("equipmentIds", "MC_M,MC_M2")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void rejectsStaleRevisionAndLeavesCurrentUnchanged() throws Exception {
        service.save("MC_M", request(0, List.of("MC_A")), 1, ADMIN);
        mvc.perform(put("/v1/assets/equipment/MC_M/meter-coverage")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(0, List.of("MC_B")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("METER_COVERAGE_CONFLICT"));
        assertThat(service.current("MC_M", ADMIN).targets()).extracting(Target::equipmentId).containsExactly("MC_A");
    }

    @Test
    void rejectsCrossBuildingSelfMeterDuplicateAndDeletedTargetsWithoutWriting() {
        jdbc.update("UPDATE biz_equipment SET del_flag=1 WHERE equip_id='MC_C'");
        for (List<String> ids : List.of(List.of("MC_OTHER"), List.of("MC_M"), List.of("MC_M2"),
                List.of("MC_A", "MC_A"), List.of("MC_C"), List.of("missing"))) {
            assertThatThrownBy(() -> service.save("MC_M", request(0, ids), 1, ADMIN))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(service.current("MC_M", ADMIN).revision()).isZero();
    }

    @Test
    void candidateSearchIsBuildingScopedAndDoesNotIncludeMeters() {
        var result = service.candidates("MC_M", "房间内机", 1, 2, ADMIN);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.items()).hasSize(2);
        assertThat(service.candidates("MC_M", "别楼", 1, 20, ADMIN).items()).isEmpty();
        assertThat(service.candidates("MC_M", "表计", 1, 20, ADMIN).items()).isEmpty();
        assertThat(service.candidates("MC_M", "%", 1, 20, ADMIN).items()).isEmpty();
    }

    @Test
    void rejectsWrongInstallationBuildingAndUnauthorizedCalls() throws Exception {
        assertThatThrownBy(() -> service.save("MC_M", new SaveRequest(0L,"not-a-space","范围",List.of("MC_A"),"依据"),1,ADMIN))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.current("MC_M", Set.of("BUILDING_OWNER")))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException)e).getCode()).isEqualTo(403);
        mvc.perform(get("/v1/assets/equipment/MC_M/meter-coverage")).andExpect(status().isUnauthorized());
        mvc.perform(put("/v1/assets/equipment/MC_M/meter-coverage").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> service.batch("BLD002",List.of("MC_M"),ADMIN)).isInstanceOf(BusinessException.class);
    }

    @Test
    void keepsDeletedTargetsVisibleAsInactiveRatherThanLosingHistory() {
        service.save("MC_M",request(0,List.of("MC_A")),1,ADMIN);
        jdbc.update("UPDATE biz_equipment SET del_flag=1 WHERE equip_id='MC_A'");
        var target=service.current("MC_M",ADMIN).targets().getFirst();
        assertThat(target.equipmentId()).isEqualTo("MC_A");
        assertThat(target.active()).isFalse();
    }
}
