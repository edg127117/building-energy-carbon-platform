package com.platform.system.controller;

import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.Building;
import com.platform.security.SecurityUser;
import com.platform.system.model.dto.BuildingAccessDtos;
import com.platform.system.service.BuildingAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 普通业务用户申请建筑查看权限的接口。
 *
 * <p>建筑业主、能效管理方和第三方账号可以调用；平台管理员拥有全部建筑，无需走申请流程。</p>
 */
@RestController
@RequestMapping("/building-access")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','THIRD_PARTY')")
public class BuildingAccessController {
    private final BuildingAccessService service;
    /** 查询当前还可以申请的建筑。 */
    @GetMapping("/available") public Result<List<Building>> available(Authentication a) { return Result.success(service.available(SecurityUser.userId(a), SecurityUser.roles(a))); }
    /** 提交新的建筑访问申请。 */
    @PostMapping("/requests") public Result<BuildingAccessDtos.RequestView> submit(Authentication a, @Valid @RequestBody BuildingAccessDtos.SubmitRequest r) { return Result.success(service.submit(SecurityUser.userId(a), SecurityUser.roles(a), r)); }
    /** 查询当前登录用户自己的申请历史。 */
    @GetMapping("/requests/mine") public Result<List<BuildingAccessDtos.RequestView>> mine(Authentication a) { return Result.success(service.mine(SecurityUser.userId(a))); }
    /** 取消自己尚未审核的申请。 */
    @PutMapping("/requests/{id}/cancel") public Result<Void> cancel(Authentication a, @PathVariable Long id) { service.cancel(SecurityUser.userId(a), id); return Result.success(); }
}
