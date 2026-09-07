package com.platform.energy.efficiency;

import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BizEquipmentService;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.util.*;
import static com.platform.energy.efficiency.EerpContracts.*;
import static com.platform.energy.efficiency.EerpSupport.*;

/** 校验审核配置的精确覆盖和历史关系；水路及制备端含义保留专业审核依据。 */
@Component
@RequiredArgsConstructor
public class EerpConfigurationValidator {
    private final RelationGovernanceService relations;
    private final BizDataPointService points;
    private final BizEquipmentService equipmentService;
    private final EerpLimits limits;
    private final EerpSupport codec;

    public String validate(long user, Collection<String> roles, Configuration c) {
        require(c != null, "INVALID_REQUEST", "配置不能为空");
        text(c.buildingId(), 32, "建筑"); text(c.stationId(), 32, "冷站");
        text(c.boundaryId(), 32, "计量边界"); text(c.relationVersionId(), 32, "关系版本");
        text(c.timezoneVersion(), 64, "时区版本");
        try { ZoneId.of(c.timezoneId()); } catch (RuntimeException e) { throw error(400, "INVALID_TIMEZONE", "时区无效"); }
        millis(c.effectiveFrom()); millis(c.effectiveTo());
        require(c.effectiveFrom().isBefore(c.effectiveTo()), "INVALID_INTERVAL", "生效区间无效");
        text(c.professionalEvidence(), 500, "专业映射依据");
        text(c.evaluationRuleVersion(), 64, "研发评价版本"); text(c.evaluationReference(), 500, "附件评价依据");
        require(c.equipment() != null && !c.equipment().isEmpty() && c.equipment().size() <= 64,
                "CAPACITY_EXCEEDED", "设备配置数量无效");
        Set<String> all = new HashSet<>(), chillers = new HashSet<>();
        Set<EquipmentRole> equipmentRoles = new HashSet<>();
        for (Equipment e : c.equipment()) {
            require(e != null && e.role() != null, "INVALID_REQUEST", "设备角色缺失"); text(e.deviceId(), 32, "设备");
            require(all.add(e.deviceId()), "COVERAGE_OVERLAP", "设备重复"); equipmentRoles.add(e.role());
            if (e.role() == EquipmentRole.CHILLER) chillers.add(e.deviceId());
        }
        require(equipmentRoles.equals(EnumSet.allOf(EquipmentRole.class)), "COVERAGE_INCOMPLETE", "必须覆盖冷机、冷水泵、冷却水泵和塔风机");
        Set<String> actualEquipment = new HashSet<>();
        for (int page = 1; ; page++) {
            var response = equipmentService.list(page,100,c.buildingId(),null,null,Set.of(c.buildingId()));
            require(response != null && response.getData() != null, "ASSET_UNAVAILABLE", "设备台账不可用");
            var inventory = response.getData();
            require(inventory.getTotal() <= 5000 && inventory.getSize() > 0, "CAPACITY_EXCEEDED", "设备台账超过读取预算");
            for (var device : inventory.getRecords()) if (c.stationId().equals(device.getSystemGroupId())
                    && device.getEquipCategory()!=null && Set.of("CHILLER","PUMP","TOWER").contains(device.getEquipCategory())) {
                require(c.buildingId().equals(device.getBuildingId()), "ASSET_MISMATCH", "设备台账返回跨建筑对象");
                actualEquipment.add(device.getEquipId());
                Equipment selected = c.equipment().stream().filter(e -> e.deviceId().equals(device.getEquipId())).findFirst().orElse(null);
                require(selected != null, "COVERAGE_INCOMPLETE", "配置遗漏冷站台账中的必需设备");
                String expectedCategory = switch(selected.role()) { case CHILLER -> "CHILLER"; case TOWER_FAN -> "TOWER"; default -> "PUMP"; };
                require(expectedCategory.equals(device.getEquipCategory())
                        && (selected.role()!=EquipmentRole.CHILLER || "WCR".equals(device.getTypeCode())),
                        "EQUIPMENT_TYPE_MISMATCH", "配置角色与电驱动水冷冷站设备类型不符");
            }
            if ((long)page*inventory.getSize()>=inventory.getTotal()) break;
        }
        require(actualEquipment.equals(all),"COVERAGE_MISMATCH","配置设备必须与冷站台账范围一致");
        require(c.coolingSources() != null && !c.coolingSources().isEmpty()
                && c.electricitySources() != null && !c.electricitySources().isEmpty(), "COVERAGE_INCOMPLETE", "冷量和电量来源不能为空");
        Set<String> coolingCoverage = new HashSet<>(), electricityCoverage = new HashSet<>();
        Set<String> sourceIds = new HashSet<>(), pointIds = new HashSet<>();
        Map<String, Set<String>> selectedMeters = new LinkedHashMap<>();
        Map<String, Set<String>> coolingPoints = new LinkedHashMap<>();
        for (CoolingSource s : c.coolingSources()) {
            require(s != null && s.mode() != null, "INVALID_REQUEST", "冷量来源无效"); unique(sourceIds, s.sourceId());
            cover(coolingCoverage, s.coveredChillerIds(), chillers);
            text(s.waterCircuitId(), 64, "水路身份"); text(s.mappingEvidence(), 500, "水路映射依据");
            require("PRODUCTION_OUTLET".equals(s.meteringPosition()), "METERING_POSITION", "仅支持制备端计量");
            if (s.mode() == SourceMode.METER_CUMULATIVE) {
                require(s.flow() == null && s.supply() == null && s.returnTemperature() == null && s.rules() == null,
                        "SOURCE_CONFLICT", "累计表来源不得混入计算冷量输入");
                point(c, s.meter(), "ACCUMULATE", Set.of("kWh"), pointIds);
                coolingPoints.put(s.meter().pointId(), s.coveredChillerIds());
            } else {
                require(s.meter() == null && s.rules() != null, "RULE_MISSING", "计算冷量须明确物性和时间规则");
                require(s.flowMeterSide()!=null && Set.of("SUPPLY", "RETURN").contains(s.flowMeterSide()), "METERING_POSITION", "须明确流量计安装侧");
                point(c, s.flow(), "ANALOG", Set.of("m3/h", "m³/h"), pointIds);
                point(c, s.supply(), "ANALOG", Set.of("degC", "℃"), pointIds);
                point(c, s.returnTemperature(), "ANALOG", Set.of("degC", "℃"), pointIds);
                for (Point p : List.of(s.flow(), s.supply(), s.returnTemperature())) coolingPoints.put(p.pointId(), s.coveredChillerIds());
                require(CoolingComputationCore.validateRules(s.rules()).isEmpty(), "RULE_INVALID", "物性或时间处理规则无效");
                require(s.rules().maxHoldMillis()<=86400000 && s.rules().maxGapMillis()<=86400000
                        && s.rules().maxSkewMillis()<=86400000,"CAPACITY_EXCEEDED","单路保持与积分间隔不得超过一天");
            }
        }
        for (ElectricitySource s : c.electricitySources()) {
            require(s != null, "INVALID_REQUEST", "电量来源无效"); unique(sourceIds, s.sourceId());
            text(s.ownershipEvidence(), 500, "独占覆盖依据"); cover(electricityCoverage, s.coveredDeviceIds(), all);
            point(c, s.meter(), "ACCUMULATE", Set.of("kWh"), pointIds);
            selectedMeters.put(s.meter().pointId(), s.coveredDeviceIds());
        }
        require(coolingCoverage.equals(chillers) && electricityCoverage.equals(all), "COVERAGE_INCOMPLETE", "冷量或电量缺必需设备");
        require(pointIds.size() <= limits.getMaximumPoints(), "CAPACITY_EXCEEDED", "测点数量超过任务上限");
        VersionDetailView version = relations.versionDetail(user, roles, c.buildingId(), c.relationVersionId());
        require(Set.of("EFFECTIVE", "SUPERSEDED").contains(version.version().status()), "RELATION_UNCONFIRMED", "关系版本未经生效");
        List<MeteringAssignmentView> assignments = new ArrayList<>();
        for (int page = 1; ; page++) {
            var v = relations.historicalMeteringAssignments(user, roles, c.buildingId(), c.relationVersionId(), page, 100);
            require(v.total() <= 5000 && v.size() > 0, "CAPACITY_EXCEEDED", "历史关系超过有界读取预算");
            assignments.addAll(v.items());
            if ((long) page * v.size() >= v.total()) break;
        }
        Set<String> selectedNodes = new HashSet<>();
        for (var entry : selectedMeters.entrySet()) {
            var matched = assignments.stream().filter(a -> entry.getKey().equals(a.pointId())).toList();
            require(!matched.isEmpty(), "RELATION_UNCONFIRMED", "电表缺历史计量分配");
            Set<String> targets = new HashSet<>();
            for (var a : matched) {
                require(c.boundaryId().equals(a.meteringBoundaryId()) && "ASSIGNED".equals(a.allocationStatus())
                        && "CONFIRMED".equals(a.boundaryConfirmationStatus()) && "CONFIRMED".equals(a.meterConfirmationStatus())
                        && "ELECTRICITY".equals(a.energyType()) && "ACTIVE".equals(a.boundaryStatus()),
                        "RELATION_UNCONFIRMED", "电表边界、分配或确认状态不适用");
                if ("SYSTEM".equals(a.targetNodeType()) && c.stationId().equals(a.targetObjectId())) targets.addAll(all);
                else if ("EQUIPMENT".equals(a.targetNodeType())) targets.add(a.targetObjectId());
                else throw error(400, "RELATION_UNCONFIRMED", "电表覆盖对象不属于配置冷站");
                selectedNodes.add(a.meterPointNodeId());
            }
            require(targets.equals(entry.getValue()), "COVERAGE_MISMATCH", "配置覆盖与历史计量覆盖不一致");
        }
        Map<String,String> parents = new HashMap<>();
        for (var s : version.meterStructures()) if (s.parentMeterPointNodeId() != null) parents.put(s.meterPointNodeId(), s.parentMeterPointNodeId());
        for (String node : selectedNodes) {
            Set<String> visited = new HashSet<>(); String parent = parents.get(node);
            while (parent != null) {
                require(visited.add(parent) && !selectedNodes.contains(parent), "COVERAGE_OVERLAP", "总分表重复或计量树存在环");
                parent = parents.get(parent);
            }
        }
        // MEASURES 证明确实测量被覆盖机组/冷站；水路与安装侧仍由该版本专业审核固定。
        var station = node(user, roles, c, "SYSTEM", c.stationId());
        Map<String, String> equipmentNodes = new HashMap<>();
        for (Equipment e : c.equipment()) equipmentNodes.put(e.deviceId(), node(user, roles, c, "EQUIPMENT", e.deviceId()).nodeId());
        List<NodeContextView> measurementEvidence = new ArrayList<>();
        for (var entry : coolingPoints.entrySet()) {
            var p = node(user, roles, c, "POINT", entry.getKey()); measurementEvidence.add(p);
            Set<String> targets = new HashSet<>();
            for (var edge : p.edges()) if ("MEASURES".equals(edge.relationType()) && "CONFIRMED".equals(edge.confirmationStatus())
                    && p.nodeId().equals(edge.sourceNodeId())) targets.add(edge.targetNodeId());
            Set<String> expected = new HashSet<>(); for (String device : entry.getValue()) expected.add(equipmentNodes.get(device));
            require(targets.equals(expected) || (entry.getValue().equals(chillers) && targets.equals(Set.of(station.nodeId()))),
                    "WATER_CIRCUIT_UNCONFIRMED", "冷量测点的历史测量对象与覆盖不一致");
        }
        return codec.json(Map.of("version", version, "assignments", assignments, "measurements", measurementEvidence));
    }
    private NodeContextView node(long user, Collection<String> roles, Configuration c, String type, String id) {
        var view = relations.historicalNodeContext(user, roles, c.buildingId(), c.relationVersionId(), type, id, 1, 1, 100);
        require(!view.metadata().truncated(), "CAPACITY_EXCEEDED", "节点关系证据超过单节点预算"); return view;
    }
    private void point(Configuration c, Point p, String semantics, Set<String> units, Set<String> used) {
        require(p != null, "POINT_MISSING", "必需测点缺失"); text(p.pointId(), 32, "测点");
        require(used.add(p.pointId()), "SOURCE_CONFLICT", "测点被重复计入或配对");
        require(semantics.equals(p.dataType()) && p.unit()!=null && units.contains(p.unit()), "UNIT_MISMATCH", "测点语义或单位不兼容");
        var actual = points.getById(p.pointId());
        require(actual != null && c.buildingId().equals(actual.getBuildingId()) && c.stationId().equals(actual.getSystemGroupId())
                && p.dataType().equals(actual.getDataType()) && p.unit().equals(actual.getUnit())
                && Integer.valueOf(1).equals(actual.getIsForCalc()), "POINT_MISMATCH", "测点档案、系统归属或参与计算状态不符");
    }
    private static void unique(Set<String> ids, String id) { text(id, 32, "来源身份"); require(ids.add(id), "SOURCE_CONFLICT", "来源身份重复"); }
    private static void cover(Set<String> seen, Set<String> current, Set<String> expected) {
        require(current != null && !current.isEmpty() && expected.containsAll(current), "COVERAGE_MISMATCH", "来源覆盖含未知设备");
        for (String id : current) require(seen.add(id), "COVERAGE_OVERLAP", "总量与分量覆盖重复");
    }
}
