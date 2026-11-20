package com.platform.iot.comfort;

import com.platform.iot.comfort.ComfortQueryService.Overview;
import com.platform.iot.comfort.ComfortQueryService.Space;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 以概览已授权房间的新鲜室温和已保存的五项输入生成当次 PMV。 */
@Service
public class ComfortPmvService {
    private static final Logger LOG = LoggerFactory.getLogger(ComfortPmvService.class);
    private static final List<String> REQUIRED_INPUTS = List.of(
            "metabolicRate", "clothingInsulation", "airVelocity", "relativeHumidity", "radiantTemperature");
    private final ComfortParametersService parameters;

    public ComfortPmvService(ComfortParametersService parameters) {
        this.parameters = parameters;
    }

    Measurement evaluate(Overview overview) {
        return evaluateWithInputs(overview).measurement();
    }

    public AlarmMeasurement readForAlarm(ComfortQueryService query, Space room, long asOf) {
        var overview = query.alarmOverview(room, asOf);
        var sample = sample(overview, asOf);
        if (sample == null) return null;
        var inputs = REQUIRED_INPUTS.stream().map(key -> sample.inputs().get(key).stripTrailingZeros()).toList();
        var bindings = overview.temperatureSources().stream()
                .map(source -> List.of(source.equipmentId(), source.bindingStartedAt(), source.mappingVersion())).toList();
        return new AlarmMeasurement(sample.value(), sample.temperatureObservedAt().toEpochMilli(),
                inputs.toString() + bindings);
    }

    Sample sample(Overview overview, long asOf) {
        var current = overview.indoorTemperature();
        if (current == null || current.observedAt().toEpochMilli() > asOf
                || overview.selectedSource() != null && !"PRESENT".equals(overview.fieldStatus())) return null;
        var evaluation = evaluateWithInputs(overview);
        if (evaluation.measurement().status() != Status.AVAILABLE) return null;
        var inputs = new java.util.LinkedHashMap<String, BigDecimal>();
        REQUIRED_INPUTS.forEach(key -> inputs.put(key, evaluation.inputs().get(key)));
        return new Sample(evaluation.measurement().value(), current.celsius(), current.observedAt(), inputs);
    }

    private Evaluation evaluateWithInputs(Overview overview) {
        var current = overview.indoorTemperature();
        if (current == null || current.celsius() == null || current.stale()) {
            var last = overview.lastValidIndoorTemperature();
            boolean stale = (current != null && current.stale()) || (last != null && last.stale());
            return unavailable(stale ? Status.TEMPERATURE_STALE : Status.TEMPERATURE_MISSING);
        }

        Map<String, BigDecimal> inputs;
        try {
            inputs = parameters.readManualInputs(overview.space());
        } catch (DataAccessException failure) {
            LOG.warn("房间 PMV 配置读取失败 {}", overview.space().spaceId(), failure);
            return unavailable(Status.PARAMETERS_UNAVAILABLE);
        }
        var missing = REQUIRED_INPUTS.stream().filter(key -> inputs.get(key) == null).toList();
        if (!missing.isEmpty()) {
            return new Evaluation(new Measurement(Status.PARAMETERS_MISSING, null, null, missing), inputs);
        }

        var computed = ComfortPmvCalculator.calculate(current.celsius(), inputs.get("radiantTemperature").doubleValue(),
                inputs.get("airVelocity").doubleValue(), inputs.get("relativeHumidity").doubleValue(),
                inputs.get("metabolicRate").doubleValue(), inputs.get("clothingInsulation").doubleValue());
        if (computed.isEmpty()) return unavailable(Status.OUTSIDE_MODEL_RANGE);
        var value = BigDecimal.valueOf(computed.getAsDouble()).setScale(2, RoundingMode.HALF_UP);
        var sensation = value.doubleValue() < -0.5 ? Sensation.COOL
                : value.doubleValue() > 0.5 ? Sensation.WARM : Sensation.COMFORTABLE;
        return new Evaluation(new Measurement(Status.AVAILABLE, value, sensation, List.of()), inputs);
    }

    private static Evaluation unavailable(Status status) {
        return new Evaluation(new Measurement(status, null, null, List.of()), Map.of());
    }

    private record Evaluation(Measurement measurement, Map<String, BigDecimal> inputs) {}
    record Sample(BigDecimal value, double indoorCelsius, Instant temperatureObservedAt, Map<String, BigDecimal> inputs) {}
    public record AlarmMeasurement(BigDecimal value, long observedAt, String inputsAndBindings) {}

    public enum Status { AVAILABLE, TEMPERATURE_MISSING, TEMPERATURE_STALE, PARAMETERS_MISSING, PARAMETERS_UNAVAILABLE, OUTSIDE_MODEL_RANGE }
    public enum Sensation { COOL, COMFORTABLE, WARM }
    public record Measurement(Status status, BigDecimal value, Sensation sensation, List<String> missingInputs) {}
}
