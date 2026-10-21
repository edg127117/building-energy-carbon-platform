package com.platform.carbon;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/carbon-projects/buildings/{buildingId}/versions/{versionId}/work-order")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 工作单读取与明确操作入口；后端领域服务继续校验职责、独立审核和当前依赖。 */
public class CarbonConfigurationWorkOrderController {
    private final CarbonConfigurationWorkOrderService service;
    public CarbonConfigurationWorkOrderController(CarbonConfigurationWorkOrderService service){this.service=service;}
    @GetMapping
    public Result<CarbonConfigurationWorkOrderService.WorkOrder> read(Authentication a,@PathVariable String buildingId,@PathVariable String versionId){return Result.success(service.read(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,versionId));}
    @PostMapping("/commands")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<CarbonConfigurationWorkOrderService.Receipt> execute(Authentication a,@PathVariable String buildingId,@PathVariable String versionId,@Valid @RequestBody CarbonConfigurationWorkOrderService.Command command){return Result.success(service.execute(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,versionId,command));}
}
