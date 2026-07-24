package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.hvac.mapper.BizSpaceMapper;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.service.BizSpaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 空间业务实现类
 */
@Slf4j
@Service
public class BizSpaceServiceImpl extends ServiceImpl<BizSpaceMapper, BizSpace> implements BizSpaceService {

    @Override
    public Result<List<BizSpace>> listByBuilding(String buildingId) {
        LambdaQueryWrapper<BizSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSpace::getBuildingId, buildingId)
                .orderByDesc(BizSpace::getFloorLevel);
        List<BizSpace> list = this.list(wrapper);
        return Result.success(list);
    }

    @Override
    public Result<List<BizSpace>> tree(String buildingId) {
        LambdaQueryWrapper<BizSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSpace::getBuildingId, buildingId)
                .orderByDesc(BizSpace::getFloorLevel);
        List<BizSpace> allSpaces = this.list(wrapper);
        List<BizSpace> tree = buildTree(allSpaces, null);
        return Result.success(tree);
    }

    @Override
    public Result<BizSpace> add(BizSpace space) {
        space.setSpaceId(null);
        validateParent(space);
        this.save(space);
        return Result.success(space);
    }

    @Override
    public Result<BizSpace> update(BizSpace space) {
        BizSpace existing = this.getById(space.getSpaceId());
        if (existing == null) {
            throw new com.platform.framework.exception.BusinessException(404, "空间不存在");
        }
        space.setBuildingId(existing.getBuildingId());
        validateParent(space);
        this.updateById(space);
        return Result.success(space);
    }

    @Override
    public Result<Void> delete(String spaceId) {
        this.removeById(spaceId);
        return Result.success();
    }

    /**
     * 递归构建空间树
     */
    private List<BizSpace> buildTree(List<BizSpace> allSpaces, String parentSpaceId) {
        List<BizSpace> children = new ArrayList<>();
        for (BizSpace space : allSpaces) {
            if (java.util.Objects.equals(space.getParentSpaceId(), parentSpaceId)) {
                space.setChildren(buildTree(allSpaces, space.getSpaceId()));
                children.add(space);
            }
        }
        return children;
    }

    private void validateParent(BizSpace space) {
        if (space.getParentSpaceId() == null) return;
        if (space.getParentSpaceId().equals(space.getSpaceId())) {
            throw new com.platform.framework.exception.BusinessException(400, "空间不能以自身作为父节点");
        }
        BizSpace parent = this.getById(space.getParentSpaceId());
        if (parent == null || !space.getBuildingId().equals(parent.getBuildingId())) {
            throw new com.platform.framework.exception.BusinessException(400, "父空间与当前空间不属于同一建筑");
        }
    }
}
