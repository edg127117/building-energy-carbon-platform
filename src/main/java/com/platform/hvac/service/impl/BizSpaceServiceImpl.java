package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.hvac.mapper.BizSpaceMapper;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.service.BizSpaceService;
import com.platform.relation.RelationGovernanceGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 建筑空间档案的 MySQL 查询、树形组装与维护实现。
 *
 * <p>Controller 完成建筑权限校验后调用列表或树形查询；写入时本类校验父空间存在
 * 且属于同一建筑，并保持更新对象的原建筑归属。结果直接返回管理接口，
 * 本类不级联迁移设备，也不检测除“自身为父”之外的更深层循环。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizSpaceServiceImpl extends ServiceImpl<BizSpaceMapper, BizSpace> implements BizSpaceService {

    private final RelationGovernanceGuard relationGuard;

    /** 从 MySQL 读取一个建筑的全部空间，并按楼层值倒序返回。 */
    @Override
    public Result<List<BizSpace>> listByBuilding(String buildingId) {
        LambdaQueryWrapper<BizSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSpace::getBuildingId, buildingId)
                .orderByDesc(BizSpace::getFloorLevel);
        List<BizSpace> list = this.list(wrapper);
        return Result.success(list);
    }

    /** 读取建筑空间后，以父 ID 为 {@code null} 的记录为根递归组装接口树。 */
    @Override
    public Result<List<BizSpace>> tree(String buildingId) {
        LambdaQueryWrapper<BizSpace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSpace::getBuildingId, buildingId)
                .orderByDesc(BizSpace::getFloorLevel);
        List<BizSpace> allSpaces = this.list(wrapper);
        List<BizSpace> tree = buildTree(allSpaces, null);
        return Result.success(tree);
    }

    /** 清除客户端内部 ID、校验父空间归属后新增 MySQL 空间档案。 */
    @Override
    public Result<BizSpace> add(BizSpace space) {
        relationGuard.requireLegacyForStructuralCreate(space.getBuildingId());
        space.setSpaceId(null);
        validateParent(space);
        this.save(space);
        return Result.success(space);
    }

    /** 确认空间存在，保持原建筑 ID 并重新校验父空间后更新档案。 */
    @Override
    public Result<BizSpace> update(BizSpace space) {
        BizSpace existing = this.getById(space.getSpaceId());
        if (existing == null) {
            throw new com.platform.framework.exception.BusinessException(404, "空间不存在");
        }
        relationGuard.rejectChangedProjection(
                existing.getBuildingId(), existing.getParentSpaceId(), space.getParentSpaceId());
        space.setBuildingId(existing.getBuildingId());
        validateParent(space);
        this.updateById(space);
        return Result.success(space);
    }

    /** 逻辑删除空间；不会自动删除子空间或解除设备关系。 */
    @Override
    public Result<Void> delete(String spaceId) {
        relationGuard.requireDeletable("SPACE", spaceId);
        this.removeById(spaceId);
        return Result.success();
    }

    /**
     * 从已加载的同建筑空间集合中递归构建指定父节点的直接与间接子树。
     *
     * <p>方法会把子列表写入实体的非数据库字段 {@code children}，结果最终交给
     * Controller 序列化；它不再访问 MySQL。</p>
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

    /**
     * 校验非空父空间不能是自身，并且必须存在于同一建筑。
     *
     * <p>新增和更新共用该校验，失败返回 400，避免跨建筑空间树和设备位置关系混杂。</p>
     */
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
