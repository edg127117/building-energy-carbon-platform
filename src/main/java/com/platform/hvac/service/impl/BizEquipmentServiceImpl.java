package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizEquipmentTypeMapper;
import com.platform.hvac.mapper.BizSpaceMapper;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizEquipmentType;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.service.BizEquipmentService;
import com.platform.hvac.service.EquipmentCodeAllocator;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Set;

/**
 * HVAC 设备台账的 MySQL 查询、关系校验与编号分配实现。
 *
 * <p>Controller 传入授权建筑范围进行查询；新增时依次校验启用设备类型、系统分组
 * 与空间归属，再扫描含逻辑删除记录的历史编码并写入设备。并发唯一键冲突最多重试
 * 三次。更新保持身份和分类字段不变；本类不访问实时测点或发送控制命令。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizEquipmentServiceImpl extends ServiceImpl<BizEquipmentMapper, BizEquipment> implements BizEquipmentService {

    private static final int ALLOCATION_ATTEMPTS = 3;

    private final BizEquipmentTypeMapper equipmentTypeMapper;
    private final BizSystemGroupMapper systemGroupMapper;
    private final BizSpaceMapper spaceMapper;
    private final EquipmentCodeAllocator codeAllocator;
    private final RelationGovernanceGuard relationGuard;

    /**
     * 在调用方传入的建筑范围内分页查询 MySQL 设备，可进一步按建筑、分类和关键字筛选。
     * 空授权集合直接返回空页。
     */
    @Override
    public Result<IPage<BizEquipment>> list(Integer page, Integer size, String buildingId, String equipCategory, String keyword, Set<String> accessibleBuildingIds) {
        Page<BizEquipment> pageParam = new Page<>(page, size);
        if (accessibleBuildingIds != null && accessibleBuildingIds.isEmpty()) return Result.success(pageParam);
        LambdaQueryWrapper<BizEquipment> wrapper = new LambdaQueryWrapper<>();
        if (accessibleBuildingIds != null) wrapper.in(BizEquipment::getBuildingId, accessibleBuildingIds);
        if (StringUtils.hasText(buildingId)) {
            wrapper.eq(BizEquipment::getBuildingId, buildingId);
        }
        if (StringUtils.hasText(equipCategory)) {
            wrapper.eq(BizEquipment::getEquipCategory, equipCategory);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(BizEquipment::getEquipName, keyword)
                    .or().like(BizEquipment::getEquipCode, keyword));
        }
        wrapper.orderByDesc(BizEquipment::getCreateTime);
        IPage<BizEquipment> result = this.page(pageParam, wrapper);
        return Result.success(result);
    }

    /**
     * 新增带后端分配业务编码的设备。
     *
     * <p>设备类型必须启用，系统分组和空间必须与建筑一致；每次尝试都重新查询历史
     * 编码并生成下一个编号。并发插入冲突重试三次后返回 409，避免覆盖或复用编号。</p>
     */
    @Override
    public Result<BizEquipment> add(BizEquipment equipment) {
        relationGuard.requireLegacyForStructuralCreate(equipment.getBuildingId());
        BizEquipmentType type = equipmentTypeMapper.selectById(equipment.getTypeCode());
        if (type == null || !Integer.valueOf(1).equals(type.getStatus())) {
            throw new BusinessException(400, "设备类型不存在或已停用");
        }
        validateBuildingRelationships(equipment);
        for (int attempt = 1; attempt <= ALLOCATION_ATTEMPTS; attempt++) {
            equipment.setEquipId(null);
            equipment.setEquipCode(codeAllocator.next(
                    type.getAssetCodePrefix(),
                    baseMapper.selectHistoricalCodes(
                            equipment.getBuildingId(), equipment.getTypeCode())));
            equipment.setEquipCategory(type.getEquipCategory());
            try {
                this.save(equipment);
                return Result.success(equipment);
            } catch (DuplicateKeyException exception) {
                log.warn("设备编码并发冲突，重新分配: buildingId={}, typeCode={}, attempt={}",
                        equipment.getBuildingId(), equipment.getTypeCode(), attempt);
            }
        }
        throw new BusinessException(409, "设备编号分配冲突，请稍后重试");
    }

    /**
     * 更新设备可编辑档案并保持内部 ID 之外的受控身份不变。
     *
     * <p>记录不存在返回 404；恢复原编码、建筑、类型和分类后再校验系统分组与空间，
     * 防止部分更新把设备移入不一致的关系。</p>
     */
    @Override
    public Result<BizEquipment> update(BizEquipment equipment) {
        BizEquipment existing = this.getById(equipment.getEquipId());
        if (existing == null) throw new BusinessException(404, "设备不存在");
        relationGuard.rejectChangedProjection(
                existing.getBuildingId(), existing.getSpaceId(), equipment.getSpaceId());
        relationGuard.rejectChangedProjection(
                existing.getBuildingId(), existing.getSystemGroupId(), equipment.getSystemGroupId());
        // 内部身份、建筑、类型和现场编码均为受控字段，普通更新不得改变。
        equipment.setEquipCode(existing.getEquipCode());
        equipment.setBuildingId(existing.getBuildingId());
        equipment.setTypeCode(existing.getTypeCode());
        equipment.setEquipCategory(existing.getEquipCategory());
        validateBuildingRelationships(equipment);
        this.updateById(equipment);
        return Result.success(equipment);
    }

    /** 逻辑删除设备台账；测点配置和 TDengine 数据不在此方法处理。 */
    @Override
    public Result<Void> delete(String equipId) {
        relationGuard.requireDeletable("EQUIPMENT", equipId);
        this.removeById(equipId);
        return Result.success();
    }

    /**
     * 校验设备引用的系统分组和空间都存在且属于设备声明的建筑。
     * 新增、更新写库前均执行，任一关系不一致返回 400。
     */
    private void validateBuildingRelationships(BizEquipment equipment) {
        BizSystemGroup group = systemGroupMapper.selectById(equipment.getSystemGroupId());
        if (group == null || !equipment.getBuildingId().equals(group.getBuildingId())) {
            throw new BusinessException(400, "设备与系统分组不属于同一建筑");
        }
        BizSpace space = spaceMapper.selectById(equipment.getSpaceId());
        if (space == null || !equipment.getBuildingId().equals(space.getBuildingId())) {
            throw new BusinessException(400, "设备与空间不属于同一建筑");
        }
    }
}
