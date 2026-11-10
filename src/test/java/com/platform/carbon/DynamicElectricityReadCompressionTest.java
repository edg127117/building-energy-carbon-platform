package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 动态结果图表已读取轻量投影；这里只固定完整证据压缩格式的兼容边界。 */
class DynamicElectricityReadCompressionTest {
    @Test void actualMysqlCompressedFramesRemainDecodableForEvidenceReaders() {
        String[][] goldens={
                {"48656C6C6F204D7953514C","0B000000789CF348CDC9C957F0AD0C0EF40100175603CB"},
                {"E4B8ADE69687F09F9880","0A000000789C7BB263EDB369ED1FE6CF680000289006F4"},
                {"61626320","04000000789C4B4C4A56000003940147"},
                {"1F","01000000789C930700002000202E"},{"",""}};
        for(var golden:goldens)
            assertThat(MysqlCompressedText.decode(HexFormat.of().parseHex(golden[1]),16_000_000)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).containsExactly(HexFormat.of().parseHex(golden[0]));
    }

    @Test void malformedCompressedEvidenceFramesFailClosed() {
        var valid=HexFormat.of().parseHex("0B000000789CF348CDC9C957F0AD0C0EF40100175603CB");
        for(int length=1;length<valid.length;length++) {
            byte[] truncated=java.util.Arrays.copyOf(valid,length);
            assertThatThrownBy(()->MysqlCompressedText.decode(truncated,16_000_000)).isInstanceOf(IllegalArgumentException.class);
        }
        byte[] changed=valid.clone();changed[changed.length-1]^=1;
        assertThatThrownBy(()->MysqlCompressedText.decode(changed,16_000_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
