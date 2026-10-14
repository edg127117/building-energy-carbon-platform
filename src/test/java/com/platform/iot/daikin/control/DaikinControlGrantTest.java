package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 批量配置使用内存只读厂家/H2，验证逐设备事务与权限；从不创建网络请求或设备写入。 */
class DaikinControlGrantTest {
    ControlFixture f;
    DaikinControlGrantService grants;
    @BeforeEach void setup() throws Exception {
        f = new ControlFixture();
        var reader = new DaikinControlGrantReader() {
            public boolean supports(String sourceId) { return f.gateway.supports(sourceId); }
            public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) { return f.gateway.read(target); }
        };
        grants = new DaikinControlGrantService(f.access, f.policy, f.store, List.of(reader), f.clock);
    }
    BatchGrant request(List<String> ids) { return new BatchGrant(ids, List.of(Action.ON_OFF, Action.TEMPERATURE), "管理员批量开放开关和调温"); }

    @Test void opensUnconfiguredDevicesWithControlDisabledAndReportsOnlyExceptions() {
        f.props.setEnabled(false);
        f.jdbc.update("DELETE FROM biz_daikin_control_grant");
        var result = grants.open(9, request(List.of("equipment", "other")), "offline-grants");
        assertThat(result.items().getFirst().opened()).isTrue();
        assertThat(result.items().getFirst().actions().stream().filter(a -> a.action() == Action.TEMPERATURE).findFirst().orElseThrow().values())
                .contains("16", "32");
        assertThat(result.items().get(1).opened()).isFalse();
        assertThat(result.items().get(1).reasonCode()).isEqualTo("TARGET_UNAVAILABLE");
        assertThat(f.access.target("equipment").approvedActions()).contains("minimum", "maximum").doesNotContain("MODE");
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("DISABLED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation", Integer.class)).isZero();
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void manufacturerTemperatureLimitsApplyWithoutApprovingEveryValueAndUnselectedActionsRemain() throws Exception {
        String originalActions = f.access.target("equipment").approvedActions();
        f.gateway.unit.put("limitSettempCool", "on").put("coolLimitsettempL", 20).put("coolLimitsettempU", 28);
        grants.open(9, request(List.of("equipment")), "offline-grants");
        var target = f.access.target("equipment");
        assertThat(f.json.readTree(target.approvedActions()).path("TEMPERATURE").path("minimum").intValue()).isEqualTo(20);
        assertThat(f.json.readTree(target.approvedActions()).path("TEMPERATURE").path("maximum").intValue()).isEqualTo(28);
        assertThat(f.json.readTree(target.approvedActions()).path("MODE").isArray()).isTrue();
        var audit = f.jdbc.queryForMap("SELECT before_summary,after_summary FROM sys_security_audit_event WHERE action_type='HVAC_CONTROL_GRANT_OPEN'");
        assertThat(audit.get("before_summary").toString()).contains("evidence=isolated fixture", "confirmedBy=9", "version=1",
                "actionsHash=" + DaikinControlAccess.hash(originalActions));
        assertThat(audit.get("after_summary").toString()).contains("reason=管理员批量开放开关和调温",
                "mergedActionsHash=" + DaikinControlAccess.hash(target.approvedActions()));
        assertThat(f.submit(Action.TEMPERATURE, "29").status()).isEqualTo(Status.NOT_SENT);
        assertThat(f.service.submit(7, "equipment", f.request(Action.TEMPERATURE, "27", "request-second"), "trace").status()).isEqualTo(Status.ACCEPTED);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void repeatedIdenticalBatchDoesNotChangeGrantVersion() {
        var first = grants.open(9, request(List.of("equipment")), "first").items().getFirst();
        var again = grants.open(9, request(List.of("equipment")), "again").items().getFirst();
        assertThat(again.opened()).isTrue();
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void adminAuthorityIsRequiredAndClientCannotSmuggleArbitraryActionsOrDuplicateDevices() {
        for (long user : List.of(7L, 8L, 10L)) {
            assertThat(grants.permissions(user).canManage()).isFalse();
            assertThatThrownBy(() -> grants.open(user, request(List.of("equipment")), "trace")).isInstanceOf(BusinessException.class);
        }
        for (BatchGrant bad : List.of(request(List.of()), request(List.of("equipment", "equipment")),
                new BatchGrant(List.of("equipment"), List.of(Action.AIR_FLOW), "未经确认的风向批量开放"),
                request(java.util.stream.IntStream.range(0, 101).mapToObj(i -> "E" + i).toList())))
            assertThatThrownBy(() -> grants.open(9, bad, "trace")).isInstanceOf(BusinessException.class);
        assertThat(f.gateway.reads).hasValue(0); assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void groupSlaveOrUnknownRelationshipAndUnavailableDevicesAreExcluded() {
        for (String flag : List.of("group", "unknown", "offline", "identity")) {
            f.gateway.unit.put("isGroupSlave", flag.equals("group"));
            f.gateway.unit.put("masterSlaveFlag", flag.equals("unknown") ? "Unknown" : "Master");
            f.gateway.controller.put("isConnectionUp", !flag.equals("offline"));
            f.gateway.unit.put("id", flag.equals("identity") ? "wrong" : "vendor-22");
            assertThat(grants.open(9, request(List.of("equipment")), "trace").items().getFirst().opened()).isFalse();
        }
        assertThat(f.access.target("equipment").grantVersion()).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void knownSlaveRelationshipIsRecordedAndLaterChangeInvalidatesControl() {
        f.gateway.unit.put("masterSlaveFlag", "Slave").withArray("masterSlaveIds").add("master-1");
        assertThat(grants.open(9, request(List.of("equipment")), "trace").items().getFirst().opened()).isTrue();
        assertThat(f.service.options(7, "equipment").canControl()).isTrue();
        assertThat(f.access.target("equipment").airFlowConfirmed()).isFalse();
        f.gateway.unit.withArray("masterSlaveIds").add("master-2");
        assertThat(f.service.options(7, "equipment").reasonCode()).isEqualTo("LINKED_DEVICE_UNCONFIRMED");
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void auditFailureRollsBackOneDeviceAndLeavesNoPermissionChange() {
        f.jdbc.update("DELETE FROM biz_daikin_control_grant"); f.auditFails = true;
        var result = grants.open(9, request(List.of("equipment")), "trace");
        assertThat(result.items().getFirst().reasonCode()).isEqualTo("GRANT_UNAVAILABLE");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_grant", Integer.class)).isZero();
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void identityChangesAndAdminRevocationDuringReadCannotPersistGrant() {
        f.gateway.onRead = target -> f.jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=2");
        assertThat(grants.open(9, request(List.of("equipment")), "trace").items().getFirst().reasonCode()).isEqualTo("REVISION_CHANGED");
        f.jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=1");
        f.gateway.onRead = target -> f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=9");
        assertThat(grants.open(9, request(List.of("equipment")), "trace").items().getFirst().reasonCode()).isEqualTo("FORBIDDEN");
        assertThat(f.access.target("equipment").grantVersion()).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void activeAndUnknownControllerIsolationCannotBeReconfigured() {
        f.gateway.loseResponse = true;
        var op = f.submit(Action.TEMPERATURE, "26");
        int before = f.gateway.reads.get();
        assertThat(grants.open(9, request(List.of("equipment")), "trace").items().getFirst().reasonCode()).isEqualTo("CONTROLLER_BUSY");
        assertThat(f.gateway.reads).hasValue(before);
        assertThat(f.store.get(op.operationId()).view().status()).isEqualTo(Status.UNKNOWN);
        assertThat(f.access.target("equipment").grantVersion()).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void perDeviceAuditRollbackDoesNotAbortOtherDevicesInSameBatch() {
        f.jdbc.update("INSERT INTO biz_equipment VALUES ('second','building','product',0)");
        f.jdbc.update("INSERT INTO biz_device_identity VALUES ('identity-2','second','building','DAIKIN_UNIT',1,'DAIKIN_INDOOR')");
        f.jdbc.update("INSERT INTO biz_pending_device VALUES ('pending-2','identity-2','BOUND')");
        f.jdbc.update("INSERT INTO biz_daikin_directory VALUES ('pending-2','source','site','controller','unit-2','vendor-2','INDOOR',0)");
        f.jdbc.update("DELETE FROM biz_daikin_control_grant");
        f.gateway.onRead = t -> {
            f.gateway.unit.put("id", t.vendorId()).put("unitId", t.unitId());
            f.auditFails = t.equipmentId().equals("equipment");
        };
        var result = grants.open(9, request(List.of("equipment", "second")), "trace");
        assertThat(result.items().getFirst().opened()).isFalse();
        assertThat(result.items().get(1).opened()).isTrue();
        assertThat(f.jdbc.queryForList("SELECT equipment_id FROM biz_daikin_control_grant", String.class)).containsExactly("second");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event", Integer.class)).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(0);
    }

    @Test void concurrentInstancesSerializeIdenticalBatchWithoutDuplicatingOrLosingGrantVersion() throws Exception {
        f.jdbc.update("DELETE FROM biz_daikin_control_grant");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return grants.open(9, request(List.of("equipment")), "first"); });
            var second = pool.submit(() -> { start.await(); return grants.open(9, request(List.of("equipment")), "second"); });
            start.countDown();
            var a = first.get(5, java.util.concurrent.TimeUnit.SECONDS).items().getFirst();
            var b = second.get(5, java.util.concurrent.TimeUnit.SECONDS).items().getFirst();
            assertThat(a.opened()).isTrue(); assertThat(b.opened()).isTrue();
            assertThat(a.version()).isEqualTo(1); assertThat(b.version()).isEqualTo(1);
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_grant", Integer.class)).isEqualTo(1);
        assertThat(f.gateway.writes).hasValue(0);
    }
}
