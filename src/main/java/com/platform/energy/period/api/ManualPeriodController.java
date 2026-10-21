package com.platform.energy.period.api;

import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.EnergyPeriodLifecycleService;
import com.platform.energy.period.ManualPeriodInputService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/energy-periods/buildings/{buildingId}/manual-months")
@PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 正式来源候选入口；封账提交与独立审核继续调用能源周期原有契约。 */
public class ManualPeriodController {
    private final EnergyPeriodLifecycleService lifecycle;
    private final ManualPeriodInputService inputs;
    private final EnergyPeriodAuthorization authorization;
    private final com.platform.energy.period.ManualPeriodContextService contexts;
    public record Candidate(String projectionId,long revision,String buildingId,String pointId,
                            java.time.Instant startInclusive,java.time.Instant endExclusive,
                            String quantity,String unit,String evidenceHash,String status) { }
    @GetMapping("/context")
    public Result<com.platform.energy.period.ManualPeriodContextService.Context> context(Authentication a,@PathVariable String buildingId,
            @RequestParam String pointId,@RequestParam java.time.LocalDate month,@RequestParam(required=false) String policyVersionId) {
        return Result.success(contexts.context(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,pointId,month,policyVersionId));
    }
    @PostMapping("/preview")
    public Result<Candidate> preview(Authentication a,@PathVariable String buildingId,@Valid @RequestBody ManualPeriodInputService.Request request) {
        authorization.requireCalculation(SecurityUser.userId(a),SecurityUser.roles(a));
        var value=inputs.prepare(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,request);
        return Result.success(new Candidate(null,request.expectedProjectionRevision(),buildingId,request.pointId(),value.window().startInclusive(),value.window().endExclusive(),value.nativeQuantity().toPlainString(),value.nativeUnitCode(),value.evidenceHash(),"PREVIEW_ONLY"));
    }
    @PostMapping
    public Result<Candidate> prepare(Authentication a,@PathVariable String buildingId,@Valid @RequestBody ManualPeriodInputService.Request request) {
        var value=lifecycle.refreshManual(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,request);
        return Result.success(new Candidate(value.projectionId(),value.revision(),buildingId,request.pointId(),value.startInclusive(),value.endExclusive(),value.nativeQuantity().toPlainString(),value.nativeUnitCode(),value.evidenceHash(),"PENDING_INDEPENDENT_LOCK"));
    }
}
