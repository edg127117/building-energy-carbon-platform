package com.platform.iot.daikin.monitoring;

import com.platform.iot.daikin.model.DaikinDeviceKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 正式监测的可信身份边界：实时复核已绑定、已启用身份和产品，以及厂家项目与设备建筑一致。
 * 待接入发现和身份ACTIVE不代表已采集；HTTP返回的厂家名称、建筑或设备ID不能覆盖平台归属。
 */
@Service
public class DaikinMonitoringTargets {
    private static final String SELECT = """
            SELECT d.source_id,d.pending_id,d.site_id,d.controller_id,d.device_kind,d.unit_id,
                   i.identity_id,i.identity_value,i.expected_profile_code,
                   e.equip_id,e.equip_code,e.building_id,e.space_id,e.system_group_id,m.mapping_version
            FROM biz_daikin_directory d
            JOIN biz_pending_device p ON p.pending_id=d.pending_id AND p.status='BOUND'
            JOIN biz_device_identity i ON i.identity_id=p.bound_identity_id
                 AND i.identity_type='DAIKIN_UNIT' AND i.status=1
            JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.del_flag=0 AND e.building_id=i.building_id
            JOIN biz_device_product product ON product.product_id=e.product_id AND product.status='ENABLED'
                 AND product.expected_profile_code=i.expected_profile_code AND product.identity_type=i.identity_type
            JOIN biz_daikin_project_mapping m ON m.source_id=d.source_id AND m.site_id=d.site_id
                 AND m.building_id=e.building_id
            """;
    private final JdbcTemplate jdbc;

    public DaikinMonitoringTargets(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Target> forSource(String sourceId) {
        var targets = query(" WHERE d.source_id=? ORDER BY d.pending_id LIMIT 10001", sourceId);
        if (targets.size() > 10000) throw new IllegalStateException("DAIKIN_MONITORING_TARGET_LIMIT");
        return targets;
    }

    public Optional<Target> find(String pendingId) {
        return query(" WHERE d.pending_id=?", pendingId).stream().findFirst();
    }

    private List<Target> query(String suffix, String parameter) {
        return jdbc.query(SELECT + suffix, (rs, row) -> new Target(
                new DaikinDeviceKey(rs.getString("source_id"), rs.getString("site_id"),
                        rs.getString("controller_id"), DaikinDeviceKey.Kind.valueOf(rs.getString("device_kind")),
                        rs.getString("unit_id")), rs.getString("pending_id"), rs.getString("identity_id"),
                rs.getString("identity_value"), rs.getString("expected_profile_code"),
                rs.getString("equip_id"), rs.getString("equip_code"), rs.getString("building_id"),
                rs.getString("space_id"), rs.getString("system_group_id"), rs.getInt("mapping_version")), parameter);
    }

    public record Target(DaikinDeviceKey key, String pendingId, String identityId, String identityValue,
                         String profileCode, String equipmentId, String equipmentCode, String buildingId,
                         String spaceId, String systemGroupId, int mappingVersion) { }
}
