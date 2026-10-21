package com.platform.carbon;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/carbon-management/calculations")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 分类报表引用计算批次，建筑权限由原核算查询逐项复核。 */
public class CarbonClassificationReportController {
    private final CarbonClassificationReportService service;
    private final CarbonCalculationService calculations;
    public CarbonClassificationReportController(CarbonClassificationReportService service,CarbonCalculationService calculations){this.service=service;this.calculations=calculations;}
    @GetMapping
    public Result<java.util.List<CarbonModels.CalculationBatch>> batches(Authentication a,@RequestParam String buildingId) {
        return Result.success(calculations.list(SecurityUser.userId(a),SecurityUser.roles(a),buildingId));
    }
    @GetMapping("/{batchId}/classification-report")
    public Result<CarbonClassificationReportService.Report> report(Authentication a,@PathVariable String batchId) {
        return Result.success(service.report(SecurityUser.userId(a),SecurityUser.roles(a),batchId));
    }
}
