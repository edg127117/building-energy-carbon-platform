package com.platform.iot.mqtt;

import com.platform.iot.reliability.mapper.MqttFailureAggregateMapper;
import com.platform.iot.reliability.model.MqttFailureAggregate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
/** MQTT 热路径只累加内存计数，定时批量刷新 MySQL，数据库故障不阻塞重连。 */
public class MqttFailureEvidenceRecorder {

    private static final int MAX_BUCKETS = 1_000;
    private final MqttFailureAggregateMapper mapper;
    private final Map<FailureKey, FailureBucket> pending = new ConcurrentHashMap<>();

    public MqttFailureEvidenceRecorder(MqttFailureAggregateMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String component, MqttFailureCategory category, String brokerUrl) {
        LocalDateTime now = LocalDateTime.now();
        FailureKey key = new FailureKey(now.truncatedTo(ChronoUnit.MINUTES),
                component, category.name(), safeEndpoint(brokerUrl));
        if (pending.size() >= MAX_BUCKETS && !pending.containsKey(key)) {
            return;
        }
        pending.computeIfAbsent(key, ignored -> new FailureBucket(now)).add(now);
    }

    @Scheduled(fixedDelayString = "${telemetry-reliability.mqtt-failure-flush-ms:60000}")
    public void flush() {
        for (Map.Entry<FailureKey, FailureBucket> entry : pending.entrySet()) {
            if (!pending.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            try {
                mapper.upsert(toEntity(entry.getKey(), entry.getValue()));
            } catch (RuntimeException exception) {
                pending.merge(entry.getKey(), entry.getValue(), FailureBucket::merge);
            }
        }
    }

    private MqttFailureAggregate toEntity(FailureKey key, FailureBucket bucket) {
        MqttFailureAggregate aggregate = new MqttFailureAggregate();
        aggregate.setAggregateId(hash(key.toString()).substring(0, 32));
        aggregate.setBucketStart(key.bucketStart());
        aggregate.setComponent(key.component());
        aggregate.setFailureCategory(key.category());
        aggregate.setBrokerEndpoint(key.endpoint());
        aggregate.setOccurrenceCount(bucket.count.sum());
        aggregate.setFirstOccurredAt(bucket.first);
        aggregate.setLastOccurredAt(bucket.last);
        return aggregate;
    }

    private String safeEndpoint(String brokerUrl) {
        try {
            URI uri = URI.create(brokerUrl);
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (RuntimeException exception) {
            return "invalid-endpoint";
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private record FailureKey(
            LocalDateTime bucketStart, String component, String category, String endpoint) {
    }

    private static final class FailureBucket {
        private final LongAdder count = new LongAdder();
        private LocalDateTime first;
        private volatile LocalDateTime last;

        private FailureBucket(LocalDateTime occurredAt) {
            first = occurredAt;
            last = occurredAt;
        }

        private synchronized void add(LocalDateTime occurredAt) {
            count.increment();
            if (occurredAt.isBefore(first)) {
                first = occurredAt;
            }
            if (occurredAt.isAfter(last)) {
                last = occurredAt;
            }
        }

        private static FailureBucket merge(FailureBucket left, FailureBucket right) {
            FailureBucket merged = new FailureBucket(
                    left.first.isBefore(right.first) ? left.first : right.first);
            merged.count.add(left.count.sum() + right.count.sum());
            merged.last = left.last.isAfter(right.last) ? left.last : right.last;
            return merged;
        }
    }
}
