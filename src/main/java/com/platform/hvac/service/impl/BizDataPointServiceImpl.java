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
import com.platform.relation.RelationGovernanceGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 测点档案的业务写入边界，负责校验标准身份、设备归属和计算单位契约。
 *
 * <p>通用代码生成器只能提供单表 CRUD，不能表达在线计算模拟量必须配置单位等
 * HVAC 业务规则，因此生成结果只能选择性合并，不能整体覆盖本实现。</p>
 *
 * <p>新增、更新和删除成功后会刷新 {@link MySqlDataPointConfigProvider} 的运行时快照，
 * 使 MQTT 身份解析与质量校验看到最新配置；本类只维护 MySQL 档案，不改写既有
 * TDengine 时序事实。</p>
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
    private final RelationGovernanceGuard relationGuard;

    /** 按设备 ID 从 MySQL 返回测点档案；设备存在性和建筑权限由 Controller 校验。 */
    @Override
    public Result<List<BizDataPoint>> listByEquip(String equipId) {
        LambdaQueryWrapper<BizDataPoint> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizDataPoint::getEquipId, equipId);
        List<BizDataPoint> list = this.list(wrapper);
        return Result.success(list);
    }

    /** 按建筑 ID 从 MySQL 返回测点档案；建筑权限由 Controller 校验。 */
    @Override
    public Result<List<BizDataPoint>> listByBuilding(String buildingId) {
        LambdaQueryWrapper<BizDataPoint> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizDataPoint::getBuildingId, buildingId);
        List<BizDataPoint> list = this.list(wrapper);
        return Result.success(list);
    }

    /**
     * 新增标准测点并刷新运行时配置快照。
     *
     * <p>先清除客户端内部 ID，再校验命名规则、建筑关系和在线计算模拟量单位；
     * 任一校验失败都不会写库或刷新快照。</p>
     */
    @Override
    public Result<BizDataPoint> add(BizDataPoint point) {
        relationGuard.requireLegacyForStructuralCreate(point.getBuildingId());
        point.setPointId(null);
        validateRelationships(point);
        validateCalculationPointUnit(point);
        this.save(point);
        configProvider.refreshAll();
        return Result.success(point);
    }

    /**
     * 更新测点可编辑字段并刷新运行时配置快照。
     *
     * <p>先读取旧档案形成数据库最终状态，再复用新增校验；记录不存在返回 404，
     * 标准身份和归属字段不能通过普通更新改变。</p>
     */
    @Override
    public Result<BizDataPoint> update(BizDataPoint point) {
        BizDataPoint existing = this.getById(point.getPointId());
        if (existing == null) {
            throw new BusinessException(404, "测点不存在");
        }
        // null 在旧的部分更新契约中表示“未提供”，不会清空数据库关系字段。
        if (point.getEquipId() != null) {
            relationGuard.rejectChangedProjection(
                    existing.getBuildingId(), existing.getEquipId(), point.getEquipId());
        }
        if (point.getSystemGroupId() != null) {
            relationGuard.rejectChangedProjection(
                    existing.getBuildingId(), existing.getSystemGroupId(), point.getSystemGroupId());
        }
        prepareFinalStateForUpdate(existing, point);
        validateRelationships(point);
        validateCalculationPointUnit(point);
        this.updateById(point);
        configProvider.refreshAll();
        return Result.success(point);
    }

    /**
     * 锁定普通编辑不能改变的标准身份，并补全单位规则依赖的部分更新字段。
     *
     * <p>MyBatis-Plus 默认忽略值为 {@code null} 的更新字段；先用旧值补全
     * {@code status}、{@code isForCalc} 和 {@code unit}，单位校验才能针对
     * 数据库真正会得到的最终状态，避免部分更新绕过或误触发规则。</p>
     */
    private void prepareFinalStateForUpdate(BizDataPoint existing, BizDataPoint point) {
        point.setPointCode(existing.getPointCode());
        point.setBuildingId(existing.getBuildingId());
        point.setSystemGroupId(existing.getSystemGroupId());
        point.setEquipId(existing.getEquipId());
        point.setNamingRuleId(existing.getNamingRuleId());
        point.setFamilyCode(existing.getFamilyCode());
        point.setComponentCode(existing.getComponentCode());
        point.setSuffixCode(existing.getSuffixCode());
        point.setDataType(existing.getDataType());
        if (point.getStatus() == null) {
            point.setStatus(existing.getStatus());
        }
        if (point.getIsForCalc() == null) {
            point.setIsForCalc(existing.getIsForCalc());
        }
        if (point.getUnit() == null) {
            point.setUnit(existing.getUnit());
        }
    }

    /** 逻辑删除 MySQL 测点后刷新配置快照，不删除 TDengine 中的历史分钟数据。 */
    @Override
    public Result<Void> delete(String pointId) {
        relationGuard.requireDeletable("POINT", pointId);
        this.removeById(pointId);
        configProvider.refreshAll();
        return Result.success();
    }

    /**
     * 阻止缺少单位的在线计算模拟量进入 MySQL 和测点配置快照。
     */
    private void validateCalculationPointUnit(BizDataPoint point) {
        boolean onlineCalculationAnalog =
                "ONLINE".equalsIgnoreCase(point.getStatus())
                        && "ANALOG".equalsIgnoreCase(point.getDataType())
                        && Integer.valueOf(1).equals(point.getIsForCalc());
        if (onlineCalculationAnalog
                && (point.getUnit() == null || point.getUnit().isBlank())) {
            // 写库后再发现缺少单位，会让公式和质量链路读取到含义不完整的计算输入。
            throw new BusinessException(400, "参与计算的在线模拟量必须配置单位");
        }
    }

    /**
     * 校验测点标准命名及建筑、系统分组、设备归属的一致性。
     *
     * <p>环境测点不得绑定设备；设备测点必须同时绑定设备和系统分组，且三者属于
     * 同一建筑和系统。校验失败返回 400，避免错误身份进入 MQTT、质量和公式链路。</p>
     */
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
