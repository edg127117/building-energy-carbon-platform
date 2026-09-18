package com.platform.iot.onboarding.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.onboarding.DaikinSyncAccessService;
import com.platform.iot.onboarding.DaikinSyncAccessService.SyncJobView;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/daikin")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "大金目录同步")
@RequiredArgsConstructor
/** 只提交异步任务并查询脱敏结果；不接受厂家报文、凭据、URL 或设备清单。 */
public class DaikinDirectorySyncController {
    private final DaikinSyncAccessService service;

    @PostMapping("/sources/{sourceId}/sync-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "触发目录同步；已有活动任务时返回原任务")
    public Result<SyncJobView> request(Authentication authentication, @PathVariable String sourceId) {
        return Result.success(service.request(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId));
    }

    @GetMapping("/sources/{sourceId}/sync-jobs/{jobId}")
    @Operation(summary = "查询授权来源的同步状态与脱敏错误码")
    public Result<SyncJobView> get(Authentication authentication, @PathVariable String sourceId,
            @PathVariable String jobId) {
        return Result.success(service.get(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, jobId));
    }

    @GetMapping("/sync-jobs")
    @Operation(summary = "分页查询当前授权范围内的目录同步记录")
    public Result<PageResponse<SyncJobView>> list(Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(service.list(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), page, size));
    }
}
