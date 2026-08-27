package com.platform.audit.api;

import com.platform.audit.query.AuditQueryFilter;
import com.platform.audit.query.AuditQueryPage;
import com.platform.audit.query.AuditQueryService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@Tag(name = "统一审计证据")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/audit-events")
@RequiredArgsConstructor
/** 统一查询入口不授予权限；职责和建筑范围在服务层按当前数据库事实重新计算。 */
public class AuditQueryController {
    private final AuditQueryService service;

    @GetMapping
    public Result<AuditQueryContracts.PageView> query(
            Authentication authentication,
            @RequestParam(required = false) String sourceModule,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        AuditQueryPage page = service.query(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                new AuditQueryFilter(sourceModule, buildingId, operatorId, actorType, actionType, objectType,
                        objectId, result, reasonCode, traceId,
                        from == null ? null : from.toInstant(), to == null ? null : to.toInstant()), cursor, size);
        return Result.success(new AuditQueryContracts.PageView(
                page.items().stream().map(AuditQueryContracts.EventView::from).toList(), page.nextCursor()));
    }
}
