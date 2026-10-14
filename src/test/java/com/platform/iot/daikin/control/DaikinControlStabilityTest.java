package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 固定时钟、内存厂家和 H2；覆盖现场暴露的回读反复及不可重发的异常边界。 */
class DaikinControlStabilityTest {
    ControlFixture f;
    @BeforeEach void setup() throws Exception { f = new ControlFixture(); }

    @Test void earlyMatchThenRevertResetsStreakAndOnlyStableReadbackAutomaticallyReleases() {
        var op = f.submit(Action.TEMPERATURE, "26");
        f.clock.advance(15_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().stableCount()).isEqualTo(1);
        assertThat(f.store.active("equipment")).isEqualTo(op.operationId());
        f.gateway.unit.put("temperature", 24);
        f.clock.advance(30_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().stableCount()).isZero();
        f.gateway.unit.put("temperature", 26);
        for (int i = 0; i < 5; i++) { f.clock.advance(30_000); f.service.reconcile(); }
        var stable = f.service.get(7, op.operationId());
        assertThat(stable.status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(stable.isolated()).isFalse();
        assertThat(stable.lastReadAt() - stable.stableSince()).isGreaterThanOrEqualTo(60_000);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(device_sample_at) FROM biz_daikin_control_sample", Integer.class)).isZero();
        assertThat(f.store.active("equipment")).isNull();
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void delayedExecutionBeyondOldSixtySecondWindowStillGetsStableReadback() {
        f.gateway.apply = false;
        var op = f.submit(Action.ON_OFF, "on");
        f.clock.advance(15_000); f.service.reconcile();
        f.clock.advance(90_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.ACCEPTED);
        f.gateway.unit.put("onOff", "on").put("unitStatus", "operating");
        for (int i = 0; i < 3; i++) { f.clock.advance(30_000); f.service.reconcile(); }
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void conflictingOperatingStatusOtherSettingChangeAndReadFailureAreNotStableEvidence() {
        var op = f.submit(Action.ON_OFF, "on");
        f.gateway.unit.put("unitStatus", "stopped");
        f.clock.advance(15_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().stableCount()).isZero();
        f.gateway.unit.put("unitStatus", "operating").put("fanSpeed", "high");
        f.clock.advance(30_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().stableCount()).isZero();
        f.gateway.unit.put("fanSpeed", "low");
        f.clock.advance(30_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().stableCount()).isEqualTo(1);
        f.gateway.failRead = true;
        f.clock.advance(30_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().stableCount()).isZero();
        Long deadline = f.store.get(op.operationId()).view().deadlineAt();
        f.clock.advance(195_000); f.service.reconcile();
        var timedOut = f.store.get(op.operationId()).view();
        assertThat(timedOut.status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(timedOut.deadlineAt()).isEqualTo(deadline);
        assertThat(timedOut.isolated()).isTrue();
        int reads = f.gateway.reads.get();
        f.clock.advance(60_000); f.service.reconcile();
        assertThat(f.gateway.reads).hasValue(reads);
    }

    @Test void unknownKeepsOriginalUncertaintyEvenWhenStableAndClosingDoesNotReleaseIsolation() {
        f.gateway.loseResponse = true;
        var op = f.submit(Action.TEMPERATURE, "26");
        f.stabilize(f.service);
        var stable = f.store.get(op.operationId()).view();
        assertThat(stable.status()).isEqualTo(Status.UNKNOWN);
        assertThat(stable.cloudStable()).isTrue();
        var closed = f.service.close(7, op.operationId(), "模拟关闭记录仍保留未知指令隔离");
        assertThat(closed.isolated()).isTrue();
        assertThat(f.service.release(7, op.operationId(), "现场确认设温稳定且接受晚到指令风险").isolated()).isFalse();
        assertThat(f.gateway.writes).hasValue(1);
        assertThat(f.jdbc.queryForList("SELECT status FROM biz_daikin_control_event WHERE operation_id=?", String.class, op.operationId()))
                .contains("UNKNOWN", "CLOSED").doesNotContain("CONFIRMED");
    }

    @Test void revokedOperatorCannotSendOrViewButSystemCompletesBoundedReadOnlyVerification() {
        var op = f.submit(Action.TEMPERATURE, "26");
        f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=7");
        f.stabilize(f.service);
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThatThrownBy(() -> f.service.get(7, op.operationId())).isInstanceOf(BusinessException.class);
        assertThat(f.store.get(op.operationId()).view().isolated()).isFalse();
        assertThat(f.gateway.writes).hasValue(1);
        assertThat(f.jdbc.queryForList("SELECT actor_type FROM sys_security_audit_event", String.class)).contains("SYSTEM");
    }

    @Test void testAdminNeedsExplicitSwitchAndCannotBypassUnavailableDevice() {
        assertThat(f.service.options(9, "equipment").reasonCode()).isEqualTo("FORBIDDEN");
        f.props.setTestAccessEnabled(true);
        assertThat(f.service.options(9, "equipment").canControl()).isTrue();
        assertThat(f.service.options(8, "equipment").reasonCode()).isEqualTo("FORBIDDEN");
        f.gateway.unit.put("isGroupSlave", true);
        assertThat(f.service.options(9, "equipment").canControl()).isFalse();
        f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=9");
        assertThatThrownBy(() -> f.service.options(9, "equipment")).isInstanceOf(BusinessException.class);
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void slaveGrantIsBoundToRelationshipAndExactDirectionAndTemperature() {
        f.gateway.unit.put("masterSlaveFlag", "Slave").withArray("masterSlaveIds").add("master-1");
        assertThat(f.service.options(7, "equipment").canControl()).isFalse();
        f.jdbc.update("UPDATE biz_daikin_control_grant SET relationship_hash=?,approved_actions=?,version=version+1",
                f.policy.relationshipHash(new DaikinControlGateway.Snapshot(f.gateway.controller, f.gateway.unit)),
                "{\"ON_OFF\":[\"off\"],\"TEMPERATURE\":[\"26\"]}");
        var options = f.service.options(7, "equipment");
        assertThat(options.canControl()).isTrue();
        assertThat(options.actions().getFirst().values()).containsExactly("off");
        assertThat(options.actions().stream().filter(a -> a.action() == Action.MODE || a.action() == Action.AIR_FLOW || a.action() == Action.FAN_SPEED))
                .allMatch(a -> !a.allowed());
        assertThat(f.submit(Action.ON_OFF, "on").status()).isEqualTo(Status.NOT_SENT);
        var request = f.request(Action.TEMPERATURE, "25", "request-456");
        assertThat(f.service.submit(7, "equipment", request, "trace").status()).isEqualTo(Status.NOT_SENT);
        var op = f.service.submit(7, "equipment", f.request(Action.TEMPERATURE, "26", "request-789"), "trace");
        f.gateway.unit.withArray("masterSlaveIds").add("new-master");
        f.clock.advance(15_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().reasonCode()).isEqualTo("LINKED_DEVICE_UNCONFIRMED");
        assertThat(f.store.get(op.operationId()).view().isolated()).isTrue();
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void onsiteConfirmationRechecksFreshSnapshotAndRefusesRevertedValue() {
        var op = f.submit(Action.TEMPERATURE, "26");
        makeLegacyOnsiteRecord(op);
        f.stabilize(f.service);
        f.gateway.unit.put("temperature", 24);
        assertThatThrownBy(() -> f.service.confirm(7, op.operationId(), "声称现场确认但读取已经反复"))
                .isInstanceOf(BusinessException.class);
        assertThat(f.store.get(op.operationId()).view().cloudStable()).isFalse();
        assertThat(f.store.get(op.operationId()).view().isolated()).isTrue();
    }

    @Test void grantVersionChangeStopsVerificationAndCannotReleaseOldIsolation() {
        var op = f.submit(Action.TEMPERATURE, "26");
        f.jdbc.update("UPDATE biz_daikin_control_grant SET version=version+1");
        f.clock.advance(15_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().reasonCode()).isEqualTo("REVISION_CHANGED");
        assertThat(f.store.get(op.operationId()).view().stableCount()).isZero();
        f.clock.advance(285_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.UNCONFIRMED);
        assertThatThrownBy(() -> f.service.release(7, op.operationId(), "试图用新授权解除旧身份隔离"))
                .isInstanceOf(BusinessException.class);
        assertThat(f.store.get(op.operationId()).view().isolated()).isTrue();
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void longGapInvalidatesOldStableEvidenceAndConfirmationNeedsNewStreak() {
        var op = f.submit(Action.TEMPERATURE, "26");
        makeLegacyOnsiteRecord(op);
        f.stabilize(f.service);
        f.clock.advance(120_000);
        assertThatThrownBy(() -> f.service.confirm(7, op.operationId(), "旧稳定记录不能代替新的现场证据"))
                .isInstanceOf(BusinessException.class);
        var row = f.store.get(op.operationId()).view();
        assertThat(row.stableCount()).isEqualTo(1);
        assertThat(row.status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(row.isolated()).isTrue();
        for (int i = 0; i < 2; i++) { f.clock.advance(30_000); f.service.recheck(7, op.operationId()); }
        assertThat(f.service.release(7, op.operationId(), "新的连续核验及现场确认允许解除隔离").isolated()).isFalse();
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void manualAndScheduledChecksShareLeaseAndExpiredWorkerCannotOverwriteNewEvidence() throws Exception {
        var op = f.submit(Action.TEMPERATURE, "26");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        f.gateway.onRead = target -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); } };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var old = pool.submit(() -> f.service.recheck(7, op.operationId()));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> f.service.recheck(7, op.operationId())).isInstanceOf(BusinessException.class);
            f.clock.advance(31_000);
            f.gateway.onRead = target -> { };
            var fresh = f.service.recheck(7, op.operationId());
            release.countDown(); old.get();
            assertThat(f.service.get(7, op.operationId()).stableCount()).isEqualTo(fresh.stableCount());
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample", Integer.class)).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void controllerScopeBlocksAnotherPhysicalDeviceAcrossServiceInstances() {
        var request = f.request(Action.TEMPERATURE, "26", "request-123");
        var target = f.access.target("equipment");
        var other = new DaikinControlAccess.Target("another", target.buildingId(), "other-identity", target.sourceId(), target.siteId(),
                target.controllerId(), "other-unit", "other-vendor", "other-pending", 1, 1, true, target.relationshipHash(), target.approvedActions(), target.evidence());
        var op = f.service.submit(7, "equipment", request, "trace");
        assertThatThrownBy(() -> f.store.reserve(7, other, new Submit(Action.TEMPERATURE, "26", "request-456", request.revision()), "different-hash", "trace", f.clock.millis()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("HVAC_CONTROL_CONTROLLER_BUSY"));
        var restarted = new DaikinControlService(f.props, f.access, f.policy, f.store, List.of(f.gateway), f.clock);
        f.stabilize(restarted);
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.store.active("equipment")).isNull();
        assertThat(f.store.reserve(7, other, new Submit(Action.TEMPERATURE, "26", "request-456", request.revision()), "different-hash", "trace", f.clock.millis()).created()).isTrue();
        assertThat(f.gateway.writes).hasValue(1);
    }

    private void makeLegacyOnsiteRecord(Operation op) {
        // 模拟升级前已经处于人工确认阶段的记录；新版不替它自动解除占用。
        f.store.transition(op.operationId(), java.util.Set.of(Status.ACCEPTED), Status.OBSERVED_STABLE,
                "CLOUD_STABLE_AWAITING_ONSITE", null, op.observedValue(), f.clock.millis() + 15_000, null, 0, f.clock.millis());
    }
}
