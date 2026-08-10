package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 约束真实后台管理冒烟脚本的鉴权、业务覆盖和回滚边界。 */
class HvacAdminSmokeScriptContractTest {

    private final String script = readScript();

    @Test
    void coversFourAdministrationAreasAndSessionInvalidation() {
        assertThat(script).contains(
                "/menu/admin/tree",
                "/system/users",
                "/system/roles",
                "/system/building-access/requests",
                "/building-access/requests",
                "/auth/me",
                "/menu/current",
                "HVAC_ADMIN_SMOKE_OK",
                "旧会话仍然有效");
    }

    @Test
    void keepsCredentialsOutOfOutputAndRestoresSharedRoleState() {
        assertThat(script).contains(
                        "HVAC_SMOKE_ADMIN_PASSWORD",
                        "finally",
                        "originalRoleMenuIds",
                        "restore role menus",
                        "DELETE temp user")
                .doesNotContain(
                        "Write-Output $adminToken",
                        "Write-Output $temporaryPassword",
                        "?token=",
                        "access_token=",
                        "docker system prune",
                        "docker volume prune");
    }

    private String readScript() {
        try {
            return Files.readString(Path.of("scripts/Test-HvacAdminSmoke.ps1"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取后台管理冒烟脚本", e);
        }
    }
}
