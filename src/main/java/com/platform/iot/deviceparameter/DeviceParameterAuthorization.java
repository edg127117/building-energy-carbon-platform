package com.platform.iot.deviceparameter;

import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
@RequiredArgsConstructor
/**
 * 把现有正式角色映射为参数治理动作能力。
 *
 * <p>这里不创造新角色或向 JWT 写入虚构权限码。平台管理员承担全局目录、审核和受控追溯能力；
 * 能效管理方只管理已授权建筑的候选和草稿；建筑业主只读。职责分离仍按用户身份独立校验。</p>
 */
public class DeviceParameterAuthorization {
    private final BuildingScopeService buildingScopeService;

    public void requireReader(Collection<String> roles) {
        if (!hasAny(roles, "BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN")) {
            forbidden("当前角色不能查看设备参数");
        }
    }

    public void requireMaintainer(Collection<String> roles) {
        if (!hasAny(roles, "ENERGY_MANAGER", "PLATFORM_ADMIN")) {
            forbidden("当前角色不能维护设备参数候选或草稿");
        }
    }

    public void requireGlobalConfigurator(Collection<String> roles) {
        if (!hasAny(roles, "PLATFORM_ADMIN")) {
            forbidden("只有平台管理员可以维护全局参数目录和映射");
        }
    }

    public void requireReviewer(Collection<String> roles) {
        if (!hasAny(roles, "PLATFORM_ADMIN")) {
            forbidden("当前角色不能审核设备参数版本");
        }
    }

    public void requireRetroactiveApprover(Collection<String> roles) {
        if (!hasAny(roles, "PLATFORM_ADMIN")) {
            forbidden("当前角色不具备受控追溯审批能力");
        }
    }

    public void checkBuilding(long userId, Collection<String> roles, String buildingId) {
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    public boolean isGlobalAdministrator(Collection<String> roles) {
        return hasAny(roles, "PLATFORM_ADMIN");
    }

    private static boolean hasAny(Collection<String> roles, String... expected) {
        if (roles == null) {
            return false;
        }
        for (String role : roles) {
            for (String item : expected) {
                if (item.equalsIgnoreCase(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void forbidden(String message) {
        throw DeviceParameterErrors.error(403, DeviceParameterErrors.FORBIDDEN, message);
    }
}
