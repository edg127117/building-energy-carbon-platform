package com.platform.adapter.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Envelope;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Entry;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Receipt;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Snapshot;
import com.platform.adapter.publication.SnapshotValidationException;
import com.platform.adapter.publication.SnapshotValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.platform.adapter.mqtt.AdapterMqttProperties;
import org.eclipse.paho.client.mqttv3.MqttTopic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 从平台主动拉取批准配置，并以本地原子文件作为离线运行基线。
 *
 * <p>更高序号只有在摘要、内容和能力全部校验且文件原子替换成功后才进入 MQTT
 * 热路径。拉取或回执失败不会清除当前快照；回执只描述实际完成的运行切换。</p>
 */
@Component
@ConditionalOnProperty(prefix = "adapter.profile", name = "mode", havingValue = "remote")
public class RemoteProtocolProfileProvider implements ProtocolProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteProtocolProfileProvider.class);
    private static final String STATUS_LOADED = "LOADED";
    private static final String STATUS_FAILED = "FAILED";

    private final ObjectMapper objectMapper;
    private final AdapterConfigurationClient client;
    private final AdapterProfileProperties properties;
    private final AdapterMqttProperties mqttProperties;
    private final ProtocolSnapshotStore store;
    private volatile ActiveSnapshot activeSnapshot;

    @Autowired
    public RemoteProtocolProfileProvider(
            ObjectMapper objectMapper,
            AdapterConfigurationClient client,
            AdapterProfileProperties properties,
            AdapterMqttProperties mqttProperties) {
        this(objectMapper, client, properties, mqttProperties, new ProtocolSnapshotStore(
                objectMapper, properties.getRemote().getSnapshotFile(),
                properties.getRemote().getMaxResponseBytes(),
                properties.getRemote().getTargetId()));
    }

    RemoteProtocolProfileProvider(
            ObjectMapper objectMapper,
            AdapterConfigurationClient client,
            AdapterProfileProperties properties,
            AdapterMqttProperties mqttProperties,
            ProtocolSnapshotStore store) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.properties = properties;
        this.mqttProperties = mqttProperties;
        this.store = store;
    }

    @PostConstruct
    public void initialize() {
        requireRemoteConfiguration();
        restore();
        refresh();
    }

    @Scheduled(fixedDelayString = "${adapter.profile.refresh-millis:60000}")
    public synchronized void refresh() {
        try {
            Optional<Envelope> fetched = client.fetch();
            fetched.ifPresent(this::accept);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("远程协议快照拉取被中断，继续使用当前版本");
        } catch (IOException | RuntimeException exception) {
            log.warn("远程协议快照拉取失败，继续使用当前版本: {}",
                    safeReason(exception));
        }
    }

    @Override
    public ResolvedProtocolProfile resolve(String topic, JsonNode payload) {
        ActiveSnapshot current = activeSnapshot;
        if (current == null) {
            throw new ProtocolProfileUnavailableException("远程协议快照尚未成功加载");
        }
        List<ResolvedProtocolProfile> candidates = current.profilesByTopic().get(topic);
        if (candidates == null || candidates.isEmpty()) {
            throw new ProtocolProfileResolutionException("MQTT主题未配置启用协议模板: " + topic);
        }
        List<ResolvedProtocolProfile> matches = candidates.stream()
                .filter(candidate -> versionMatches(candidate.profile(), payload))
                .sorted(Comparator.comparingInt(candidate -> candidate.profile().profileVersion()))
                .toList();
        if (matches.size() != 1) {
            throw new ProtocolProfileResolutionException(
                    "无法唯一确定协议模板: topic=" + topic + ", matches=" + matches.size());
        }
        return matches.getFirst();
    }

    private void restore() {
        try {
            Optional<Envelope> persisted = store.load();
            if (persisted.isPresent()) {
                ActiveSnapshot restored = validateAndBuild(persisted.get());
                activeSnapshot = restored;
                log.info("已恢复本地协议快照: sequence={}, digest={}",
                        restored.sequence(), abbreviated(restored.digest()));
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("本地协议快照恢复失败，等待远程有效版本: {}", safeReason(exception));
        }
    }

    private void accept(Envelope envelope) {
        ActiveSnapshot current = activeSnapshot;
        if (envelope == null || envelope.sequence() <= 0) {
            sendFailed(envelope, "INVALID_SEQUENCE");
            return;
        }
        if (current != null && envelope.sequence() < current.sequence()) {
            log.warn("忽略旧协议快照: receivedSequence={}, currentSequence={}",
                    envelope.sequence(), current.sequence());
            return;
        }
        if (current != null && envelope.sequence() == current.sequence()) {
            if (constantTimeEquals(current.digest(), normalizedDigest(envelope.digest()))) {
                sendReceipt(new Receipt(current.sequence(), current.digest(), STATUS_LOADED, null));
            } else {
                sendFailed(envelope, "SEQUENCE_DIGEST_CONFLICT");
            }
            return;
        }

        try {
            ActiveSnapshot candidate = validateAndBuild(envelope);
            // 文件是进程重启后的权威缓存，落盘失败时不能先切换内存。
            store.save(envelope);
            activeSnapshot = candidate;
            log.info("远程协议快照已加载: sequence={}, digest={}, profiles={}",
                    candidate.sequence(), abbreviated(candidate.digest()),
                    candidate.profileCount());
            sendReceipt(new Receipt(
                    candidate.sequence(), candidate.digest(), STATUS_LOADED, null));
        } catch (SnapshotValidationException exception) {
            sendFailed(envelope, exception.getErrorCode());
        } catch (JsonProcessingException exception) {
            sendFailed(envelope, "INVALID_CONTENT_JSON");
        } catch (IOException exception) {
            sendFailed(envelope, "SNAPSHOT_PERSIST_FAILED");
        } catch (RuntimeException exception) {
            sendFailed(envelope, "INVALID_SNAPSHOT");
        }
    }

    private ActiveSnapshot validateAndBuild(Envelope envelope) throws IOException {
        if (envelope.sequence() <= 0) {
            throw new SnapshotValidationException("INVALID_SEQUENCE", "发布序号必须为正数");
        }
        String digest = normalizedDigest(envelope.digest());
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new SnapshotValidationException("INVALID_DIGEST", "摘要必须是SHA-256十六进制");
        }
        byte[] content = envelope.contentJson() == null
                ? new byte[0] : envelope.contentJson().getBytes(StandardCharsets.UTF_8);
        if (content.length == 0 || content.length > properties.getRemote().getMaxResponseBytes()) {
            throw new SnapshotValidationException("INVALID_CONTENT_SIZE", "快照内容为空或超过大小限制");
        }
        if (!constantTimeEquals(digest, sha256(content))) {
            throw new SnapshotValidationException("DIGEST_MISMATCH", "快照内容摘要不匹配");
        }
        Snapshot snapshot = objectMapper.readValue(content, Snapshot.class);
        SnapshotValidator.validate(snapshot, properties.getOutputVersion());
        validateSubscribedTopics(snapshot);
        return new ActiveSnapshot(
                envelope.sequence(), digest, buildIndex(snapshot.profiles()), snapshot.profiles().size());
    }

    private void validateSubscribedTopics(Snapshot snapshot) {
        String subscription = mqttProperties.getRawTopic();
        if (subscription == null || subscription.isBlank()) {
            throw new SnapshotValidationException(
                    "RAW_TOPIC_MISSING", "adapter.mqtt.raw-topic不能为空");
        }
        for (Entry entry : snapshot.profiles()) {
            if (!MqttTopic.isMatched(subscription, entry.profile().sourceTopic())) {
                throw new SnapshotValidationException(
                        "SOURCE_TOPIC_NOT_SUBSCRIBED", "快照包含适配器未订阅的sourceTopic");
            }
        }
    }

    private Map<String, List<ResolvedProtocolProfile>> buildIndex(List<Entry> entries) {
        Map<String, List<ResolvedProtocolProfile>> mutable = new LinkedHashMap<>();
        for (Entry entry : entries) {
            List<com.platform.adapter.profile.ProtocolFieldMapping> mappings = entry.mappings().stream()
                    .sorted(Comparator
                            .comparingInt(com.platform.adapter.profile.ProtocolFieldMapping::sortOrder)
                            .thenComparing(com.platform.adapter.profile.ProtocolFieldMapping::mappingId))
                    .toList();
            mutable.computeIfAbsent(entry.profile().sourceTopic(), ignored -> new ArrayList<>())
                    .add(new ResolvedProtocolProfile(entry.profile(), mappings));
        }
        Map<String, List<ResolvedProtocolProfile>> immutable = new LinkedHashMap<>();
        mutable.forEach((topic, profiles) -> immutable.put(topic, List.copyOf(profiles)));
        return Map.copyOf(immutable);
    }

    private boolean versionMatches(
            com.platform.adapter.profile.ProtocolProfile profile,
            JsonNode payload) {
        String expected = profile.expectedProtocolVersion();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (payload == null) {
            return false;
        }
        JsonNode versionNode = payload.at(profile.protocolVersionPath());
        return versionNode.isValueNode() && expected.equals(versionNode.asText());
    }

    private void sendFailed(Envelope envelope, String errorCode) {
        if (envelope == null || envelope.sequence() <= 0) {
            log.warn("拒绝无有效序号的协议快照: errorCode={}", errorCode);
            return;
        }
        sendReceipt(new Receipt(
                envelope.sequence(), normalizedDigest(envelope.digest()), STATUS_FAILED, errorCode));
    }

    private void sendReceipt(Receipt receipt) {
        try {
            client.sendReceipt(receipt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("协议快照回执上报被中断: sequence={}, status={}",
                    receipt.sequence(), receipt.status());
        } catch (IOException | RuntimeException exception) {
            log.warn("协议快照回执上报失败: sequence={}, status={}, reason={}",
                    receipt.sequence(), receipt.status(), safeReason(exception));
        }
    }

    private void requireRemoteConfiguration() {
        if (!"remote".equalsIgnoreCase(properties.getMode())) {
            throw new IllegalArgumentException("远程provider只能在adapter.profile.mode=remote时启用");
        }
        if (properties.getRemote().getMaxResponseBytes() <= 0 || properties.getRefreshMillis() <= 0) {
            throw new IllegalArgumentException("远程快照大小和刷新间隔必须为正数");
        }
    }

    private static String normalizedDigest(String digest) {
        return digest == null ? null : digest.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256", exception);
        }
    }

    private static String abbreviated(String digest) {
        return digest == null || digest.length() < 12 ? "invalid" : digest.substring(0, 12);
    }

    private static String safeReason(Exception exception) {
        return exception.getClass().getSimpleName();
    }

    private record ActiveSnapshot(
            long sequence,
            String digest,
            Map<String, List<ResolvedProtocolProfile>> profilesByTopic,
            int profileCount) {
    }
}
