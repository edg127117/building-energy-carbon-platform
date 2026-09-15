package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Envelope;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Entry;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Receipt;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Snapshot;
import com.platform.adapter.mqtt.AdapterMqttProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteProtocolProfileProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void persistsBeforeSwitchAndRestoresAfterOfflineRestart() throws Exception {
        FakeClient firstClient = new FakeClient();
        Envelope envelope = envelope(1, "V2", "PROFILE_A", "/MAC");
        firstClient.next = Optional.of(envelope);
        AdapterProfileProperties properties = properties("V2");
        RemoteProtocolProfileProvider first = provider(properties, firstClient);

        first.initialize();

        assertThat(first.resolve("device/raw/up", objectMapper.readTree("{}"))
                .profile().profileId()).isEqualTo("PROFILE_A");
        assertThat(firstClient.receipts).containsExactly(
                new Receipt(1, envelope.digest(), "LOADED", null));

        FakeClient offline = new FakeClient();
        offline.failure = new IOException("offline");
        RemoteProtocolProfileProvider restarted = provider(properties, offline);
        restarted.initialize();

        assertThat(restarted.resolve("device/raw/up", objectMapper.readTree("{}"))
                .profile().profileId()).isEqualTo("PROFILE_A");
        assertThat(offline.receipts).isEmpty();
    }

    @Test
    void rejectsFailedCandidateAndKeepsPreviousSnapshot() throws Exception {
        FakeClient client = new FakeClient();
        AdapterProfileProperties properties = properties("V2");
        RemoteProtocolProfileProvider provider = provider(properties, client);
        client.next = Optional.of(envelope(2, "V2", "PROFILE_A", "/MAC"));
        provider.initialize();
        client.receipts.clear();
        Envelope invalid = new Envelope(3, "0".repeat(64), "{}");
        client.next = Optional.of(invalid);

        provider.refresh();

        assertThat(client.receipts).containsExactly(
                new Receipt(3, invalid.digest(), "FAILED", "DIGEST_MISMATCH"));
        assertThat(provider.resolve("device/raw/up", objectMapper.readTree("{}"))
                .profile().profileId()).isEqualTo("PROFILE_A");
    }

    @Test
    void handlesOldDuplicateAndConflictingSequencesPrecisely() throws Exception {
        FakeClient client = new FakeClient();
        RemoteProtocolProfileProvider provider = provider(properties("V2"), client);
        Envelope current = envelope(10, "V2", "PROFILE_A", "/MAC");
        client.next = Optional.of(current);
        provider.initialize();
        client.receipts.clear();

        client.next = Optional.of(envelope(9, "V2", "OLD", "/old"));
        provider.refresh();
        assertThat(client.receipts).isEmpty();

        client.next = Optional.of(current);
        provider.refresh();
        assertThat(client.receipts).containsExactly(
                new Receipt(10, current.digest(), "LOADED", null));

        client.receipts.clear();
        Envelope conflict = envelope(10, "V2", "OTHER", "/other");
        client.next = Optional.of(conflict);
        provider.refresh();
        assertThat(client.receipts).containsExactly(
                new Receipt(10, conflict.digest(), "FAILED", "SEQUENCE_DIGEST_CONFLICT"));
    }

    @Test
    void rollbackUsesHigherSequenceWithHistoricalContent() throws Exception {
        FakeClient client = new FakeClient();
        RemoteProtocolProfileProvider provider = provider(properties("V2"), client);
        Envelope historical = envelope(1, "V2", "PROFILE_A", "/MAC");
        client.next = Optional.of(historical);
        provider.initialize();
        client.next = Optional.of(envelope(2, "V2", "PROFILE_B", "/SN"));
        provider.refresh();
        Envelope rollback = new Envelope(3, historical.digest(), historical.contentJson());
        client.next = Optional.of(rollback);

        provider.refresh();

        assertThat(provider.resolve("device/raw/up", objectMapper.readTree("{}"))
                .profile().profileId()).isEqualTo("PROFILE_A");
        assertThat(client.receipts.getLast()).isEqualTo(
                new Receipt(3, historical.digest(), "LOADED", null));
    }

    @Test
    void rejectsOutputVersionMismatchWithoutMakingSnapshotAvailable() throws Exception {
        FakeClient client = new FakeClient();
        client.next = Optional.of(envelope(1, "V1", "PROFILE_A", "/MAC"));
        RemoteProtocolProfileProvider provider = provider(properties("V2"), client);

        provider.initialize();

        assertThat(client.receipts.getFirst().errorCode()).isEqualTo("OUTPUT_VERSION_MISMATCH");
        assertThatThrownBy(() -> provider.resolve("device/raw/up", objectMapper.readTree("{}")))
                .isInstanceOf(ProtocolProfileUnavailableException.class);
    }

    @Test
    void rejectsSnapshotTopicOutsideActualMqttSubscription() throws Exception {
        FakeClient client = new FakeClient();
        client.next = Optional.of(envelope(
                1, "V2", "PROFILE_A", "/MAC", "other/raw/up"));
        RemoteProtocolProfileProvider provider = provider(properties("V2"), client);

        provider.initialize();

        assertThat(client.receipts.getFirst().errorCode())
                .isEqualTo("SOURCE_TOPIC_NOT_SUBSCRIBED");
        assertThatThrownBy(() -> provider.resolve("other/raw/up", objectMapper.readTree("{}")))
                .isInstanceOf(ProtocolProfileUnavailableException.class);
    }

    @Test
    void doesNotRestoreCacheOwnedByAnotherTarget() throws Exception {
        FakeClient firstClient = new FakeClient();
        firstClient.next = Optional.of(envelope(1, "V2", "PROFILE_A", "/MAC"));
        AdapterProfileProperties firstProperties = properties("V2");
        RemoteProtocolProfileProvider first = provider(firstProperties, firstClient);
        first.initialize();

        AdapterProfileProperties otherProperties = properties("V2");
        otherProperties.getRemote().setTargetId("target-2");
        FakeClient offline = new FakeClient();
        offline.failure = new IOException("offline");
        RemoteProtocolProfileProvider other = provider(otherProperties, offline);
        other.initialize();

        assertThatThrownBy(() -> other.resolve("device/raw/up", objectMapper.readTree("{}")))
                .isInstanceOf(ProtocolProfileUnavailableException.class);
        assertThat(otherProperties.getRemote().getSnapshotFile()).exists();
    }

    private RemoteProtocolProfileProvider provider(
            AdapterProfileProperties properties, FakeClient client) {
        return new RemoteProtocolProfileProvider(
                objectMapper, client, properties, mqttProperties(),
                new ProtocolSnapshotStore(objectMapper, properties.getRemote().getSnapshotFile(),
                        properties.getRemote().getMaxResponseBytes(),
                        properties.getRemote().getTargetId()));
    }

    private AdapterMqttProperties mqttProperties() {
        AdapterMqttProperties properties = new AdapterMqttProperties();
        properties.setRawTopic("device/raw/#");
        return properties;
    }

    private AdapterProfileProperties properties(String outputVersion) {
        AdapterProfileProperties properties = new AdapterProfileProperties();
        properties.setMode("remote");
        properties.setOutputVersion(outputVersion);
        properties.getRemote().setSnapshotFile(tempDir.resolve("snapshot.json"));
        properties.getRemote().setTargetId("target-1");
        return properties;
    }

    private Envelope envelope(
            long sequence, String outputVersion, String profileId, String identityPath)
            throws Exception {
        return envelope(sequence, outputVersion, profileId, identityPath, "device/raw/up");
    }

    private Envelope envelope(
            long sequence, String outputVersion, String profileId, String identityPath,
            String sourceTopic) throws Exception {
        ProtocolProfile profile = new ProtocolProfile(
                profileId, "CODE_" + profileId, 1, sourceTopic,
                "MAC", identityPath, null, null, null, null,
                "/messageId", null, null, null, "EVIDENCE_ONLY", "NONE", true);
        ProtocolFieldMapping mapping = new ProtocolFieldMapping(
                "MAP_" + profileId, profileId, "/value", "POWER", "DECIMAL",
                "kW", "kW", BigDecimal.ONE, BigDecimal.ZERO, true, true, 10);
        String content = objectMapper.writeValueAsString(
                new Snapshot(1, outputVersion, List.of(new Entry(profile, List.of(mapping)))));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        return new Envelope(sequence, digest, content);
    }

    private static final class FakeClient implements AdapterConfigurationClient {
        private Optional<Envelope> next = Optional.empty();
        private IOException failure;
        private final List<Receipt> receipts = new ArrayList<>();

        @Override
        public Optional<Envelope> fetch() throws IOException {
            if (failure != null) {
                throw failure;
            }
            return next;
        }

        @Override
        public void sendReceipt(Receipt receipt) {
            receipts.add(receipt);
        }
    }
}
