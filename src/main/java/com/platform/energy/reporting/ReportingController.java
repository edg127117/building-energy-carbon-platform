package com.platform.energy.reporting;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import static com.platform.energy.reporting.ReportingContracts.*;

@RestController
@RequestMapping("/v1/energy-reporting/buildings/{building}")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@RequiredArgsConstructor
/** 业主按月填写原始用量；来源治理与独立封账权限由服务端动态职责执行。 */
public class ReportingController {
    private final ReportingService service;
    @GetMapping("/context")
    public Result<Context> context(Authentication a,@PathVariable String building,@RequestParam LocalDate month) {
        return Result.success(service.context(SecurityUser.userId(a),SecurityUser.roles(a),building,month));
    }
    @PostMapping("/sources")
    public Result<Source> createSource(Authentication a,@PathVariable String building,@Valid @RequestBody CreateSource r) {
        return Result.success(service.createSource(SecurityUser.userId(a),SecurityUser.roles(a),building,r));
    }
    @PutMapping("/sources/{id}")
    public Result<Source> saveSource(Authentication a,@PathVariable String building,@PathVariable String id,@Valid @RequestBody SaveSource r) {
        return Result.success(service.saveSource(SecurityUser.userId(a),SecurityUser.roles(a),building,id,r));
    }
    @PostMapping("/sources/{id}/{action:submit|approve|reject}")
    public Result<Source> sourceCommand(Authentication a,@PathVariable String building,@PathVariable String id,@PathVariable String action,@Valid @RequestBody Command r) {
        return Result.success(service.sourceCommand(SecurityUser.userId(a),SecurityUser.roles(a),building,id,action,r));
    }
    @PostMapping("/batches")
    public Result<Batch> createBatch(Authentication a,@PathVariable String building,@Valid @RequestBody CreateBatch r) {
        return Result.success(service.createBatch(SecurityUser.userId(a),SecurityUser.roles(a),building,r));
    }
    @GetMapping("/batches/{id}")
    public Result<Batch> batch(Authentication a,@PathVariable String building,@PathVariable String id) {
        return Result.success(service.batch(SecurityUser.userId(a),SecurityUser.roles(a),building,id));
    }
    @PutMapping("/batches/{id}")
    public Result<Batch> saveBatch(Authentication a,@PathVariable String building,@PathVariable String id,@Valid @RequestBody SaveBatch r) {
        return Result.success(service.saveBatch(SecurityUser.userId(a),SecurityUser.roles(a),building,id,r));
    }
    @PostMapping("/batches/{id}/preview")
    public Result<Preview> preview(Authentication a,@PathVariable String building,@PathVariable String id) {
        return Result.success(service.preview(SecurityUser.userId(a),SecurityUser.roles(a),building,id));
    }
    @PostMapping("/batches/{id}/{action:submit|approve|reject|seal}")
    public Result<Batch> command(Authentication a,@PathVariable String building,@PathVariable String id,@PathVariable String action,@Valid @RequestBody Command r) {
        return Result.success(service.batchCommand(SecurityUser.userId(a),SecurityUser.roles(a),building,id,action,r));
    }
}
