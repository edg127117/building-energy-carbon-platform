package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;

import static com.platform.iot.daikin.control.DaikinBatchContracts.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** H2 和内存厂家验证两台重叠核验及失败隔离；不访问现场或外部服务。 */
class DaikinBatchOverlapTest {
    private DaikinBatchStore.Work work(DaikinBatchControlTest.Fixture t, Batch batch, int index) {
        var item = t.service.get(7, batch.batchId()).items().stream()
                .filter(i -> i.equipmentId().equals(t.device(index))).findFirst().orElseThrow();
        return t.store.work(item.itemId());
    }

    private void send(DaikinBatchControlTest.Fixture t, Batch batch, int index) {
        var claimed = t.store.claim(work(t, batch, index), t.f.clock, 4);
        assertThat(claimed).isNotNull();
        t.service.dispatch(claimed);
    }

    @ParameterizedTest @ValueSource(strings = {"on", "off"})
    void twoDevicesAreAcceptedBeforeEitherRunningStateMatches(String target) throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            if (target.equals("off")) t.units.values().forEach(u -> u.put("onOff", "on").put("unitStatus", "operating"));
            t.delayedRun.addAll(t.devices(2));
            var batch = t.submit(2, Action.ON_OFF, target);
            send(t, batch, 0);
            send(t, batch, 1);
            t.controls.reconcile(); t.store.synchronize(t.f.clock);
            assertThat(t.sends).hasValue(2);
            assertThat(t.service.get(7, batch.batchId()).items()).allMatch(i -> i.status() == ItemStatus.ACTIVE);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isEqualTo(2);
            for (int i = 0; i < 2; i++) t.units.get(t.device(i)).put("unitStatus", target.equals("on") ? "operating" : "stopped");
            t.f.clock.advance(2_000); t.controls.reconcile(); t.store.synchronize(t.f.clock);
            assertThat(t.service.get(7, batch.batchId()).completedCount()).isEqualTo(2);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_slot", Integer.class)).isZero();
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isZero();
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_lane", Integer.class)).isZero();
        }
    }

    @Test void thirdWaitsAndOnlyOneOfTwoInstancesCanClaimTheSecondSlot() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(3, 3); var pool = Executors.newFixedThreadPool(2)) {
            t.delayedRun.addAll(t.devices(3));
            var batch = t.submit(3, Action.ON_OFF, "on"); send(t, batch, 0);
            var other = t.anotherStore(); var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return t.store.claim(work(t, batch, 1), t.f.clock, 4); });
            var b = pool.submit(() -> { start.await(); return other.claim(work(t, batch, 2), t.f.clock, 4); });
            start.countDown();
            var claimed = Arrays.asList(a.get(), b.get());
            assertThat(claimed.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            var second = claimed.stream().filter(Objects::nonNull).findFirst().orElseThrow(); t.service.dispatch(second);
            int third = second.item().equipmentId().equals(t.device(1)) ? 2 : 1;
            assertThat(t.store.claim(work(t, batch, third), t.f.clock, 4)).isNull();
            assertThat(t.sends).hasValue(2);
            t.units.get(t.device(0)).put("unitStatus", "operating"); t.controls.reconcile(); t.store.synchronize(t.f.clock);
            send(t, batch, third);
            assertThat(t.sends).hasValue(3);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isEqualTo(2);
        }
    }

    @Test void claimIsNotPermissionToSendBeforeTheFirstAcceptance() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            var batch = t.submit(2, Action.ON_OFF, "on");
            var first = t.store.claim(work(t, batch, 0), t.f.clock, 4);
            assertThat(t.store.claim(work(t, batch, 1), t.f.clock, 4)).isNull();
            t.service.dispatch(first); send(t, batch, 1);
            assertThat(t.sends).hasValue(2);
        }
    }

    @Test void legacyBatchOtherActionsLinkedScopeAndDifferentBatchesRemainSerial() throws Exception {
        for (boolean linked : List.of(false, true)) try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            if (linked) t.units.get(t.device(0)).putArray("masterSlaveIds").add("v1");
            var batch = t.submit(2, Action.ON_OFF, "on");
            if (!linked) t.f.jdbc.update("UPDATE biz_daikin_control_batch SET overlap_allowed=FALSE WHERE batch_id=?", batch.batchId());
            send(t, batch, 0);
            assertThat(t.store.claim(work(t, batch, 1), t.f.clock, 4)).isNull();
        }
        for (var entry : Map.of(Action.MODE, "heating", Action.TEMPERATURE, "26", Action.FAN_SPEED, "high").entrySet())
            try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
                var batch = t.submit(2, entry.getKey(), entry.getValue()); send(t, batch, 0);
                assertThat(t.store.claim(work(t, batch, 1), t.f.clock, 4)).isNull();
            }
        try (var t = new DaikinBatchControlTest.Fixture(3, 3)) {
            var batch = t.submit(2, Action.ON_OFF, "on"); send(t, batch, 0);
            var preview = t.service.preview(7, new PreviewRequest(List.of(t.device(2))));
            var other = t.service.submit(7, t.request(preview, Action.ON_OFF, "on", "another-batch"), "offline");
            assertThat(t.store.claim(work(t, other, 2), t.f.clock, 4)).isNull();
            var target = t.f.access.target(t.device(2));
            assertThatThrownBy(() -> t.controls.submit(7, t.device(2), new Submit(Action.ON_OFF, "off", "single-conflict", t.f.policy.testRevision(target)), "offline"))
                    .isInstanceOf(BusinessException.class);
            assertThat(t.sends).hasValue(1);
        }
    }

    @Test void unknownFirstStopsUnsentItemsWithoutRetry() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(3, 3)) {
            t.unknown.add(t.device(0)); var batch = t.submit(3, Action.ON_OFF, "on"); send(t, batch, 0);
            assertThat(t.store.claim(work(t, batch, 1), t.f.clock, 4)).isNull();
            var result = t.drain(batch.batchId());
            assertThat(result.items().stream().filter(i -> i.status() == ItemStatus.NOT_SENT).count()).isEqualTo(2);
            assertThat(t.sends).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownOrTimedOutSecondDoesNotAbandonFirstOrReplayTheThird(boolean unknown) throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(3, 3)) {
            t.delayedRun.add(t.device(0));
            if (unknown) t.unknown.add(t.device(1)); else t.noApply.add(t.device(1));
            var batch = t.submit(3, Action.ON_OFF, "on"); send(t, batch, 0); send(t, batch, 1);
            t.units.get(t.device(0)).put("unitStatus", "operating");
            t.f.clock.advance(2_000); t.controls.reconcile(); t.store.synchronize(t.f.clock);
            // 超时被确认后才阻止剩余项；在确认前仍可能合法推进一个空闲槽。
            if (!unknown) { t.f.clock.advance(60_001); t.controls.reconcile(); t.store.synchronize(t.f.clock); }
            var result = t.drain(batch.batchId());
            assertThat(work(t, batch, 0).item().status()).isEqualTo(ItemStatus.MATCHED);
            assertThat(work(t, batch, 1).item().status()).isEqualTo(ItemStatus.UNCONFIRMED);
            assertThat(work(t, batch, 2).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(result.attentionCount()).isEqualTo(2); assertThat(t.sends).hasValue(2);
        }
    }

    @Test void peerBecomingUnknownDuringPreflightPreventsSecondSendingIntent() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            var batch = t.submit(2, Action.ON_OFF, "on"); send(t, batch, 0);
            var second = t.store.claim(work(t, batch, 1), t.f.clock, 4);
            String firstOperation = work(t, batch, 0).item().operationId();
            t.onRead = () -> t.f.jdbc.update("UPDATE biz_daikin_control_operation SET status='UNKNOWN' WHERE operation_id=?", firstOperation);
            t.service.dispatch(second); t.onRead = () -> {};
            assertThat(t.sends).hasValue(1);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_event WHERE status='SENDING'", Integer.class)).isEqualTo(1);
            t.f.clock.advance(120_001); t.controls.reconcile(); t.store.synchronize(t.f.clock);
            t.service.dispatch(second); assertThat(t.sends).hasValue(1);
        }
    }

    @Test void secondReservationAndAuditRollbackCannotLeakASlotOrSend() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            var batch = t.submit(2, Action.ON_OFF, "on"); send(t, batch, 0);
            var second = t.store.claim(work(t, batch, 1), t.f.clock, 4); t.f.auditFails = true;
            assertThatThrownBy(() -> t.service.dispatch(second)).isInstanceOf(IllegalStateException.class);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation", Integer.class)).isEqualTo(1);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isEqualTo(1);
            assertThat(t.sends).hasValue(1);
            t.f.auditFails = false; t.f.clock.advance(120_001); t.store.synchronize(t.f.clock);
            t.service.dispatch(second); assertThat(t.sends).hasValue(1);
        }
    }

    @Test void peerCompletingDuringSecondPreflightDoesNotInvalidateTheSecond() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            var batch = t.submit(2, Action.ON_OFF, "on"); send(t, batch, 0);
            var second = t.store.claim(work(t, batch, 1), t.f.clock, 4);
            String first = work(t, batch, 0).item().operationId();
            t.onRead = () -> t.f.store.transition(first, Set.of(Status.ACCEPTED), Status.CLOUD_VERIFIED,
                    "FAST_TARGET_READBACK_MATCH", null, "on", null, null, 0, t.f.clock.millis());
            t.service.dispatch(second);
            assertThat(t.sends).hasValue(2);
            assertThat(t.f.store.get(work(t, batch, 1).item().operationId()).view().status()).isEqualTo(Status.ACCEPTED);
        }
    }

    @Test void expiredPeerCannotAdmitAnotherBeforeTheReconcileScan() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            var batch = t.submit(2, Action.ON_OFF, "on"); send(t, batch, 0); t.f.clock.advance(60_001);
            assertThat(t.store.claim(work(t, batch, 1), t.f.clock, 4)).isNull();
            assertThat(t.sends).hasValue(1);
        }
    }

    @Test void cancellingDoesNotWithdrawEitherAcceptedDevice() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(3, 3)) {
            t.delayedRun.addAll(t.devices(3)); var batch = t.submit(3, Action.ON_OFF, "on");
            send(t, batch, 0); send(t, batch, 1);
            var cancelled = t.service.cancel(7, batch.batchId());
            assertThat(cancelled.activeCount()).isEqualTo(2); assertThat(cancelled.queuedCount()).isZero();
            assertThat(work(t, batch, 2).item().status()).isEqualTo(ItemStatus.CANCELLED);
            t.units.values().forEach(u -> u.put("unitStatus", "operating"));
            t.controls.reconcile(); t.store.synchronize(t.f.clock);
            assertThat(t.service.get(7, batch.batchId()).completedCount()).isEqualTo(2);
            assertThat(t.sends).hasValue(2);
        }
    }

    @Test void readFailureOfOnePeerDoesNotPreventTheOtherFromCompletingOrCauseAResend() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            t.delayedRun.addAll(t.devices(2)); var batch = t.submit(2, Action.ON_OFF, "on");
            send(t, batch, 0); send(t, batch, 1);
            t.readFailure.add(t.device(0)); t.units.get(t.device(1)).put("unitStatus", "operating");
            t.controls.reconcile(); t.store.synchronize(t.f.clock);
            assertThat(work(t, batch, 0).item().status()).isEqualTo(ItemStatus.ACTIVE);
            assertThat(work(t, batch, 1).item().status()).isEqualTo(ItemStatus.MATCHED);
            assertThat(t.sends).hasValue(2);
            t.readFailure.clear(); t.units.get(t.device(0)).put("unitStatus", "operating");
            t.f.clock.advance(2_000); t.controls.reconcile(); t.store.synchronize(t.f.clock);
            assertThat(t.service.get(7, batch.batchId()).completedCount()).isEqualTo(2); assertThat(t.sends).hasValue(2);
        }
    }

    @Test void overlappingPairsStillShareTheGlobalFourOperationLimit() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(6, 2)) {
            var batch = t.submit(6, Action.ON_OFF, "on");
            for (int i = 0; i < 4; i++) send(t, batch, i);
            assertThat(t.store.claim(work(t, batch, 4), t.f.clock, 4)).isNull();
            assertThat(t.sends).hasValue(4);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_lane", Integer.class)).isEqualTo(4);
        }
    }

    @Test void twoTimedOutPeersCanBeRecoveredIndividuallyWithoutResendingOldQueue() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(3, 3)) {
            t.noApply.addAll(t.devices(3)); var batch = t.submit(3, Action.ON_OFF, "on"); send(t, batch, 0); send(t, batch, 1);
            t.f.clock.advance(60_001); t.controls.reconcile(); t.store.synchronize(t.f.clock);
            assertThat(work(t, batch, 0).item().status()).isEqualTo(ItemStatus.UNCONFIRMED);
            assertThat(work(t, batch, 1).item().status()).isEqualTo(ItemStatus.UNCONFIRMED);
            var reader = new DaikinControlGrantReader() {
                public boolean supports(String source) { return true; }
                public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) {
                    return new DaikinControlGateway.Snapshot(t.f.gateway.controller.deepCopy().put("lcNo", "c0"), t.units.get(target.equipmentId()).deepCopy());
                }
            };
            var recovery = new DaikinControlRecoveryService(t.f.access, t.f.store, t.f.policy, List.of(reader), t.f.clock);
            recovery.continueCurrent(7, work(t, batch, 0).item().operationId(), new Close("按本次当前状态继续测试"), "offline");
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isEqualTo(1);
            recovery.continueCurrent(7, work(t, batch, 1).item().operationId(), new Close("按本次当前状态继续测试"), "offline");
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isZero();
            for (int i = 0; i < 3; i++) t.round();
            assertThat(t.sends).hasValue(2); assertThat(work(t, batch, 2).item().status()).isEqualTo(ItemStatus.NOT_SENT);
        }
    }

    @Test void recoveryCannotReleaseOnePeerWhileTheOtherStillRuns() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            t.noApply.addAll(t.devices(2)); var batch = t.submit(2, Action.ON_OFF, "on"); send(t, batch, 0); send(t, batch, 1);
            String first = work(t, batch, 0).item().operationId();
            t.f.jdbc.update("UPDATE biz_daikin_control_operation SET deadline_at=?,next_check_at=0 WHERE operation_id=?", t.f.clock.millis()-1, first);
            t.controls.reconcile(); t.store.synchronize(t.f.clock);
            var reader = new DaikinControlGrantReader() {
                public boolean supports(String source) { return true; }
                public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) {
                    return new DaikinControlGateway.Snapshot(t.f.gateway.controller.deepCopy().put("lcNo", "c0"), t.units.get(target.equipmentId()).deepCopy());
                }
            };
            var recovery = new DaikinControlRecoveryService(t.f.access, t.f.store, t.f.policy, List.of(reader), t.f.clock);
            assertThatThrownBy(() -> recovery.continueCurrent(7, first, new Close("按当前状态继续本轮测试"), "offline"))
                    .isInstanceOf(BusinessException.class);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot", Integer.class)).isEqualTo(2);
            assertThat(t.sends).hasValue(2);
        }
    }

    @Test void recoveredUnknownCannotReviveAnAlreadyClaimedButUnsentPeer() throws Exception {
        try (var t = new DaikinBatchControlTest.Fixture(2, 2)) {
            var batch = t.submit(2, Action.ON_OFF, "on"); send(t, batch, 0);
            var second = t.store.claim(work(t, batch, 1), t.f.clock, 4);
            String first = work(t, batch, 0).item().operationId();
            t.f.store.transition(first, Set.of(Status.ACCEPTED), Status.UNKNOWN, "SEND_RESULT_UNKNOWN", null, "on", null, null, 0, t.f.clock.millis());
            var reader = new DaikinControlGrantReader() {
                public boolean supports(String source) { return true; }
                public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) {
                    return new DaikinControlGateway.Snapshot(t.f.gateway.controller.deepCopy().put("lcNo", "c0"), t.units.get(target.equipmentId()).deepCopy());
                }
            };
            var recovery = new DaikinControlRecoveryService(t.f.access, t.f.store, t.f.policy, List.of(reader), t.f.clock);
            recovery.continueCurrent(7, first, new Close("按当前现场基线结束旧异常"), "offline");
            t.service.dispatch(second);
            assertThat(work(t, batch, 1).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(t.sends).hasValue(1);
        }
    }
}
