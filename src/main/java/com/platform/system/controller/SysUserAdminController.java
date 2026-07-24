package com.platform.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.security.JwtUserPrincipal;
import com.platform.system.model.dto.UserAdminDtos;
import com.platform.system.service.SysUserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    /** 创建人员；角色和建筑与账号在同一事务中写入。 */
    @PostMapping public Result<UserAdminDtos.UserView> create(@Valid @RequestBody UserAdminDtos.CreateRequest r) { return Result.success(service.create(r)); }
    /** 修改昵称和手机等基础资料。 */
    @PutMapping("/{id}") public Result<UserAdminDtos.UserView> update(@PathVariable Long id, @Valid @RequestBody UserAdminDtos.UpdateRequest r) { return Result.success(service.update(id, r)); }
    /** 逻辑删除人员；当前管理员不能删除自己。 */
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable Long id, Authentication a) { service.delete(currentId(a), id); return Result.success(); }
    /** 恢复逻辑删除账号，恢复后需重新分配角色和建筑。 */
    @PutMapping("/{id}/restore") public Result<UserAdminDtos.UserView> restore(@PathVariable Long id) { return Result.success(service.restore(id)); }
    /** 启用或禁用账号，禁用会撤销当前登录 Token。 */
    @PutMapping("/{id}/status") public Result<Void> status(@PathVariable Long id, @Valid @RequestBody UserAdminDtos.StatusRequest r, Authentication a) { service.updateStatus(currentId(a), id, r.status()); return Result.success(); }
    /** 重置用户密码并撤销其当前 Token。 */
    @PutMapping("/{id}/password") public Result<Void> password(@PathVariable Long id, @Valid @RequestBody UserAdminDtos.PasswordRequest r) { service.resetPassword(id, r.password()); return Result.success(); }
    /** 全量替换正式角色；角色变化后用户需要重新登录。 */
    @PutMapping("/{id}/roles") public Result<Void> roles(@PathVariable Long id, @Valid @RequestBody UserAdminDtos.RolesRequest r, Authentication a) { service.replaceRoles(currentId(a), id, r.roleKeys()); return Result.success(); }
    /** 全量替换建筑授权，空列表表示撤销全部建筑。 */
    @PutMapping("/{id}/buildings") public Result<Void> buildings(@PathVariable Long id, @RequestBody UserAdminDtos.BuildingsRequest r) { service.replaceBuildings(id, r.buildingIds()); return Result.success(); }
    /** 只撤销一个指定建筑授权。 */
    @DeleteMapping("/{id}/buildings/{buildingId}") public Result<Void> revokeBuilding(@PathVariable Long id, @PathVariable String buildingId) { service.revokeBuilding(id, buildingId); return Result.success(); }

    /** 从 JWT principal 中取得操作者 ID，用于自操作和最后管理员保护。 */
    private Long currentId(Authentication authentication) {
        return ((JwtUserPrincipal) authentication.getPrincipal()).getId();
    }
}
