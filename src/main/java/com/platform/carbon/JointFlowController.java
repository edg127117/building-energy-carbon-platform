package com.platform.carbon;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/carbon-management/joint-flow")
@SecurityRequirement(name="bearerAuth")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 新增分析查询保留原报表契约，不发布核算、变更分类或触发设备操作。 */
public class JointFlowController {
    private final JointFlowService service;
    private final CarbonDisplayQueryService display;
    @PostMapping("/summary-query")
    public Result<CarbonDisplayQueryContracts.View> summary(Authentication authentication,@Valid @RequestBody JointFlowContracts.Request request) {
        return Result.success(display.jointFlow(SecurityUser.userId(authentication),SecurityUser.roles(authentication),request));
    }
    @PostMapping("/query")
    public Result<JointFlowContracts.View> query(Authentication authentication, @Valid @RequestBody JointFlowContracts.Request request) {
        return Result.success(service.query(SecurityUser.userId(authentication), SecurityUser.roles(authentication), request));
    }
    @PostMapping("/preview")
    public Result<JointFlowContracts.View> preview(Authentication authentication, @Valid @RequestBody JointFlowContracts.Request request) {
        return Result.success(service.preview(SecurityUser.userId(authentication), SecurityUser.roles(authentication), request));
    }
}
