package com.platform.audit.api;

import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.audit.api.SensitiveChangeContracts.CreateRequest;
import static com.platform.audit.api.SensitiveChangeContracts.ReviewRequest;
import static com.platform.audit.api.SensitiveChangeContracts.View;

@Tag(name = "后台敏感变更治理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/backoffice/change-requests")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
/** 平台管理员使用的系统敏感变更两步审批入口；后台职责由服务层动态校验。 */
public class SensitiveChangeController {
    private final SensitiveChangeService service;

    @PostMapping
    public Result<View> create(Authentication authentication, @Valid @RequestBody CreateRequest request) {
        return Result.success(View.from(service.createDraft(SecurityUser.userId(authentication),
                request.operationCode(), request.command(), request.idempotencyKey())));
    }

    @GetMapping("/{requestId}")
    public Result<View> detail(Authentication authentication, @PathVariable String requestId) {
        return Result.success(View.from(service.detail(SecurityUser.userId(authentication), requestId)));
    }

    @PostMapping("/{requestId}/submit")
    public Result<View> submit(Authentication authentication, @PathVariable String requestId) {
        return Result.success(View.from(service.submit(SecurityUser.userId(authentication), requestId)));
    }

    @PostMapping("/{requestId}/withdraw")
    public Result<View> withdraw(Authentication authentication, @PathVariable String requestId) {
        return Result.success(View.from(service.withdraw(SecurityUser.userId(authentication), requestId)));
    }

    @PostMapping("/{requestId}/approve")
    public Result<View> approve(Authentication authentication, @PathVariable String requestId,
                                @Valid @RequestBody ReviewRequest request) {
        return Result.success(View.from(service.approve(SecurityUser.userId(authentication),
                requestId, request.comment())));
    }

    @PostMapping("/{requestId}/reject")
    public Result<View> reject(Authentication authentication, @PathVariable String requestId,
                               @Valid @RequestBody ReviewRequest request) {
        return Result.success(View.from(service.reject(SecurityUser.userId(authentication),
                requestId, request.comment())));
    }

    @PostMapping("/{requestId}/execute")
    public Result<View> execute(Authentication authentication, @PathVariable String requestId) {
        return Result.success(View.from(service.execute(SecurityUser.userId(authentication), requestId)));
    }
}
