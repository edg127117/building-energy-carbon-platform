package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;

class DaikinControlServiceTest {
    ControlFixture f;
    @BeforeEach void setUp() throws Exception { f = new ControlFixture(); }

    @ParameterizedTest
    @CsvSource({"ON_OFF,on", "TEMPERATURE,26", "MODE,heating", "AIR_FLOW,airFlowAuto", "FAN_SPEED,high"})
    void persistsSendsExactlyOnceThenObservesEveryAction(Action action, String value) {
        var request = f.request(action, value, "request-123");
        var op = f.service.submit(7, "equipment", request, "trace");
        assertThat(op.status()).isEqualTo(Status.ACCEPTED);
        assertThat(f.gateway.writes).hasValue(1);
        assertThat(f.service.submit(7, "equipment", request, "another-trace").operationId()).isEqualTo(op.operationId());
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("BUSY");
        f.stabilize(f.service);
        var confirmed = f.service.get(7, op.operationId());
        assertThat(confirmed.status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(confirmed.observedValue()).isEqualTo(value);
        assertThat(f.store.active("equipment")).isNull();
        assertThat(f.gateway.writes).hasValue(1);
        assertThat(f.jdbc.queryForList("SELECT action_type FROM sys_security_audit_event", String.class))
                .contains("HVAC_CONTROL_SENDING", "HVAC_CONTROL_CLOUD_VERIFIED").doesNotContain("HVAC_CONTROL_CONFIRMED");
        assertThat(f.jdbc.queryForObject("SELECT MAX(operation_time) FROM sys_security_audit_event", java.sql.Timestamp.class).toLocalDateTime())
                .isEqualTo(java.time.LocalDateTime.ofInstant(f.clock.instant(), java.time.ZoneId.systemDefault()));
        assertThat(f.service.history(7, "equipment", 1, 10).items()).containsExactly(confirmed);
    }

    @Test void disabledMeansNoGatewayReadOrWriteEvenWithAuthorizedUser() {
        var req = f.request(Action.ON_OFF, "on", "request-123");
        f.props.setEnabled(false); int reads = f.gateway.reads.get();
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> f.service.submit(7, "equipment", req, "trace")).isInstanceOf(BusinessException.class);
        f.service.reconcile(); assertThat(f.gateway.writes).hasValue(0); assertThat(f.gateway.reads).hasValue(reads);
    }

    @Test void ownersThirdPartiesAndUnassignedAdminsCannotWriteAndRevocationUsesDatabase() {
        var req = f.request(Action.ON_OFF, "on", "request-123");
        for (long user : List.of(8L, 9L, 10L))
            assertThatThrownBy(() -> f.service.submit(user, "equipment", req, "trace")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> f.service.submit(7, "other", req, "trace")).isInstanceOf(BusinessException.class);
        f.jdbc.update("DELETE FROM sys_user_building WHERE user_id=7");
        assertThatThrownBy(() -> f.service.submit(7, "equipment", req, "trace")).isInstanceOf(BusinessException.class);
        f.jdbc.update("INSERT INTO sys_user_building VALUES (7,'building')");
        f.jdbc.update("UPDATE sys_user_backend_duty SET status='REVOKED' WHERE user_id=7");
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("FORBIDDEN");
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void revocationDuringLivePreflightPreventsSending() {
        var req = f.request(Action.ON_OFF, "on", "request-123");
        f.gateway.onRead = t -> f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=7");
        assertThat(f.service.submit(7, "equipment", req, "trace").status()).isEqualTo(Status.NOT_SENT);
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void revisionChangesAndMissingTargetsNeverSend() {
        var req = f.request(Action.ON_OFF, "on", "request-123");
        f.gateway.unit.put("mode", "heating");
        assertThat(f.service.submit(7, "equipment", req, "trace").reasonCode()).isEqualTo("REVISION_CHANGED");
        f.jdbc.update("UPDATE biz_daikin_directory SET missing=1");
        assertThat(f.service.options(7, "equipment").canControl()).isFalse();
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void responseLostAfterDeviceAcceptsStaysUnknownWithoutResendAndCanBeClosed() {
        f.gateway.loseResponse = true;
        var op = f.submit(Action.ON_OFF, "on");
        assertThat(op.status()).isEqualTo(Status.UNKNOWN);
        f.clock.advance(15_000); f.service.reconcile();
        op = f.service.get(7, op.operationId());
        assertThat(op.status()).isEqualTo(Status.UNKNOWN);
        assertThat(op.reasonCode()).isEqualTo("SEND_RESULT_UNKNOWN");
        assertThat(op.observedValue()).isEqualTo("on");
        assertThat(f.store.active("equipment")).isEqualTo(op.operationId());
        f.service.recheck(7, op.operationId());
        var closed = f.service.close(7, op.operationId(), "已现场核对并确认结束待核实记录");
        assertThat(closed.status()).isEqualTo(Status.CLOSED);
        assertThat(closed.reasonCode()).isEqualTo("MANUALLY_CLOSED_UNKNOWN");
        assertThat(f.gateway.writes).hasValue(1); assertThat(f.store.active("equipment")).isEqualTo(op.operationId());
        assertThatThrownBy(() -> f.service.release(7, closed.operationId(), "模拟现场核对并解除隔离"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void successfulResponseWithNoChangeOrReadFailureNeverClaimsExecutionAndReadbacksAreBounded() {
        f.gateway.apply = false;
        var op = f.submit(Action.TEMPERATURE, "26");
        f.gateway.failRead = true;
        for (long n : List.of(15_000L, 30_000L, 30_000L, 225_000L)) { f.clock.advance(n); f.service.reconcile(); }
        assertThat(f.service.get(7, op.operationId()).status()).isEqualTo(Status.UNCONFIRMED);
        int reads = f.gateway.reads.get();
        f.clock.advance(100_000); f.service.reconcile();
        assertThat(f.gateway.reads).hasValue(reads); assertThat(f.gateway.writes).hasValue(1);
    }

    @ParameterizedTest @CsvSource({"10003", "10008", "10007", "10015", "10016", "10010", "20000"})
    void explicitVendorFailuresAreRecordedWithoutRetries(String code) {
        f.gateway.code = code;
        var op = f.submit(Action.ON_OFF, "on");
        assertThat(op.status()).isEqualTo(Status.REJECTED); assertThat(op.vendorCode()).isEqualTo(code);
        f.clock.advance(10_000); f.service.reconcile(); assertThat(f.gateway.writes).hasValue(1);
        if (Set.of("10003", "10008").contains(code)) assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("SOURCE_PAUSED");
    }

    @Test void auditFailureRollsBackBeforeSendAndPostSendPersistenceFailureDoesNotReplay() {
        var req = f.request(Action.ON_OFF, "on", "request-123");
        f.auditFails = true;
        assertThatThrownBy(() -> f.service.submit(7, "equipment", req, "trace")).isInstanceOf(IllegalStateException.class);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation", Integer.class)).isZero();
        assertThat(f.gateway.writes).hasValue(0);
        f.auditFails = false;
        f.gateway.onSend = () -> f.auditFails = true;
        assertThatThrownBy(() -> f.service.submit(7, "equipment", req, "trace")).isInstanceOf(IllegalStateException.class);
        assertThat(f.gateway.writes).hasValue(1);
        String id = f.store.active("equipment");
        assertThat(f.store.get(id).view().status()).isEqualTo(Status.SENDING);
        f.auditFails = false;
        var restarted = new DaikinControlService(f.props, f.access, f.policy, f.store, List.of(f.gateway), f.clock);
        f.clock.advance(30_000); restarted.reconcile(); restarted.reconcile();
        assertThat(f.store.get(id).view().status()).isEqualTo(Status.UNKNOWN); assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void concurrentSameRequestReturnsPersistentRecordAndDifferentRequestIsRejected() throws Exception {
        var request = f.request(Action.ON_OFF, "on", "request-123");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        f.gateway.onRead = t -> { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> f.service.submit(7, "equipment", request, "trace"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var duplicate = f.service.submit(7, "equipment", request, "trace2");
            assertThat(duplicate.status()).isEqualTo(Status.PREPARING);
            assertThatThrownBy(() -> f.service.submit(7, "equipment", new Submit(Action.ON_OFF, "off", "request-456", request.revision()), "trace3"))
                    .isInstanceOf(BusinessException.class);
            release.countDown(); assertThat(first.get().operationId()).isEqualTo(duplicate.operationId());
        }
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void unrecognizedVendorCodeKeepsDeviceOccupiedAndNeverRetries() {
        f.gateway.code = "99999";
        var op = f.submit(Action.ON_OFF, "on");
        assertThat(op.status()).isEqualTo(Status.UNKNOWN);
        assertThat(op.vendorCode()).isEqualTo("99999");
        f.clock.advance(15_000); f.service.reconcile();
        assertThat(f.service.get(7, op.operationId()).status()).isEqualTo(Status.UNKNOWN);
        assertThat(f.store.active("equipment")).isEqualTo(op.operationId());
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void offlineCachedMatchingValueCannotBeReportedAsObservedMatch() {
        var op = f.submit(Action.ON_OFF, "on");
        f.gateway.controller.put("isConnectionUp", false);
        f.clock.advance(15_000); f.service.reconcile();
        var checked = f.service.get(7, op.operationId());
        assertThat(checked.status()).isEqualTo(Status.ACCEPTED);
        assertThat(checked.observedValue()).isNull();
        assertThat(checked.reasonCode()).isEqualTo("CONTROLLER_UNAVAILABLE");
        f.clock.advance(285_000); f.service.reconcile();
        assertThat(f.service.get(7, op.operationId()).status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(f.store.active("equipment")).isEqualTo(op.operationId());
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void stalePreflightAndChangedIdempotentPayloadCannotSend() {
        var request = f.request(Action.ON_OFF, "on", "request-123");
        f.gateway.onRead = t -> f.clock.advance(30_000);
        var op = f.service.submit(7, "equipment", request, "trace");
        assertThat(op.status()).isEqualTo(Status.NOT_SENT);
        assertThat(op.reasonCode()).isEqualTo("PREFLIGHT_EXPIRED");
        assertThatThrownBy(() -> f.service.submit(7, "equipment",
                new Submit(Action.ON_OFF, "off", request.idempotencyKey(), request.revision()), "trace"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("HVAC_CONTROL_IDEMPOTENCY_CONFLICT"));
        assertThat(f.gateway.writes).hasValue(0);
    }
}
