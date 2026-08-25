package com.platform.iot.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mqtt.tls")
/** MQTT TLS 的外部部署参数；密钥材料只通过路径引用，不进入业务数据库。 */
public class MqttTlsProperties {

    private boolean enabled = true;
    private boolean allowPlaintextForTests;
    private String trustStore;
    private String trustStorePassword;
    private String trustStoreType = "PKCS12";
    private String keyStore;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowPlaintextForTests() {
        return allowPlaintextForTests;
    }

    public void setAllowPlaintextForTests(boolean allowPlaintextForTests) {
        this.allowPlaintextForTests = allowPlaintextForTests;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStoreType() {
        return keyStoreType;
    }

    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }
}
