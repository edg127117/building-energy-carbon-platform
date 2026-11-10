package com.platform.carbon.api;

import com.platform.carbon.DynamicElectricityContracts.*;
import com.platform.carbon.DynamicElectricityService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;

@RestController
@RequestMapping("/v1/carbon-management/buildings/{buildingId}/dynamic-electricity")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 动态监测入口沿用碳职责和建筑范围。因子接收与计算显式写入，图表查询保持只读。 */
public class DynamicElectricityController {
    private final DynamicElectricityService service;
    private final com.platform.carbon.DynamicElectricityLiveService live;
    private final com.platform.carbon.PeriodElectricityQueryService prepared;

    @PostMapping("/intervals")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DeviceIntervalReceipt> interval(Authentication auth, @PathVariable String buildingId,
                                                @Valid @RequestBody DeviceIntervalRequest request) {
        return Result.success(live.ingest(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, request));
    }


    @PostMapping("/factors")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ImportReceipt> ingest(Authentication auth, @PathVariable String buildingId, @Valid @RequestBody ImportRequest request) {
        return Result.success(service.ingest(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, request));
    }

    @PostMapping("/calculations")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<String>> calculate(Authentication auth, @PathVariable String buildingId, @Valid @RequestBody CalculationRequest request) {
        return Result.success(service.calculate(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, request));
    }

    @GetMapping
    public Result<View> query(Authentication auth, @PathVariable String buildingId, @RequestParam String periodType, @RequestParam String period) {
        return Result.success(prepared.dynamic(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, periodType, period));
    }

    @GetMapping("/hourly")
    public Result<View> hourly(Authentication auth, @PathVariable String buildingId, @RequestParam java.time.LocalDate date) {
        return Result.success(prepared.dynamic(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, "DAY", date.toString()));
    }

    @GetMapping("/results/{resultId}/evidence")
    public Result<com.platform.carbon.DynamicElectricityRepository.Result> evidence(Authentication auth,
            @PathVariable String buildingId, @PathVariable String resultId) {
        return Result.success(service.evidence(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, resultId));
    }
}
