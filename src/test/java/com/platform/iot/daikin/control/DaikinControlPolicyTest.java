package com.platform.iot.daikin.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;

class DaikinControlPolicyTest {
    ControlFixture f;
    @BeforeEach void setUp() throws Exception { f = new ControlFixture(); }

    @ParameterizedTest @ValueSource(strings = {"15", "33", "16.5", "016", "-1", "unknown"})
    void rejectsInvalidTemperatures(String value) {
        assertThat(f.submit(Action.TEMPERATURE, value).status()).isEqualTo(Status.NOT_SENT);
        assertThat(f.gateway.writes).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings = {"16", "32"})
    void acceptsProtocolTemperatureBoundaries(String value) { assertThat(f.submit(Action.TEMPERATURE, value).status()).isEqualTo(Status.ACCEPTED); }

    @Test void intersectsTemperatureLimitsAndRejectsUncertainModeOrRange() {
        f.gateway.unit.put("limitSettempCool", "on").put("coolLimitsettempL", 20).put("coolLimitsettempU", 28);
        var option = f.service.options(7, "equipment").actions().stream().filter(a -> a.action() == Action.TEMPERATURE).findFirst().orElseThrow();
        assertThat(option.minimum()).isEqualTo(20); assertThat(option.maximum()).isEqualTo(28);
        assertThat(f.submit(Action.TEMPERATURE, "19").status()).isEqualTo(Status.NOT_SENT);
        f.gateway.unit.put("mode", "fan");
        assertThat(f.service.options(7, "equipment").actions().stream().filter(a -> a.action() == Action.TEMPERATURE).findFirst().orElseThrow().allowed()).isFalse();
        f.gateway.unit.put("mode", "cooling").put("coolLimitsettempL", 29);
        assertThat(f.service.options(7, "equipment").actions().stream().filter(a -> a.action() == Action.TEMPERATURE).findFirst().orElseThrow().allowed()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"inCommunicationError", "inEquipmentError", "inMaintenanceMode", "isGroupSlave"})
    void unsafeOrUnknownFieldsDisableAllOperations(String field) {
        f.gateway.unit.put(field, true);
        assertThat(f.service.options(7, "equipment").actions()).allMatch(a -> !a.allowed());
        f.gateway.unit.remove(field);
        assertThat(f.service.options(7, "equipment").canControl()).isFalse();
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void sharedControllersAndForcedStopNeverBecomeAdminOverrides() {
        f.gateway.controller.put("inForcedStop", true);
        assertThat(f.service.options(7, "equipment").canControl()).isFalse();
        f.gateway.controller.put("inForcedStop", false);
        f.gateway.unit.withArray("masterSlaveIds").add("other-unit");
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("LINKED_DEVICE_UNCONFIRMED");
    }

    @Test void capabilitiesMustBeKnownAndAirFlowNeedsExplicitConfirmation() {
        f.gateway.unit.put("modeSetList", "[cooling, unknown]");
        f.gateway.unit.remove("fanSpeedSetList");
        f.jdbc.update("UPDATE biz_daikin_control_grant SET air_flow_confirmed=FALSE");
        var options = f.service.options(7, "equipment");
        assertThat(options.actions().stream().filter(a -> a.action() == Action.MODE || a.action() == Action.FAN_SPEED || a.action() == Action.AIR_FLOW))
                .allMatch(a -> !a.allowed());
        f.gateway.unit.put("isFilterDirty", true);
        assertThat(f.service.options(7, "equipment").actions().getFirst().allowed()).isTrue();
    }

    @Test void targetIdsMissingGrantsOrProductsBlockInsteadOfFallingBackToUnitId() {
        f.gateway.unit.put("id", "unit-11");
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("IDENTITY_CONFLICT");
        f.gateway.unit.put("id", "vendor-22");
        f.jdbc.update("UPDATE biz_device_product SET status='DISABLED'");
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("TARGET_UNAVAILABLE");
        assertThat(f.gateway.writes).hasValue(0);
    }
}
