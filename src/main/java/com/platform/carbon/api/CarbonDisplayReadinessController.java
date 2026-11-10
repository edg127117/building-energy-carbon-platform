package com.platform.carbon.api;

import com.platform.carbon.CarbonDisplayPrewarmService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/carbon-display")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 仅报告默认两类卡片的就绪，不纳入全平台健康探针，也不由读取请求登记或重建任务。 */
public class CarbonDisplayReadinessController {
    private final CarbonDisplayPrewarmService service;
    @GetMapping("/buildings/{buildingId}/readiness")
    public Result<CarbonDisplayPrewarmService.Readiness> readiness(Authentication auth,@PathVariable String buildingId,jakarta.servlet.http.HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return Result.success(service.readiness(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId));
    }
}
