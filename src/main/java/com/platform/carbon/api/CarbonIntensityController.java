package com.platform.carbon.api;

import com.platform.carbon.*;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.carbon.CarbonIntensityContracts.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/carbon-intensity")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 目录集中维护，建筑卡片只读；不授予新的计算、发布或设备写入权限。 */
public class CarbonIntensityController {
    private final CarbonIntensityCatalogService catalog;
    private final CarbonIntensityService intensity;
    @GetMapping("/catalog") public Result<List<Version>> list() { return Result.success(catalog.list()); }
    @PostMapping("/catalog")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<Version> save(Authentication auth,@RequestBody Save input) {
        return Result.success(catalog.save(SecurityUser.userId(auth),SecurityUser.roles(auth),input));
    }
    @GetMapping("/buildings/{buildingId}")
    public Result<View> query(Authentication auth,@PathVariable String buildingId,@RequestParam String mode,
                              @RequestParam int year,@RequestParam(required=false) Integer month,
                              @RequestParam(defaultValue="Asia/Shanghai") String timezoneId) {
        return Result.success(intensity.query(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,mode,year,month,timezoneId));
    }
}
