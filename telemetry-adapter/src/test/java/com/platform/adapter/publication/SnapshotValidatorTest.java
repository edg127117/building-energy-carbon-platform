package com.platform.adapter.publication;

import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.profile.ProtocolProfile;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Entry;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Snapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotValidatorTest {

    @Test
    void acceptsV1AndV2WhenRuntimeCapabilityMatches() {
        assertThatCode(() -> SnapshotValidator.validate(snapshot("V1", true), "V1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SnapshotValidator.validate(snapshot("V2", true), "V2"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDisabledRulesFromRuntimeSnapshot() {
        assertThatThrownBy(() -> SnapshotValidator.validate(snapshot("V2", false), "V2"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode")
                .isEqualTo("DISABLED_PROFILE");
    }

    @Test
    void rejectsAmbiguousSelectorsAndUnsupportedTypes() {
        Entry first = entry("P1", null, null, "DECIMAL");
        Entry second = entry("P2", null, null, "DECIMAL");
        assertThatThrownBy(() -> SnapshotValidator.validate(
                new Snapshot(1, "V2", List.of(first, second)), "V2"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode")
                .isEqualTo("AMBIGUOUS_PROFILE_SELECTOR");

        assertThatThrownBy(() -> SnapshotValidator.validate(
                new Snapshot(1, "V2", List.of(entry("P1", null, null, "TEXT"))), "V2"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode")
                .isEqualTo("UNSUPPORTED_VALUE_TYPE");
    }

    @Test
    void requiresExactTopicButAllowsDifferentDiscriminatorPaths() {
        Entry wildcard = entry("P1", "/version", "1", "DECIMAL");
        ProtocolProfile p = wildcard.profile();
        ProtocolProfile wildcardProfile = copy(p, "device/raw/+", p.deviceIdentityType(),
                p.maxAckMode(), p.correlationPolicy(), p.messageIdPath());
        assertThatThrownBy(() -> SnapshotValidator.validate(new Snapshot(
                1, "V2", List.of(new Entry(wildcardProfile, wildcard.mappings()))), "V2"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode").isEqualTo("WILDCARD_SOURCE_TOPIC");

        assertThatCode(() -> SnapshotValidator.validate(new Snapshot(1, "V2", List.of(
                entry("P1", "/version", "1", "DECIMAL"),
                entry("P2", "/kind", "2", "DECIMAL"))), "V2"))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesAckCorrelationAndV1ReliabilityBoundary() {
        Entry base = entry("P1", null, null, "DECIMAL");
        ProtocolProfile p = base.profile();
        ProtocolProfile v1Elevated = copy(p, p.sourceTopic(), p.deviceIdentityType(),
                "ADAPTER_PROXY", "SOURCE_MESSAGE_ID", "/messageId");
        assertThatThrownBy(() -> SnapshotValidator.validate(new Snapshot(
                1, "V1", List.of(new Entry(v1Elevated, base.mappings()))), "V1"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode").isEqualTo("V1_RELIABILITY_UPGRADE");

        ProtocolProfile missingField = copy(p, p.sourceTopic(), p.deviceIdentityType(),
                "ADAPTER_PROXY", "SOURCE_MESSAGE_ID", null);
        assertThatThrownBy(() -> SnapshotValidator.validate(new Snapshot(
                1, "V2", List.of(new Entry(missingField, base.mappings()))), "V2"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode").isEqualTo("CORRELATION_FIELD_MISSING");

        ProtocolProfile unsupported = copy(p, p.sourceTopic(), p.deviceIdentityType(),
                "MAGIC", "NONE", null);
        assertThatThrownBy(() -> SnapshotValidator.validate(new Snapshot(
                1, "V2", List.of(new Entry(unsupported, base.mappings()))), "V2"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode").isEqualTo("UNSUPPORTED_ACK_MODE");
    }

    @Test
    void rejectsDuplicateSourcePathsButAllowsEqualSortOrder() {
        Entry base = entry("P1", null, null, "DECIMAL");
        ProtocolFieldMapping second = new ProtocolFieldMapping(
                "M2", "P1", "/other", "ENERGY", "DECIMAL",
                "kWh", "kWh", BigDecimal.ONE, BigDecimal.ZERO, true, true, 10);
        assertThatCode(() -> SnapshotValidator.validate(new Snapshot(
                1, "V2", List.of(new Entry(base.profile(),
                List.of(base.mappings().getFirst(), second)))), "V2"))
                .doesNotThrowAnyException();

        ProtocolFieldMapping duplicatePath = new ProtocolFieldMapping(
                "M3", "P1", "/value", "ENERGY", "DECIMAL",
                "kWh", "kWh", BigDecimal.ONE, BigDecimal.ZERO, true, true, 20);
        assertThatThrownBy(() -> SnapshotValidator.validate(new Snapshot(
                1, "V2", List.of(new Entry(base.profile(),
                List.of(base.mappings().getFirst(), duplicatePath)))), "V2"))
                .isInstanceOf(SnapshotValidationException.class)
                .extracting("errorCode").isEqualTo("DUPLICATE_SOURCE_PATH");
    }

    private Snapshot snapshot(String outputVersion, boolean enabled) {
        Entry entry = entry("P1", null, null, "DECIMAL");
        ProtocolProfile original = entry.profile();
        ProtocolProfile profile = new ProtocolProfile(
                original.profileId(), original.profileCode(), original.profileVersion(),
                original.sourceTopic(), original.deviceIdentityType(),
                original.deviceIdentityPath(), original.protocolVersionPath(),
                original.expectedProtocolVersion(), original.timestampPath(), original.seqPath(),
                original.messageIdPath(), original.bootIdPath(), original.batchIdPath(),
                original.retransmittedAtPath(), original.maxAckMode(),
                original.correlationPolicy(), enabled);
        return new Snapshot(1, outputVersion, List.of(new Entry(profile, entry.mappings())));
    }

    private Entry entry(String id, String versionPath, String expected, String valueType) {
        ProtocolProfile profile = new ProtocolProfile(
                id, "CODE_" + id, 1, "device/raw/up", "MAC", "/MAC",
                versionPath, expected, null, null, null, null, null, null,
                "EVIDENCE_ONLY", "NONE", true);
        ProtocolFieldMapping mapping = new ProtocolFieldMapping(
                "M_" + id, id, "/value", "POWER", valueType,
                "kW", "kW", BigDecimal.ONE, BigDecimal.ZERO, true, true, 10);
        return new Entry(profile, List.of(mapping));
    }

    private ProtocolProfile copy(
            ProtocolProfile source,
            String topic,
            String identityType,
            String ackMode,
            String correlationPolicy,
            String messageIdPath) {
        return new ProtocolProfile(
                source.profileId(), source.profileCode(), source.profileVersion(), topic,
                identityType, source.deviceIdentityPath(), source.protocolVersionPath(),
                source.expectedProtocolVersion(), source.timestampPath(), source.seqPath(),
                messageIdPath, source.bootIdPath(), source.batchIdPath(),
                source.retransmittedAtPath(), ackMode, correlationPolicy, source.enabled());
    }
}
