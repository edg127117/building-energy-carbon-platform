package com.platform.system.controller;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import com.platform.system.model.dto.BuildingAccessDtos;
import com.platform.system.service.BuildingAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 建筑访问申请审核入口；角色负责进入管理域，后台审核职责由 Service 动态校验。 */
@RestController
@RequestMapping("/system/building-access/requests")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class BuildingAccessAdminController {
    private final BuildingAccessService service;
    /** 查询全部申请，可用状态值筛选。 */
    @GetMapping public Result<List<BuildingAccessDtos.RequestView>> list(@RequestParam(required=false) String status) { return Result.success(service.listAll(status)); }
    /** 批准待审申请，同时生成正式用户建筑授权。 */
    @PutMapping("/{id}/approve") public Result<Void> approve(Authentication a, @PathVariable Long id, @Valid @RequestBody(required=false) BuildingAccessDtos.ReviewRequest r) { service.approve(SecurityUser.userId(a), id, r == null ? null : r.comment()); return Result.success(); }
    /** 拒绝待审申请，不改变用户现有建筑权限。 */
    @PutMapping("/{id}/reject") public Result<Void> reject(Authentication a, @PathVariable Long id, @Valid @RequestBody(required=false) BuildingAccessDtos.ReviewRequest r) { service.reject(SecurityUser.userId(a), id, r == null ? null : r.comment()); return Result.success(); }
}
