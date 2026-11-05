package com.platform.alarm;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将已持久化的大金活动异常映射为统一只读故障事实。 */
@Component
public class DaikinAlarmFaultSource implements AlarmFaultSource {
    private final JdbcTemplate jdbc;

    public DaikinAlarmFaultSource(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FaultReading> load(String buildingId) {
        return jdbc.query("""
                SELECT x.identity_id,x.building_id,x.equipment_id,x.exception_type,x.active_key,x.last_detected_at_ms,
                       COALESCE(x.field_name,'')
                FROM biz_daikin_exception_instance x
                JOIN biz_equipment e ON e.equip_id=x.equipment_id AND e.building_id=x.building_id AND e.del_flag=0
                JOIN biz_daikin_monitoring_target t ON t.identity_id=x.identity_id AND t.equipment_id=e.equip_id
                  AND t.building_id=e.building_id AND t.mapping_version=x.mapping_version AND t.active=1
                JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                  AND i.building_id=e.building_id AND i.status=1
                WHERE x.building_id=? AND x.scope_type='DEVICE' AND x.active_key IS NOT NULL
                  AND x.exception_type IN ('VENDOR_EQUIPMENT','VENDOR_COMMUNICATION')
                """, (rs, row) -> new FaultReading(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5) != null, rs.getLong(6), rs.getString(7)), buildingId);
    }
}
