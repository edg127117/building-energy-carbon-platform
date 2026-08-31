package com.platform.iot.energymetadata;

import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
@RequiredArgsConstructor
/** 将正式角色和建筑数据范围映射为能源测点属性读写能力。 */
public class EnergyPointProfileAuthorization {
    private final BuildingScopeService buildingScopeService;

    public void requireReader(Collection<String> roles) {
        if (!hasAny(roles, "BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN")) {
            forbidden();
        }
    }

    public void requireMaintainer(Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")) {
            forbidden();
        }
    }

    public void checkBuilding(Long userId, Collection<String> roles, String buildingId) {
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
        throw EnergyMetadataErrors.error(403, EnergyMetadataErrors.FORBIDDEN, "无权访问能源测点属性");
    }
}
