package com.platform.iot.temperature;

import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;

/** 大金内机的已确认双温度能力；目录不提供可信型号时只允许明确的默认匹配规则。 */
@Component
@RequiredArgsConstructor
public class DaikinTemperatureAdapter implements TemperatureAdapter {
    private final DaikinDirectoryService directory;
    private final JdbcTemplate jdbc;
    @Override public String id() { return "DAIKIN_INDOOR_V2"; }
    @Override public boolean supports(BizPendingDevice pending) {
        return "DAIKIN_UNIT".equals(pending.getIdentityType()) && id().equals(pending.getProfileCode());
    }
    @Override public Context context(BizPendingDevice pending) {
        var view = directory.detail(pending.getPendingId());
        if (view.missing() || view.buildingId() == null) {
            throw TemperaturePlanService.invalid("设备目录缺失或尚未映射建筑");
        }
        var version = jdbc.queryForObject("SELECT mapping_version FROM biz_daikin_project_mapping WHERE source_id=? AND site_id=?",
                Integer.class, view.sourceId(), view.siteId());
        return new Context(view.buildingId(), view.sourceId(), "", view.siteId() + ":" + version);
    }
    @Override public Map<String, String> fields() { return Map.of("roomTemp", "室内温度", "temperature", "设定温度"); }
    @Override public String sourceSystem() { return "DAIKIN_V2"; }
    @Override public String transportType() { return "HTTP"; }
}
