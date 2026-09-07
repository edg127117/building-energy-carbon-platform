package com.platform.carbon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarbonEvidenceTest {
    @Test
    void sharedEvidenceReconstructsExactlyAndRejectsMissingOrChangedContent() {
        var full = (com.fasterxml.jackson.databind.node.ObjectNode) CarbonEvidence.parse(
                "{\"schemaVersion\":1,\"snapshotId\":\"a\",\"factor\":{\"value\":\"0.5568\"}}");
        var shared = CarbonEvidence.object();
        String hash = CarbonCalculationCore.sha256(CarbonEvidence.canonical(full));
        String packed = CarbonEvidence.compact(full, shared);
        assertThat(shared.size()).isEqualTo(1);
        assertThat(CarbonEvidence.stored(packed, hash, CarbonEvidence.canonical(shared))).isEqualTo(full);
        CarbonEvidence.compact(full, shared);
        assertThat(shared.size()).isEqualTo(1);
        assertThatThrownBy(() -> CarbonEvidence.stored(packed, hash, "{}"))
                .hasMessageContaining("共享证据缺失");
        ((com.fasterxml.jackson.databind.node.ObjectNode) shared.elements().next()).put("value", "9");
        assertThatThrownBy(() -> CarbonEvidence.stored(packed, hash, CarbonEvidence.canonical(shared)))
                .hasMessageContaining("摘要不一致");
    }

    @Test
    void preservesDecimalsAndVerifiesAfterMysqlReordersProperties() {
        String json = "{\"schemaVersion\":1,\"amount\":123456789012345678.012345678901234567,\"ratio\":1.000}";
        String hash = CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.parse(json)));
        String stored = "{\"ratio\":\"1\", \"amount\":\"123456789012345678.012345678901234567\", \"schemaVersion\":1}";
        assertThat(CarbonEvidence.stored(stored, hash).path("amount").asText())
                .isEqualTo("123456789012345678.012345678901234567");
        assertThatThrownBy(() -> CarbonEvidence.stored(stored.replace("123456789012345678", "123456789012345679"), hash))
                .hasMessageContaining("摘要不一致");
    }

    @Test
    void exposesLegacyAsPartialWithoutInventingEvidence() {
        var legacy = CarbonEvidence.stored("{\"snapshotId\":\"old-id\"}", "old-hash");
        assertThat(legacy.path("traceStatus").asText()).isEqualTo("LEGACY_PARTIAL");
        assertThat(legacy.at("/originalEvidence/snapshotId").asText()).isEqualTo("old-id");
        assertThat(legacy.has("upstream")).isFalse();
    }
}
