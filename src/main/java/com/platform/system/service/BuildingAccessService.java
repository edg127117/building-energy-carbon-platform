package com.platform.system.service;

import com.platform.hvac.model.entity.Building;
import com.platform.system.model.dto.BuildingAccessDtos;

import java.util.Collection;
import java.util.List;

/**
 * 用户申请建筑查看权限的业务服务。
 *
 * <p>普通业务角色负责提交、查看和取消自己的申请；平台管理员通过管理端接口执行审批。
 * 审批通过会创建正式用户建筑授权，审批和授权写入在同一事务中完成。</p>
 */
public interface BuildingAccessService {
    /** 查询当前用户尚未授权且没有待审申请的建筑。 */
    List<Building> available(Long userId, Collection<String> roles);
    /** 提交一条待审核申请，重复授权或重复待审申请会被拒绝。 */
    BuildingAccessDtos.RequestView submit(Long userId, Collection<String> roles, BuildingAccessDtos.SubmitRequest request);
    /** 查询当前用户自己的全部历史申请。 */
    List<BuildingAccessDtos.RequestView> mine(Long userId);
    /** 取消当前用户自己尚未审核的申请。 */
    void cancel(Long userId, Long requestId);
    /** 平台管理员按可选状态查询全部申请。 */
    List<BuildingAccessDtos.RequestView> listAll(String status);
    /** 审批通过并授予建筑权限。 */
    void approve(Long reviewerId, Long requestId, String comment);
    /** 拒绝待审核申请，不产生建筑授权。 */
    void reject(Long reviewerId, Long requestId, String comment);
}
