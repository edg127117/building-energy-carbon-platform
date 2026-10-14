package com.platform.iot.daikin.control;

import org.junit.jupiter.api.Test;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 正常云端核验可自动结束，但不把未知发送、超时恢复或历史现场确认变成成功。 */
class DaikinControlAutoFinishTest {
    @Test void normalStableReadbackEndsPollingReleasesBothSlotsAndAllowsNextCommand() throws Exception {
        var f = new ControlFixture();
        var request = f.request(Action.TEMPERATURE, "26", "first-command");
        var op = f.service.submit(7, "equipment", request, "trace");
        f.stabilize(f.service);
        var done = f.store.get(op.operationId());
        assertThat(done.view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(done.view().reasonCode()).isEqualTo("CLOUD_VERIFIED_AUTO_FINISHED");
        assertThat(done.view().closedReason()).isNull();
        assertThat(done.view().isolated()).isFalse();
        assertThat(done.nextCheckAt()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isZero();
        int reads = f.gateway.reads.get();
        f.clock.advance(600_000); f.service.reconcile();
        assertThat(f.gateway.reads).hasValue(reads);
        assertThat(f.service.submit(7, "equipment", request, "repeat").operationId()).isEqualTo(op.operationId());
        assertThat(f.gateway.writes).hasValue(1);
        var next = f.service.submit(7, "equipment", f.request(Action.TEMPERATURE, "25", "next-command"), "trace");
        assertThat(next.status()).isEqualTo(Status.ACCEPTED);
        assertThat(f.gateway.writes).hasValue(2);
    }

    @Test void auditFailureAtAutoFinishRollsBackSampleAndSlotReleaseThenReadOnlyRetrySucceeds() throws Exception {
        var f = new ControlFixture();
        var op = f.submit(Action.TEMPERATURE, "26");
        f.clock.advance(15_000); f.service.reconcile();
        for (int i = 0; i < 5; i++) { f.clock.advance(30_000); f.service.reconcile(); }
        int samples = f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample", Integer.class);
        f.auditFails = true;
        f.clock.advance(30_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.ACCEPTED);
        assertThat(f.store.active("equipment")).isEqualTo(op.operationId());
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample", Integer.class)).isEqualTo(samples);
        f.auditFails = false;
        f.clock.advance(30_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void deadlineAndUnknownResultsDoNotAutomaticallyReleaseEvenAfterMatchingReadbacks() throws Exception {
        for (boolean lost : new boolean[]{false, true}) {
            var f = new ControlFixture();
            f.gateway.loseResponse = lost;
            var op = f.submit(Action.TEMPERATURE, "26");
            if (lost) f.stabilize(f.service);
            else {
                f.clock.advance(300_000); f.service.reconcile();
                for (int i = 0; i < 6; i++) { f.clock.advance(30_000); f.service.recheck(7, op.operationId()); }
            }
            var view = f.store.get(op.operationId()).view();
            assertThat(view.status()).isEqualTo(lost ? Status.UNKNOWN : Status.UNCONFIRMED);
            assertThat(view.cloudStable()).isTrue(); assertThat(view.isolated()).isTrue();
            assertThat(f.gateway.writes).hasValue(1);
        }
    }
}
