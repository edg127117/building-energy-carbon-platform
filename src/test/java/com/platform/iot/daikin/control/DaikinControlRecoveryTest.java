package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 仅 H2 和内存厂家；新基线恢复不调用 send，不修改旧指令或解除其他设备占用。 */
class DaikinControlRecoveryTest {
    static class Scenario {
        final ControlFixture f;
        final Operation old;
        final DaikinControlRecoveryService recovery;
        final RecoveryBaseline baseline=new RecoveryBaseline("on",25);
        Scenario(boolean unknown) throws Exception {
            f=new ControlFixture();
            f.gateway.unit.put("onOff","on").put("unitStatus","operating").put("temperature",25);
            f.gateway.loseResponse=unknown;
            old=f.submit(Action.ON_OFF,"off");
            f.clock.advance(300_000); f.service.reconcile();
            f.gateway.unit.put("onOff","on").put("unitStatus","operating");
            f.jdbc.update("UPDATE biz_daikin_control_grant SET enabled=FALSE");
            f.props.setEnabled(false);
            var reader=new DaikinControlGrantReader() {
                public boolean supports(String source) { return f.gateway.supports(source); }
                public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) { return f.gateway.read(target); }
            };
            recovery=new DaikinControlRecoveryService(f.access,f.store,f.policy,List.of(reader),f.clock);
        }
        RecoveryProgress stable() {
            var first=recovery.observe(9,old.operationId(),baseline,"recovery-test");
            assertThat(first.stableCount()).isEqualTo(1); assertThat(first.canRecover()).isFalse();
            f.clock.advance(30_000); recovery.observe(9,old.operationId(),baseline,"recovery-test");
            f.clock.advance(30_000); return recovery.observe(9,old.operationId(),baseline,"recovery-test");
        }
        RecoverCurrentBaseline completeBody(RecoveryProgress p) {
            return new RecoverCurrentBaseline("on",25,p.proof(),"现场核对开机25度，按新基线恢复旧隔离，不重发旧指令",true);
        }
    }

    @Test void disabledGrantAndControlCanRecoverExpiredOldOffWithoutClaimingOffSucceeded() throws Exception {
        var s=new Scenario(false); var f=s.f;
        var original=f.jdbc.queryForMap("SELECT requested_value,baseline_json,target_json,revision,request_hash,idempotency_key,vendor_code FROM biz_daikin_control_operation WHERE operation_id=?",s.old.operationId());
        assertThat(s.recovery.candidate(9,"equipment").eligible()).isTrue();
        int writes=f.gateway.writes.get(); var proof=s.stable();
        assertThat(proof.canRecover()).isTrue(); assertThat(proof.onOff()).isEqualTo("on"); assertThat(proof.temperature()).isEqualTo(25);
        var done=s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"onsite-recovery");
        assertThat(done.status()).isEqualTo(Status.CLOSED); assertThat(done.reasonCode()).isEqualTo("RECOVERY_CURRENT_BASELINE_RELEASED");
        assertThat(done.isolated()).isFalse(); assertThat(done.cloudStable()).isFalse();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT enabled FROM biz_daikin_control_grant",Boolean.class)).isFalse();
        assertThat(f.jdbc.queryForMap("SELECT requested_value,baseline_json,target_json,revision,request_hash,idempotency_key,vendor_code FROM biz_daikin_control_operation WHERE operation_id=?",s.old.operationId())).isEqualTo(original);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample WHERE reason_code LIKE 'RECOVERY_%' AND matches_target=TRUE",Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event WHERE action_type='HVAC_CONTROL_RECOVERY_RELEASE'",Integer.class)).isEqualTo(1);
        int reads=f.gateway.reads.get();
        assertThat(s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"repeat")).isEqualTo(done);
        assertThat(f.gateway.reads).hasValue(reads); assertThat(f.gateway.writes).hasValue(writes);
    }

    @Test void unknownCanOnlyCloseAsRecoveryAndOriginalResultStaysUnknownInHistory() throws Exception {
        var s=new Scenario(true); var proof=s.stable();
        var done=s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"recovery");
        assertThat(done.status()).isEqualTo(Status.CLOSED); assertThat(done.vendorCode()).isNull();
        assertThat(s.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_event WHERE status='UNKNOWN'",Integer.class)).isPositive();
        assertThat(s.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_event WHERE status IN ('CONFIRMED','CLOUD_VERIFIED')",Integer.class)).isZero();
        assertThat(s.f.gateway.writes).hasValue(1);
    }

    @Test void beforeDeadlineAndNonAdminCannotReadOrRecoverAndNoImplicitDutyIsGranted() throws Exception {
        var s=new Scenario(false);
        for(long actor:List.of(7L,8L,10L)) assertThatThrownBy(()->s.recovery.candidate(actor,"equipment")).isInstanceOf(BusinessException.class);
        s.f.jdbc.update("UPDATE biz_daikin_control_operation SET deadline_at=?",s.f.clock.millis()+1);
        assertThat(s.recovery.candidate(9,"equipment").reasonCode()).isEqualTo("RECOVERY_NOT_ELIGIBLE");
        int reads=s.f.gateway.reads.get();
        assertThatThrownBy(()->s.recovery.observe(9,s.old.operationId(),s.baseline,"trace")).hasMessageContaining("不满足");
        assertThat(s.f.gateway.reads).hasValue(reads);
        s.f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=9");
        assertThatThrownBy(()->s.recovery.candidate(9,"equipment")).isInstanceOf(BusinessException.class);
    }

    @Test void frequentRequestsDoNotCountAndChangedOtherSettingsRestartEvidence() throws Exception {
        var s=new Scenario(false); var f=s.f;
        s.recovery.observe(9,s.old.operationId(),s.baseline,"first"); int reads=f.gateway.reads.get();
        f.clock.advance(20_000);
        assertThat(s.recovery.observe(9,s.old.operationId(),s.baseline,"too-soon").reasonCode()).isEqualTo("RECOVERY_SAMPLE_INTERVAL");
        assertThat(f.gateway.reads).hasValue(reads);
        f.clock.advance(10_000); s.recovery.observe(9,s.old.operationId(),s.baseline,"second");
        f.gateway.unit.put("fanSpeed","high"); f.clock.advance(30_000);
        var changed=s.recovery.observe(9,s.old.operationId(),s.baseline,"changed");
        assertThat(changed.stableCount()).isEqualTo(1); assertThat(changed.canRecover()).isFalse();
        f.clock.advance(61_000);
        assertThat(s.recovery.observe(9,s.old.operationId(),s.baseline,"gap").stableCount()).isEqualTo(1);
        assertThat(f.store.get(s.old.operationId()).view().isolated()).isTrue();
    }

    @Test void finalReadMismatchInvalidatesProofAndKeepsBothSlots() throws Exception {
        var s=new Scenario(false); var proof=s.stable();
        s.f.gateway.unit.put("temperature",26);
        assertThatThrownBy(()->s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"changed"))
                .isInstanceOf(BusinessException.class);
        assertThat(s.f.store.get(s.old.operationId()).view().isolated()).isTrue();
        assertThat(s.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isEqualTo(1);
        assertThat(s.f.store.recoveryEvidence(s.f.store.get(s.old.operationId()),s.f.access.candidate("equipment"),9,s.f.clock.millis()).canRecover()).isFalse();
    }

    @Test void oldProofDifferentAdminAndMissingOnsiteConfirmationCannotRelease() throws Exception {
        var s=new Scenario(false); var proof=s.stable();
        s.f.jdbc.update("INSERT INTO sys_user VALUES (11,1,0)"); s.f.jdbc.update("INSERT INTO user_roles VALUES (11,'PLATFORM_ADMIN')");
        assertThatThrownBy(()->s.recovery.complete(11,s.old.operationId(),s.completeBody(proof),"other-admin")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->s.recovery.complete(9,s.old.operationId(),new RecoverCurrentBaseline("on",25,proof.proof(),"没有现场确认",false),"no-onsite"))
                .isInstanceOf(BusinessException.class);
        s.f.clock.advance(30_000);
        assertThatThrownBy(()->s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"expired")).isInstanceOf(BusinessException.class);
        assertThat(s.f.store.get(s.old.operationId()).view().isolated()).isTrue();
    }

    @Test void readErrorsRelationsIdentityAndAdminRevocationKeepIsolation() throws Exception {
        for(String kind:List.of("read","relationship","identity","admin","slow")) {
            var s=new Scenario(false); int before=s.f.gateway.writes.get();
            if(kind.equals("read")) s.f.gateway.failRead=true;
            if(kind.equals("relationship")) s.f.gateway.unit.withArray("masterSlaveIds").add("new-master");
            if(kind.equals("identity")) s.f.gateway.onRead=t->s.f.jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=2");
            if(kind.equals("admin")) s.f.gateway.onRead=t->s.f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=9");
            if(kind.equals("slow")) s.f.gateway.onRead=t->s.f.clock.advance(31_000);
            if(List.of("identity","admin","slow").contains(kind))
                assertThatThrownBy(()->s.recovery.observe(9,s.old.operationId(),s.baseline,"reject")).isInstanceOf(BusinessException.class);
            else assertThat(s.recovery.observe(9,s.old.operationId(),s.baseline,"reject").canRecover()).isFalse();
            assertThat(s.f.store.get(s.old.operationId()).view().isolated()).isTrue();
            assertThat(s.f.gateway.writes).hasValue(before);
        }
    }

    @Test void auditFailureRollsBackFinalSampleClosureAndBothSlotReleases() throws Exception {
        var s=new Scenario(false); var proof=s.stable(); var f=s.f;
        int samples=f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class);
        int events=f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_event",Integer.class);
        f.auditFails=true;
        assertThatThrownBy(()->s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"audit-fails")).isInstanceOf(RuntimeException.class);
        assertThat(f.store.get(s.old.operationId()).view().status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(f.store.get(s.old.operationId()).view().isolated()).isTrue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class)).isEqualTo(samples);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_event",Integer.class)).isEqualTo(events);
        f.auditFails=false; f.clock.advance(30_000);
        var fresh=s.recovery.observe(9,s.old.operationId(),s.baseline,"readonly-retry");
        assertThat(s.recovery.complete(9,s.old.operationId(),s.completeBody(fresh),"retry").isolated()).isFalse();
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void competingInstancesCannotBothCommitRecovery() throws Exception {
        var s=new Scenario(false); var proof=s.stable(); var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await(); try{return s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"a").status().name();}catch(BusinessException e){return e.getErrorCode();}});
            var b=pool.submit(()->{start.await(); try{return s.recovery.complete(9,s.old.operationId(),s.completeBody(proof),"b").status().name();}catch(BusinessException e){return e.getErrorCode();}});
            start.countDown(); assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).contains("CLOSED");
        }
        assertThat(s.f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event WHERE action_type='HVAC_CONTROL_RECOVERY_RELEASE'",Integer.class)).isEqualTo(1);
        assertThat(s.f.gateway.writes).hasValue(1);
    }

    @Test void explicitTestDeviceListRestrictsEvenAdministratorsAndDoesNotGrantDevices() throws Exception {
        var f=new ControlFixture(); f.props.setTestAccessEnabled(true); f.props.setTestEquipmentIds(List.of("equipment"));
        assertThatCode(()->f.access.requireControl(9,"equipment")).doesNotThrowAnyException();
        assertThatThrownBy(()->f.access.requireControl(9,"other")).isInstanceOf(BusinessException.class);
        assertThat(f.service.options(9,"other").canControl()).isFalse(); assertThat(f.gateway.writes).hasValue(0);
    }
    @Test void closingTestWindowRejectsNewCommandsButAllowsExistingOperationToFinish() throws Exception {
        var f=new ControlFixture(); f.props.setTestAccessEnabled(true); f.props.setTestEquipmentIds(List.of("equipment"));
        f.props.setTestWindowExpiresAt(f.clock.millis()+480_000);
        var body=f.request(Action.TEMPERATURE,"26","window-command");
        var op=f.service.submit(7,"equipment",body,"window");
        f.stabilize(f.service);
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.service.options(7,"equipment").reasonCode()).isEqualTo("TEST_WINDOW_CLOSING");
        assertThat(f.service.submit(7,"equipment",body,"repeat").operationId()).isEqualTo(op.operationId());
        assertThatThrownBy(()->f.service.submit(7,"equipment",new Submit(Action.TEMPERATURE,"25","too-late-command","a".repeat(64)),"late"))
                .isInstanceOf(BusinessException.class);
        assertThat(f.gateway.writes).hasValue(1);
    }
}
