package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.service.BizSystemGroupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Set;

/**
 * 系统组业务实现类
 */
@Slf4j
@Service
public class BizSystemGroupServiceImpl extends ServiceImpl<BizSystemGroupMapper, BizSystemGroup> implements BizSystemGroupService {

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

    @Override
    public Result<BizSystemGroup> add(BizSystemGroup group) {
        group.setSystemGroupId(null);
        this.save(group);
        return Result.success(group);
    }

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

    @Override
    public Result<Void> delete(String systemGroupId) {
        this.removeById(systemGroupId);
        return Result.success();
    }
}
