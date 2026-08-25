package com.platform.iot.ingest.v2;

import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.CorrelationPolicy;
import org.springframework.stereotype.Component;

@Component
/** 只按可信绑定和真实字段降级 ACK 能力，绝不采用载荷自报 Topic。 */
public class AckModeResolver {

    public AckModeResolution resolve(DeviceIdentityBinding binding, TelemetryV2Message message) {
        AckMode configured = binding.maxAckMode();
        AckMode declared = parseMode(message.declaredAckMode());
        AckMode upperBound = level(configured) <= level(declared) ? configured : declared;
        boolean correlatable = satisfies(binding.correlationPolicy(), message);

        if (!correlatable || upperBound == AckMode.EVIDENCE_ONLY) {
            return new AckModeResolution(configured, AckMode.EVIDENCE_ONLY, null,
                    correlatable ? "PROTOCOL_CAPABILITY_LIMIT" : "CORRELATION_FIELDS_MISSING");
        }
        if (upperBound == AckMode.DEVICE_DIRECT && hasText(binding.deviceAckTopic())) {
            return new AckModeResolution(configured, AckMode.DEVICE_DIRECT,
                    binding.deviceAckTopic(), null);
        }
        if (hasText(binding.adapterAckTopic())) {
            String reason = upperBound == AckMode.DEVICE_DIRECT
                    ? "DEVICE_ACK_ROUTE_MISSING" : null;
            return new AckModeResolution(configured, AckMode.ADAPTER_PROXY,
                    binding.adapterAckTopic(), reason);
        }
        return new AckModeResolution(configured, AckMode.EVIDENCE_ONLY, null,
                "TRUSTED_ACK_ROUTE_MISSING");
    }

    private boolean satisfies(CorrelationPolicy policy, TelemetryV2Message message) {
        return switch (policy) {
            case SOURCE_MESSAGE_ID -> hasText(message.sourceMessageId());
            case BOOT_ID_AND_SEQ -> hasText(message.bootId()) && message.sourceSeq() != null;
            case SEQ_AND_COLLECTED_AT -> message.sourceSeq() != null && message.collectedAt() != null;
            case UNIQUE_COLLECTED_AT -> message.collectedAt() != null;
            case NONE -> false;
        };
    }

    private AckMode parseMode(String value) {
        try {
            return AckMode.valueOf(value == null ? "EVIDENCE_ONLY" : value.trim());
        } catch (IllegalArgumentException exception) {
            return AckMode.EVIDENCE_ONLY;
        }
    }

    private int level(AckMode mode) {
        return switch (mode) {
            case EVIDENCE_ONLY -> 0;
            case ADAPTER_PROXY -> 1;
            case DEVICE_DIRECT -> 2;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
