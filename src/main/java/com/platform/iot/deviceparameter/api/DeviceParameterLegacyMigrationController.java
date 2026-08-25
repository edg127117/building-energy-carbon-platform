package com.platform.iot.deviceparameter.api;

import com.platform.framework.common.Result;
import com.platform.iot.deviceparameter.DeviceParameterLegacyMigrationService;
import com.platform.iot.deviceparameter.DeviceParameterLegacyMigrationService.MigrationPreview;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
/** 遗留数据预演和执行分开；执行最多生成待审核迁移草稿，不生成正式参数版本。 */
public class DeviceParameterLegacyMigrationController {
    private final DeviceParameterLegacyMigrationService service;

    @GetMapping("/v1/device-parameter-legacy-migrations/preview")
    public Result<MigrationPreview> preview(Authentication authentication) {
        return Result.success(service.preview(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    @PostMapping("/v1/device-parameter-legacy-migrations/execute")
    public Result<MigrationPreview> execute(
            Authentication authentication,
            @RequestHeader("Migration-Batch-Id") String batchId) {
        return Result.success(service.execute(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), batchId));
    }
}
