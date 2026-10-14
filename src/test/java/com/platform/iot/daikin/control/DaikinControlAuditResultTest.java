package com.platform.iot.daikin.control;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实审计枚举契约与控制阶段分开保存；使用带 V25 CHECK 的隔离夹具。 */
class DaikinControlAuditResultTest {
    @ParameterizedTest
    @EnumSource(Status.class)
    void persistsEveryControlPhaseWithinSharedAuditResultContract(Status phase) throws Exception {
        var fixture = new ControlFixture();
        var target = fixture.access.target("equipment");
        var request = fixture.request(Action.ON_OFF, "on", "audit-contract");
        var row = fixture.store.reserve(7, target, request, "audit-hash", "audit-trace", fixture.clock.millis()).row();
        if (phase != Status.PREPARING) {
            fixture.store.transition(row.view().operationId(), Set.of(Status.PREPARING), phase,
                    "CONTRACT_VERIFICATION", null, null, null, null, 7, fixture.clock.millis());
        }
        var evidence = fixture.jdbc.queryForMap("SELECT result,after_summary FROM sys_security_audit_event WHERE action_type=?", "HVAC_CONTROL_" + phase);
        assertThat(evidence.get("result")).isIn("SUCCESS", "REJECTED", "DENIED", "FAILED");
        assertThat(evidence.get("after_summary").toString()).contains("status=" + phase);
        if (Set.of(Status.UNKNOWN, Status.UNCONFIRMED).contains(phase)) {
            assertThat(evidence.get("result")).isEqualTo("FAILED");
        }
        assertThat(fixture.gateway.writes.get()).isZero();
    }
}
