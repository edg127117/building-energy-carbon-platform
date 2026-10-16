package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.platform.iot.daikin.control.DaikinBatchContracts.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 内存厂家与 H2 模拟冷热选择权、独立启停及共用遥控器边界，不连接真实设备。 */
class DaikinBatchRelationshipTest {
    private void commonModeMaster(DaikinBatchControlTest.Fixture t, int master) {
        var primary=t.units.get(t.device(master));
        primary.put("masterSlaveFlag","Master").put("isGroupSlave",false).putArray("masterSlaveIds");
        for(int i=0;i<master;i++) {
            var unit=t.units.get(t.device(i));
            unit.put("masterSlaveFlag","Slave").put("isGroupSlave",false).putArray("masterSlaveIds").add("v"+master);
            unit.putArray("modeSetList").add("fan").add("dependent").add("dry");
            primary.withArray("masterSlaveIds").add("v"+i);
        }
    }

    private DaikinBatchStore.Work work(DaikinBatchControlTest.Fixture t, Batch batch, int index) {
        return t.store.work(t.service.get(7,batch.batchId()).items().stream()
                .filter(i->i.equipmentId().equals(t.device(index))).findFirst().orElseThrow().itemId());
    }

    private void send(DaikinBatchControlTest.Fixture t, Batch batch, int index) {
        var claim=t.store.claim(work(t,batch,index),t.f.clock,4);
        assertThat(claim).isNotNull();t.service.dispatch(claim);
    }

    @ParameterizedTest @ValueSource(strings={"on","off"})
    void commonModeMasterDoesNotPreventOverlappingSwitchesOrReceiveACommand(String target) throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,3)) {
            commonModeMaster(t,2);
            if(target.equals("off")) for(int i=0;i<2;i++) t.units.get(t.device(i)).put("onOff","on").put("unitStatus","operating");
            var masterBefore=t.units.get(t.device(2)).deepCopy();
            t.delayedRun.addAll(t.devices(2));
            var preview=t.preview(2);
            assertThat(preview.items()).allSatisfy(i->{
                assertThat(i.independentOnOff()).isTrue();
                assertThat(i.impactedEquipmentIds()).containsExactly(t.device(2));
            });
            var batch=t.service.submit(7,t.request(preview,Action.ON_OFF,target,"mode-master-switch"),"offline");
            assertThat(t.f.jdbc.queryForList("SELECT impacted_targets_json FROM biz_daikin_control_batch_item",String.class))
                    .containsOnly("[]");
            // Exercise the production scheduler/worker entry, not just direct claims.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                t.service.tick();
                if(t.service.get(7,batch.batchId()).items().stream().allMatch(i->i.status()==ItemStatus.ACTIVE)) break;
                Thread.sleep(10);
            }
            assertThat(t.sends).hasValue(2);
            assertThat(t.service.get(7,batch.batchId()).items()).allSatisfy(i->{
                assertThat(i.status()).isEqualTo(ItemStatus.ACTIVE);
                assertThat(t.f.store.get(i.operationId()).view().status()).isEqualTo(Status.ACCEPTED);
            });
            t.controls.reconcile();t.store.synchronize(t.f.clock);
            assertThat(t.service.get(7,batch.batchId()).completedCount()).isZero();
            for(int i=0;i<2;i++) t.units.get(t.device(i)).put("unitStatus",target.equals("on")?"operating":"stopped");
            t.f.clock.advance(2_000);t.controls.reconcile();t.store.synchronize(t.f.clock);
            assertThat(t.service.get(7,batch.batchId()).completedCount()).isEqualTo(2);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation WHERE equipment_id=?",Integer.class,t.device(2))).isZero();
            assertThat(t.units.get(t.device(2))).isEqualTo(masterBefore);
            for(String table:List.of("slot","controller_slot","batch_lane"))
                assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_"+table,Integer.class)).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings={"shared","missingGroup","invalidGroup","missingIds","invalidIds","unknownRole"})
    void sharedRemoteOrIncompleteRelationshipNeverPermitsSwitching(String kind) throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,3)) {
            commonModeMaster(t,2);ObjectNode unit=t.units.get(t.device(0));
            switch(kind) {
                case "shared" -> unit.put("isGroupSlave",true);
                case "missingGroup" -> unit.remove("isGroupSlave");
                case "invalidGroup" -> unit.put("isGroupSlave","false");
                case "missingIds" -> unit.remove("masterSlaveIds");
                case "invalidIds" -> unit.put("masterSlaveIds","v2");
                case "unknownRole" -> unit.put("masterSlaveFlag","Unknown");
                default -> throw new AssertionError(kind);
            }
            var preview=t.preview(1);var item=preview.items().getFirst();
            assertThat(item.independentOnOff()).isFalse();
            assertThat(item.actions().stream().filter(a->a.action()==Action.ON_OFF).findFirst().orElseThrow().allowed()).isFalse();
            var batch=t.service.submit(7,new SubmitBatch(preview.previewId(),Action.ON_OFF,"on","guard-"+kind,true),"offline");
            assertThat(t.drain(batch.batchId()).items().getFirst().status()).isEqualTo(ItemStatus.UNSUPPORTED);
            assertThat(t.sends).hasValue(0);
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void relationshipOrSharedRemoteChangingAfterPreviewPreventsSend(boolean sharedRemote) throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,3)) {
            commonModeMaster(t,2);var batch=t.submit(1,Action.ON_OFF,"on");
            if(sharedRemote)t.units.get(t.device(0)).put("isGroupSlave",true);
            else t.units.get(t.device(0)).putArray("masterSlaveIds").add("v1");
            send(t,batch,0);
            assertThat(work(t,batch,0).item().status()).isEqualTo(ItemStatus.ACTIVE);
            t.store.synchronize(t.f.clock);
            assertThat(work(t,batch,0).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(t.sends).hasValue(0);
        }
    }

    @Test void slaveModeCapabilitiesAndMasterModeConfirmationRemainEnforced() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,3)) {
            commonModeMaster(t,2);
            var unsupported=t.drain(t.submit(1,Action.MODE,"heating").batchId());
            assertThat(unsupported.items().getFirst().status()).isEqualTo(ItemStatus.UNSUPPORTED);
            var preview=t.service.preview(7,new PreviewRequest(List.of(t.device(2))));
            assertThat(preview.items().getFirst().impactedEquipmentIds()).containsExactly(t.device(0),t.device(1));
            assertThatThrownBy(()->t.service.submit(7,t.request(preview,Action.MODE,"heating","master-mode-no-confirm"),"offline"))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("HVAC_CONTROL_LINKED_CONFIRMATION_REQUIRED"));
            var batch=t.service.submit(7,new SubmitBatch(preview.previewId(),Action.MODE,"heating","master-mode-confirmed",true),"offline");
            assertThat(work(t,batch,2).impactedTargets()).hasSize(2);
            assertThat(t.sends).hasValue(0);
        }
    }

    @Test void oldPreviewDoesNotAcquireIndependentSwitchPermission() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,3)) {
            commonModeMaster(t,2);var preview=t.preview(2);
            var saved=t.f.json.valueToTree(t.store.preview(7,preview.previewId(),t.f.clock.millis()));
            for(var item:saved.path("items"))((ObjectNode)item.path("view")).remove("independentOnOff");
            t.f.jdbc.update("UPDATE biz_daikin_control_preview SET payload_json=? WHERE preview_id=?",saved.toString(),preview.previewId());
            assertThatThrownBy(()->t.service.submit(7,t.request(preview,Action.ON_OFF,"on","old-preview-unconfirmed"),"offline"))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("HVAC_CONTROL_LINKED_CONFIRMATION_REQUIRED"));
            var batch=t.service.submit(7,new SubmitBatch(preview.previewId(),Action.ON_OFF,"on","old-preview-confirmed",true),"offline");
            send(t,batch,0);
            assertThat(t.store.claim(work(t,batch,1),t.f.clock,4)).isNull();
            assertThat(work(t,batch,1).impactedTargets()).hasSize(1);
            assertThat(t.sends).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void unknownOrTimeoutWithCommonModeMasterStopsUnsentPeersWithoutReplay(boolean unknown) throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(4,4)) {
            commonModeMaster(t,3);var masterBefore=t.units.get(t.device(3)).deepCopy();
            t.delayedRun.add(t.device(0));
            if(unknown)t.unknown.add(t.device(1));else t.noApply.add(t.device(1));
            var batch=t.submit(3,Action.ON_OFF,"on");send(t,batch,0);send(t,batch,1);
            t.units.get(t.device(0)).put("unitStatus","operating");
            t.f.clock.advance(2_000);t.controls.reconcile();t.store.synchronize(t.f.clock);
            if(!unknown){t.f.clock.advance(60_001);t.controls.reconcile();t.store.synchronize(t.f.clock);}
            var result=t.drain(batch.batchId());
            assertThat(work(t,batch,0).item().status()).isEqualTo(ItemStatus.MATCHED);
            assertThat(work(t,batch,1).item().status()).isEqualTo(ItemStatus.UNCONFIRMED);
            assertThat(work(t,batch,2).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(result.attentionCount()).isEqualTo(2);assertThat(t.sends).hasValue(2);
            assertThat(t.units.get(t.device(3))).isEqualTo(masterBefore);
        }
    }

    @Test void legacyConfirmedPreviewCannotBypassSharedRemoteGuardAtDispatch() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,3)) {
            commonModeMaster(t,2);t.units.get(t.device(0)).put("isGroupSlave",true);
            var preview=t.preview(1);
            var saved=t.f.json.valueToTree(t.store.preview(7,preview.previewId(),t.f.clock.millis()));
            var view=(ObjectNode)saved.path("items").get(0).path("view");
            view.remove("independentOnOff");
            for(var action:view.path("actions"))if(action.path("action").asText().equals("ON_OFF")) {
                ((ObjectNode)action).put("allowed",true).putNull("reasonCode");
                ((ObjectNode)action).putArray("values").add("on").add("off");
            }
            t.f.jdbc.update("UPDATE biz_daikin_control_preview SET payload_json=? WHERE preview_id=?",saved.toString(),preview.previewId());
            var batch=t.service.submit(7,new SubmitBatch(preview.previewId(),Action.ON_OFF,"on","legacy-shared-confirmed",true),"offline");
            send(t,batch,0);t.store.synchronize(t.f.clock);
            assertThat(work(t,batch,0).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(work(t,batch,0).item().reasonCode()).isEqualTo("LINKED_SCOPE_UNKNOWN");
            assertThat(t.sends).hasValue(0);
        }
    }

    @Test void readFailureAfterPreviewSendsNothingAndReleasesTheReservation() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,3)) {
            commonModeMaster(t,2);var batch=t.submit(1,Action.ON_OFF,"on");
            t.readFailure.add(t.device(0));send(t,batch,0);t.store.synchronize(t.f.clock);
            assertThat(work(t,batch,0).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(t.sends).hasValue(0);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isZero();
        }
    }
}
