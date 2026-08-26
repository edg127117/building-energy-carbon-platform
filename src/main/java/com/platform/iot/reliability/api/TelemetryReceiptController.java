package com.platform.iot.reliability.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.reliability.TelemetryReceiptQueryService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(summary = "查询24小时热回执",
            description = "默认查询最近24小时，单次跨度不得超过24小时。平台不再逐条持久化成功ACK证据，兼容字段返回UNKNOWN或NOT_TRACKED。")
    public Result<PageResponse<ReceiptView>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long fromEpochMillis,
            @RequestParam(required = false) Long toEpochMillis) {
        return Result.success(queryService.list(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, equipmentId, status,
                fromEpochMillis, toEpochMillis, page, size));
    }

    @GetMapping("/v1/telemetry-receipts/{canonicalMessageId}")
    @Operation(summary = "查询回执处理链",
            description = "成功回执最多保留24小时；关联异常的回执和异常明细最多保留180天。ACK成功状态只通过监控指标提供。")
    public Result<ReceiptDetail> detail(
            Authentication authentication,
            @PathVariable String canonicalMessageId) {
        return Result.success(queryService.detail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), canonicalMessageId));
    }

    @GetMapping("/v1/telemetry-receipts/statistics")
    @Operation(summary = "统计24小时热回执",
            description = "默认统计最近24小时，单次跨度不得超过24小时，响应scope固定为HOT_RECEIPT_WINDOW。")
    public Result<ReceiptStatistics> statistics(
            Authentication authentication,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) Long fromEpochMillis,
            @RequestParam(required = false) Long toEpochMillis) {
        return Result.success(queryService.statistics(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId,
                fromEpochMillis, toEpochMillis));
    }

    @GetMapping("/v1/telemetry-receipts/failure-statistics")
    @Operation(summary = "统计异常明细",
            description = "默认统计最近180天，单次跨度不得超过180天，响应scope固定为FAILURE_RETENTION_WINDOW。")
    public Result<FailureStatistics> failureStatistics(
            Authentication authentication,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) Long fromEpochMillis,
            @RequestParam(required = false) Long toEpochMillis) {
        return Result.success(queryService.failureStatistics(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                buildingId, fromEpochMillis, toEpochMillis));
    }

    @GetMapping("/v1/telemetry-receipts/transport-failures")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<PageResponse<TransportFailureView>> transportFailures(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(queryService.transportFailures(page, size));
    }
}
