package com.platform.carbon.api;

import com.platform.carbon.ElectricityFactorCatalog;
import com.platform.carbon.ElectricityFactorImportService;
import com.platform.carbon.api.CarbonContracts.FactorVersionView;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/carbon-management/electricity-factor-catalog")
@PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
@RequiredArgsConstructor
/**
 * 提供有界官方目录预览与逐项候选录入；审核和激活继续使用碳规则入口。
 */
public class ElectricityFactorCatalogController {
    private final ElectricityFactorImportService service;

    public record ImportRequest(@NotBlank String usageNature) { }

    @GetMapping
    public Result<List<ElectricityFactorCatalog.Entry>> list() {
        return Result.success(service.catalog());
    }

    @PostMapping("/{entryCode}/import")
    public Result<FactorVersionView> importEntry(Authentication authentication,
                                                @PathVariable String entryCode,
                                                @Valid @RequestBody ImportRequest request) {
        return Result.success(CarbonManagementController.factor(service.importEntry(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                entryCode, request.usageNature())));
    }
}
