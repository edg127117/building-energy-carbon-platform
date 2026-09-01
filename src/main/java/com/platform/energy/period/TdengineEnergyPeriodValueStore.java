package com.platform.energy.period;

import com.platform.config.TdengineProperties;
import com.platform.energy.period.EnergyPeriodModels.NumericResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static com.platform.energy.period.EnergyPeriodErrors.VALUE_STORE_UNAVAILABLE;
import static com.platform.energy.period.EnergyPeriodErrors.error;

@Repository
/**
 * 使用确定性子表和周期起点时间戳幂等保存当前投影或封账快照数值。
 * TDengine 3.2.3 的数值列使用 DOUBLE，同时保留精确十进制文本作为核对证据。
 */
public class TdengineEnergyPeriodValueStore implements EnergyPeriodValueStore {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate jdbc;
    private final String database;

    public TdengineEnergyPeriodValueStore(
            @Qualifier("taosJdbcTemplate") JdbcTemplate jdbc,
            TdengineProperties properties) {
        this.jdbc = jdbc;
        this.database = identifier(properties.getDatabase());
    }

    @Override
    public void write(NumericResult result) {
        if (result == null || result.resultKey() == null || result.periodStart() == null
                || result.nativeQuantity() == null || result.coverageRatio() == null) {
            throw error(409, VALUE_STORE_UNAVAILABLE, "周期数值写入内容不完整");
        }
        String stable = "`" + database + "`.`st_energy_period_result`";
        String child = "`" + database + "`.`ep_" + digest(result.resultKey()).substring(0, 24) + "`";
        String sql = "INSERT INTO " + child + " USING " + stable
                + " (result_key,building_id,point_id,native_unit_code,tce_unit_code,result_nature)"
                + " TAGS (" + quote(result.resultKey()) + "," + quote(result.buildingId()) + ","
                + quote(result.pointId()) + "," + quote(result.nativeUnitCode()) + ","
                + nullableQuote(result.tceUnitCode()) + "," + quote(result.resultNature()) + ")"
                + " (ts,native_quantity,tce_value,coverage_ratio,native_quantity_decimal,"
                + "tce_value_decimal,coverage_ratio_decimal,revision,evidence_hash) VALUES ("
                + result.periodStart().toEpochMilli() + "," + number(result.nativeQuantity()) + ","
                + nullableNumber(result.tce()) + "," + number(result.coverageRatio()) + ","
                + quote(result.nativeQuantity().toPlainString()) + ","
                + nullableDecimal(result.tce()) + ","
                + quote(result.coverageRatio().toPlainString()) + ","
                + result.revision() + "," + quote(result.evidenceHash()) + ")";
        jdbc.execute(sql);
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("TDengine database identifier is invalid");
        }
        return value;
    }

    private static String quote(String value) {
        if (value == null) throw new IllegalArgumentException("TDengine tag cannot be null");
        return "'" + value.replace("'", "''") + "'";
    }

    private static String nullableQuote(String value) {
        return value == null ? "NULL" : quote(value);
    }

    private static String number(BigDecimal value) {
        return value.toPlainString();
    }

    private static String nullableNumber(BigDecimal value) {
        return value == null ? "NULL" : number(value);
    }

    private static String nullableDecimal(BigDecimal value) {
        return value == null ? "NULL" : quote(value.toPlainString());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
