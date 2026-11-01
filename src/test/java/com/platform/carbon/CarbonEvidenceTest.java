package com.platform.carbon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarbonEvidenceTest {
    @Test
    void canonicalBytesAndHashesStayStableForOrderedAndUnorderedNestedEvidence() {
        var unordered=CarbonEvidence.parse("{\"z\":[{\"b\":true,\"a\":null}],\"a\":{\"y\":2,\"x\":\"南京\\n证据\"}}");
        String original=unordered.toString();
        String expected="{\"a\":{\"x\":\"南京\\n证据\",\"y\":2},\"z\":[{\"a\":null,\"b\":true}]}";
        String canonical=CarbonEvidence.canonical(unordered);
        assertThat(canonical).isEqualTo(expected);
        assertThat(CarbonEvidence.canonical(CarbonEvidence.parse(canonical))).isEqualTo(expected);
        assertThat(CarbonCalculationCore.sha256(canonical)).isEqualTo(CarbonCalculationCore.sha256(expected));
        assertThat(unordered.toString()).isEqualTo(original);
    }
    @Test
    void orderedPropertiesStillNormalizeNestedFloatingPointNumbersToExactDecimalText() {
        var ordered=CarbonEvidence.parse("{\"a\":[{\"b\":1.0000,\"c\":0.01234567890123456789}],\"z\":-0.0}");
        String expected="{\"a\":[{\"b\":\"1\",\"c\":\"0.01234567890123456789\"}],\"z\":\"0\"}";
        assertThat(CarbonEvidence.canonical(ordered)).isEqualTo(expected);
        assertThat(CarbonEvidence.canonical(CarbonEvidence.parse(expected))).isEqualTo(expected);
    }
    @Test
    void upstreamIsSharedAcrossSlicesAndFullEvidenceStillDetectsTampering() {
        var common=CarbonEvidence.object();common.put("contractVersion","ENERGY_SUMMARY_CARBON_V1");
        common.putArray("compositionSnapshots").addObject().put("snapshotId","ROOT");
        var upstream=new CarbonEvidence.SharedUpstream(common);
        var dictionary=CarbonEvidence.object();dictionary.set(upstream.hash(),upstream.copy());
        for(String point:java.util.List.of("A","B")) {
            var full=CarbonEvidence.object();full.put("schemaVersion",1);
            var evidence=upstream.copy();evidence.putObject("sourceSlice").put("pointId",point);
            full.set("upstream",evidence);
            String hash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(full));
            var reference=CarbonEvidence.object();reference.put("sharedUpstreamReference",upstream.hash());
            reference.set("sourceSlice",evidence.path("sourceSlice"));
            var stored=full.deepCopy();stored.set("upstream",reference);
            String json=CarbonEvidence.canonical(stored);
            assertThat(CarbonEvidence.stored(json,hash,CarbonEvidence.canonical(dictionary))).isEqualTo(full);
            assertThatThrownBy(()->CarbonEvidence.stored(json,hash,"{}" )).hasMessageContaining("共享证据缺失");
            var tampered=stored.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)tampered.path("upstream").path("sourceSlice")).put("pointId","OTHER");
            assertThatThrownBy(()->CarbonEvidence.stored(CarbonEvidence.canonical(tampered),hash,
                    CarbonEvidence.canonical(dictionary))).hasMessageContaining("摘要不一致");
        }
        assertThat(dictionary.size()).isEqualTo(1);
        upstream.copy().remove("compositionSnapshots");
        assertThat(upstream.copy().path("compositionSnapshots")).hasSize(1);
    }

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
