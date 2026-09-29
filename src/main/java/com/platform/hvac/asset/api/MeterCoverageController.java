package com.platform.hvac.asset.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.asset.service.MeterCoverageService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.hvac.asset.api.MeterCoverageContracts.*;

@RestController
@RequestMapping("/v1/assets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "表计覆盖档案")
/** 沿用资产管理员权限；公开档案接口不执行任何设备控制或能耗计算。 */
public class MeterCoverageController {
    private final MeterCoverageService service;

    @GetMapping("/equipment/{id}/meter-coverage")
    public Result<View> current(@PathVariable String id, Authentication auth) {
        return Result.success(service.current(id, SecurityUser.roles(auth)));
    }

    @PutMapping("/equipment/{id}/meter-coverage")
    public Result<View> save(@PathVariable String id, @Valid @RequestBody SaveRequest request, Authentication auth) {
        return Result.success(service.save(id, request, SecurityUser.userId(auth), SecurityUser.roles(auth)));
    }

    @GetMapping("/equipment/{id}/meter-coverage/history")
    public Result<PageResponse<View>> history(@PathVariable String id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return Result.success(service.history(id, page, size, SecurityUser.roles(auth)));
    }

    @GetMapping("/equipment/{id}/meter-coverage/candidates")
    public Result<PageResponse<Target>> candidates(@PathVariable String id, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return Result.success(service.candidates(id, keyword, page, size, SecurityUser.roles(auth)));
    }

    @GetMapping("/meter-coverages")
    public Result<List<View>> batch(@RequestParam(required = false) String buildingId,
            @RequestParam List<String> equipmentIds, Authentication auth) {
        return Result.success(service.batch(buildingId, equipmentIds, SecurityUser.roles(auth)));
    }
}
