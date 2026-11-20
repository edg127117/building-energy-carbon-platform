package com.platform.iot.comfort;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.comfort.ComfortQueryService.Space;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 保存采样当时的 PMV 输入证据，读取时不套用当前配置回算旧时段。 */
@Service
public class ComfortPmvHistory {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long HOUR_MILLIS = 3_600_000;
    private final JdbcTemplate mysql;
    private final ObjectMapper json;

    public ComfortPmvHistory(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql, ObjectMapper json) {
        this.mysql = mysql;
        this.json = json;
    }

    void record(Space room, ComfortPmvService.Sample sample, long sampledAt) {
        try {
            mysql.update("""
                    INSERT INTO biz_comfort_pmv_sample(building_id,space_id,temperature_observed_at,sampled_at,
                        indoor_celsius,pmv,inputs_json) VALUES (?,?,?,?,?,?,?)
                    """, room.buildingId(), room.spaceId(), sample.temperatureObservedAt().toEpochMilli(),
                    sampledAt, sample.indoorCelsius(), sample.value(), json.writeValueAsString(sample.inputs()));
        } catch (DuplicateKeyException duplicate) {
            // 一次室温观测仅保留第一次成功计算；重试或改参不覆盖已保存的数值。
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法保存 PMV 输入依据", failure);
        }
    }

    List<Hour> hours(Space room, LocalDate day, long asOf) {
        long start = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        var hourly = new Point[24];
        try (var rows = mysql.queryForStream("""
                SELECT sampled_at,temperature_observed_at,pmv,indoor_celsius,inputs_json
                FROM biz_comfort_pmv_sample
                WHERE building_id=? AND space_id=? AND sampled_at>=? AND sampled_at<? AND sampled_at<=?
                ORDER BY sampled_at,temperature_observed_at
                """, (rs, row) -> new Point(Instant.ofEpochMilli(rs.getLong(1)), Instant.ofEpochMilli(rs.getLong(2)),
                rs.getBigDecimal(3), rs.getBigDecimal(4), readInputs(rs.getString(5))),
                room.buildingId(), room.spaceId(), start, end, asOf)) {
            rows.forEach(point -> hourly[(int) ((point.sampledAt().toEpochMilli() - start) / HOUR_MILLIS)] = point);
        }
        var result = new ArrayList<Hour>(24);
        for (int hour = 0; hour < hourly.length; hour++) {
            result.add(new Hour(Instant.ofEpochMilli(start + hour * HOUR_MILLIS), hourly[hour]));
        }
        return result;
    }

    private JsonNode readInputs(String evidence) {
        try { return json.readTree(evidence); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("无法读取 PMV 输入依据", failure); }
    }

    public record Hour(Instant startsAt, Point observation) {}
    public record Point(Instant sampledAt, Instant temperatureObservedAt, BigDecimal value,
                        BigDecimal indoorCelsius, JsonNode inputs) {}
}
