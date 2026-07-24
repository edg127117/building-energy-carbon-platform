package com.platform.hvac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizPointNamingRuleMapper;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizPointNamingRule;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.PointCodeNamingValidator;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据测点业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizDataPointServiceImpl extends ServiceImpl<BizDataPointMapper, BizDataPoint> implements BizDataPointService {

    private final MySqlDataPointConfigProvider configProvider;
    private final BizEquipmentMapper equipmentMapper;
    private final BizSystemGroupMapper systemGroupMapper;
    private final BizPointNamingRuleMapper namingRuleMapper;
    private final PointCodeNamingValidator namingValidator;

    @Override
    public Result<List<BizDataPoint>> listByEquip(String equipId) {
        LambdaQueryWrapper<BizDataPoint> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizDataPoint::getEquipId, equipId);
        List<BizDataPoint> list = this.list(wrapper);
        return Result.success(list);
    }

    @Override
    public Result<List<BizDataPoint>> listByBuilding(String buildingId) {
        LambdaQueryWrapper<BizDataPoint> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizDataPoint::getBuildingId, buildingId);
        List<BizDataPoint> list = this.list(wrapper);
        return Result.success(list);
    }

    @Override
    public Result<BizDataPoint> add(BizDataPoint point) {
        point.setPointId(null);
        validateRelationships(point);
        this.save(point);
        configProvider.refreshAll();
        return Result.success(point);
    }

    @Override
    public Result<BizDataPoint> update(BizDataPoint point) {
        BizDataPoint existing = this.getById(point.getPointId());
        if (existing == null) throw new BusinessException(404, "测点不存在");
        // 标准身份和建筑归属只能通过受控迁移调整，普通编辑保持不变。
        point.setPointCode(existing.getPointCode());
        point.setBuildingId(existing.getBuildingId());
        point.setSystemGroupId(existing.getSystemGroupId());
        point.setEquipId(existing.getEquipId());
        point.setNamingRuleId(existing.getNamingRuleId());
        point.setFamilyCode(existing.getFamilyCode());
        point.setComponentCode(existing.getComponentCode());
        point.setSuffixCode(existing.getSuffixCode());
        point.setDataType(existing.getDataType());
        validateRelationships(point);
        this.updateById(point);
        configProvider.refreshAll();
        return Result.success(point);
    }

    @Override
    public Result<Void> delete(String pointId) {
        this.removeById(pointId);
        configProvider.refreshAll();
        return Result.success();
    }

    private void validateRelationships(BizDataPoint point) {
        BizPointNamingRule rule = namingRuleMapper.selectById(point.getNamingRuleId());
        if (rule == null || !Integer.valueOf(1).equals(rule.getStatus())) {
            throw new BusinessException(400, "测点命名规则不存在或已停用");
        }
        if (!rule.getFamilyCode().equals(point.getFamilyCode())
                || !rule.getComponentCode().equals(point.getComponentCode())
                || !namingValidator.matches(rule, point.getPointCode())) {
            throw new BusinessException(400, "标准测点编码不符合所选命名规则");
        }
        boolean environment = "ENV".equalsIgnoreCase(rule.getComponentCode());
        if (!environment && (point.getSuffixCode() == null
                || !point.getPointCode().endsWith("_" + point.getSuffixCode()))) {
            throw new BusinessException(400, "测点后缀与标准测点编码不一致");
        }
        if (environment && point.getEquipId() != null) {
            throw new BusinessException(400, "建筑环境测点不能绑定设备");
        }
        if (!environment && (point.getEquipId() == null || point.getSystemGroupId() == null)) {
            throw new BusinessException(400, "设备测点必须绑定设备和系统分组");
        }
        if (point.getSystemGroupId() != null) {
            BizSystemGroup group = systemGroupMapper.selectById(point.getSystemGroupId());
            if (group == null || !point.getBuildingId().equals(group.getBuildingId())) {
                throw new BusinessException(400, "测点与系统分组不属于同一建筑");
            }
        }
        if (point.getEquipId() != null) {
            BizEquipment equipment = equipmentMapper.selectById(point.getEquipId());
            if (equipment == null || !point.getBuildingId().equals(equipment.getBuildingId())) {
                throw new BusinessException(400, "测点与设备不属于同一建筑");
            }
            if (point.getSystemGroupId() != null
                    && !point.getSystemGroupId().equals(equipment.getSystemGroupId())) {
                throw new BusinessException(400, "测点与设备不属于同一系统分组");
            }
        }
    }
}
