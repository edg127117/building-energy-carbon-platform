package com.platform.iot.daikin.runtime;

import com.platform.iot.daikin.model.DaikinDeviceKey;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 厂家运行统计的可信适配边界。生产适配须确认单位、统计时区和修订口径，并使用只读客户端；
 * 未提供适配时调度不发起请求。这里的类型不是厂家报文，不猜测尚未确认的请求参数或 JSON 字段。
 */
@FunctionalInterface
public interface DaikinRuntimeClientProvider {
    Optional<Client> clientFor(String sourceId);

    interface Client {
        /** 配置版本在单位、时区或统计语义改变时必须改变，防止混写已有统计。 */
        Semantics semantics();

        /** 必须完成所有分页后返回；分页期间续租，失败抛异常，不将部分结果解释为设备缺失。 */
        Batch read(String sourceId, DaikinDeviceKey.Kind kind, DaikinRuntimePeriod period, Runnable heartbeat);
    }

    record Semantics(String version, String unit, ZoneId statisticsZone) {
        public Semantics {
            if (version == null || !version.matches("[A-Za-z0-9_.-]{1,64}")
                    || unit == null || unit.isBlank() || unit.length() > 32 || !unit.equals(unit.strip())
                    || unit.chars().anyMatch(Character::isISOControl) || statisticsZone == null) {
                throw new IllegalArgumentException("DAIKIN_RUNTIME_UNCONFIRMED_SEMANTICS");
            }
        }
    }

    enum Status { PRESENT, MISSING, UNSUPPORTED }

    record Reading(DaikinDeviceKey key, Status status, Map<String, BigDecimal> metrics, boolean complete) {
        public Reading {
            if (key == null || status == null || metrics == null || metrics.size() > 32) {
                throw new IllegalArgumentException("DAIKIN_RUNTIME_INVALID_READING");
            }
            metrics = Map.copyOf(metrics);
            if ((status == Status.PRESENT) != !metrics.isEmpty()
                    || metrics.entrySet().stream().anyMatch(e -> !e.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || e.getValue().signum() < 0 || e.getValue().precision() > 30
                    || Math.abs((long) e.getValue().scale()) > 12)) {
                throw new IllegalArgumentException("DAIKIN_RUNTIME_INVALID_METRICS");
            }
        }
    }

    record Batch(boolean unsupported, List<Reading> readings) {
        public Batch {
            if (readings == null || readings.size() > 10000 || unsupported && !readings.isEmpty()) {
                throw new IllegalArgumentException("DAIKIN_RUNTIME_INVALID_BATCH");
            }
            readings = List.copyOf(readings);
        }
    }
}
