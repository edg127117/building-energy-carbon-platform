package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.TraceContext;
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
import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

/**
 * 建筑访问申请状态机实现。
 *
 * <p>用户侧提交、查询和取消申请，具备后台审核职责的平台管理员批准或拒绝；两侧都只传当前 JWT 用户 ID，
 * 不接受客户端冒充申请人或审核人。申请和正式授权写入 MySQL，批准后清理用户建筑范围缓存。</p>
 *
 * <p>审批使用 {@code SELECT ... FOR UPDATE} 锁定申请，并在同一事务内校验 PENDING 状态、
 * 写入 {@code sys_user_building} 和审核结果，防止两个管理员并发重复审批。</p>
 */
@Service
@RequiredArgsConstructor
public class BuildingAccessServiceImpl implements BuildingAccessService {
    private final BuildingAccessRequestMapper requestMapper;
    private final SysUserBuildingMapper userBuildingMapper;
    private final SysUserMapper userMapper;
    private final BuildingMapper buildingMapper;
    private final BuildingScopeService scopeService;
    private final BackendDutyService dutyService;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    /**
     * 列出当前用户尚可申请的建筑。
     * 已正式授权或存在 PENDING 申请的建筑会被排除；PLATFORM_ADMIN 拥有全量范围，调用申请
     * 流程属于业务错误并返回 403。
     */
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

    /**
     * 创建一条 PENDING 申请。
     * 在写入前验证建筑存在、用户尚未获授权且没有同建筑待审记录；重复场景返回 409，
     * 历史 REJECTED/CANCELLED 记录不阻止再次申请。
     */
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

    /**
     * 批准待审申请并授予建筑。
     * 行锁保证状态只从 PENDING 流转一次，授权关系采用幂等写入；事务成功后清范围缓存，
     * 因建筑权限不在 JWT 中，申请人无需重新登录即可在下一次请求获得权限。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long reviewerId, Long requestId, String comment) {
        dutyService.requireDuty(reviewerId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
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
        appendReviewEvidence(request, reviewerId, BuildingAccessStatus.APPROVED);
        // 建筑权限不写入 JWT，因此只需清理范围缓存，无需强制用户重新登录。
        scopeService.evict(request.getUserId());
    }

    /** 拒绝一条已锁定的 PENDING 申请，只记录审核状态和意见，不改变用户现有建筑授权。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long reviewerId, Long requestId, String comment) {
        dutyService.requireDuty(reviewerId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        BuildingAccessRequest request = requireLockedRequest(requestId);
        requirePending(request);
        finishReview(request, reviewerId, BuildingAccessStatus.REJECTED, comment);
        appendReviewEvidence(request, reviewerId, BuildingAccessStatus.REJECTED);
    }

    /** 公共层只保存跨模块检索索引，完整申请原因和审核意见仍以建筑访问申请记录为准。 */
    private void appendReviewEvidence(BuildingAccessRequest request, Long reviewerId, BuildingAccessStatus status) {
        auditWriter.append(new AuditEvidence("BUILDING_ACCESS", request.getBuildingId(), "USER", reviewerId,
                status == BuildingAccessStatus.APPROVED ? "APPROVE_BUILDING_ACCESS" : "REJECT_BUILDING_ACCESS",
                "USER_BUILDING_SCOPE", request.getUserId() + ":" + request.getBuildingId(), null,
                request.getId().toString(), "status=PENDING", "status=" + status.name(),
                status == BuildingAccessStatus.APPROVED ? "SUCCESS" : "REJECTED",
                status == BuildingAccessStatus.REJECTED ? "BUILDING_ACCESS_REJECTED" : null,
                TraceContext.current(), LocalDateTime.now(), auditProperties.getEnvironmentMode(), false));
    }

    /** 统一写入审核状态、操作者、意见和时间，保证批准与拒绝使用相同审计字段。 */
    private void finishReview(BuildingAccessRequest request, Long reviewerId, BuildingAccessStatus status, String comment) {
        request.setStatus(status.name()); request.setReviewerId(reviewerId); request.setReviewComment(comment);
        request.setReviewTime(new Date()); requestMapper.updateById(request);
    }
    private BuildingAccessRequest requireRequest(Long id) {
        BuildingAccessRequest request = requestMapper.selectById(id);
        if (request == null) throw new BusinessException(404, "建筑访问申请不存在"); return request;
    }
    /** 在审批事务中用行锁读取申请，避免并发审核同时通过 PENDING 校验。 */
    private BuildingAccessRequest requireLockedRequest(Long id) {
        BuildingAccessRequest request = requestMapper.selectByIdForUpdate(id);
        if (request == null) throw new BusinessException(404, "建筑访问申请不存在"); return request;
    }
    /** 只允许 PENDING 进入取消、批准或拒绝分支，重复处理统一返回 409。 */
    private void requirePending(BuildingAccessRequest request) {
        if (!BuildingAccessStatus.PENDING.name().equals(request.getStatus())) throw new BusinessException(409, "申请已处理，不能重复操作");
    }
    private void rejectPlatformAdmin(Collection<String> roles) {
        if (roles.stream().anyMatch(FormalRole.PLATFORM_ADMIN.name()::equalsIgnoreCase)) throw new BusinessException(403, "平台管理员无需申请建筑权限");
    }
    /**
     * 把申请记录补充为接口视图；即使关联用户已删除或建筑已不存在，也保留历史申请并返回空名称。
     */
    private BuildingAccessDtos.RequestView view(BuildingAccessRequest request) {
        SysUser user = userMapper.selectAnyById(request.getUserId());
        Building building = buildingMapper.selectById(request.getBuildingId());
        return new BuildingAccessDtos.RequestView(request.getId(), request.getUserId(), user == null ? null : user.getUsername(),
                request.getBuildingId(), building == null ? null : building.getBuildingName(), request.getReason(), request.getStatus(),
                request.getReviewerId(), request.getReviewComment(), request.getReviewTime(), request.getCreateTime());
    }
}
