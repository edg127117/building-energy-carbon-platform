package com.platform.carbon;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CarbonEvidenceStreamingHashTest {
    @Test void preservesCanonicalBytesForNestedOrderingNumbersEscapesAndUnicode() {
        for(String json:java.util.List.of("null","true","123","{\"z\":[1.200,0.0001,-0.0],\"a\":{\"b\":2,\"a\":null}}")) {
            var tree=CarbonEvidence.parse(json);var before=tree.deepCopy();
            assertThat(CarbonEvidence.canonicalHash(tree)).isEqualTo(CarbonCalculationCore.sha256(CarbonEvidence.canonical(tree)));
            assertThat(tree).isEqualTo(before);
            String canonical=CarbonEvidence.canonical(tree);
            assertThat(CarbonEvidence.canonicalEquals(tree,canonical)).isTrue();
            assertThat(CarbonEvidence.canonicalEquals(tree,canonical+" ")).isFalse();
            assertThat(CarbonEvidence.canonicalEquals(tree,canonical.substring(1))).isFalse();
        }
        for(String text:java.util.List.of("南京\n\t\"\\", "\uD83D\uDE00", "\uD800", "\uDC00", "x\uD800y", "a".repeat(8191)+"\uD83D\uDE00"+"b".repeat(8191))) {
            var tree=CarbonEvidence.object();tree.put("value",text);
            assertThat(CarbonEvidence.canonicalEquals(tree,CarbonEvidence.canonical(tree))).isTrue();
            assertThat(CarbonEvidence.canonicalHash(tree)).isEqualTo(CarbonCalculationCore.sha256(CarbonEvidence.canonical(tree)));
        }
    }
    @Test void largeEvidenceAndTamperingPreserveFullDigestContract() {
        var tree=CarbonEvidence.object();var rows=tree.putArray("calculations");
        for(int i=0;i<5000;i++)rows.addObject().put("quantity",Integer.toString(i)).put("factor","0.57").put("source","x".repeat(512));
        String expected=CarbonCalculationCore.sha256(CarbonEvidence.canonical(tree));
        assertThat(CarbonEvidence.canonicalHash(tree)).isEqualTo(expected);
        assertThat(CarbonEvidence.canonicalEquals(tree,CarbonEvidence.canonical(tree))).isTrue();
        assertThat(CarbonEvidence.canonicalEquals(tree,null)).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(4999)).put("factor","0.58");
        assertThat(CarbonEvidence.canonicalHash(tree)).isNotEqualTo(expected);
    }
}
