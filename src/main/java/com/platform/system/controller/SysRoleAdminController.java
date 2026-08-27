package com.platform.system.controller;

import com.platform.audit.AuditGovernanceErrors;
import com.platform.framework.common.Result;
import com.platform.system.model.dto.RoleAdminDtos;
import com.platform.system.service.SysRoleAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PLATFORM_ADMIN 专用的正式角色管理接口。
 *
 * <p>四类角色键由设计冻结，不在此处任意新增或删除；本接口主要用于查看角色和配置动态菜单。</p>
 */
@RestController
@RequestMapping("/system/roles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class SysRoleAdminController {
    private final SysRoleAdminService service;
    /** 查询四类正式角色。 */
    @GetMapping public Result<List<RoleAdminDtos.RoleView>> list() { return Result.success(service.list()); }
    /** 查询正式角色详情。 */
    @GetMapping("/{id}") public Result<RoleAdminDtos.RoleView> detail(@PathVariable Long id) { return Result.success(service.detail(id)); }
    /** 查询角色已经关联的菜单 ID。 */
    @GetMapping("/{id}/menus") public Result<List<Long>> menus(@PathVariable Long id) { return Result.success(service.menuIds(id)); }
    /** 旧直改入口保留稳定拒绝，角色菜单必须改走通用敏感变更申请。 */
    @PutMapping("/{id}/menus") public Result<Void> replace(@PathVariable Long id, @RequestBody RoleAdminDtos.MenuAssignmentRequest r) { throw AuditGovernanceErrors.reviewRequired(); }
}
