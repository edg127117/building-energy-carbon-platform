package com.platform.audit.api;

import com.platform.audit.retention.AuditRetentionPolicy;
import com.platform.audit.retention.AuditRetentionPolicyService;
import com.platform.audit.retention.AuditCleanupRun;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Tag(name = "审计保留策略")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/audit-retention")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
/** 仅查询已审核策略版本；新增、修改和启停统一使用敏感变更申请。 */
public class AuditRetentionController {
    private final AuditRetentionPolicyService service;

    @GetMapping("/policies")
    public Result<List<AuditRetentionPolicy>> list(Authentication authentication) {
        return Result.success(service.list(SecurityUser.userId(authentication)));
    }

    @GetMapping("/cleanup-runs")
    public Result<List<AuditCleanupRun>> cleanupRuns(
            Authentication authentication,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.cleanupRuns(SecurityUser.userId(authentication), limit));
    }
}
