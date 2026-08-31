package com.platform.iot.energymetadata;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.CreateRequest;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.UpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyPointProfileServiceIntegrationTest {
    private static final long ENERGY_USER = 101L;
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    @Autowired private EnergyPointProfileService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepareEnergyPointsAndBuildingScope() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)",
                ENERGY_USER, "BLD001");
        insertPoint("ENERGY_ELECTRIC", "METER_ELECTRIC_TOTAL", "BLD001", "kWh");
        insertPoint("ENERGY_GAS", "METER_GAS_TOTAL", "BLD001", "Nm³");
        insertPoint("ENERGY_BLD2", "METER_BLD2_TOTAL", "BLD002", "kWh");
    }

    @Test
    void createsElectricityAndNaturalGasProfilesAndKeepsExistingNineteenUnchanged() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_point_profile", Integer.class)).isZero();

        var electricity = service.create(ENERGY_USER, ENERGY,
                create("BLD001", "ENERGY_ELECTRIC", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED"));
        var gas = service.create(ENERGY_USER, ENERGY,
                create("BLD001", "ENERGY_GAS", "NATURAL_GAS", null, "PENDING_EXPERT"));

        assertThat(electricity.unit()).isEqualTo("kWh");
        assertThat(electricity.valueSemantics()).isEqualTo("CUMULATIVE");
        assertThat(gas.unit()).isEqualTo("Nm³");
        assertThat(gas.energySubtype()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_point_alias
                WHERE source_id='SOURCE_MQTT_FREEZE_V1' AND status=1
                """, Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_collection_config_audit_log
                WHERE object_type='ENERGY_POINT_PROFILE' AND action_type='CREATE_ENERGY_POINT_PROFILE'
                """, Integer.class)).isEqualTo(2);
        String summary = jdbc.queryForObject("""
                SELECT after_summary FROM biz_collection_config_audit_log
                WHERE object_id=?
                """, String.class, electricity.profileId());
        assertThat(summary).contains("evidencePresent=true").doesNotContain("专家台账");
    }

    @Test
    void rejectsInvalidSubtypeMissingPointDuplicateBuildingMismatchAndUnsupportedUnit() {
        assertCode(() -> service.create(ENERGY_USER, ENERGY,
                        create("BLD001", "ENERGY_GAS", "NATURAL_GAS", "GRID_PURCHASED", "CONFIRMED")),
                400, EnergyMetadataErrors.VALIDATION_FAILED);
        assertCode(() -> service.create(ENERGY_USER, ENERGY,
                        create("BLD001", "MISSING", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED")),
                404, EnergyMetadataErrors.POINT_NOT_FOUND);
        assertCode(() -> service.create(1L, ADMIN,
                        create("BLD001", "ENERGY_BLD2", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED")),
                409, EnergyMetadataErrors.BUILDING_MISMATCH);
        assertCode(() -> service.create(1L, ADMIN,
                        create("BLD001", "POINT001", "HEAT", null, "PENDING_EXPERT")),
                400, EnergyMetadataErrors.UNIT_UNSUPPORTED);

        service.create(ENERGY_USER, ENERGY,
                create("BLD001", "ENERGY_ELECTRIC", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED"));
        assertCode(() -> service.create(ENERGY_USER, ENERGY,
                        create("BLD001", "ENERGY_ELECTRIC", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED")),
                409, EnergyMetadataErrors.DUPLICATE);
    }

    @Test
    void rejectsStaleRevisionCrossBuildingAndThirdPartyRoles() {
        var created = service.create(ENERGY_USER, ENERGY,
                create("BLD001", "ENERGY_ELECTRIC", "ELECTRICITY", null, "PENDING_EXPERT"));
        var updated = service.update(ENERGY_USER, ENERGY, created.profileId(),
                update("ELECTRICITY", "GRID_PURCHASED", "CONFIRMED", 0));
        assertThat(updated.configRevision()).isEqualTo(1);
        assertCode(() -> service.update(ENERGY_USER, ENERGY, created.profileId(),
                        update("ELECTRICITY", "SELF_GENERATED", "CONFIRMED", 0)),
                409, EnergyMetadataErrors.VERSION_CONFLICT);
        assertThatThrownBy(() -> service.create(ENERGY_USER, ENERGY,
                create("BLD002", "ENERGY_BLD2", "ELECTRICITY", "GRID_PURCHASED", "CONFIRMED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
        assertCode(() -> service.options(Set.of("THIRD_PARTY")),
                403, EnergyMetadataErrors.FORBIDDEN);
    }

    @Test
    void monthlyReportingNeverChangesDeviceCollectionInterval() {
        var profile = service.create(1L, ADMIN,
                new CreateRequest("BLD001", "POINT004", "ELECTRICITY", "GRID_PURCHASED",
                        "INSTANTANEOUS", "MONTH", true, "CONFIRMED", "专家台账-功率测点"));
        var context = service.collectionContext(1L, ADMIN, "SOURCE_MQTT_FREEZE_V1", "ALIAS004");

        assertThat(context.profilePresent()).isTrue();
        assertThat(context.profileRevision()).isEqualTo(profile.configRevision());
        assertThat(context.reportingPeriod()).isEqualTo("MONTH");
        assertThat(context.expectedIntervalSeconds()).isEqualTo(60);
        assertThat(context.allowedDelaySeconds()).isEqualTo(30);
        assertThat(context.timeSemantics()).isEqualTo("DEVICE_EVENT_TIME");
        assertThat(jdbc.queryForObject("""
                SELECT expected_interval_seconds FROM biz_collection_policy_version
                WHERE version_id=?
                """, Integer.class, context.policyVersionId())).isEqualTo(60);
    }

    @Test
    void contextForUnprofiledPointKeepsExistingCollectionReadable() {
        var context = service.collectionContext(1L, ADMIN, "SOURCE_MQTT_FREEZE_V1", "ALIAS001");
        assertThat(context.profilePresent()).isFalse();
        assertThat(context.energyType()).isNull();
        assertThat(context.expectedIntervalSeconds()).isEqualTo(60);
    }

    private CreateRequest create(String buildingId, String pointId, String type,
                                 String subtype, String status) {
        return new CreateRequest(buildingId, pointId, type, subtype, "CUMULATIVE", "MONTH",
                true, status, "专家台账-能源测点核对");
    }

    private UpdateRequest update(String type, String subtype, String status, int revision) {
        return new UpdateRequest(type, subtype, "CUMULATIVE", "MONTH", true, status,
                "专家台账-修订确认", revision);
    }

    private void insertPoint(String pointId, String pointCode, String buildingId, String unit) {
        jdbc.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                 component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES (?,?,?,?,'RULE_WCR_MAIN','WCR','MAIN','PPE','ACCUMULATE',?,0,'ONLINE',0)
                """, pointId, pointCode, pointCode, buildingId, unit);
    }

    private static void assertCode(Runnable invocation, int httpStatus, String code) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(httpStatus);
            assertThat(error.getErrorCode()).isEqualTo(code);
        });
    }
}
