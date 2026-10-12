package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 人工控制的短生命周期编排。唯一写请求在 submit 中；后台恢复、核验和人工结束均只读厂家。 */
@Service
public class DaikinControlService {
    private static final Set<Status> CHECKABLE = Set.of(Status.ACCEPTED, Status.UNKNOWN, Status.UNCONFIRMED, Status.OBSERVED_STABLE, Status.CLOSED);
    private static final Set<String> REJECTION_CODES = Set.of("10001", "10002", "10003", "10005", "10007", "10008",
            "10009", "10010", "10011", "10012", "10013", "10014", "10015", "10016", "20000", "20001", "20002");
    private final DaikinControlProperties properties;
    private final DaikinControlAccess access;
    private final DaikinControlPolicy policy;
    private final DaikinControlStore store;
    private final List<DaikinControlGateway> gateways;
    private final Clock clock;

    @Autowired
    public DaikinControlService(DaikinControlProperties properties, DaikinControlAccess access, DaikinControlPolicy policy,
                                 DaikinControlStore store, List<DaikinControlGateway> gateways) {
        this(properties, access, policy, store, gateways, Clock.systemUTC());
    }
    DaikinControlService(DaikinControlProperties properties, DaikinControlAccess access, DaikinControlPolicy policy,
                         DaikinControlStore store, List<DaikinControlGateway> gateways, Clock clock) {
        this.properties = properties; this.access = access; this.policy = policy; this.store = store;
        this.gateways = List.copyOf(gateways); this.clock = clock;
    }

    public Options options(long user, String equipment) {
        access.requireRead(user, equipment);
        try { access.requireControl(user, equipment); }
        catch (BusinessException e) { return disabled(equipment, "FORBIDDEN", null); }
        String active = store.active(equipment);
        if (!properties.isEnabled()) return disabled(equipment, "DISABLED", active);
        if (active != null) return disabled(equipment, "BUSY", active);
        try {
            var target = access.target(equipment);
            if (store.controllerBusy(target)) return disabled(equipment, "CONTROLLER_BUSY", null);
            store.requireSourceReady(target.sourceId(), clock.millis());
            var snapshot = gateway(target).read(target);
            // 取数期间可能撤权或迁移设备；返回结果前也复核，不泄露旧授权范围的实时数据。
            access.requireControl(user, equipment);
            if (!access.target(equipment).equals(target)) throw error(409, "REVISION_CHANGED");
            var actions = policy.options(target, snapshot);
            boolean allowed = actions.stream().anyMatch(ActionOption::allowed);
            return new Options(equipment, allowed, allowed ? null : actions.getFirst().reasonCode(),
                    policy.revision(target, snapshot), actions, null);
        } catch (BusinessException e) { return disabled(equipment, reason(e), active); }
        catch (RuntimeException e) { return disabled(equipment, "PREFLIGHT_UNAVAILABLE", active); }
    }

    public Operation submit(long user, String equipment, Submit request, String traceId) {
        access.requireControl(user, equipment);
        validate(request);
        String hash = DaikinControlAccess.hash(equipment + "|" + request.action() + "|" + request.value() + "|" + request.revision());
        var previous = store.existing(user, request.idempotencyKey(), hash);
        if (previous != null) return visible(user, previous).view();
        requireEnabled();
        var target = access.target(equipment);
        var gateway = gateway(target);
        var reservation = store.reserve(user, target, request, hash, traceId, clock.millis());
        if (!reservation.created()) return visible(user, reservation.row()).view();
        String id = reservation.row().view().operationId();
        String before;
        String baseline;
        try {
            var snapshot = gateway.read(target);
            if (!request.revision().equals(policy.revision(target, snapshot))) throw error(409, "REVISION_CHANGED");
            policy.validate(request.action(), request.value(), policy.options(target, snapshot));
            before = policy.observed(target, snapshot, request.action());
            baseline = policy.settings(target, snapshot);
            requireEnabled();
            access.requireControl(user, equipment);
            if (!access.target(equipment).equals(target)) throw error(409, "REVISION_CHANGED");
            store.requireSourceReady(target.sourceId(), clock.millis());
            if (clock.millis() - reservation.row().view().createdAt() >= 30_000) throw error(409, "PREFLIGHT_EXPIRED");
        } catch (RuntimeException failure) {
            return store.transition(id, Set.of(Status.PREPARING), Status.NOT_SENT,
                    failure instanceof BusinessException e ? reason(e) : "PREFLIGHT_UNAVAILABLE",
                    null, null, null, null, user, clock.millis()).view();
        }
        // 此事务成功是唯一发送许可。进程在提交后退出也只会被恢复为 UNKNOWN，不再调用 send。
        var sending = store.markSending(id, before, baseline, user, clock.millis());
        if (sending.view().status() != Status.SENDING) return sending.view();
        DaikinControlGateway.Reply reply;
        try { reply = gateway.send(target, request.action(), request.value()); }
        catch (RuntimeException failure) {
            return store.transition(id, Set.of(Status.SENDING), Status.UNKNOWN, "SEND_RESULT_UNKNOWN",
                    null, null, clock.millis() + 15_000, null, user, clock.millis()).view();
        }
        if (reply == null || reply.code() == null || !reply.code().matches("[0-9]{5,6}")) {
            return store.transition(id, Set.of(Status.SENDING), Status.UNKNOWN, "SEND_RESULT_UNKNOWN",
                    null, null, clock.millis() + 15_000, null, user, clock.millis()).view();
        }
        boolean accepted = "10000".equals(reply.code());
        if (!accepted && !REJECTION_CODES.contains(reply.code())) {
            return store.transition(id, Set.of(Status.SENDING), Status.UNKNOWN, "SEND_RESULT_UNKNOWN", reply.code(),
                    null, clock.millis() + 15_000, null, user, clock.millis()).view();
        }
        // 写结果失败时保留 SENDING；不能因为本地事务失败重新执行外部副作用。
        return store.transition(id, Set.of(Status.SENDING), accepted ? Status.ACCEPTED : Status.REJECTED,
                accepted ? null : "VENDOR_" + reply.code(), reply.code(), null,
                accepted ? clock.millis() + 15_000 : null, null, user, clock.millis()).view();
    }

    public Operation get(long user, String id) { return visible(user, store.get(id)).view(); }

    public PageResponse<Operation> history(long user, String equipment, int page, int size) {
        access.requireControl(user, equipment);
        return store.history(equipment, access.requireRead(user, equipment), page, size);
    }

    public Operation recheck(long user, String id) {
        var row = visible(user, store.get(id));
        access.requireControl(user, row.view().equipmentId());
        requireEnabled();
        if (!CHECKABLE.contains(row.view().status()) || !row.view().isolated()) throw error(409, "NOT_CHECKABLE");
        if (!store.claimManual(row, clock.millis())) throw error(409, "CHECK_BUSY");
        return check(store.get(id), user).view();
    }

    public Operation close(long user, String id, String reason) {
        var row = visible(user, store.get(id));
        access.requireControl(user, row.view().equipmentId());
        if (!Set.of(Status.UNKNOWN, Status.UNCONFIRMED).contains(row.view().status())) throw error(409, "NOT_CHECKABLE");
        validateReason(reason);
        // 关闭仅结束后台观察，未知发送仍可能延迟生效，物理设备和控制器隔离均保留。
        return store.transition(id, Set.of(row.view().status()), Status.CLOSED, "MANUALLY_CLOSED_" + row.view().status(),
                null, row.view().observedValue(), null, reason.strip(), user, clock.millis()).view();
    }

    public Operation confirm(long user, String id, String reason) {
        validateReason(reason);
        var row = visible(user, store.get(id));
        if (row.view().status() != Status.OBSERVED_STABLE) throw error(409, "ONSITE_CONFIRMATION_REQUIRED");
        recheck(user, id);
        access.requireControl(user, row.view().equipmentId());
        if (!access.target(row.view().equipmentId()).equals(row.target())) throw error(409, "REVISION_CHANGED");
        return store.finish(id, Set.of(Status.OBSERVED_STABLE), false, reason.strip(), user, clock.millis()).view();
    }

    public Operation release(long user, String id, String reason) {
        validateReason(reason);
        var row = visible(user, store.get(id));
        if (!Set.of(Status.UNKNOWN, Status.UNCONFIRMED, Status.CLOSED).contains(row.view().status())) throw error(409, "NOT_CHECKABLE");
        recheck(user, id);
        access.requireControl(user, row.view().equipmentId());
        if (!access.target(row.view().equipmentId()).equals(row.target())) throw error(409, "REVISION_CHANGED");
        return store.finish(id, Set.of(Status.UNKNOWN, Status.UNCONFIRMED, Status.CLOSED), true, reason.strip(), user, clock.millis()).view();
    }

    @Scheduled(fixedDelayString = "${daikin.control.reconcile-delay-ms:1000}")
    public void reconcile() {
        if (!properties.isEnabled()) return;
        for (var row : store.due(clock.millis())) {
            try {
                if (!store.claimCheck(row, clock.millis())) continue;
                if (row.view().status() == Status.PREPARING) {
                    store.transition(row.view().operationId(), Set.of(Status.PREPARING), Status.NOT_SENT,
                            "PREFLIGHT_EXPIRED", null, null, null, null, 0, clock.millis());
                } else if (row.view().status() == Status.SENDING) {
                    store.transition(row.view().operationId(), Set.of(Status.SENDING), Status.UNKNOWN,
                            "SEND_RESULT_UNKNOWN", null, null, clock.millis() + 15_000, null, 0, clock.millis());
                } else if (CHECKABLE.contains(row.view().status())) check(store.get(row.view().operationId()), 0);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("空调控制只读恢复失败，记录保留: operationId={}", row.view().operationId());
            }
        }
    }

    private DaikinControlStore.Row check(DaikinControlStore.Row row, long actor) {
        long requestedAt = clock.millis();
        String observed = null;
        String failure = null;
        String settings = null;
        String unitStatus = null;
        boolean matches = false;
        try {
            requireEnabled();
            // 已发送操作由系统继续有界只读核验；人员撤权阻止新控制和查询，不遗弃在途结果。
            if (actor != 0) visible(actor, row);
            var target = access.target(row.view().equipmentId());
            if (!target.equals(row.target())) throw error(409, "REVISION_CHANGED");
            store.requireSourceReady(target.sourceId(), clock.millis());
            var snapshot = gateway(target).read(target);
            settings = policy.settings(target, snapshot);
            observed = policy.observed(target, snapshot, row.view().action());
            unitStatus = snapshot.unit().path("unitStatus").asText();
            matches = policy.matches(row.baseline(), settings, row.view().action(), row.view().value());
            if (actor != 0) visible(actor, row);
            if (!access.target(row.view().equipmentId()).equals(target)) throw error(409, "REVISION_CHANGED");
        } catch (RuntimeException e) {
            observed = null; settings = null; unitStatus = null; matches = false;
            failure = e instanceof BusinessException business ? reason(business) : "READBACK_UNAVAILABLE";
        }
        return store.observe(row, settings, observed, unitStatus, matches, failure, requestedAt, actor, clock.millis());
    }

    private DaikinControlStore.Row visible(long user, DaikinControlStore.Row row) {
        access.requireControl(user, row.view().equipmentId());
        if (!row.buildingId().equals(access.requireRead(user, row.view().equipmentId()))) throw error(403, "FORBIDDEN");
        return row;
    }
    private void requireEnabled() { if (!properties.isEnabled()) throw error(503, "DISABLED"); }
    private DaikinControlGateway gateway(DaikinControlAccess.Target target) {
        var available = gateways.stream().filter(g -> g.supports(target.sourceId())).toList();
        if (available.size() != 1) throw error(503, "PROVIDER_UNAVAILABLE");
        return available.getFirst();
    }
    private static Options disabled(String equipment, String reason, String active) {
        return new Options(equipment, false, reason, null, Arrays.stream(Action.values())
                .map(a -> new ActionOption(a, false, reason, List.of(), null, null)).toList(), active);
    }
    private static void validate(Submit request) {
        if (request == null || request.action() == null || request.value() == null || request.value().isBlank() || request.value().length() > 40
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{8,100}")
                || request.revision() == null || !request.revision().matches("[a-f0-9]{64}")) throw error(400, "INVALID_REQUEST");
    }
    private static void validateReason(String reason) {
        if (reason == null || reason.strip().length() < 5 || reason.length() > 500
                || reason.chars().anyMatch(Character::isISOControl)) throw error(400, "INVALID_REQUEST");
    }
    private static String reason(BusinessException e) { return e.getErrorCode() == null ? "FORBIDDEN" : e.getErrorCode().replaceFirst("^HVAC_CONTROL_", ""); }
}
