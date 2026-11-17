package com.platform.hvac.asset.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.hvac.asset.api.AssetManagementContracts.EquipmentListItemView;
import com.platform.hvac.asset.api.AssetManagementContracts.PointSummary;
import com.platform.hvac.asset.service.AssetErrors;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.LatestRawReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import static com.platform.hvac.asset.ledger.LedgerContracts.*;
import static com.platform.iot.qualityusage.QualityUsageModels.POINT_REALTIME_VIEW;

/**
 * 组合正式 MySQL 档案、已存大金状态及 TDengine 最近原始值的只读台账。
 * 元数据统计在分页前执行；时序值各自保留事件时间，不声称跨库强一致快照。
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerService {
    private static final Set<String> GROUPS = Set.of("POWER", "HVAC", "RENEWABLE", "WATER", "ENVIRONMENT", "UNCLASSIFIED");
    private static final long DAIKIN_STALE_MS = 300_000;
    private static final String GROUP_SQL = "COALESCE(o.professional_group,r.professional_group,'UNCLASSIFIED')";
    private static final String POINT_COUNT = "(SELECT COUNT(*) FROM biz_data_point p WHERE p.equip_id=e.equip_id AND p.building_id=e.building_id AND p.del_flag=0)";
    private static final String FROM = """
             FROM biz_equipment e
             LEFT JOIN biz_ledger_type_rule r ON r.type_code=e.type_code
             LEFT JOIN biz_ledger_equipment_classification o ON o.equipment_id=e.equip_id
             LEFT JOIN building b ON b.building_id=e.building_id
             LEFT JOIN biz_space sp ON sp.space_id=e.space_id AND sp.building_id=e.building_id
             LEFT JOIN biz_system_group sg ON sg.system_group_id=e.system_group_id AND sg.building_id=e.building_id
             LEFT JOIN biz_device_product pr ON pr.product_id=e.product_id
            """;
    private final JdbcTemplate jdbc;
    private final HvacRawEventRepository rawEvents;
    private final QualityUsagePolicyResolver quality;
    private final ObjectMapper json;
    private final com.platform.iot.communication.DeviceCommunicationService communication;

    public LedgerPage query(int page, int size, String buildingId, String spaceId, String systemGroupId,
                            String typeCode, String keyword, String professionalGroup, Set<String> roles) {
        admin(roles);
        if (page < 1 || size < 1 || size > 100) throw invalid("分页范围无效");
        String group = StringUtils.hasText(professionalGroup) ? professionalGroup : "ALL";
        if (!"ALL".equals(group)) validGroup(group);
        Map<String, Object> params = new HashMap<>();
        long now=System.currentTimeMillis();
        params.put("commNow",now);
        String from=FROM+" LEFT JOIN ("+communication.equipmentProjectionSql()+") cm ON cm.equip_id=e.equip_id AND cm.building_id=e.building_id ";
        StringBuilder where = new StringBuilder(" WHERE e.del_flag=0");
        filter(where, params, "buildingId", "e.building_id", buildingId);
        filter(where, params, "spaceId", "e.space_id", spaceId);
        filter(where, params, "systemGroupId", "e.system_group_id", systemGroupId);
        filter(where, params, "typeCode", "e.type_code", typeCode);
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (e.equip_name LIKE :keyword OR e.equip_code LIKE :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        var named = new NamedParameterJdbcTemplate(jdbc);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String key : List.of("ALL", "POWER", "HVAC", "RENEWABLE", "WATER", "ENVIRONMENT", "UNCLASSIFIED")) counts.put(key, 0L);
        named.query("SELECT " + GROUP_SQL + " AS professional_group,COUNT(*) AS n" + from + where
                + " GROUP BY " + GROUP_SQL, params, rs -> {
            counts.put(rs.getString("professional_group"), rs.getLong("n"));
            counts.put("ALL", counts.get("ALL") + rs.getLong("n"));
        });
        if (!"ALL".equals(group)) {
            where.append(" AND ").append(GROUP_SQL).append("=:professionalGroup");
            params.put("professionalGroup", group);
        }
        long total = counts.get(group);
        Long pointTotal = named.queryForObject("SELECT COALESCE(SUM(" + POINT_COUNT + "),0)" + from + where, params, Long.class);
        Map<String,Object> summary=named.queryForMap("SELECT COALESCE(SUM(CASE WHEN cm.communication_value='ONLINE' THEN 1 ELSE 0 END),0) AS online_count,COALESCE(SUM(CASE WHEN cm.communication_value='OFFLINE' THEN 1 ELSE 0 END),0) AS offline_count,COALESCE(SUM(CASE WHEN cm.communication_value='UNKNOWN' THEN 1 ELSE 0 END),0) AS unknown_count,COALESCE(SUM(CASE WHEN cm.communication_value IS NULL THEN 1 ELSE 0 END),0) AS not_monitored,COALESCE(SUM(CASE WHEN cm.communication_value='ONLINE' AND cm.reason='REPORT_DELAYED' THEN 1 ELSE 0 END),0) AS delayed_count"+from+where,params);
        params.put("limit", size);
        params.put("offset", ((long) page - 1) * size);
        String select = """
            SELECT e.*,b.building_name,sp.space_name,sg.system_group_name,pr.product_name,pr.expected_profile_code,
              r.professional_group AS type_professional_group,r.operation_applicable,r.point_component,r.point_suffix,
              cm.communication_value AS comm_value,cm.reason AS comm_reason,cm.observed_at AS comm_observed_at,cm.source_key AS comm_source,r.revision AS rule_revision,o.professional_group AS override_group,o.revision AS classification_revision,
            """ + GROUP_SQL + " AS professional_group," + POINT_COUNT + " AS point_count," + """
              (SELECT COUNT(*) FROM biz_device_identity i WHERE i.equip_id=e.equip_id AND i.building_id=e.building_id) AS identity_count,
              (SELECT COUNT(*) FROM biz_device_identity i WHERE i.equip_id=e.equip_id AND i.building_id=e.building_id AND i.status=1) AS active_identity_count,
              (SELECT COUNT(*) FROM biz_product_point_template pt WHERE pt.product_id=e.product_id AND pt.status=1 AND pt.required_flag=1) AS required_count,
              (SELECT COUNT(*) FROM biz_product_point_template pt WHERE pt.product_id=e.product_id AND pt.status=1 AND pt.required_flag=1
                AND EXISTS (SELECT 1 FROM biz_data_point p WHERE p.equip_id=e.equip_id AND p.building_id=e.building_id
                  AND p.del_flag=0 AND p.status='ONLINE' AND p.suffix_code=pt.suffix_code)) AS configured_required_count
            """;
        List<Row> rows = named.query(select + from + where + " ORDER BY e.create_time DESC,e.equip_id LIMIT :limit OFFSET :offset", params, this::row);
        Map<String, List<Point>> points = loadPoints(rows);
        Map<String, List<NativeState>> states = loadStates(rows);
        List<LedgerItem> items = rows.stream().map(row -> new LedgerItem(row.equipment(), row.group(), row.source(),
                row.revision(), row.classificationRevision(), operation(row, states.getOrDefault(row.equipment().equipmentId(), List.of()), now),
                row.communication(),
                displayPoint(row, points.getOrDefault(row.equipment().equipmentId(), List.of())))).toList();
        return new LedgerPage(page, size, total, items, counts,
                new LedgerSummary(total, pointTotal == null ? 0 : pointTotal, ((Number)summary.get("online_count")).longValue(), ((Number)summary.get("offline_count")).longValue(), ((Number)summary.get("unknown_count")).longValue(), 0, ((Number)summary.get("not_monitored")).longValue(), ((Number)summary.get("delayed_count")).longValue()), now);
    }

    public List<TypeRule> rules(Set<String> roles) {
        admin(roles);
        return jdbc.query("SELECT * FROM biz_ledger_type_rule ORDER BY type_code", this::rule);
    }

    /** 类型规则一次确认后供同类设备使用；精确部件/后缀匹配，不按名称或点位顺序猜测。 */
    @Transactional
    public TypeRule saveRule(String typeCode, TypeRuleRequest request, Long userId, Set<String> roles) {
        admin(roles);
        validGroup(request.professionalGroup());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment_type WHERE type_code=? AND status=1", Long.class, typeCode) != 1) {
            throw AssetErrors.error(404, AssetErrors.NOT_FOUND, "启用的设备类型不存在");
        }
        String category = jdbc.queryForObject("SELECT equip_category FROM biz_equipment_type WHERE type_code=?", String.class, typeCode);
        if ("ELECTRIC_METER".equals(category) && request.operationApplicable()) throw invalid("电表不展示运行状态");
        String component = blankToNull(request.pointComponent()), suffix = blankToNull(request.pointSuffix());
        if ((component == null) != (suffix == null)) throw invalid("展示测点必须同时指定部件和后缀，或同时留空");
        List<TypeRule> previous = jdbc.query("SELECT * FROM biz_ledger_type_rule WHERE type_code=?", this::rule, typeCode);
        long next = request.expectedRevision() + 1;
        if (previous.isEmpty()) {
            if (request.expectedRevision() != 0) throw conflict();
            try {
                jdbc.update("INSERT INTO biz_ledger_type_rule(type_code,professional_group,operation_applicable,point_component,point_suffix,evidence,revision,updated_by) VALUES(?,?,?,?,?,?,?,?)",
                        typeCode, request.professionalGroup(), request.operationApplicable(), component, suffix, request.evidence(), next, userId);
            } catch (DuplicateKeyException ex) { throw conflict(); }
        } else if (jdbc.update("UPDATE biz_ledger_type_rule SET professional_group=?,operation_applicable=?,point_component=?,point_suffix=?,evidence=?,revision=?,updated_by=? WHERE type_code=? AND revision=?",
                request.professionalGroup(), request.operationApplicable(), component, suffix, request.evidence(), next, userId, typeCode, request.expectedRevision()) != 1) throw conflict();
        TypeRule result = new TypeRule(typeCode, request.professionalGroup(), request.operationApplicable(), component, suffix, request.evidence(), next, userId);
        audit("TYPE_RULE", typeCode, userId, previous.isEmpty() ? null : previous.getFirst(), result);
        return result;
    }

    @Transactional
    public ClassificationView saveClassification(String equipmentId, ClassificationRequest request, Long userId, Set<String> roles) {
        admin(roles);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment WHERE equip_id=? AND del_flag=0", Long.class, equipmentId) != 1) {
            throw AssetErrors.error(404, AssetErrors.NOT_FOUND, "设备不存在");
        }
        String group = blankToNull(request.professionalGroup());
        if (group != null) validGroup(group);
        List<ClassificationView> previous = jdbc.query("SELECT * FROM biz_ledger_equipment_classification WHERE equipment_id=?",
                (rs, n) -> new ClassificationView(rs.getString("equipment_id"), rs.getString("professional_group"), rs.getString("evidence"), rs.getLong("revision")), equipmentId);
        long next = request.expectedRevision() + 1;
        if (previous.isEmpty()) {
            if (request.expectedRevision() != 0) throw conflict();
            try {
                jdbc.update("INSERT INTO biz_ledger_equipment_classification(equipment_id,professional_group,evidence,revision,updated_by) VALUES(?,?,?,?,?)",
                        equipmentId, group, request.evidence(), next, userId);
            } catch (DuplicateKeyException ex) { throw conflict(); }
        } else if (jdbc.update("UPDATE biz_ledger_equipment_classification SET professional_group=?,evidence=?,revision=?,updated_by=? WHERE equipment_id=? AND revision=?",
                group, request.evidence(), next, userId, equipmentId, request.expectedRevision()) != 1) throw conflict();
        ClassificationView result = new ClassificationView(equipmentId, group, request.evidence(), next);
        audit("EQUIPMENT_CLASSIFICATION", equipmentId, userId, previous.isEmpty() ? null : previous.getFirst(), result);
        return result;
    }

    private Row row(ResultSet rs, int ignored) throws SQLException {
        String status = rs.getLong("identity_count") == 0 ? "UNBOUND" : rs.getLong("active_identity_count") > 0 ? "ACTIVE" : "DISABLED";
        var updated = rs.getTimestamp("update_time");
        var equipment = new EquipmentListItemView(rs.getString("equip_id"), rs.getString("equip_code"), rs.getString("equip_name"),
                rs.getString("type_code"), rs.getString("equip_category"), rs.getString("building_id"), rs.getString("building_name"),
                rs.getString("space_id"), rs.getString("space_name"), rs.getString("system_group_id"), rs.getString("system_group_name"),
                rs.getString("product_id"), rs.getString("product_name"), status, rs.getString("expected_profile_code"), null,
                new PointSummary(rs.getLong("point_count"), rs.getLong("required_count"), rs.getLong("configured_required_count")),
                rs.getLong("identity_count") == 0 && rs.getLong("point_count") == 0 ? List.of("UPDATE", "DELETE") : List.of("UPDATE"), updated == null ? 0 : updated.getTime());
        return new Row(equipment, rs.getString("professional_group"), rs.getString("override_group") != null ? "EQUIPMENT"
                : rs.getString("type_professional_group") != null ? "TYPE_RULE" : "UNMAPPED", rs.getLong("rule_revision"), rs.getLong("classification_revision"),
                rs.getBoolean("operation_applicable"), rs.getString("point_component"), rs.getString("point_suffix"),
                new LedgerState(rs.getString("comm_value")!=null,rs.getString("comm_value")==null?"UNKNOWN":rs.getString("comm_value"),
                        rs.getString("comm_reason")==null?"NOT_MONITORED":rs.getString("comm_reason"),rs.getString("comm_source"),
                        rs.getObject("comm_observed_at")==null?null:rs.getLong("comm_observed_at"),"DEVICE"));
    }

    private Map<String, List<Point>> loadPoints(List<Row> rows) {
        if (rows.isEmpty()) return Map.of();
        var named = new NamedParameterJdbcTemplate(jdbc);
        Map<String, List<Point>> result = new HashMap<>();
        named.query("SELECT p.* FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0 WHERE p.del_flag=0 AND p.equip_id IN (:ids)",
                Map.of("ids", rows.stream().map(r -> r.equipment().equipmentId()).toList()), rs -> {
                    Point p = new Point(rs.getString("point_id"), rs.getString("equip_id"), rs.getString("building_id"), rs.getString("point_code"),
                            rs.getString("point_name"), rs.getString("unit"), rs.getString("component_code"), rs.getString("suffix_code"), rs.getString("status"));
                    if (rows.stream().noneMatch(row -> row.equipment().equipmentId().equals(p.equipmentId())
                            && row.equipment().buildingId().equals(p.buildingId()))) return;
                    result.computeIfAbsent(p.equipmentId(), key -> new ArrayList<>()).add(p);
                });
        return result;
    }

    private Map<String, List<NativeState>> loadStates(List<Row> rows) {
        if (rows.isEmpty()) return Map.of();
        Map<String, List<NativeState>> result = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query("""
            SELECT t.equipment_id,t.identity_id,t.mapping_version,t.active,i.status AS identity_status,
              d.device_kind,s.field_name,s.normalized_value,s.field_status,s.last_valid_at_ms,
              s.building_id AS value_building,s.mapping_version AS value_mapping,
              s.last_attempt_building_id,s.last_attempt_mapping_version,e.building_id
            FROM biz_daikin_monitoring_target t
            JOIN biz_equipment e ON e.equip_id=t.equipment_id AND e.building_id=t.building_id AND e.del_flag=0
            JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id AND i.building_id=e.building_id AND i.identity_type='DAIKIN_UNIT'
            JOIN biz_daikin_directory d ON d.pending_id=t.pending_id AND d.source_id=t.source_id
            LEFT JOIN biz_daikin_current_state s ON s.identity_id=t.identity_id AND s.equipment_id=e.equip_id
              AND s.field_name IN ('onOff','compressorOnOff','inCommunicationError')
            WHERE t.equipment_id IN (:ids)
            """, Map.of("ids", rows.stream().map(r -> r.equipment().equipmentId()).toList()), rs -> {
                String equipmentId = rs.getString("equipment_id"), buildingId = rs.getString("building_id");
                if (rows.stream().noneMatch(row -> row.equipment().equipmentId().equals(equipmentId)
                        && row.equipment().buildingId().equals(buildingId))) return;
                Long observed = rs.getObject("last_valid_at_ms") == null ? null : rs.getLong("last_valid_at_ms");
                boolean valid = rs.getBoolean("active") && rs.getInt("identity_status") == 1
                        && Objects.equals(rs.getString("building_id"), rs.getString("value_building"))
                        && Objects.equals(rs.getString("building_id"), rs.getString("last_attempt_building_id"))
                        && rs.getInt("mapping_version") == rs.getInt("value_mapping")
                        && rs.getInt("mapping_version") == rs.getInt("last_attempt_mapping_version");
                var state = new NativeState(rs.getString("identity_id"), rs.getString("device_kind"), rs.getString("field_name"),
                        rs.getString("normalized_value"), rs.getString("field_status"), observed, valid);
                result.computeIfAbsent(rs.getString("equipment_id"), key -> new ArrayList<>()).add(state);
            });
        return result;
    }

    private LedgerState operation(Row row, List<NativeState> states, long now) {
        if (!row.operationApplicable()) return new LedgerState(false, "NOT_APPLICABLE", "TYPE_HAS_NO_OPERATION_STATE", null, null, "DEVICE");
        if (identityConflict(states)) return unknown("IDENTITY_CONFLICT", null, null, "DEVICE");
        String kind = states.isEmpty() ? null : states.getFirst().kind();
        boolean compressor = "OUTDOOR".equals(kind);
        String field = compressor ? "compressorOnOff" : "onOff";
        NativeState state = states.stream().filter(s -> field.equals(s.field())).findFirst().orElse(null);
        String scope = compressor ? "COMPRESSOR" : "DEVICE";
        if (state == null) return unknown("STATE_NOT_CONNECTED", null, null, scope);
        if (!fresh(state, now)) return unknown("STATE_UNAVAILABLE_OR_STALE", field, state.observedAt(), scope);
        String value = state.value();
        if ("on".equals(value) || (compressor && "true".equals(value))) return new LedgerState(true, "RUNNING", "VALID_OBSERVATION", field, state.observedAt(), scope);
        if ("off".equals(value) || (compressor && "false".equals(value))) return new LedgerState(true, "STOPPED", "VALID_OBSERVATION", field, state.observedAt(), scope);
        return unknown("UNRECOGNIZED_STATE", field, state.observedAt(), scope);
    }

    private LedgerDisplayPoint displayPoint(Row row, List<Point> points) {
        if (row.component() == null || row.suffix() == null) return missingPoint("UNCONFIGURED", "TYPE_DISPLAY_RULE_NOT_CONFIGURED");
        List<Point> matched = points.stream().filter(p -> Objects.equals(p.component(), row.component()) && Objects.equals(p.suffix(), row.suffix())).toList();
        if (matched.isEmpty()) return missingPoint("UNMATCHED", "DISPLAY_POINT_NOT_FOUND");
        if (matched.size() != 1) return missingPoint("AMBIGUOUS", "DISPLAY_POINT_NOT_UNIQUE");
        Point p = matched.getFirst();
        if (!"ONLINE".equals(p.status())) return point(p, "DISABLED", "POINT_DISABLED", null, null);
        LatestRawReading reading;
        try {
            // 时序查询只读当前页已精确匹配的一个测点，不读取其他建筑或把任意首点当默认值。
            List<LatestRawReading> evidence = rawEvents.findLatestByEquipmentPoints(p.buildingId(), p.equipmentId(), List.of(p.id()))
                    .stream().filter(candidate -> p.id().equals(candidate.pointId())).toList();
            if (evidence.isEmpty()) return point(p, "NO_DATA", "NO_RAW_EVENT", null, null);
            reading = evidence.getFirst();
        } catch (RuntimeException ex) {
            log.warn("台账展示读数不可用 equipmentId={} pointId={} cause={}", p.equipmentId(), p.id(), ex.getClass().getSimpleName());
            return point(p, "UNAVAILABLE", "READING_OR_POLICY_UNAVAILABLE", null, null);
        }
        if (!Double.isFinite(reading.value())) {
            return new LedgerDisplayPoint("UNAVAILABLE", "INVALID_READING", p.id(), p.code(), p.name(), p.unit(),
                    null, reading.eventTime(), reading.receivedTime(), reading.dataQuality(), "INVALID_READING");
        }
        try {
            var resolution = quality.resolve(quality.runtimeContext(), p.id(), POINT_REALTIME_VIEW,
                    QualityUsagePolicyResolver.alignMinute(reading.eventTime()), reading.dataQuality());
            boolean allowed = resolution.decision() == Decision.ALLOW;
            return new LedgerDisplayPoint(allowed ? "AVAILABLE" : "QUALITY_BLOCKED", resolution.reason(), p.id(), p.code(), p.name(), p.unit(),
                    allowed ? reading.value() : null, reading.eventTime(), reading.receivedTime(), reading.dataQuality(), resolution.usageStatus().name());
        } catch (RuntimeException ex) {
            // 策略故障只隐藏数值，保留已读取的时间和质量证据，不能伪装为无数据或零值。
            log.warn("台账展示策略不可用 equipmentId={} pointId={} cause={}", p.equipmentId(), p.id(), ex.getClass().getSimpleName());
            return new LedgerDisplayPoint("UNAVAILABLE", "READING_OR_POLICY_UNAVAILABLE", p.id(), p.code(), p.name(), p.unit(),
                    null, reading.eventTime(), reading.receivedTime(), reading.dataQuality(), "POLICY_SNAPSHOT_UNAVAILABLE");
        }
    }

    private LedgerDisplayPoint point(Point p, String status, String reason, Double value, Long time) {
        return new LedgerDisplayPoint(status, reason, p.id(), p.code(), p.name(), p.unit(), value, time, null, null, status);
    }
    private LedgerDisplayPoint missingPoint(String status, String reason) { return new LedgerDisplayPoint(status, reason, null, null, null, null, null, null, null, null, status); }
    private boolean fresh(NativeState s, long now) { return s.valid() && "PRESENT".equals(s.status()) && s.observedAt() != null && s.observedAt() <= now && s.observedAt() >= now - DAIKIN_STALE_MS; }
    private boolean identityConflict(List<NativeState> states) { return states.stream().map(NativeState::identityId).distinct().count() > 1; }
    private LedgerState unknown(String reason, String source, Long time, String scope) { return new LedgerState(true, "UNKNOWN", reason, source, time, scope); }
    private TypeRule rule(ResultSet rs, int ignored) throws SQLException { return new TypeRule(rs.getString("type_code"), rs.getString("professional_group"), rs.getBoolean("operation_applicable"), rs.getString("point_component"), rs.getString("point_suffix"), rs.getString("evidence"), rs.getLong("revision"), rs.getObject("updated_by") == null ? null : rs.getLong("updated_by")); }
    private void filter(StringBuilder where, Map<String, Object> params, String key, String column, String value) { if (StringUtils.hasText(value)) { where.append(" AND ").append(column).append("=:").append(key); params.put(key, value); } }
    private void validGroup(String group) { if (!GROUPS.contains(group)) throw invalid("不支持的设备专业"); }
    private String blankToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private void admin(Set<String> roles) { if (roles == null || !roles.contains("PLATFORM_ADMIN")) throw AssetErrors.error(403, "ASSET_FORBIDDEN", "仅平台管理员可访问台账"); }
    private RuntimeException invalid(String message) { return AssetErrors.error(400, AssetErrors.VALIDATION_FAILED, message); }
    private RuntimeException conflict() { return AssetErrors.error(409, AssetErrors.STATE_CONFLICT, "展示配置已变更，请重新读取后再保存"); }
    private void audit(String kind, String target, Long userId, Object before, Object after) {
        try {
            jdbc.update("INSERT INTO biz_ledger_config_audit(config_kind,target_id,changed_by,before_json,after_json) VALUES(?,?,?,?,?)",
                    kind, target, userId, json.writeValueAsString(before), json.writeValueAsString(after));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("无法保存展示配置审计", ex); }
    }
    private record Row(EquipmentListItemView equipment, String group, String source, long revision, long classificationRevision, boolean operationApplicable, String component, String suffix, LedgerState communication) { }
    private record Point(String id, String equipmentId, String buildingId, String code, String name, String unit, String component, String suffix, String status) { }
    private record NativeState(String identityId, String kind, String field, String value, String status, Long observedAt, boolean valid) { }
}
