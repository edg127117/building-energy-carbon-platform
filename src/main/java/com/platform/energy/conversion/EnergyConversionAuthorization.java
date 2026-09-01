package com.platform.energy.conversion;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
@RequiredArgsConstructor
/** 将正式角色、动态职责和建筑范围组合为折标规则治理及模拟计算权限。 */
public class EnergyConversionAuthorization {
    private final BackendDutyService dutyService;
    private final BuildingScopeService buildingScopeService;

    public void requireReader(Collection<String> roles) {
        if (!hasAny(roles, "BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN")) forbidden();
    }

    public void requireMaintainer(long userId, Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")
                || !dutyService.hasDuty(userId, BackendDuty.ENERGY_RULE_MAINTAIN)) {
            forbidden();
        }
    }

    public void requireReviewer(long userId, Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")
                || !dutyService.hasDuty(userId, BackendDuty.ENERGY_RULE_REVIEW)) {
            forbidden();
        }
    }

    public void requireCalculationRunner(long userId, Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")
                || !dutyService.hasDuty(userId, BackendDuty.ENERGY_CALCULATION_RUN)) {
            forbidden();
        }
    }

    public void requireSeparation(long submitterId, long reviewerId) {
        dutyService.requireSeparation(submitterId, reviewerId);
    }

    public void checkBuilding(long userId, Collection<String> roles, String buildingId) {
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    private static boolean hasAny(Collection<String> roles, String... expected) {
        if (roles == null) return false;
        for (String role : roles) {
            for (String item : expected) {
                if (item.equalsIgnoreCase(role)) return true;
            }
        }
        return false;
    }

    private static void forbidden() {
        throw EnergyConversionErrors.error(403, EnergyConversionErrors.FORBIDDEN,
                "无权治理或执行能源折标规则");
    }
}
