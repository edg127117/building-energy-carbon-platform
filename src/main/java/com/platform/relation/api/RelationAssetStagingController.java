package com.platform.relation.api;

import com.platform.framework.common.Result;
import com.platform.relation.RelationAssetStagingService;
import com.platform.relation.RelationStagingContracts.*;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/relation-models/{buildingId}/versions/{versionId}/staged-assets")
@PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 暂存档案不绕过关系审核；发布仍使用原关系审核和生效接口。 */
public class RelationAssetStagingController {
    private final RelationAssetStagingService service;
    public RelationAssetStagingController(RelationAssetStagingService service){this.service=service;}
    @GetMapping public Result<Mutation> list(Authentication a,@PathVariable String buildingId,@PathVariable String versionId){return Result.success(service.list(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,versionId));}
    @GetMapping("/options") public Result<Options> options(Authentication a,@PathVariable String buildingId,@PathVariable String versionId){return Result.success(service.options(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,versionId));}
    @PostMapping("/equipment") public Result<Mutation> equipment(Authentication a,@PathVariable String buildingId,@PathVariable String versionId,@Valid @RequestBody EquipmentRequest request){return Result.success(service.equipment(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,versionId,request));}
    @PostMapping("/points") public Result<Mutation> point(Authentication a,@PathVariable String buildingId,@PathVariable String versionId,@Valid @RequestBody PointRequest request){return Result.success(service.point(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,versionId,request));}
    @DeleteMapping("/{id}") public Result<Mutation> remove(Authentication a,@PathVariable String buildingId,@PathVariable String versionId,@PathVariable String id,@Valid @RequestBody RemoveRequest request){return Result.success(service.remove(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,versionId,id,request));}
}
