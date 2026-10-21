package com.platform.carbon;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/carbon-projects/buildings/{buildingId}/versions/{versionId}/guide")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 引导只返回授权建筑内的已存版本；不会执行提交、审核、生效或专业规则确认。 */
public class CarbonConfigurationGuideController {
    private final CarbonConfigurationGuideService service;

    public CarbonConfigurationGuideController(CarbonConfigurationGuideService service) { this.service = service; }

    @GetMapping
    public Result<CarbonConfigurationGuideService.Guide> guide(Authentication authentication,
                                                              @PathVariable String buildingId,
                                                              @PathVariable String versionId) {
        return Result.success(service.guide(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId, versionId));
    }
}
