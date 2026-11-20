package com.platform.iot.comfort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** 按大金监测开关采样，复用房间实测温度与 PMV 算法，独立于网页和报警开关。 */
@Component
@ConditionalOnProperty(name = "daikin.monitoring.enabled", havingValue = "true")
class ComfortPmvSampler {
    private static final Logger log = LoggerFactory.getLogger(ComfortPmvSampler.class);
    private final JdbcTemplate mysql;
    private final ComfortQueryService rooms;
    private final ComfortPmvService pmv;
    private final ComfortPmvHistory history;

    ComfortPmvSampler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql, ComfortQueryService rooms,
                      ComfortPmvService pmv, ComfortPmvHistory history) {
        this.mysql = mysql;
        this.rooms = rooms;
        this.pmv = pmv;
        this.history = history;
    }

    @Scheduled(fixedDelayString = "${comfort.pmv-history.scan-ms:60000}")
    void sample() {
        sampleAt(System.currentTimeMillis());
    }

    void sampleAt(long asOf) {
        // 仅扫描已填齐五项输入的房间，避免为缺配置的房间读取时序库。
        var configured = mysql.query("""
                SELECT building_id, space_id FROM biz_comfort_manual_parameter
                WHERE parameter_key IN ('metabolicRate','clothingInsulation','airVelocity','relativeHumidity','radiantTemperature')
                GROUP BY building_id,space_id HAVING COUNT(*)=5
                """, rs -> {
            var result = new LinkedHashMap<String, Set<String>>();
            while (rs.next()) result.computeIfAbsent(rs.getString(1), ignored -> new HashSet<>()).add(rs.getString(2));
            return result;
        });
        configured.forEach((building, spaceIds) -> sampleBuilding(building, spaceIds, asOf));
    }

    private void sampleBuilding(String building, Set<String> spaceIds, long asOf) {
        for (var room : rooms.alarmSpaces(building)) {
            if (!spaceIds.contains(room.spaceId())) continue;
            try {
                var reading = pmv.sample(rooms.alarmOverview(room, asOf), asOf);
                if (reading != null) history.record(room, reading, asOf);
            } catch (RuntimeException failure) {
                log.warn("PMV 历史采样失败，房间={}，异常类型={}", room.spaceId(), failure.getClass().getSimpleName());
            }
        }
    }
}
