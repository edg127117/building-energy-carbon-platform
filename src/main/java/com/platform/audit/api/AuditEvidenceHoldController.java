package com.platform.audit.api;

import com.platform.audit.retention.AuditEvidenceHold;
import com.platform.audit.retention.AuditEvidenceHoldService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "审计证据保全")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/audit-holds")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
/** 设置保全要求专项职责；解除保全只通过敏感变更申请，不提供直接接口。 */
public class AuditEvidenceHoldController {
    private final AuditEvidenceHoldService service;

    @PostMapping
    public Result<AuditEvidenceHold> create(
            Authentication authentication, @Valid @RequestBody CreateRequest request) {
        var command = new AuditEvidenceHoldService.CreateHold(request.sourceModule(), request.auditId(),
                request.buildingId(), request.actionType(), request.objectType(), request.objectId(),
                request.fromTime(), request.toTime(), request.investigationId(), request.reason(),
                request.legalBasis(), request.reviewAt());
        return Result.success(service.create(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), command));
    }

    @GetMapping
    public Result<List<AuditEvidenceHold>> list(Authentication authentication) {
        return Result.success(service.list(SecurityUser.userId(authentication)));
    }

    public record CreateRequest(
            @Size(max = 50) String sourceModule,
            @Size(max = 32) String auditId,
            @Size(max = 32) String buildingId,
            @Size(max = 64) String actionType,
            @Size(max = 64) String objectType,
            @Size(max = 128) String objectId,
            LocalDateTime fromTime,
            LocalDateTime toTime,
            @Size(max = 100) String investigationId,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 500) String legalBasis,
            @NotNull LocalDateTime reviewAt) {
    }
}
