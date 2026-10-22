package com.platform.energy.classification;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import static com.platform.energy.classification.ClassificationContracts.*;

@RestController
@RequestMapping("/v1/energy-classification/buildings/{buildingId}")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 通用分类、版本治理和原生统计入口；每个服务调用仍复核职责及建筑范围。 */
public class ClassificationController {
    private final ClassificationService service;
    public ClassificationController(ClassificationService service){this.service=service;}
    @GetMapping("/versions")
    public Result<List<VersionView>> versions(Authentication a,@PathVariable String buildingId){return Result.success(service.versions(SecurityUser.userId(a),SecurityUser.roles(a),buildingId));}
    @GetMapping("/versions/{id}")
    public Result<VersionView> version(Authentication a,@PathVariable String buildingId,@PathVariable String id){return Result.success(service.version(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id));}
    @PostMapping("/versions")
    public Result<VersionView> create(Authentication a,@PathVariable String buildingId,@Valid @RequestBody CreateRequest r){return Result.success(service.create(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,r));}
    @PutMapping("/versions/{id}")
    public Result<VersionView> save(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody SaveRequest r){return Result.success(service.save(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r));}
    @PostMapping("/versions/{id}/submit")
    public Result<VersionView> submit(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody TransitionRequest r){return Result.success(service.submit(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r));}
    @PostMapping("/versions/{id}/approve")
    public Result<VersionView> approve(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody TransitionRequest r){return Result.success(service.approve(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r));}
    @PostMapping("/versions/{id}/withdraw")
    public Result<VersionView> withdraw(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody TransitionRequest r){return Result.success(service.reopen(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r,false));}
    @PostMapping("/versions/{id}/reject")
    public Result<VersionView> reject(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody TransitionRequest r){return Result.success(service.reopen(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r,true));}
    @PostMapping("/query")
    public Result<QueryView> query(Authentication a,@PathVariable String buildingId,@Valid @RequestBody QueryRequest r){return Result.success(service.query(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,r));}
    @PostMapping("/readings/preview")
    public Result<ImportView> preview(Authentication a,@PathVariable String buildingId,@Valid @RequestBody ImportRequest r){return Result.success(service.preview(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,r));}
    @PostMapping("/readings/import")
    public Result<ImportView> importReadings(Authentication a,@PathVariable String buildingId,@Valid @RequestBody ImportRequest r){return Result.success(service.importReadings(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,r));}
    @GetMapping("/readings/batches/{id}")
    public Result<ReadingBatchView> readingBatch(Authentication a, @PathVariable String buildingId,
                                                 @PathVariable String id) {
        return Result.success(service.readingBatch(SecurityUser.userId(a),
                SecurityUser.roles(a), buildingId, id));
    }
    @PostMapping("/readings/batches/{id}/approve")
    public Result<ImportView> approveReadings(Authentication a,@PathVariable String buildingId,@PathVariable String id,@Valid @RequestBody TransitionRequest r){return Result.success(service.approveReadings(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,id,r));}
    @GetMapping("/readings")
    public Result<List<ReadingView>> readings(Authentication a,@PathVariable String buildingId,@RequestParam Instant startInclusive,@RequestParam Instant endExclusive){return Result.success(service.readings(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,startInclusive,endExclusive));}
}
