package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.hvac.model.entity.Building;
import com.platform.security.FormalRole;
import com.platform.system.mapper.BuildingAccessRequestMapper;
import com.platform.system.mapper.SysUserBuildingMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.BuildingAccessStatus;
import com.platform.system.model.dto.BuildingAccessDtos;
import com.platform.system.model.entity.BuildingAccessRequest;
import com.platform.system.model.entity.SysUser;
import com.platform.system.model.entity.SysUserBuilding;
import com.platform.system.service.BuildingAccessService;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建筑访问申请状态机实现。
 *
 * <p>用户侧负责申请、查询和取消；平台管理员侧负责批准或拒绝。审核时使用
 * {@code SELECT ... FOR UPDATE} 锁定申请，防止两个管理员并发重复审批。
 * 批准操作在同一事务中写入审核结果和正式建筑授权。</p>
 */
@Service
@RequiredArgsConstructor
public class BuildingAccessServiceImpl implements BuildingAccessService {
    private final BuildingAccessRequestMapper requestMapper;
    private final SysUserBuildingMapper userBuildingMapper;
    private final SysUserMapper userMapper;
    private final BuildingMapper buildingMapper;
    private final BuildingScopeService scopeService;

    @Override
    public List<Building> available(Long userId, Collection<String> roles) {
        rejectPlatformAdmin(roles);
        // 已授权或已有待审申请的建筑不再出现在“可申请”列表中。
        Set<String> granted = Set.copyOf(userBuildingMapper.selectBuildingIdsByUserId(userId));
        Set<String> pending = requestMapper.selectList(new LambdaQueryWrapper<BuildingAccessRequest>()
                        .eq(BuildingAccessRequest::getUserId, userId)
                        .eq(BuildingAccessRequest::getStatus, BuildingAccessStatus.PENDING.name()))
                .stream().map(BuildingAccessRequest::getBuildingId).collect(java.util.stream.Collectors.toSet());
        return buildingMapper.selectList(new LambdaQueryWrapper<Building>().orderByAsc(Building::getBuildingName))
                .stream().filter(b -> !granted.contains(b.getBuildingId()) && !pending.contains(b.getBuildingId())).toList();
    }

    @Override
    public BuildingAccessDtos.RequestView submit(Long userId, Collection<String> roles, BuildingAccessDtos.SubmitRequest input) {
        rejectPlatformAdmin(roles);
        if (buildingMapper.selectById(input.buildingId()) == null) throw new BusinessException(404, "建筑不存在");
        if (userBuildingMapper.selectCount(new LambdaQueryWrapper<SysUserBuilding>()
                .eq(SysUserBuilding::getUserId, userId).eq(SysUserBuilding::getBuildingId, input.buildingId())) > 0) {
            throw new BusinessException(409, "已经拥有该建筑权限");
        }
        if (requestMapper.selectCount(new LambdaQueryWrapper<BuildingAccessRequest>()
                .eq(BuildingAccessRequest::getUserId, userId).eq(BuildingAccessRequest::getBuildingId, input.buildingId())
                .eq(BuildingAccessRequest::getStatus, BuildingAccessStatus.PENDING.name())) > 0) {
            throw new BusinessException(409, "该建筑已有待审核申请");
        }
        BuildingAccessRequest request = new BuildingAccessRequest();
        request.setUserId(userId); request.setBuildingId(input.buildingId()); request.setReason(input.reason().trim());
        request.setStatus(BuildingAccessStatus.PENDING.name());
        requestMapper.insert(request);
        return view(request);
    }

    @Override public List<BuildingAccessDtos.RequestView> mine(Long userId) {
        return requestMapper.selectList(new LambdaQueryWrapper<BuildingAccessRequest>()
                .eq(BuildingAccessRequest::getUserId, userId).orderByDesc(BuildingAccessRequest::getCreateTime))
                .stream().map(this::view).toList();
    }

    @Override public void cancel(Long userId, Long requestId) {
        BuildingAccessRequest request = requireRequest(requestId);
        if (!userId.equals(request.getUserId())) throw new BusinessException(403, "只能取消自己的申请");
        requirePending(request);
        request.setStatus(BuildingAccessStatus.CANCELLED.name()); requestMapper.updateById(request);
    }

    @Override public List<BuildingAccessDtos.RequestView> listAll(String status) {
        LambdaQueryWrapper<BuildingAccessRequest> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            try { BuildingAccessStatus.valueOf(status.toUpperCase()); }
            catch (IllegalArgumentException e) { throw new BusinessException(400, "非法申请状态"); }
            wrapper.eq(BuildingAccessRequest::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(BuildingAccessRequest::getCreateTime);
        return requestMapper.selectList(wrapper).stream().map(this::view).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long reviewerId, Long requestId, String comment) {
        // 行锁与事务共同保证申请只能从 PENDING 成功流转一次。
        BuildingAccessRequest request = requireLockedRequest(requestId);
        requirePending(request);
        SysUser user = userMapper.selectById(request.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) throw new BusinessException(409, "申请用户不可用");
        if (buildingMapper.selectById(request.getBuildingId()) == null) throw new BusinessException(409, "申请建筑不可用");
        // 授权写入保持幂等：即使关联已由管理员手工添加，也不会重复插入。
        if (userBuildingMapper.selectCount(new LambdaQueryWrapper<SysUserBuilding>()
                .eq(SysUserBuilding::getUserId, request.getUserId()).eq(SysUserBuilding::getBuildingId, request.getBuildingId())) == 0) {
            SysUserBuilding grant = new SysUserBuilding(); grant.setUserId(request.getUserId()); grant.setBuildingId(request.getBuildingId());
            userBuildingMapper.insert(grant);
        }
        finishReview(request, reviewerId, BuildingAccessStatus.APPROVED, comment);
        // 建筑权限不写入 JWT，因此只需清理范围缓存，无需强制用户重新登录。
        scopeService.evict(request.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long reviewerId, Long requestId, String comment) {
        BuildingAccessRequest request = requireLockedRequest(requestId);
        requirePending(request);
        finishReview(request, reviewerId, BuildingAccessStatus.REJECTED, comment);
    }

    private void finishReview(BuildingAccessRequest request, Long reviewerId, BuildingAccessStatus status, String comment) {
        request.setStatus(status.name()); request.setReviewerId(reviewerId); request.setReviewComment(comment);
        request.setReviewTime(new Date()); requestMapper.updateById(request);
    }
    private BuildingAccessRequest requireRequest(Long id) {
        BuildingAccessRequest request = requestMapper.selectById(id);
        if (request == null) throw new BusinessException(404, "建筑访问申请不存在"); return request;
    }
    private BuildingAccessRequest requireLockedRequest(Long id) {
        BuildingAccessRequest request = requestMapper.selectByIdForUpdate(id);
        if (request == null) throw new BusinessException(404, "建筑访问申请不存在"); return request;
    }
    private void requirePending(BuildingAccessRequest request) {
        if (!BuildingAccessStatus.PENDING.name().equals(request.getStatus())) throw new BusinessException(409, "申请已处理，不能重复操作");
    }
    private void rejectPlatformAdmin(Collection<String> roles) {
        if (roles.stream().anyMatch(FormalRole.PLATFORM_ADMIN.name()::equalsIgnoreCase)) throw new BusinessException(403, "平台管理员无需申请建筑权限");
    }
    private BuildingAccessDtos.RequestView view(BuildingAccessRequest request) {
        SysUser user = userMapper.selectAnyById(request.getUserId());
        Building building = buildingMapper.selectById(request.getBuildingId());
        return new BuildingAccessDtos.RequestView(request.getId(), request.getUserId(), user == null ? null : user.getUsername(),
                request.getBuildingId(), building == null ? null : building.getBuildingName(), request.getReason(), request.getStatus(),
                request.getReviewerId(), request.getReviewComment(), request.getReviewTime(), request.getCreateTime());
    }
}
