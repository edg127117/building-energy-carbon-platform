package com.platform.iot.ingest.v2;

import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.identity.DeviceIdentityProvider;
import com.platform.iot.identity.DeviceIdentitySnapshotUnavailableException;
import com.platform.iot.identity.UnknownDeviceHandler;
import com.platform.iot.ingest.standard.StandardTelemetryIngestionService;
import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import com.platform.iot.ingest.standard.StandardTelemetryOutcome;
import com.platform.iot.ingest.standard.StandardTelemetryResult;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.EvidenceState;
import com.platform.iot.reliability.ReceiptStatus;
import com.platform.iot.reliability.mapper.TelemetryReceiptFailureMapper;
import com.platform.iot.reliability.mapper.TelemetryReceiptMapper;
import com.platform.iot.reliability.model.TelemetryReceipt;
import com.platform.iot.reliability.model.TelemetryReceiptFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

@Service
/** V2 消息级幂等、不可覆盖写入、终态回执和应用 ACK 决策。 */
public class TelemetryV2IngestionService {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final DeviceIdentityProvider identityProvider;
    private final UnknownDeviceHandler unknownDeviceHandler;
    private final StandardTelemetryIngestionService standardIngestionService;
    private final TelemetryReceiptMapper receiptMapper;
    private final TelemetryReceiptFailureMapper failureMapper;
    private final AckModeResolver ackModeResolver;
    private final MeterRegistry meterRegistry;
    private final String trustedAdapterAckTopic;

    public TelemetryV2IngestionService(
            DeviceIdentityProvider identityProvider,
            UnknownDeviceHandler unknownDeviceHandler,
            StandardTelemetryIngestionService standardIngestionService,
            TelemetryReceiptMapper receiptMapper,
            TelemetryReceiptFailureMapper failureMapper,
            AckModeResolver ackModeResolver,
            MeterRegistry meterRegistry,
            @Value("${mqtt.topics.v2-adapter-ack:platform/telemetry/v2/ack/adapter}")
            String trustedAdapterAckTopic) {
        this.identityProvider = identityProvider;
        this.unknownDeviceHandler = unknownDeviceHandler;
        this.standardIngestionService = standardIngestionService;
        this.receiptMapper = receiptMapper;
        this.failureMapper = failureMapper;
        this.ackModeResolver = ackModeResolver;
        this.meterRegistry = meterRegistry;
        this.trustedAdapterAckTopic = trustedAdapterAckTopic;
    }

    @Transactional
    public V2ProcessingResult ingest(TelemetryV2Message message, long platformReceivedAt) {
        String envelopeError = validateEnvelope(message, platformReceivedAt);
        if (envelopeError != null) {
            recordFailure(message == null ? null : message.canonicalMessageId(), null,
                    "VALIDATION", "INVALID_V2_ENVELOPE", envelopeError);
            return permanent(null, ReceiptStatus.PLATFORM_REJECTED,
                    "INVALID_V2_ENVELOPE", null, platformReceivedAt, null, envelopeError);
        }

        DeviceIdentityBinding binding;
        try {
            binding = identityProvider.find(message.deviceIdentity()).orElse(null);
        } catch (DeviceIdentitySnapshotUnavailableException exception) {
            return retryable(message.canonicalMessageId(), "IDENTITY_SNAPSHOT_UNAVAILABLE",
                    exception.getMessage());
        }
        if (binding == null) {
            discoverUnknown(message, platformReceivedAt);
            recordFailure(message.canonicalMessageId(), null, "IDENTITY",
                    "DISCOVERED_NOT_ACTIVE", "设备尚未完成启用绑定");
            AckModeResolution unknownMode = resolveUnknownMode(message);
            return permanent(message.canonicalMessageId(),
                    ReceiptStatus.DISCOVERED_NOT_ACTIVE, "DISCOVERED_NOT_ACTIVE",
                    unknownMode, platformReceivedAt, null, "设备尚未完成启用绑定");
        }

        AckModeResolution mode = ackModeResolver.resolve(binding, message);
        if (!binding.expectedProfileCode().equals(message.profileCode())) {
            recordFailure(message.canonicalMessageId(), binding.buildingId(),
                    "IDENTITY", "PROFILE_MISMATCH", "协议模板与设备绑定不一致");
            return permanent(message.canonicalMessageId(), ReceiptStatus.PLATFORM_REJECTED,
                    "PROFILE_MISMATCH", mode, platformReceivedAt, null,
                    "协议模板与设备绑定不一致");
        }

        String payloadHash = payloadHash(message);
        LocalDateTime receivedTime = time(platformReceivedAt);
        TelemetryReceipt existing = receiptMapper.selectForUpdate(message.canonicalMessageId());
        if (existing != null) {
            receiptMapper.incrementAttempt(message.canonicalMessageId(), receivedTime,
                    trimToNull(message.batchId()), nullableTime(message.retransmittedAt()));
            if (!payloadHash.equals(existing.getPayloadHash())) {
                recordFailure(message.canonicalMessageId(), binding.buildingId(),
                        "IDEMPOTENCY", "MESSAGE_CONFLICT",
                        "相同设备业务键对应不同有效载荷");
                return permanent(message.canonicalMessageId(), ReceiptStatus.MESSAGE_CONFLICT,
                        "MESSAGE_CONFLICT", mode, platformReceivedAt,
                        existing.getPersistedAt(), "相同设备业务键对应不同有效载荷");
            }
            meterRegistry.counter("iot.telemetry.v2", "outcome", "duplicate").increment();
            return permanent(message.canonicalMessageId(), ReceiptStatus.DUPLICATE_PERSISTED,
                    "DUPLICATE_PERSISTED", mode, platformReceivedAt,
                    existing.getPersistedAt(), null);
        }

        StandardTelemetryResult storage = standardIngestionService.ingestImmutable(
                toStandardMessage(message), platformReceivedAt);
        if (storage.outcome() == StandardTelemetryOutcome.RETRYABLE_FAILURE) {
            meterRegistry.counter("iot.telemetry.v2", "outcome", "retryable").increment();
            return retryable(message.canonicalMessageId(), "STORAGE_TEMPORARILY_UNAVAILABLE",
                    storage.detail());
        }
        if (storage.outcome() == StandardTelemetryOutcome.REJECTED) {
            boolean conflict = storage.detail() != null
                    && storage.detail().startsWith("MESSAGE_CONFLICT");
            String code = conflict ? "MESSAGE_CONFLICT" : "PLATFORM_REJECTED";
            ReceiptStatus status = conflict
                    ? ReceiptStatus.MESSAGE_CONFLICT : ReceiptStatus.PLATFORM_REJECTED;
            recordFailure(message.canonicalMessageId(), binding.buildingId(),
                    conflict ? "IDEMPOTENCY" : "VALIDATION", code, storage.detail());
            return permanent(message.canonicalMessageId(), status, code, mode,
                    platformReceivedAt, null, storage.detail());
        }

        LocalDateTime persistedAt = LocalDateTime.now(PROJECT_ZONE);
        TelemetryReceipt receipt = new TelemetryReceipt();
        receipt.setCanonicalMessageId(message.canonicalMessageId());
        receipt.setIdentityId(binding.identityId());
        receipt.setBuildingId(binding.buildingId());
        receipt.setEquipId(binding.equipmentId());
        receipt.setProfileCode(message.profileCode());
        receipt.setSourceMessageId(trimToNull(message.sourceMessageId()));
        receipt.setSourceSeq(message.sourceSeq());
        receipt.setCollectedAt(nullableTime(message.collectedAt()));
        receipt.setAdapterReceivedAt(time(message.adapterReceivedAt()));
        receipt.setFirstPlatformReceivedAt(receivedTime);
        receipt.setLastPlatformReceivedAt(receivedTime);
        receipt.setPersistedAt(persistedAt);
        receipt.setRetransmittedAt(nullableTime(message.retransmittedAt()));
        receipt.setBatchId(trimToNull(message.batchId()));
        receipt.setIdSource(message.idSource());
        receipt.setTimeSource(message.timeSource());
        receipt.setDedupMode(message.dedupMode());
        receipt.setPayloadHash(payloadHash);
        receipt.setConfiguredAckMode(mode.configuredMode().name());
        receipt.setActualAckMode(mode.actualMode().name());
        receipt.setDowngradeReason(mode.downgradeReason());
        receipt.setReceiptStatus(ReceiptStatus.PLATFORM_PERSISTED.name());
        receipt.setResultCode("PLATFORM_PERSISTED");
        receipt.setMetricCount(message.metrics().size());
        receipt.setAttemptCount(1);
        receipt.setDevicePubackState(EvidenceState.UNKNOWN.name());
        receipt.setAdapterPublishPubackState(EvidenceState.UNKNOWN.name());
        receipt.setPlatformConsumerAckState(EvidenceState.PENDING.name());
        receipt.setApplicationAckPubackState(mode.actualMode() == AckMode.EVIDENCE_ONLY
                ? EvidenceState.NOT_APPLICABLE.name() : EvidenceState.PENDING.name());
        receiptMapper.insert(receipt);
        meterRegistry.counter("iot.telemetry.v2", "outcome", "persisted").increment();
        return permanent(message.canonicalMessageId(), ReceiptStatus.PLATFORM_PERSISTED,
                "PLATFORM_PERSISTED", mode, platformReceivedAt, persistedAt, null);
    }

    public void markDeliveryCompleted(
            String canonicalMessageId, boolean applicationAckPublished) {
        if (canonicalMessageId == null) {
            return;
        }
        receiptMapper.markDeliveryCompleted(canonicalMessageId,
                applicationAckPublished ? EvidenceState.OBSERVED.name()
                        : EvidenceState.NOT_APPLICABLE.name(),
                applicationAckPublished ? LocalDateTime.now(PROJECT_ZONE) : null);
    }

    public void recordApplicationAckFailure(String canonicalMessageId, String safeDetail) {
        TelemetryReceipt receipt = canonicalMessageId == null
                ? null : receiptMapper.selectById(canonicalMessageId);
        recordFailure(canonicalMessageId,
                receipt == null ? null : receipt.getBuildingId(),
                "APPLICATION_ACK", "APPLICATION_ACK_PUBLISH_FAILED", safeDetail);
    }

    public void recordMalformedFailure(String safeDetail) {
        recordFailure(null, null, "VALIDATION", "MALFORMED_V2_JSON", safeDetail);
    }

    private V2ProcessingResult permanent(
            String canonicalMessageId,
            ReceiptStatus status,
            String resultCode,
            AckModeResolution mode,
            long platformReceivedAt,
            LocalDateTime persistedAt,
            String detail) {
        AckMode actual = mode == null ? AckMode.EVIDENCE_ONLY : mode.actualMode();
        PlatformApplicationAck ack = new PlatformApplicationAck(
                "1.0", canonicalMessageId, status, resultCode,
                platformReceivedAt, epoch(persistedAt), actual,
                actual == AckMode.ADAPTER_PROXY ? "ADAPTER_ONLY"
                        : actual == AckMode.DEVICE_DIRECT ? "DEVICE" : "EVIDENCE_ONLY");
        return new V2ProcessingResult(canonicalMessageId, status, resultCode, 0,
                false, actual, mode == null ? null : mode.ackTopic(), ack, detail);
    }

    private V2ProcessingResult retryable(String canonicalMessageId, String code, String detail) {
        return new V2ProcessingResult(canonicalMessageId, null, code, 0,
                true, AckMode.EVIDENCE_ONLY, null, null, detail);
    }

    private StandardTelemetryMessage toStandardMessage(TelemetryV2Message message) {
        long effectiveEventTime = message.collectedAt() == null
                ? message.adapterReceivedAt() : message.collectedAt();
        String source = message.collectedAt() == null
                ? "SERVER_RECEIVED" : "DEVICE_REPORTED";
        return new StandardTelemetryMessage(
                "1.0", message.profileCode(), message.profileVersion(),
                message.deviceIdentity(), effectiveEventTime, message.adapterReceivedAt(),
                source, message.sourceSeq(), message.metrics());
    }

    private AckModeResolution resolveUnknownMode(TelemetryV2Message message) {
        if ("ADAPTER_PROXY".equals(message.declaredAckMode())
                && hasText(trustedAdapterAckTopic)) {
            return new AckModeResolution(AckMode.ADAPTER_PROXY, AckMode.ADAPTER_PROXY,
                    trustedAdapterAckTopic, "DEVICE_BINDING_MISSING");
        }
        return new AckModeResolution(AckMode.EVIDENCE_ONLY, AckMode.EVIDENCE_ONLY,
                null, "DEVICE_BINDING_MISSING");
    }

    private void discoverUnknown(TelemetryV2Message message, long platformReceivedAt) {
        unknownDeviceHandler.recordDiscovered(toStandardMessage(message), platformReceivedAt);
    }

    private String validateEnvelope(TelemetryV2Message message, long platformReceivedAt) {
        if (message == null || !"2.0".equals(message.standardVersion())
                || !hasText(message.profileCode()) || message.profileVersion() <= 0
                || message.deviceIdentity() == null || !hasText(message.deviceIdentity().type())
                || !hasText(message.deviceIdentity().value())
                || !hasText(message.canonicalMessageId())
                || message.canonicalMessageId().length() > 64
                || message.adapterReceivedAt() <= 0 || platformReceivedAt <= 0
                || message.collectedAt() != null && message.collectedAt() <= 0
                || message.retransmittedAt() != null && message.retransmittedAt() <= 0
                || message.sourceSeq() != null && message.sourceSeq() < 0
                || message.metrics() == null || message.metrics().isEmpty()
                || !("DEVICE_REPORTED".equals(message.timeSource())
                || "ADAPTER_RECEIVED".equals(message.timeSource()))
                || !("DEVICE_REPORTED".equals(message.idSource())
                || "ADAPTER_DERIVED".equals(message.idSource())
                || "ADAPTER_GENERATED".equals(message.idSource()))
                || !("EXACT".equals(message.dedupMode())
                || "DERIVED".equals(message.dedupMode())
                || "NONE".equals(message.dedupMode()))) {
            return "V2 报文结构、版本、身份或时间字段无效";
        }
        if (message.collectedAt() == null
                && !"ADAPTER_RECEIVED".equals(message.timeSource())) {
            return "缺少采集时间时 timeSource 必须为 ADAPTER_RECEIVED";
        }
        return null;
    }

    private String payloadHash(TelemetryV2Message message) {
        StringBuilder canonical = new StringBuilder()
                .append(message.deviceIdentity().type()).append('\u001f')
                .append(message.deviceIdentity().value()).append('\u001f')
                .append(message.profileCode()).append('\u001f')
                .append(message.profileVersion()).append('\u001f')
                .append(value(message.sourceMessageId())).append('\u001f')
                .append(value(message.bootId())).append('\u001f')
                .append(value(message.sourceSeq())).append('\u001f')
                .append(value(message.collectedAt())).append('\u001f');
        message.metrics().stream().sorted(Comparator.comparing(metric -> metric.code()))
                .forEach(metric -> canonical.append(metric.code()).append('=')
                        .append(metric.value().stripTrailingZeros().toPlainString()).append('@')
                        .append(metric.unit()).append('\u001e'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private void recordFailure(
            String canonicalMessageId,
            String buildingId,
            String stage,
            String code,
            String detail) {
        try {
            TelemetryReceiptFailure failure = new TelemetryReceiptFailure();
            failure.setFailureId(UUID.randomUUID().toString().replace("-", ""));
            failure.setCanonicalMessageId(trimToNull(canonicalMessageId));
            failure.setBuildingId(trimToNull(buildingId));
            failure.setFailureStage(stage);
            failure.setFailureCode(code);
            failure.setSafeDetail(sanitize(detail));
            failure.setOccurredAt(LocalDateTime.now(PROJECT_ZONE));
            failureMapper.insert(failure);
        } catch (RuntimeException ignored) {
            meterRegistry.counter("iot.telemetry.v2.failure-evidence", "outcome", "failed")
                    .increment();
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String safe = value.replace('\r', ' ').replace('\n', ' ');
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    private LocalDateTime time(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }

    private LocalDateTime nullableTime(Long epochMillis) {
        return epochMillis == null ? null : time(epochMillis);
    }

    private Long epoch(LocalDateTime value) {
        return value == null ? null : value.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
