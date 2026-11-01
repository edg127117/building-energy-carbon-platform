package com.platform.carbon;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import static com.platform.carbon.CarbonPlanContracts.*;

@RestController
@RequestMapping("/v1/carbon-management/buildings/{buildingId}/plan")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 计划读写共用既有建筑授权及碳规则动态职责，不新增权限体系。 */
public class CarbonPlanController {
    private final CarbonPlanService service;
    private final CarbonPlanImport importer;
    public CarbonPlanController(CarbonPlanService service,CarbonPlanImport importer) { this.service=service;this.importer=importer; }
    @GetMapping
    public Result<Response> query(Authentication a,@PathVariable String buildingId,@RequestParam int year,@RequestParam(required=false) String throughMonth) {
        return Result.success(service.query(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,year,throughMonth));
    }
    @GetMapping("/baseline-preview")
    public Result<Baseline> baseline(Authentication a,@PathVariable String buildingId,@RequestParam int sourceYear) {
        return Result.success(service.baselineYear(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,sourceYear));
    }
    @PostMapping(value="/import-preview",consumes="multipart/form-data")
    public Result<Baseline> importPreview(Authentication a,@PathVariable String buildingId,@RequestParam MultipartFile file,
            @RequestParam String nature,@RequestParam String source,@RequestParam String boundary) {
        // 先校验身份与维护职责，再解析上传文件；预览本身没有持久化副作用。
        service.importedAccess(SecurityUser.userId(a),SecurityUser.roles(a),buildingId);
        return Result.success(service.imported(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,importer.parse(file,nature,source,boundary)));
    }
    @GetMapping("/versions")
    public Result<List<Version>> versions(Authentication a,@PathVariable String buildingId,@RequestParam int year) {
        return Result.success(service.versions(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,year));
    }
    @PostMapping("/versions")
    public Result<Version> create(Authentication a,@PathVariable String buildingId,@Valid @RequestBody Create r) {
        return Result.success(service.create(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,r));
    }
    @PutMapping("/versions/{id}")
    public Result<Version> save(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody Save r) {
        return Result.success(service.save(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r));
    }
    @PostMapping("/versions/{id}/{action:submit|withdraw|approve|reject|activate}")
    public Result<Version> transition(Authentication a,@PathVariable String buildingId,@PathVariable String id,@PathVariable String action,@Valid @RequestBody Transition r) {
        return Result.success(service.transition(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,action,r));
    }
}
