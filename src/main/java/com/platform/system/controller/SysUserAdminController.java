package com.platform.system.controller;

import com.platform.audit.AuditGovernanceErrors;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.platform.framework.common.Result;
import com.platform.system.model.dto.UserAdminDtos;
import com.platform.system.service.SysUserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * PLATFORM_ADMIN 专用的人员管理接口。
 *
 * <p>类级 {@link PreAuthorize} 保证所有端点都只能由平台管理员调用。接口覆盖账号生命周期、
 * 正式角色分配和建筑范围分配，返回的人员视图不包含密码。</p>
 */
@RestController
@RequestMapping("/system/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class SysUserAdminController {
    private final SysUserAdminService service;

    /** 分页查询人员，可按关键字、状态过滤并选择是否包含逻辑删除账号。 */
    @GetMapping
    public Result<IPage<UserAdminDtos.UserView>> page(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String keyword,
            @RequestParam(required=false) Integer status, @RequestParam(defaultValue="false") boolean includeDeleted) {
        return Result.success(service.page(page, size, keyword, status, includeDeleted));
    }

    /** 查询单个人员及其角色、建筑范围。 */
    @GetMapping("/{id}") public Result<UserAdminDtos.UserView> detail(@PathVariable Long id) { return Result.success(service.detail(id)); }
    /** 旧直改入口保留稳定拒绝，账号、角色和建筑必须作为一个开通申请包审核。 */
    @PostMapping public Result<UserAdminDtos.UserView> create(@RequestBody JsonNode ignored) { throw AuditGovernanceErrors.reviewRequired(); }
    /** 修改昵称和手机等基础资料。 */
    @PutMapping("/{id}") public Result<UserAdminDtos.UserView> update(@PathVariable Long id, @Valid @RequestBody UserAdminDtos.UpdateRequest r) { return Result.success(service.update(id, r)); }
    /** 旧直改入口保留稳定拒绝，逻辑删除账号必须改走通用敏感变更申请。 */
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable Long id) { throw AuditGovernanceErrors.reviewRequired(); }
    /** 旧直改入口保留稳定拒绝，恢复账号必须改走通用敏感变更申请。 */
    @PutMapping("/{id}/restore") public Result<UserAdminDtos.UserView> restore(@PathVariable Long id) { throw AuditGovernanceErrors.reviewRequired(); }
    /** 旧直改入口保留稳定拒绝，账号启停必须改走通用敏感变更申请。 */
    @PutMapping("/{id}/status") public Result<Void> status(@PathVariable Long id, @Valid @RequestBody UserAdminDtos.StatusRequest r) { throw AuditGovernanceErrors.reviewRequired(); }
    /** 旧直改入口保留稳定拒绝，密码重置申请只包含账号 ID，执行后返回一次性令牌。 */
    @PutMapping("/{id}/password") public Result<Void> password(@PathVariable Long id, @RequestBody JsonNode ignored) { throw AuditGovernanceErrors.reviewRequired(); }
    /** 旧直改入口保留稳定拒绝，正式角色必须改走通用敏感变更申请。 */
    @PutMapping("/{id}/roles") public Result<Void> roles(@PathVariable Long id, @Valid @RequestBody UserAdminDtos.RolesRequest r) { throw AuditGovernanceErrors.reviewRequired(); }
    /** 旧直改入口保留稳定拒绝，建筑范围必须改走通用敏感变更申请。 */
    @PutMapping("/{id}/buildings") public Result<Void> buildings(@PathVariable Long id, @RequestBody UserAdminDtos.BuildingsRequest r) { throw AuditGovernanceErrors.reviewRequired(); }
    /** 旧直改入口保留稳定拒绝，单建筑撤销必须改走通用敏感变更申请。 */
    @DeleteMapping("/{id}/buildings/{buildingId}") public Result<Void> revokeBuilding(@PathVariable Long id, @PathVariable String buildingId) { throw AuditGovernanceErrors.reviewRequired(); }

}
