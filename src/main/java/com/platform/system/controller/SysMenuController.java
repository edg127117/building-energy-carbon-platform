package com.platform.system.controller;

import com.platform.audit.AuditGovernanceErrors;
import com.platform.framework.common.Result;
import com.platform.system.model.dto.MenuAdminDtos;
import com.platform.system.model.entity.SysMenu;
import com.platform.security.JwtUserPrincipal;
import com.platform.system.service.SysMenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 动态菜单查询与管理接口。
 *
 * <p>平台管理员可以查看和维护完整菜单、角色菜单；任意已认证正式角色可读取自己的菜单树。</p>
 */
@Slf4j
@RestController
@RequestMapping("/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService menuService;

    /** 获取完整菜单树 */
    @GetMapping("/tree")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<List<SysMenu>> tree() {
        return menuService.buildTree();
    }

    /** 查询完整维护树；隐藏和停用菜单也必须可见，才能由管理员恢复。 */
    @GetMapping("/admin/tree")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<List<SysMenu>> adminTree() {
        return menuService.adminTree();
    }

    /** 按角色查询菜单树 */
    @GetMapping("/role/{roleKey}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<List<SysMenu>> listByRole(@PathVariable String roleKey) {
        return menuService.listByRole(roleKey);
    }

    /** 旧直改入口保留稳定拒绝，新增菜单必须改走通用敏感变更申请。 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<SysMenu> add(@Valid @RequestBody MenuAdminDtos.CreateRequest request) {
        throw AuditGovernanceErrors.reviewRequired();
    }

    /** 旧直改入口保留稳定拒绝，更新菜单必须改走通用敏感变更申请。 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<SysMenu> update(@Valid @RequestBody MenuAdminDtos.UpdateRequest request) {
        throw AuditGovernanceErrors.reviewRequired();
    }

    /** 旧直改入口保留稳定拒绝，删除菜单必须改走通用敏感变更申请。 */
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        throw AuditGovernanceErrors.reviewRequired();
    }

    /** 查询当前登录用户所有角色的菜单并集；第三方账号通常返回空列表。 */
    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    public Result<List<SysMenu>> current(Authentication authentication) {
        Long userId = ((JwtUserPrincipal) authentication.getPrincipal()).getId();
        return menuService.currentMenu(userId);
    }
}
