package com.platform.carbon;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.carbon.CarbonProjectContracts.*;

@RestController
@RequestMapping("/v1/carbon-projects/buildings/{buildingId}")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 项目配置公开契约；服务层复核建筑范围、动态职责及版本状态。 */
public class CarbonProjectController {
    private final CarbonProjectService service;
    public CarbonProjectController(CarbonProjectService service){this.service=service;}
    @GetMapping("/options")
    public Result<List<PathOption>> options(Authentication a,@PathVariable String buildingId){return Result.success(service.options(SecurityUser.userId(a),SecurityUser.roles(a),buildingId));}
    @GetMapping("/versions")
    public Result<List<Version>> versions(Authentication a,@PathVariable String buildingId){return Result.success(service.versions(SecurityUser.userId(a),SecurityUser.roles(a),buildingId));}
    @PostMapping("/versions")
    public Result<Version> create(Authentication a,@PathVariable String buildingId,@Valid @RequestBody Create r){return Result.success(service.create(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,r));}
    @PutMapping("/versions/{id}")
    public Result<Version> save(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody Save r){return Result.success(service.save(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r));}
    @GetMapping("/versions/{id}/readiness")
    public Result<Readiness> readiness(Authentication a,@PathVariable String buildingId,@PathVariable String id){return Result.success(service.readiness(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id));}
    @PostMapping("/versions/{id}/{action:submit|withdraw|approve|reject}")
    public Result<Version> transition(Authentication a,@PathVariable String buildingId,@PathVariable String id,@PathVariable String action,@Valid @RequestBody Transition r){return Result.success(service.transition(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,action,r));}
    @PostMapping("/versions/{id}/activate")
    public Result<Version> activate(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody Transition r){return Result.success(service.activate(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r));}
}
