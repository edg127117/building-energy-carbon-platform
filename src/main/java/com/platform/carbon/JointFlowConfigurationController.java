package com.platform.carbon;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.carbon.JointFlowConfigurationContracts.*;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@RequestMapping("/v1/carbon-management/joint-flow/buildings/{building}/configurations")
/** 配置平台治理入口；职责和建筑范围在服务层再次校验。 */
public class JointFlowConfigurationController {
    private final JointFlowConfigurationService service;
    @GetMapping public Result<List<Version>> list(Authentication a,@PathVariable String building){return Result.success(service.list(SecurityUser.userId(a),SecurityUser.roles(a),building));}
    @GetMapping("/{id}") public Result<Version> get(Authentication a,@PathVariable String building,@PathVariable String id){return Result.success(service.get(SecurityUser.userId(a),SecurityUser.roles(a),building,id));}
    @PostMapping public Result<Version> create(Authentication a,@PathVariable String building,@Valid @RequestBody Create r){return Result.success(service.create(SecurityUser.userId(a),SecurityUser.roles(a),building,r));}
    @PutMapping("/{id}") public Result<Version> save(Authentication a,@PathVariable String building,@PathVariable String id,@Valid @RequestBody Save r){return Result.success(service.save(SecurityUser.userId(a),SecurityUser.roles(a),building,id,r));}
    @PostMapping("/{id}/{action:submit|approve|reject}") public Result<Version> transition(Authentication a,@PathVariable String building,@PathVariable String id,@PathVariable String action,@Valid @RequestBody Transition r){return Result.success(service.transition(SecurityUser.userId(a),SecurityUser.roles(a),building,id,r,action));}
    @GetMapping("/selection") public Result<Selection> selection(Authentication a,@PathVariable String building){return Result.success(service.selection(SecurityUser.userId(a),SecurityUser.roles(a),building));}
    @PutMapping("/selection") public Result<Selection> select(Authentication a,@PathVariable String building,@Valid @RequestBody Select r){return Result.success(service.select(SecurityUser.userId(a),SecurityUser.roles(a),building,r));}
}
