package com.platform.iot.daikin.control;

import com.platform.audit.AuditEnvironmentMode;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** H2 和内存厂家；验证测试全开放、快速目标核验、外部操作及异常事务，不连接现场。 */
class DaikinControlFastTest {
    static ControlFixture fixture() throws Exception {
        var f = new ControlFixture(); f.props.setTestOpenControls(true);
        f.jdbc.update("DELETE FROM sys_user_backend_duty"); f.jdbc.update("DELETE FROM sys_user_building");
        f.jdbc.update("DELETE FROM biz_daikin_control_grant"); return f;
    }
    static Operation submit(ControlFixture f, long user, Action action, String value, String key) {
        var options = f.service.options(user,"equipment");
        assertThat(options.testOpenControls()).isTrue(); assertThat(options.canControl()).isTrue();
        return f.service.submit(user,"equipment",new Submit(action,value,key,options.revision()),"offline-fast");
    }
    static DaikinControlRecoveryService recovery(ControlFixture f) {
        var reader = new DaikinControlGrantReader() {
            public boolean supports(String source) { return f.gateway.supports(source); }
            public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target t) { return f.gateway.read(t); }
        };
        return new DaikinControlRecoveryService(f.access,f.store,f.policy,List.of(reader),f.clock);
    }

    @Test void allValidAccountsHaveControlHistoryAndRecoveryWithoutRolesDutyBuildingOrGrants() throws Exception {
        var f=fixture(); f.jdbc.update("INSERT INTO sys_user VALUES (11,1,0)");
        for(long actor:List.of(7L,8L,9L,10L,11L)) {
            assertThatCode(()->f.access.requireControl(actor,"equipment")).doesNotThrowAnyException();
            assertThat(f.service.options(actor,"equipment").canControl()).isTrue();
            assertThat(f.service.history(actor,"equipment",1,20).total()).isZero();
            assertThat(recovery(f).candidate(actor,"equipment").simpleRecovery()).isTrue();
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_grant",Integer.class)).isZero();
        f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=11");
        assertThatThrownBy(()->f.access.requireControl(11,"equipment")).isInstanceOf(BusinessException.class);
    }

    @Test void testOpenIsRejectedOutsideExplicitTestAndNeverOpensRealTransportByDefault() {
        var p=new DaikinControlProperties(); assertThat(p.isEnabled()).isFalse(); assertThat(p.isWireConfirmed()).isFalse();
        assertThat(p.isTestOpenControls()).isFalse();
        p.setTestOpenControls(true); var a=new AuditGovernanceProperties();
        for(var mode:List.of(AuditEnvironmentMode.DEVELOPMENT,AuditEnvironmentMode.PRODUCTION)) {
            a.setEnvironmentMode(mode); assertThatThrownBy(()->new DaikinControlTestGuard(p,a).validate()).isInstanceOf(IllegalStateException.class);
        }
        a.setEnvironmentMode(AuditEnvironmentMode.TEST); assertThatCode(()->new DaikinControlTestGuard(p,a).validate()).doesNotThrowAnyException();
        p.setFastReadbackTimeoutMillis(0); assertThatThrownBy(()->new DaikinControlTestGuard(p,a).validate()).isInstanceOf(IllegalStateException.class);
    }

    @Test void oldWindowAndSingleDeviceListsDoNotRestrictOpenTestMode() throws Exception {
        var f=fixture(); f.props.setTestAccessEnabled(true); f.props.setTestEquipmentIds(List.of("not-this-device"));
        f.props.setTestWindowExpiresAt(f.clock.millis()-1);
        var op=submit(f,10,Action.TEMPERATURE,"26","ignore-old-window");
        assertThat(op.status()).isEqualTo(Status.ACCEPTED); assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void firstPostSendReadCompletesWithoutArtificialTimeOrMultipleSamples() throws Exception {
        var f=fixture(); long started=f.clock.millis();
        var op=submit(f,10,Action.TEMPERATURE,"26","first-read-command");
        assertThat(op.verificationPolicy()).isEqualTo(DaikinControlPolicy.TEST_FAST);
        assertThat(op.deadlineAt()).isEqualTo(started+60_000); assertThat(op.status()).isEqualTo(Status.ACCEPTED);
        int preSendReads=f.gateway.reads.get(); assertThat(preSendReads).isEqualTo(1);
        f.service.reconcile(); var done=f.store.get(op.operationId()).view();
        assertThat(done.status()).isEqualTo(Status.CLOUD_VERIFIED); assertThat(done.reasonCode()).isEqualTo("FAST_TARGET_READBACK_MATCH");
        assertThat(done.isolated()).isFalse(); assertThat(done.cloudStable()).isFalse(); assertThat(done.stableCount()).isEqualTo(1);
        assertThat(f.clock.millis()).isEqualTo(started); assertThat(f.gateway.reads).hasValue(preSendReads+1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isZero();
        var next=submit(f,8,Action.TEMPERATURE,"25","second-read-command"); f.service.reconcile();
        assertThat(f.store.get(next.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
    }

    @Test void onsitePowerOffAndModeChangeDoNotFailTemperatureAndNeverCauseCompensatingSend() throws Exception {
        var f=fixture(); f.gateway.unit.put("onOff","on").put("unitStatus","operating");
        f.gateway.onSend=()->f.gateway.unit.put("onOff","off").put("unitStatus","stopped").put("mode","fan");
        var op=submit(f,10,Action.TEMPERATURE,"26","external-power-off"); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.gateway.unit.path("onOff").asText()).isEqualTo("off"); assertThat(f.gateway.unit.path("mode").asText()).isEqualTo("fan");
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void differentTargetFromExternalChannelIsNeverCalledSuccessOrReapplied() throws Exception {
        var f=fixture(); var op=submit(f,10,Action.TEMPERATURE,"26","external-temperature");
        f.gateway.unit.put("temperature",28); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.ACCEPTED);
        assertThat(f.store.get(op.operationId()).view().observedValue()).isEqualTo("28");
        f.clock.advance(60_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(f.store.get(op.operationId()).view().reasonCode()).isEqualTo("FAST_TARGET_NOT_CONFIRMED");
        assertThat(f.gateway.writes).hasValue(1); assertThat(f.gateway.unit.path("temperature").asInt()).isEqualTo(28);
    }

    @Test void allProtocolActionsAreOpenWithoutManualRelationshipAndAirflowApproval() throws Exception {
        for(var action:Action.values()) {
            var f=fixture(); f.gateway.unit.put("isGroupSlave",true).put("masterSlaveFlag","Slave").withArray("masterSlaveIds").add("external-master");
            String value=switch(action) {case ON_OFF->"on";case TEMPERATURE->"26";case MODE->"fan";case AIR_FLOW->"airFlowSeven";case FAN_SPEED->"high";};
            var op=submit(f,10,action,value,"all-action-"+action); f.service.reconcile();
            assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
            assertThat(f.gateway.writes).hasValue(1);
        }
    }

    @Test void protocolRangeAndUniqueIdentityStillProtectAgainstWrongDeviceAndInvalidValues() throws Exception {
        var f=fixture(); var revision=f.service.options(10,"equipment").revision();
        assertThat(f.service.submit(10,"equipment",new Submit(Action.TEMPERATURE,"33","range-invalid",revision),"test").status()).isEqualTo(Status.NOT_SENT);
        assertThat(f.gateway.writes).hasValue(0);
        f.clock.advance(5_000); f.gateway.unit.put("id","another-device");
        assertThat(f.service.options(10,"equipment").canControl()).isFalse(); assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void unrelatedCurrentStateDoesNotInvalidateOldPageButIdentityChangesDo() throws Exception {
        var f=fixture(); var revision=f.service.options(10,"equipment").revision();
        f.gateway.unit.put("onOff","on").put("unitStatus","operating").put("fanSpeed","high"); f.clock.advance(5_000);
        var op=f.service.submit(10,"equipment",new Submit(Action.TEMPERATURE,"26","stale-page-values",revision),"test");
        assertThat(op.status()).isEqualTo(Status.ACCEPTED); f.service.reconcile();
        f.jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=2");
        assertThat(f.service.submit(10,"equipment",new Submit(Action.TEMPERATURE,"25","stale-page-identity",revision),"test").status()).isEqualTo(Status.NOT_SENT);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void sameControllerIsStillSerializedAndRepeatedRequestsDoNotSendTwice() throws Exception {
        var f=fixture(); var revision=f.service.options(10,"equipment").revision();
        var body=new Submit(Action.TEMPERATURE,"26","idempotent-fast-test",revision);
        var first=f.service.submit(10,"equipment",body,"test");
        assertThat(f.service.submit(10,"equipment",body,"retry").operationId()).isEqualTo(first.operationId());
        assertThatThrownBy(()->f.service.submit(8,"equipment",new Submit(Action.TEMPERATURE,"28","busy-fast-test",revision),"test"))
                .isInstanceOf(BusinessException.class);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void unknownSendStaysUnknownEvenWhenTargetMatchesAndAnyTestUserCanContinueOnce() throws Exception {
        var f=fixture(); f.gateway.loseResponse=true;
        var op=submit(f,10,Action.TEMPERATURE,"26","unknown-fast-send"); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.UNKNOWN);
        var r=recovery(f); assertThat(r.candidate(8,"equipment").eligible()).isTrue();
        var old=f.jdbc.queryForMap("SELECT requested_value,target_json,baseline_json,revision,idempotency_key,vendor_code FROM biz_daikin_control_operation WHERE operation_id=?",op.operationId());
        int reads=f.gateway.reads.get(); var done=r.continueCurrent(8,op.operationId(),new Close("按当前最新回读继续测试，旧结果不确认成功"),"continue");
        assertThat(done.status()).isEqualTo(Status.CLOSED); assertThat(done.reasonCode()).isEqualTo("TEST_CURRENT_STATE_RELEASED");
        assertThat(done.isolated()).isFalse(); assertThat(done.cloudStable()).isFalse(); assertThat(f.gateway.reads).hasValue(reads+1);
        assertThat(f.jdbc.queryForMap("SELECT requested_value,target_json,baseline_json,revision,idempotency_key,vendor_code FROM biz_daikin_control_operation WHERE operation_id=?",op.operationId())).isEqualTo(old);
        assertThat(r.continueCurrent(8,op.operationId(),new Close("按当前最新回读继续测试"),"retry")).isEqualTo(done);
        assertThat(f.gateway.reads).hasValue(reads+1); assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void fastFinishAuditFailureRollsBackSampleStateAndBothSlots() throws Exception {
        var f=fixture(); var op=submit(f,10,Action.TEMPERATURE,"26","audit-fast-failure");
        int samples=f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class);
        f.auditFails=true; f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.ACCEPTED);
        assertThat(f.store.get(op.operationId()).view().isolated()).isTrue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class)).isEqualTo(samples);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isEqualTo(1);
        f.auditFails=false; f.clock.advance(30_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED); assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void continueAuditFailureOrIdentityChangeCannotPartiallyReleaseOldIsolation() throws Exception {
        for(String kind:List.of("audit","identity","account","mode","slow")) {
            var f=fixture(); f.gateway.loseResponse=true; var op=submit(f,10,Action.TEMPERATURE,"26","continue-guard-"+kind);
            if(kind.equals("audit")) f.auditFails=true;
            if(kind.equals("identity")) f.gateway.onRead=t->f.jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=2");
            if(kind.equals("account")) f.gateway.onRead=t->f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=8");
            if(kind.equals("mode")) f.gateway.onRead=t->f.props.setTestOpenControls(false);
            if(kind.equals("slow")) f.gateway.onRead=t->f.clock.advance(31_000);
            int samples=f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class);
            assertThatThrownBy(()->recovery(f).continueCurrent(8,op.operationId(),new Close("按当前最新状态继续测试"),"guard"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.UNKNOWN);
            assertThat(f.store.get(op.operationId()).view().isolated()).isTrue();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class)).isEqualTo(samples);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isEqualTo(1);
            assertThat(f.gateway.writes).hasValue(1);
        }
    }

    @Test void pollingBacksOffWithoutAStartingWaitAndStopsAtDeadline() throws Exception {
        var f=fixture(); f.gateway.apply=false;
        var op=submit(f,10,Action.TEMPERATURE,"26","fast-poll-schedule");
        assertThat(f.store.get(op.operationId()).nextCheckAt()).isEqualTo(f.clock.millis());
        f.service.reconcile();
        assertThat(f.store.get(op.operationId()).nextCheckAt()).isEqualTo(f.clock.millis()+2_000);
        f.clock.advance(15_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).nextCheckAt()).isEqualTo(f.clock.millis()+5_000);
        f.clock.advance(15_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).nextCheckAt()).isEqualTo(f.clock.millis()+10_000);
        f.clock.advance(30_000); f.service.reconcile();
        int reads=f.gateway.reads.get(); f.clock.advance(10_000); f.service.reconcile();
        assertThat(f.gateway.reads).hasValue(reads); assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void expiredReadLeaseCannotFinishAndSecondInstanceCanReadWithoutResending() throws Exception {
        var f=fixture(); var op=submit(f,10,Action.TEMPERATURE,"26","fast-stale-lease");
        f.gateway.onRead=t->f.clock.advance(31_000); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.ACCEPTED);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class)).isZero();
        f.gateway.onRead=t->{};
        var other=new DaikinControlService(f.props,f.access,f.policy,f.store,List.of(f.gateway),f.clock);
        other.reconcile(); f.service.reconcile();
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_sample",Integer.class)).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void failedRecoveryReadLeavesOriginalHistoryAndOccupancy() throws Exception {
        var f=fixture(); f.gateway.loseResponse=true;
        var op=submit(f,10,Action.TEMPERATURE,"26","fast-recovery-read-failure");
        f.gateway.failRead=true;
        assertThatThrownBy(()->recovery(f).continueCurrent(8,op.operationId(),new Close("按当前最新状态继续测试"),"offline"))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.UNKNOWN);
        assertThat(f.store.get(op.operationId()).view().isolated()).isTrue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void restoringFanSpeedDoesNotRequireAnUnrelatedTemperatureField() throws Exception {
        var f=fixture(); f.gateway.unit.remove("temperature"); f.gateway.loseResponse=true;
        var op=submit(f,10,Action.FAN_SPEED,"high","fast-fan-recovery");
        var result=recovery(f).continueCurrent(8,op.operationId(),new Close("按最新风速状态继续测试"),"offline");
        assertThat(result.status()).isEqualTo(Status.CLOSED); assertThat(result.isolated()).isFalse();
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void historicalOperationsKeepTheirOldVerificationPolicyAfterModeIsEnabled() throws Exception {
        var f=new ControlFixture(); var op=f.submit(Action.TEMPERATURE,"26"); f.props.setTestOpenControls(true);
        f.service.reconcile(); assertThat(f.store.get(op.operationId()).view().verificationPolicy()).isEqualTo("LEGACY_STABLE");
        assertThat(f.store.get(op.operationId()).view().status()).isNotEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.store.get(op.operationId()).view().isolated()).isTrue();
        f.stabilize(f.service);
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.CLOUD_VERIFIED);
        assertThat(f.store.get(op.operationId()).view().cloudStable()).isTrue();
    }
}
