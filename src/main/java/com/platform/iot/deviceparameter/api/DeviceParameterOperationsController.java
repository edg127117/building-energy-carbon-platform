package com.platform.iot.deviceparameter.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.deviceparameter.DeviceParameterOperationsService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.iot.deviceparameter.api.DeviceParameterContracts.AuditView;
import static com.platform.iot.deviceparameter.api.DeviceParameterContracts.RecalculationView;
import static com.platform.iot.deviceparameter.api.DeviceParameterContracts.FormulaResultRevisionView;

@Tag(name = "设备参数重算与审计")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 参数治理运维查询不暴露内部异常、载荷哈希或未脱敏业务快照。 */
public class DeviceParameterOperationsController {
    private final DeviceParameterOperationsService service;

    @GetMapping("/v1/device-parameter-recalculations")
    public Result<PageResponse<RecalculationView>> listRecalculations(
            Authentication authentication,
            @RequestParam String buildingId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listRecalculations(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, status, page, size));
    }

    @PostMapping("/v1/device-parameter-recalculations/{jobId}/resume")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<RecalculationView> resume(
            Authentication authentication, @PathVariable String jobId) {
        return Result.success(service.resumeFailed(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), jobId));
    }

    @GetMapping("/v1/device-parameter-audits")
    public Result<PageResponse<AuditView>> listAudits(
            Authentication authentication,
            @RequestParam(required = false) String buildingId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listAudits(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size));
    }

    @GetMapping("/v1/device-parameter-formula-results/{indicatorId}/{minuteStart}")
    public Result<FormulaResultRevisionView> formulaResultAt(
            Authentication authentication,
            @PathVariable String indicatorId,
            @PathVariable long minuteStart,
            @RequestParam(required = false) Long knowledgeAt) {
        return Result.success(service.formulaResultAt(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), indicatorId, minuteStart, knowledgeAt));
    }
}
