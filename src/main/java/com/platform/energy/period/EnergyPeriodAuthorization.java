package com.platform.energy.period;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

import static com.platform.energy.period.EnergyPeriodErrors.FORBIDDEN;
import static com.platform.energy.period.EnergyPeriodErrors.error;

@Component
@RequiredArgsConstructor
/** 将角色、动态职责和建筑范围组合为周期结果治理权限。 */
public class EnergyPeriodAuthorization {
    private final BackendDutyService dutyService;
    private final BuildingScopeService buildingScopeService;

    public void requireReader(Collection<String> roles) {
        if (!hasAny(roles, "BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN")) forbidden();
    }

    public void requireCalculation(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_CALCULATION_RUN);
    }

    public void requirePolicyMaintainer(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_RULE_MAINTAIN);
    }

    public void requirePolicyReviewer(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_RULE_REVIEW);
    }

    public void requireExceptionReviewer(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_EXCEPTION_APPROVE);
    }

    public void requireLockSubmitter(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_LOCK_SUBMIT);
    }

    public void requireLockReviewer(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_LOCK_APPROVE);
    }

    public void requireRecalculationSubmitter(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_RECALC_SUBMIT);
    }

    public void requireRecalculationReviewer(long userId, Collection<String> roles) {
        requireRole(roles);
        dutyService.requireDuty(userId, BackendDuty.ENERGY_RECALC_APPROVE);
    }

    public void requireSeparation(long submitterId, long reviewerId) {
        dutyService.requireSeparation(submitterId, reviewerId);
    }

    public void checkBuilding(long userId, Collection<String> roles, String buildingId) {
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    private static void requireRole(Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")) forbidden();
    }

    private static boolean hasAny(Collection<String> roles, String... expected) {
        if (roles == null) return false;
        for (String role : roles) {
            for (String value : expected) {
                if (value.equalsIgnoreCase(role)) return true;
            }
        }
        return false;
    }

    private static void forbidden() {
        throw error(403, FORBIDDEN, "无权治理周期投影、封账或重算");
    }
}
