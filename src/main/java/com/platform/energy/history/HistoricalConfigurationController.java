package com.platform.energy.history;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.platform.energy.history.HistoricalConfigurationContracts.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/energy-history/buildings/{buildingId}")
@PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 无副作用的历史导入配置检查；后续导入、审核和封账仍走各域原入口。 */
public class HistoricalConfigurationController {
    private final HistoricalConfigurationService service;
    @PostMapping("/configuration-coverage")
    public Result<Coverage> coverage(Authentication authentication, @PathVariable String buildingId,
            @Valid @RequestBody Request request) {
        return Result.success(service.coverage(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, request));
    }
}
