package com.platform.iot.deviceparameter.api;

import com.platform.framework.common.Result;
import com.platform.iot.deviceparameter.importing.DeviceParameterExcelImportService;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static com.platform.iot.deviceparameter.api.DeviceParameterContracts.ImportBatchView;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
/** Excel 文件只在校验请求内解析，文件本体不写数据库或日志。 */
public class DeviceParameterImportController {
    private final DeviceParameterExcelImportService service;

    @PostMapping(value = "/v1/device-parameter-imports/validate",
            consumes = "multipart/form-data")
    public Result<ImportBatchView> validate(
            Authentication authentication,
            @RequestParam String buildingId,
            @RequestPart("file") MultipartFile file) {
        return Result.success(service.validate(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, file));
    }

    @PostMapping("/v1/device-parameter-imports/{batchId}/confirm")
    public Result<ImportBatchView> confirm(
            Authentication authentication, @PathVariable String batchId) {
        return Result.success(service.confirm(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), batchId));
    }

    @GetMapping("/v1/device-parameter-imports/{batchId}")
    public Result<ImportBatchView> detail(
            Authentication authentication, @PathVariable String batchId) {
        return Result.success(service.detail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), batchId));
    }
}
