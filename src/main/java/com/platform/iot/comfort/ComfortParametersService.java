package com.platform.iot.comfort;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.comfort.ComfortQueryService.Space;
import jakarta.validation.constraints.Digits;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 只存储已授权房间的温度展示区间和人工测算输入。 */
@Service
public class ComfortParametersService {
    static final String EMISSION_FACTOR = "dynamicEmissionFactor";
    private static final Map<String, InputRange> MANUAL_RANGES = Map.of(
            "metabolicRate", new InputRange("0.8", "2.0", 1),
            "clothingInsulation", new InputRange("0.3", "1.5", 2),
            "airVelocity", new InputRange("0", "0.45", 2),
            "relativeHumidity", new InputRange("20", "80", 0),
            "airTemperature", new InputRange("18", "37", 1),
            "radiantTemperature", new InputRange("18", "37", 1),
            EMISSION_FACTOR, new InputRange("0", "2.000", 3));
    private final JdbcTemplate mysql;
    private final ComfortQueryService query;
    private final ObjectMapper json;

    public ComfortParametersService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql, ComfortQueryService query,
                                    ObjectMapper json) {
        this.mysql = mysql;
        this.query = query;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Parameters parameters(Long userId, Set<String> roles, String spaceId) {
        return readParameters(query.room(userId, roles, spaceId), canEdit(roles));
    }

    private Parameters readParameters(Space room, boolean editable) {
        var band = mysql.query("""
                SELECT t_low, t_high FROM biz_comfort_temperature_band WHERE building_id=? AND space_id=?
                """, (rs, row) -> new TemperatureBand(rs.getBigDecimal(1), rs.getBigDecimal(2)),
                room.buildingId(), room.spaceId()).stream().findFirst().orElse(null);
        return new Parameters(band, readManualInputs(room), editable);
    }

    // 房间必须来自已经过菜单及建筑范围校验的查询。
    Map<String, BigDecimal> readManualInputs(Space room) {
        return mysql.query("""
                SELECT parameter_key, parameter_value FROM biz_comfort_manual_parameter
                WHERE building_id=? AND space_id=?
                """, rs -> {
            var values = new LinkedHashMap<String, BigDecimal>();
            while (rs.next()) values.put(rs.getString(1), rs.getBigDecimal(2));
            return values;
        }, room.buildingId(), room.spaceId());
    }

    @Transactional
    public Parameters save(Long userId, Set<String> roles, String spaceId, String key, ParameterUpdate update) {
        if (!canEdit(roles)) throw new BusinessException(403, "COMFORT_PARAMETERS_FORBIDDEN", "无权修改房间参数");
        var room = query.room(userId, roles, spaceId);
        boolean changeBand = update.tLowPresent || update.tHighPresent;
        if (update.tLowPresent != update.tHighPresent || (!changeBand && update.manualInputs.isEmpty())) {
            throw new BusinessException(400, "COMFORT_PARAMETERS_INVALID", "请提交要修改的参数，温度上下限须成对提交");
        }
        update.manualInputs.forEach(ComfortParametersService::checkManualInput);
        if (key == null) return writeParameters(room, update, changeBand);
        if (key.isBlank() || key.length() > 160) {
            throw new BusinessException(400, "COMFORT_REQUEST_KEY_INVALID", "保存请求键无效");
        }
        String hash = requestHash(room, update);
        // 请求凭证、参数写入和响应共用一个事务。同键重试先锁凭证，免得较早的请求覆盖之后保存的配置。
        try {
            mysql.update("INSERT INTO biz_comfort_parameter_command(actor_id,idempotency_key,request_hash) VALUES (?,?,?)",
                    userId, key, hash);
        } catch (DuplicateKeyException existing) {
            // 已占用的键在行锁下读取原始内容与结果。
        }
        var receipt = mysql.queryForObject("""
                SELECT request_hash,result_json FROM biz_comfort_parameter_command
                WHERE actor_id=? AND idempotency_key=? FOR UPDATE
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)), userId, key);
        if (!hash.equals(receipt.requestHash())) {
            throw new BusinessException(409, "IDEMPOTENCY_CONFLICT", "请求键已用于不同内容");
        }
        if (receipt.result() != null) return readReceipt(receipt.result());
        var saved = writeParameters(room, update, changeBand);
        mysql.update("UPDATE biz_comfort_parameter_command SET result_json=? WHERE actor_id=? AND idempotency_key=?",
                encode(saved), userId, key);
        return saved;
    }

    private Parameters writeParameters(Space room, ParameterUpdate update, boolean changeBand) {
        if (changeBand) writeBand(room, new TemperatureBand(update.tLow, update.tHigh));
        // 省略的字段保留原值，显式传 null 清空。固定写入顺序避免多字段更新反向争用。
        new TreeMap<>(update.manualInputs).forEach((key, value) -> {
            writeManualInput(room, key, value);
            if (EMISSION_FACTOR.equals(key)) {
                // 清空也记录生效时间。和当前参数同事务提交，重试不会改写生效时间。
                mysql.update("""
                        INSERT INTO biz_comfort_emission_factor_history(building_id,space_id,effective_at,factor)
                        VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE factor=VALUES(factor)
                        """, room.buildingId(), room.spaceId(), query.now(), value);
            }
        });
        return readParameters(room, true);
    }

    private String requestHash(Space room, ParameterUpdate update) {
        var inputs = new TreeMap<String, BigDecimal>();
        update.manualInputs.forEach((name, value) -> inputs.put(name, normalized(value)));
        var command = new ParameterCommand(room.buildingId(), room.spaceId(), update.tLowPresent,
                normalized(update.tLow), normalized(update.tHigh), inputs);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(command)));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("无法生成参数提交摘要", failure);
        }
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private String encode(Parameters parameters) {
        try { return json.writeValueAsString(parameters); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("无法保存参数提交结果", failure); }
    }

    private Parameters readReceipt(String saved) {
        try { return json.readValue(saved, Parameters.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("无法读取参数提交结果", failure); }
    }

    private record ParameterCommand(String building, String room, boolean changeBand, BigDecimal low,
                                    BigDecimal high, Map<String, BigDecimal> inputs) {}
    private record Receipt(String requestHash, String result) {}

    private void writeBand(Space room, TemperatureBand band) {
        if (band.tLow() == null && band.tHigh() == null) {
            mysql.update("DELETE FROM biz_comfort_temperature_band WHERE building_id=? AND space_id=?",
                    room.buildingId(), room.spaceId());
            return;
        }
        if (band.tLow() == null || band.tHigh() == null || band.tLow().compareTo(band.tHigh()) >= 0) {
            throw new BusinessException(400, "COMFORT_TEMPERATURE_BAND_INVALID", "请填写上下限，且温度下限须低于上限");
        }
        mysql.update("""
                INSERT INTO biz_comfort_temperature_band(building_id, space_id, t_low, t_high)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE t_low=VALUES(t_low), t_high=VALUES(t_high)
                """, room.buildingId(), room.spaceId(), band.tLow(), band.tHigh());
    }

    private static void checkManualInput(String key, BigDecimal value) {
        var range = MANUAL_RANGES.get(key);
        if (range == null || (value != null && !range.contains(value))) {
            throw new BusinessException(400, "COMFORT_MANUAL_INPUT_INVALID", "人工测算参数超出允许范围或精度");
        }
    }

    private void writeManualInput(Space room, String key, BigDecimal value) {
        if (value == null) {
            mysql.update("""
                    DELETE FROM biz_comfort_manual_parameter WHERE building_id=? AND space_id=? AND parameter_key=?
                    """, room.buildingId(), room.spaceId(), key);
            return;
        }
        mysql.update("""
                INSERT INTO biz_comfort_manual_parameter(building_id, space_id, parameter_key, parameter_value)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE parameter_value=VALUES(parameter_value)
                """, room.buildingId(), room.spaceId(), key, value);
    }

    private static boolean canEdit(Set<String> roles) {
        return roles != null && (roles.contains("PLATFORM_ADMIN") || roles.contains("ENERGY_MANAGER"));
    }

    public record TemperatureBand(BigDecimal tLow, BigDecimal tHigh) {}
    public record Parameters(TemperatureBand comfortBand, Map<String, BigDecimal> manualInputs, boolean editable) {}

    public static final class ParameterUpdate {
        @Digits(integer = 3, fraction = 2)
        private BigDecimal tLow;
        @Digits(integer = 3, fraction = 2)
        private BigDecimal tHigh;
        private boolean tLowPresent;
        private boolean tHighPresent;
        private Map<String, BigDecimal> manualInputs = Map.of();

        @JsonSetter("tLow")
        public void setTLow(BigDecimal value) {
            tLow = value;
            tLowPresent = true;
        }

        @JsonSetter("tHigh")
        public void setTHigh(BigDecimal value) {
            tHigh = value;
            tHighPresent = true;
        }

        @JsonSetter(value = "manualInputs", nulls = Nulls.FAIL)
        public void setManualInputs(Map<String, BigDecimal> inputs) {
            manualInputs = inputs;
        }
    }

    private record InputRange(BigDecimal min, BigDecimal max, int digits) {
        InputRange(String min, String max, int digits) {
            this(new BigDecimal(min), new BigDecimal(max), digits);
        }

        boolean contains(BigDecimal value) {
            return value.compareTo(min) >= 0 && value.compareTo(max) <= 0 && value.stripTrailingZeros().scale() <= digits;
        }
    }
}
