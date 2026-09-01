package com.platform.energy.catalog;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
@RequiredArgsConstructor
/** 将正式角色、动态职责和建筑范围组合为能源字典治理权限。 */
public class EnergyCatalogAuthorization {
    private final BackendDutyService dutyService;
    private final BuildingScopeService buildingScopeService;

    public void requireReader(Collection<String> roles) {
        if (!hasAny(roles, "BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN")) {
            forbidden();
        }
    }

    public void requireMaintainer(long userId, Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")) {
            forbidden();
        }
        if (!dutyService.hasDuty(userId, BackendDuty.ENERGY_CATALOG_MAINTAIN)) {
            forbidden();
        }
    }

    public void requireReviewer(long userId, Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")) {
            forbidden();
        }
        if (!dutyService.hasDuty(userId, BackendDuty.ENERGY_CATALOG_REVIEW)) {
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
        throw EnergyCatalogErrors.error(403, EnergyCatalogErrors.FORBIDDEN, "无权治理能源字典");
    }
}
