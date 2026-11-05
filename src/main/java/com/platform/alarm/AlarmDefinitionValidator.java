package com.platform.alarm;

import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.platform.alarm.AlarmModels.*;

/** 校验扩展报警规则及其同建筑资产归属；只读MySQL设备、空间和测点元数据。 */
@Component
public class AlarmDefinitionValidator {
    private static final Set<String> OPERATORS = Set.of("GT", "GE", "LT", "LE", "EQ", "NE", "BETWEEN", "OUTSIDE");
    private final JdbcTemplate jdbc;

    public AlarmDefinitionValidator(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(String building, RuleRequest request) {
        if (request == null) throw new BusinessException("规则内容不能为空");
        Advanced advanced = request.advanced();
        if (advanced == null) return;
        if ("QUALITY".equals(request.type())) {
            if (advanced.primaryEquipmentId() != null || advanced.trigger() != null || advanced.recovery() != null
                    || advanced.window() != null || advanced.emission() != null) {
                throw new BusinessException("质量规则扩展参数仅支持采样Timing");
            }
            validateTiming(advanced.timing());
            if (request.conditions() != null && request.conditions().size() > 1
                    && (advanced.timing() == null || advanced.timing().alignmentSeconds() == null)) {
                throw new BusinessException("多测点质量规则必须指定时间对齐容差");
            }
            return;
        }
        if (request.type() == null || !Set.of("CONDITION", "JUMP", "STUCK").contains(request.type())) {
            throw new BusinessException("此规则类型不支持扩展判定参数");
        }
        if (blank(building) || blank(advanced.primaryEquipmentId())) throw new BusinessException("扩展规则必须指定主设备");
        if(advanced.emission()!=null||request.type().equals("CONDITION")&&advanced.window()!=null
                ||Set.of("JUMP","STUCK").contains(request.type())&&(advanced.trigger()!=null||advanced.recovery()!=null)) {
            throw new BusinessException("判定参数与规则类型不一致");
        }

        validateTiming(advanced.timing());
        validateWindow(request.type(), advanced.window());
        List<String> points = new ArrayList<>();
        if (advanced.trigger() != null) validateTree(advanced.trigger(), 1, points);
        if (advanced.recovery() != null) validateTree(advanced.recovery(), 1, points);
        int treeLeafCount = points.size();
        if (request.conditions() != null) for (Condition condition : request.conditions()) {
            if (condition == null || blank(condition.pointId()) || condition.operator() == null || !OPERATORS.contains(condition.operator()) || condition.value() == null
                    || Set.of("BETWEEN", "OUTSIDE").contains(condition.operator()) != (condition.upperValue() != null)
                    || condition.upperValue() != null && condition.upperValue().compareTo(condition.value()) < 0) {
                throw new BusinessException("窗口目标测点条件无效");
            }
            points.add(condition.pointId());
        }
        int sidecarLeafCount = request.conditions() == null ? 0
                : advanced.trigger() == null || Set.of("JUMP", "STUCK").contains(request.type()) ? request.conditions().size() : 0;
        if (treeLeafCount + sidecarLeafCount > 20) throw new BusinessException("条件树最多包含20个测点条件");
        if (request.type().equals("CONDITION") && advanced.trigger() == null &&
                (request.conditions() == null || request.conditions().isEmpty())) {
            throw new BusinessException("条件规则必须配置触发条件");
        }
        if (request.type().equals("JUMP") && advanced.window() == null) {
            throw new BusinessException("突变规则必须配置判断窗口");
        }
        if (Set.of("JUMP", "STUCK").contains(request.type())
                && (request.conditions() == null || request.conditions().isEmpty())) {
            throw new BusinessException("窗口规则必须配置目标测点");
        }
        if(Set.of("JUMP","STUCK").contains(request.type())&&request.conditions().size()!=1) throw new BusinessException("窗口规则仅可绑定一个目标测点");
        if (request.type().equals("STUCK") && (advanced.window() == null || blank(advanced.window().runningPointId()))) {
            throw new BusinessException("卡值规则必须配置运行状态测点");
        }
        Equipment primary = equipment(building, advanced.primaryEquipmentId());
        if (primary == null) throw new BusinessException("扩展规则主设备不存在或不属于当前建筑");
        if (!blank(request.spaceId()) && !Objects.equals(request.spaceId(), primary.spaceId())) {
            throw new BusinessException("主设备不属于规则空间范围");
        }
        if (!blank(request.deviceCategory()) && !Objects.equals(request.deviceCategory(), primary.category())) {
            throw new BusinessException("主设备不属于规则设备类别范围");
        }
        for (String pointId : points) validatePoint(building, pointId);
        if (advanced.window() != null && !blank(advanced.window().runningPointId())) {
            validatePoint(building, advanced.window().runningPointId());
            String kind=jdbc.queryForObject("SELECT data_type FROM biz_data_point WHERE building_id=? AND point_id=? AND status='ONLINE' AND del_flag=0",String.class,building,advanced.window().runningPointId());
            if(!"DIGITAL".equals(kind)) throw new BusinessException("运行前置测点必须是DIGITAL状态点");
        }
    }

    private void validateTree(ConditionNode node, int depth, List<String> points) {
        if (node == null || depth > 3) throw new BusinessException("条件树最多三层");
        boolean leaf = node.condition() != null;
        boolean branch = node.children() != null && !node.children().isEmpty();
        if (leaf == branch) throw new BusinessException("条件树节点必须是单一条件或逻辑分支");
        if (leaf) {
            Condition condition = node.condition();
            if (!blank(node.logic())) throw new BusinessException("条件叶子不能设置逻辑运算符");
            if (blank(condition.pointId()) || condition.operator() == null || !OPERATORS.contains(condition.operator()) || condition.value() == null
                    || Set.of("BETWEEN", "OUTSIDE").contains(condition.operator()) != (condition.upperValue() != null)
                    || condition.upperValue() != null && condition.upperValue().compareTo(condition.value()) < 0) {
                throw new BusinessException("条件叶子配置无效");
            }
            points.add(condition.pointId());
            if (points.size() > 20) throw new BusinessException("条件树最多包含20个测点条件");
            return;
        }
        if (node.logic() == null || !Set.of("ALL", "ANY").contains(node.logic())) {
            throw new BusinessException("条件分支逻辑或子节点数量无效");
        }
        for (ConditionNode child : node.children()) validateTree(child, depth + 1, points);
    }

    private void validateTiming(Timing timing) {
        if (timing == null) return;
        if (timing.expectedIntervalSeconds() != null && (timing.expectedIntervalSeconds() < 1 || timing.expectedIntervalSeconds() > 86_400)
                || timing.allowedDelaySeconds() != null && (timing.allowedDelaySeconds() < 0 || timing.allowedDelaySeconds() > 604_800)
                || timing.alignmentSeconds() != null && (timing.alignmentSeconds() < 1
                    || timing.expectedIntervalSeconds() != null && timing.alignmentSeconds() > timing.expectedIntervalSeconds())
                || timing.staleAfterSeconds() != null && (timing.staleAfterSeconds() < 1 || timing.staleAfterSeconds() > 604_800)) {
            throw new BusinessException("判定时间参数无效");
        }
    }

    private void validateWindow(String type, Window window) {
        if (window == null) return;
        if (window.minutes() == null || window.minutes() < 1 || window.minutes() > 10_080
                || window.tolerance() == null || window.tolerance().signum() < 0
                || window.changeMode() == null || !Set.of("ABS", "PERCENT").contains(window.changeMode())) {
            throw new BusinessException("判定窗口或容差无效");
        }
        if (type.equals("STUCK") && (blank(window.runningPointId()) || window.runningValue() == null)) {
            throw new BusinessException("卡值规则必须指定运行状态测点和运行值");
        }
    }

    private void validatePoint(String building, String pointId) {
        List<Equipment> equipment = jdbc.query("""
                SELECT e.equip_id,e.space_id,e.equip_category FROM biz_data_point p JOIN biz_equipment e
                  ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0
                WHERE p.point_id=? AND p.building_id=? AND p.status='ONLINE' AND p.del_flag=0
                """, (rs, row) -> new Equipment(rs.getString(1), rs.getString(2), rs.getString(3)), pointId, building);
        if (equipment.size() != 1) throw new BusinessException("规则测点必须在线且属于当前建筑");
    }

    private Equipment equipment(String building, String equipmentId) {
        List<Equipment> rows = jdbc.query("""
                SELECT equip_id,space_id,equip_category FROM biz_equipment
                WHERE building_id=? AND equip_id=? AND del_flag=0
                """, (rs, row) -> new Equipment(rs.getString(1), rs.getString(2), rs.getString(3)), building, equipmentId);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record Equipment(String id, String spaceId, String category) { }
}
