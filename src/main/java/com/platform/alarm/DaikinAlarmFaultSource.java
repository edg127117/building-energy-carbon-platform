package com.platform.alarm;

import com.platform.iot.daikin.monitoring.DaikinMonitoringProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 读取已持久化的大金故障及恢复观测；扫描不替代采集轮次。 */
@Component
public class DaikinAlarmFaultSource implements AlarmFaultSource {
    private final JdbcTemplate jdbc;
    private final DaikinMonitoringProperties monitoring;

    public DaikinAlarmFaultSource(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this(jdbc, new DaikinMonitoringProperties());
    }

    @Autowired
    public DaikinAlarmFaultSource(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            DaikinMonitoringProperties monitoring) {
        this.jdbc = jdbc;
        this.monitoring = monitoring;
    }

    @Override
    public List<FaultReading> load(String buildingId) {
        var readings = new ArrayList<>(jdbc.query("""
                SELECT x.identity_id,x.building_id,x.equipment_id,x.exception_type,x.active_key,x.last_detected_at_ms,
                       COALESCE(x.field_name,''),x.last_round_id
                FROM biz_daikin_exception_instance x
                JOIN biz_equipment e ON e.equip_id=x.equipment_id AND e.building_id=x.building_id AND e.del_flag=0
                JOIN biz_daikin_monitoring_target t ON t.identity_id=x.identity_id AND t.equipment_id=e.equip_id
                  AND t.building_id=e.building_id AND t.mapping_version=x.mapping_version AND t.active=1
                JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                  AND i.building_id=e.building_id AND i.status=1
                WHERE x.building_id=? AND x.scope_type='DEVICE' AND x.active_key IS NOT NULL
                  AND x.exception_type IN ('VENDOR_EQUIPMENT','VENDOR_COMMUNICATION')
                """, (rs, row) -> new FaultReading(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5) != null, rs.getLong(6), rs.getString(7),
                cycle(rs.getObject(8, Long.class))), buildingId));
        // 已触发的设备只有在同一成功轮次明确读到两项故障均解除时，才能推进恢复。
        readings.addAll(jdbc.query("""
                SELECT t.identity_id,t.building_id,t.equipment_id,r.round_id,
                       CASE WHEN f.last_valid_at_ms IS NOT NULL AND c.last_valid_at_ms IS NOT NULL
                            THEN GREATEST(f.last_valid_at_ms,c.last_valid_at_ms) END AS healthy_at
                FROM biz_daikin_monitoring_target t
                JOIN biz_equipment e ON e.equip_id=t.equipment_id AND e.building_id=t.building_id AND e.del_flag=0
                JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                  AND i.building_id=e.building_id AND i.status=1
                LEFT JOIN biz_daikin_monitor_round r ON r.source_id=t.source_id AND r.round_id=t.last_round_id
                  AND r.status='SUCCEEDED'
                LEFT JOIN biz_daikin_current_state f ON f.identity_id=t.identity_id AND f.field_name='inEquipmentError'
                  AND f.last_round_id=r.round_id AND f.field_status='PRESENT' AND f.normalized_value='false'
                  AND f.building_id=t.building_id AND f.mapping_version=t.mapping_version
                  AND f.last_attempt_building_id=t.building_id AND f.last_attempt_mapping_version=t.mapping_version
                LEFT JOIN biz_daikin_current_state c ON c.identity_id=t.identity_id AND c.field_name='inCommunicationError'
                  AND c.last_round_id=r.round_id AND c.field_status='PRESENT' AND c.normalized_value='false'
                  AND c.building_id=t.building_id AND c.mapping_version=t.mapping_version
                  AND c.last_attempt_building_id=t.building_id AND c.last_attempt_mapping_version=t.mapping_version
                WHERE t.building_id=? AND t.active=1 AND EXISTS (
                    SELECT 1 FROM biz_alarm_event a WHERE a.building_id=t.building_id AND a.object_id=t.equipment_id
                    AND a.category='DEVICE' AND a.rule_type='FAULT' AND a.status='ACTIVE')
                """, (rs, row) -> {
            Long healthyAt = rs.getObject(5, Long.class);
            return new FaultReading(rs.getString(1), rs.getString(2), rs.getString(3), "VENDOR_OBSERVATION",
                    healthyAt == null ? null : Boolean.FALSE, healthyAt == null ? 0L : healthyAt,
                    "厂家故障状态观测", cycle(rs.getObject(4, Long.class)));
        }, buildingId));
        return readings;
    }

    private Long cycle(Long round) {
        return round == null ? null : round / (monitoring.getIntervalSeconds() * 1000L);
    }
}
