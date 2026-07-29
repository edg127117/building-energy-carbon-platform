package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.BuildingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Set;

/**
 * 建筑业务实现类
 */
@Slf4j
@Service
public class BuildingServiceImpl extends ServiceImpl<BuildingMapper, Building> implements BuildingService {

    /**
     * 通过建筑主表行锁保护后续“检查重叠任务并创建任务”的事务区间。
     */
    @Override
    public void lockExistingForUpdate(String buildingId) {
        if (baseMapper.selectExistingForUpdate(buildingId) == null) {
            throw new BusinessException(404, "建筑不存在");
        }
    }

    @Override
    public Result<IPage<Building>> list(Integer page, Integer size, String keyword, Set<String> accessibleBuildingIds) {
        Page<Building> pageParam = new Page<>(page, size);
        if (accessibleBuildingIds != null && accessibleBuildingIds.isEmpty()) return Result.success(pageParam);
        LambdaQueryWrapper<Building> wrapper = new LambdaQueryWrapper<>();
        if (accessibleBuildingIds != null) wrapper.in(Building::getBuildingId, accessibleBuildingIds);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Building::getBuildingName, keyword)
                    .or().like(Building::getBuildingCode, keyword));
        }
        wrapper.orderByDesc(Building::getCreateTime);
        IPage<Building> result = this.page(pageParam, wrapper);
        return Result.success(result);
    }

    @Override
    public Result<Building> add(Building building) {
        this.save(building);
        return Result.success(building);
    }

    @Override
    public Result<Building> update(Building building) {
        this.updateById(building);
        return Result.success(building);
    }

    @Override
    public Result<Void> delete(String buildingId) {
        // @TableLogic 自动将 del_flag 从 0 置为 1，不走物理删除
        this.removeById(buildingId);
        return Result.success();
    }
}
