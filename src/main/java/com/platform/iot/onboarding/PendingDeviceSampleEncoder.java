package com.platform.iot.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
/**
 * 把标准报文压缩为可持久化的有限最近样例。
 *
 * <p>编码器只保留指标代码、数值、单位和报文时间语义。每增加一个指标都会重新核对
 * UTF-8 总大小；超限指标整项丢弃，保证不会通过截断 JSON 字节产生损坏内容。</p>
 */
public class PendingDeviceSampleEncoder {

    private final ObjectMapper objectMapper;
    private final PendingDeviceDiscoveryProperties properties;

    public PendingDeviceSampleEncoder(
            ObjectMapper objectMapper,
            PendingDeviceDiscoveryProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /** 以源报文顺序确定性保留能够完整放入上限的指标。 */
    public PendingDeviceSample encode(StandardTelemetryMessage message) {
        Objects.requireNonNull(message, "message 不能为空");
        List<SampleMetric> retained = new ArrayList<>();
        boolean truncated = false;
        int sourceCount = message.metrics().size();
        int candidateCount = Math.min(sourceCount, properties.getMaxMetricCount());
        if (candidateCount < sourceCount) {
            truncated = true;
        }

        for (int index = 0; index < candidateCount; index++) {
            StandardMetric metric = message.metrics().get(index);
            TruncatedText code = truncate(metric.code());
            TruncatedText unit = truncate(metric.unit());
            truncated = truncated || code.truncated() || unit.truncated();
            SampleMetric candidate = new SampleMetric(code.value(), metric.value(), unit.value());
            retained.add(candidate);
            String json = serialize(message, retained);
            if (utf8Length(json) > properties.getMaxSampleBytes()) {
                retained.remove(retained.size() - 1);
                truncated = true;
                break;
            }
        }

        String json = serialize(message, retained);
        if (utf8Length(json) > properties.getMaxSampleBytes()) {
            throw new IllegalStateException("待绑定样例基础时间元数据超过配置上限");
        }
        return new PendingDeviceSample(json, truncated);
    }

    private String serialize(
            StandardTelemetryMessage message,
            List<SampleMetric> metrics) {
        try {
            return objectMapper.writeValueAsString(new SampleEnvelope(
                    message.eventTime(), message.receivedTime(),
                    message.timeSource(), List.copyOf(metrics)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("待绑定规范化样例无法序列化", exception);
        }
    }

    private TruncatedText truncate(String value) {
        if (value.length() <= properties.getMaxStringLength()) {
            return new TruncatedText(value, false);
        }
        return new TruncatedText(
                value.substring(0, properties.getMaxStringLength()), true);
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record SampleEnvelope(
            long eventTime,
            long receivedTime,
            String timeSource,
            List<SampleMetric> metrics) {
    }

    private record SampleMetric(
            String code,
            java.math.BigDecimal value,
            String unit) {
    }

    private record TruncatedText(String value, boolean truncated) {
    }
}
