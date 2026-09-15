package com.platform.iot.daikin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DaikinProtocolCryptoTest {

    @Test
    void signsParametersInKeyOrderUsingUtf8AndUppercaseMd5() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("key1", "value1");

        assertThat(DaikinProtocolCrypto.sign(parameters, "53c25ad3643b43d4a5b89e6f5ad0403e"))
                .isEqualTo("4D6DE48A94E64199E63B3D70FCB0D020");

        parameters.clear();
        parameters.put("b", "2");
        parameters.put("a", "中文");
        assertThat(DaikinProtocolCrypto.sign(parameters, "salt"))
                .isEqualTo("374452A9D11E8CFF7A7E6E2E1916E4A2");
    }

    @Test
    void encryptsWithAesEcbPkcs5AndBase64() {
        String encrypted = DaikinProtocolCrypto.encrypt("大金-readonly", "0123456789abcdef");

        assertThat(encrypted).isEqualTo("0igRf9EuO93vpEJLmEu8IQ==");

        assertThat(DaikinProtocolCrypto.decrypt(encrypted, "0123456789abcdef"))
                .isEqualTo("大金-readonly");
        assertThat(encrypted).matches("[A-Za-z0-9+/]+={0,2}");
    }

    @Test
    void rejectsUnsupportedAesKeyLength() {
        assertThatThrownBy(() -> DaikinProtocolCrypto.encrypt("value", "short"))
                .isInstanceOf(DaikinClientException.class)
                .extracting(ex -> ((DaikinClientException) ex).code())
                .isEqualTo(DaikinClientException.Code.CRYPTO_FAILURE);
    }
}
