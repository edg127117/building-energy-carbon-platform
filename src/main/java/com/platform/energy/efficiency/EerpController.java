package com.platform.energy.efficiency;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.energy.efficiency.EerpContracts.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/energy-efficiency")
@Tag(name="冷站 EERp 研发计算")
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="请求、单位、覆盖或容量无效",
        content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=EerpApiError.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="建筑、角色或动态职责不足"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="配置或任务不存在"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="幂等、版本、审核状态或执行租约冲突")
})
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 只暴露电驱动水冷冷站研发配置和显式任务；读取结果不触发隐式计算。 */
public class EerpController {
    private final EerpService service;
    @PostMapping("/configurations")
    @Operation(summary="创建不可覆盖的冷站配置草稿")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ConfigView> createConfiguration(Authentication a,@RequestBody Configuration body) {
        return Result.success(service.createConfig(SecurityUser.userId(a),SecurityUser.roles(a),body));
    }
    @GetMapping("/configurations")
    public Result<List<ConfigView>> configurations(Authentication a,@RequestParam String buildingId,
            @RequestParam String stationId,@RequestParam(defaultValue="20") int limit) {
        return Result.success(service.configs(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,stationId,limit));
    }
    @GetMapping("/configurations/{id}")
    public Result<ConfigView> configuration(Authentication a,@PathVariable String id) {
        return Result.success(service.config(SecurityUser.userId(a),SecurityUser.roles(a),id));
    }
    @PostMapping("/configurations/{id}/actions/{action}")
    @Operation(summary="提交、批准、驳回或生效配置",description="action: SUBMIT / APPROVE / REJECT / ACTIVATE；版本并发与动态后台职责由服务端检查。")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ConfigView> configurationAction(Authentication a,@PathVariable String id,@PathVariable String action,@RequestBody Review body) {
        return Result.success(service.reviewConfig(SecurityUser.userId(a),SecurityUser.roles(a),id,action,body));
    }
    @PostMapping("/period-tasks")
    @Operation(summary="显式计算不超过一天的原生冷量和电量",description="前序任务非空时进入重算审核；计算不涉及折标煤。")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TaskView> period(Authentication a,@RequestBody PeriodRequest body) {
        return Result.success(service.createPeriod(SecurityUser.userId(a),SecurityUser.roles(a),body));
    }
    @PostMapping("/annual-tasks")
    @Operation(summary="用已封账的周期快照计算自然年度 EERp",description="研发评价严格使用全年4.0/5.0阈值，正式标准适用性未核验。")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TaskView> annual(Authentication a,@RequestBody AnnualRequest body) {
        return Result.success(service.createAnnual(SecurityUser.userId(a),SecurityUser.roles(a),body));
    }
    @PostMapping("/tasks/{id}/resume")
    @Operation(summary="恢复失败或租约过期的有界计算",description="已固定输入的任务重试同一证据；已完成任务直接返回原结果。")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TaskView> resume(Authentication a,@PathVariable String id) {
        return Result.success(service.execute(SecurityUser.userId(a),SecurityUser.roles(a),id));
    }
    @PostMapping("/tasks/{id}/actions/{action}")
    @Operation(summary="审核重算或封账",description="action: APPROVE_RECALC / SUBMIT_SEAL / APPROVE_SEAL。")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TaskView> taskAction(Authentication a,@PathVariable String id,@PathVariable String action,@RequestBody Review body) {
        return Result.success(service.reviewTask(SecurityUser.userId(a),SecurityUser.roles(a),id,action,body));
    }
    @GetMapping("/tasks")
    public Result<List<TaskView>> tasks(Authentication a,@RequestParam String buildingId,@RequestParam String stationId,@RequestParam(defaultValue="20") int limit) {
        return Result.success(service.tasks(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,stationId,limit));
    }
    @GetMapping("/tasks/{id}")
    public Result<TaskView> task(Authentication a,@PathVariable String id) {
        return Result.success(service.task(SecurityUser.userId(a),SecurityUser.roles(a),id));
    }
    @GetMapping("/tasks/{id}/trace")
    public Result<TraceView> trace(Authentication a,@PathVariable String id) {
        return Result.success(service.trace(SecurityUser.userId(a),SecurityUser.roles(a),id));
    }
}
