package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.service.BizSystemGroupService;
import com.platform.relation.RelationGovernanceGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Set;

/**
 * 建筑内 HVAC 系统分组的 MySQL 业务实现。
 *
 * <p>上游 Controller 负责角色与建筑范围，随后本类完成分页和档案写入；设备与测点
 * 服务会把这里保存的分组作为归属校验依据。更新时锁定原建筑与业务编码，避免普通
 * 档案编辑破坏下游关系；本类不级联维护设备或读取运行数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizSystemGroupServiceImpl extends ServiceImpl<BizSystemGroupMapper, BizSystemGroup> implements BizSystemGroupService {

    private final RelationGovernanceGuard relationGuard;

    /**
     * 在调用方传入的建筑范围内查询 MySQL 系统分组，并支持建筑和名称/编码筛选。
     * 空授权集合直接返回空页，避免构造无范围 SQL。
     */
    @Override
    public Result<IPage<BizSystemGroup>> list(Integer page, Integer size, String buildingId, String keyword, Set<String> accessibleBuildingIds) {
        Page<BizSystemGroup> pageParam = new Page<>(page, size);
        if (accessibleBuildingIds != null && accessibleBuildingIds.isEmpty()) return Result.success(pageParam);
        LambdaQueryWrapper<BizSystemGroup> wrapper = new LambdaQueryWrapper<>();
        if (accessibleBuildingIds != null) wrapper.in(BizSystemGroup::getBuildingId, accessibleBuildingIds);
        if (StringUtils.hasText(buildingId)) {
            wrapper.eq(BizSystemGroup::getBuildingId, buildingId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(BizSystemGroup::getSystemGroupName, keyword)
                    .or().like(BizSystemGroup::getSystemGroupCode, keyword));
        }
        wrapper.orderByDesc(BizSystemGroup::getCreateTime);
        IPage<BizSystemGroup> result = this.page(pageParam, wrapper);
        return Result.success(result);
    }

    /** 清除客户端指定的内部 ID 后保存系统分组，使主键由后端生成。 */
    @Override
    public Result<BizSystemGroup> add(BizSystemGroup group) {
        group.setSystemGroupId(null);
        this.save(group);
        return Result.success(group);
    }

    /**
     * 更新系统分组的可编辑属性。
     *
     * <p>先确认记录存在，否则返回 404；随后恢复原业务编码和建筑 ID，防止更新请求
     * 把已有设备、测点关系迁移到另一建筑。</p>
     */
    @Override
    public Result<BizSystemGroup> update(BizSystemGroup group) {
        BizSystemGroup existing = this.getById(group.getSystemGroupId());
        if (existing == null) {
            throw new com.platform.framework.exception.BusinessException(404, "系统分组不存在");
        }
        group.setSystemGroupCode(existing.getSystemGroupCode());
        group.setBuildingId(existing.getBuildingId());
        this.updateById(group);
        return Result.success(group);
    }

    /** 逻辑删除系统分组；现有设备和测点不会由此方法级联处理。 */
    @Override
    public Result<Void> delete(String systemGroupId) {
        relationGuard.requireDeletable("SYSTEM", systemGroupId);
        this.removeById(systemGroupId);
        return Result.success();
    }
}
