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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Set;

/**
 * 设备业务实现类
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

    @Override
    public Result<BizEquipment> add(BizEquipment equipment) {
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

    @Override
    public Result<BizEquipment> update(BizEquipment equipment) {
        BizEquipment existing = this.getById(equipment.getEquipId());
        if (existing == null) throw new BusinessException(404, "设备不存在");
        // 内部身份、建筑、类型和现场编码均为受控字段，普通更新不得改变。
        equipment.setEquipCode(existing.getEquipCode());
        equipment.setBuildingId(existing.getBuildingId());
        equipment.setTypeCode(existing.getTypeCode());
        equipment.setEquipCategory(existing.getEquipCategory());
        validateBuildingRelationships(equipment);
        this.updateById(equipment);
        return Result.success(equipment);
    }

    @Override
    public Result<Void> delete(String equipId) {
        this.removeById(equipId);
        return Result.success();
    }

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
