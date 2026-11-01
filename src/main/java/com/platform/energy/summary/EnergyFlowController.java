package com.platform.energy.summary;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name="能源计量边界汇总")
@SecurityRequirement(name="bearerAuth")
@RequiredArgsConstructor
@RequestMapping("/v1/energy-boundary-summaries/flow")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 只读聚合能流；不创建参数、不封账，也不触发碳核算。 */
public class EnergyFlowController {
    private final EnergyFlowService service;

    @PostMapping("/query")
    public Result<EnergyFlowContracts.View> query(Authentication authentication,
            @Valid @RequestBody EnergyFlowContracts.Request request) {
        return Result.success(service.query(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }
}
