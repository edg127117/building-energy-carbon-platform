package com.platform.iot.reliability.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.reliability.TelemetryReceiptQueryService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.iot.reliability.api.TelemetryReceiptContracts.*;

@Tag(name = "遥测可靠性回执")
@SecurityRequirement(name = "bearerAuth")
@RestController
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** V2 回执的版本化只读入口；`/api` 前缀由应用 context-path 提供。 */
public class TelemetryReceiptController {

    private final TelemetryReceiptQueryService queryService;

    public TelemetryReceiptController(TelemetryReceiptQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/v1/telemetry-receipts")
    public Result<PageResponse<ReceiptView>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String status) {
        return Result.success(queryService.list(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, equipmentId, status, page, size));
    }

    @GetMapping("/v1/telemetry-receipts/{canonicalMessageId}")
    public Result<ReceiptDetail> detail(
            Authentication authentication,
            @PathVariable String canonicalMessageId) {
        return Result.success(queryService.detail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), canonicalMessageId));
    }

    @GetMapping("/v1/telemetry-receipts/statistics")
    public Result<ReceiptStatistics> statistics(
            Authentication authentication,
            @RequestParam(required = false) String buildingId) {
        return Result.success(queryService.statistics(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }

    @GetMapping("/v1/telemetry-receipts/transport-failures")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<PageResponse<TransportFailureView>> transportFailures(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(queryService.transportFailures(page, size));
    }
}
