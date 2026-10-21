package com.platform.carbon;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/v1/carbon-projects/buildings/{buildingId}/versions/{id}/impact")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 影响预览只读取已保存版本；不接受浏览器传入的总量或扣减量。 */
public class CarbonProjectImpactController {
    private final CarbonProjectImpactService service;
    public CarbonProjectImpactController(CarbonProjectImpactService service) { this.service = service; }

    @GetMapping
    public Result<CarbonProjectImpactService.Impact> preview(Authentication authentication,
            @PathVariable String buildingId, @PathVariable String id,
            @RequestParam Instant startInclusive, @RequestParam Instant endExclusive) {
        return Result.success(service.preview(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                buildingId, id, startInclusive, endExclusive));
    }
}
