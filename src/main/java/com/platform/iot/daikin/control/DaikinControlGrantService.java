package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 管理员一次开放多台设备；每台独立只读预检和事务，异常项不阻止其他设备，不下发控制。 */
@Service
public class DaikinControlGrantService {
    private final DaikinControlAccess access;
    private final DaikinControlPolicy policy;
    private final DaikinControlStore store;
    private final List<DaikinControlGrantReader> readers;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public DaikinControlGrantService(DaikinControlAccess access, DaikinControlPolicy policy,
                                    DaikinControlStore store, List<DaikinControlGrantReader> readers) {
        this(access, policy, store, readers, Clock.systemUTC());
    }

    DaikinControlGrantService(DaikinControlAccess access, DaikinControlPolicy policy,
                             DaikinControlStore store, List<DaikinControlGrantReader> readers, Clock clock) {
        this.access = access; this.policy = policy; this.store = store; this.readers = List.copyOf(readers); this.clock = clock;
    }

    public GrantAccess permissions(long user) {
        if (access.testOpenControls()) access.requireAccount(user);
        return new GrantAccess(access.canManageGrants(user), access.testOpenControls());
    }

    public BatchGrantResult open(long user, BatchGrant request, String traceId) {
        access.requireGrantAdmin(user);
        validate(request);
        var results = new ArrayList<GrantItem>();
        for (String equipment : request.equipmentIds()) {
            // 循环之间复核撤权；禁止把浏览器早先显示的管理权限用作整批授权凭证。
            access.requireGrantAdmin(user);
            try {
                access.requireRead(user, equipment);
                var target = access.candidate(equipment);
                if (store.controllerBusy(target)) throw error(409, "CONTROLLER_BUSY");
                store.requireSourceReady(target.sourceId(), clock.millis());
                var available = readers.stream().filter(r -> r.supports(target.sourceId())).toList();
                if (available.size() != 1) throw error(503, "PROVIDER_UNAVAILABLE");
                long started = clock.millis();
                var snapshot = available.getFirst().read(target);
                String relationship = policy.relationshipHash(snapshot);
                var permits = json.createObjectNode();
                for (Action action : request.actions()) {
                    if (action == Action.ON_OFF) permits.putArray(action.name()).add("on").add("off");
                    else permits.putObject(action.name()).put("minimum", 16).put("maximum", 32);
                }
                var proposed = new DaikinControlAccess.Target(target.equipmentId(), target.buildingId(), target.identityId(),
                        target.sourceId(), target.siteId(), target.controllerId(), target.unitId(), target.vendorId(), target.pendingId(),
                        target.mappingVersion(), 0, false, relationship, permits.toString(), request.evidence().strip());
                var options = policy.options(proposed, snapshot).stream().filter(o -> request.actions().contains(o.action())).toList();
                // 从当前厂家能力生成温度范围；不逐温度配置，也不将未知/群控设备降级放行。
                for (var option : options) {
                    if (!option.allowed()) throw error(409, option.reasonCode());
                    if (option.action() == Action.TEMPERATURE)
                        permits.putObject(option.action().name()).put("minimum", option.minimum()).put("maximum", option.maximum());
                    else {
                        var values = permits.putArray(option.action().name());
                        option.values().forEach(values::add);
                    }
                }
                if (clock.millis() - started >= 30_000) throw error(409, "PREFLIGHT_EXPIRED");
                long version = store.openGrant(user, target, relationship, permits.toString(), request.evidence().strip(), traceId,
                        access, started, clock);
                results.add(new GrantItem(equipment, true, null, version, options));
            } catch (BusinessException e) {
                results.add(new GrantItem(equipment, false, e.getErrorCode().replaceFirst("^HVAC_CONTROL_", ""), null, List.of()));
            } catch (RuntimeException e) {
                results.add(new GrantItem(equipment, false, "GRANT_UNAVAILABLE", null, List.of()));
            }
        }
        return new BatchGrantResult(List.copyOf(results));
    }

    private static void validate(BatchGrant r) {
        if (r == null || r.equipmentIds() == null || r.equipmentIds().isEmpty() || r.equipmentIds().size() > 100
                || r.equipmentIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,32}"))
                || new HashSet<>(r.equipmentIds()).size() != r.equipmentIds().size()
                || r.actions() == null || r.actions().isEmpty() || r.actions().size() > 2
                || r.actions().stream().anyMatch(a -> a == null || !Set.of(Action.ON_OFF, Action.TEMPERATURE).contains(a))
                || new HashSet<>(r.actions()).size() != r.actions().size()
                || r.evidence() == null || r.evidence().strip().length() < 5 || r.evidence().length() > 500
                || r.evidence().chars().anyMatch(Character::isISOControl)) throw error(400, "INVALID_REQUEST");
    }
}
