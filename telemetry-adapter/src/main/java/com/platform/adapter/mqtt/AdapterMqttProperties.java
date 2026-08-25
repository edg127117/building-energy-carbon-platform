package com.platform.adapter.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 云端适配器连接 EMQX 所需的部署参数，不包含协议字段映射。 */
@ConfigurationProperties(prefix = "adapter.mqtt")
public class AdapterMqttProperties {

    private boolean enabled = true;
    private String brokerUrl;
    private String clientId;
    private String username;
    private String password;
    private String rawTopic;
    private String standardTopic;
    private String applicationAckTopic;
    private int maxPayloadBytes = 64 * 1024;
    private long initialRetryMillis = 5_000L;
    private long maxRetryMillis = 120_000L;
    private long securityRetryMillis = 300_000L;
    private double retryMultiplier = 2.0;
    private double retryJitterRatio = 0.2;
    private Tls tls = new Tls();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public void setBrokerUrl(String brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRawTopic() {
        return rawTopic;
    }

    public void setRawTopic(String rawTopic) {
        this.rawTopic = rawTopic;
    }

    public String getStandardTopic() {
        return standardTopic;
    }

    public void setStandardTopic(String standardTopic) {
        this.standardTopic = standardTopic;
    }

    public String getApplicationAckTopic() {
        return applicationAckTopic;
    }

    public void setApplicationAckTopic(String applicationAckTopic) {
        this.applicationAckTopic = applicationAckTopic;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public long getInitialRetryMillis() {
        return initialRetryMillis;
    }

    public void setInitialRetryMillis(long initialRetryMillis) {
        this.initialRetryMillis = initialRetryMillis;
    }

    public long getMaxRetryMillis() {
        return maxRetryMillis;
    }

    public void setMaxRetryMillis(long maxRetryMillis) {
        this.maxRetryMillis = maxRetryMillis;
    }

    public long getSecurityRetryMillis() {
        return securityRetryMillis;
    }

    public void setSecurityRetryMillis(long securityRetryMillis) {
        this.securityRetryMillis = securityRetryMillis;
    }

    public double getRetryMultiplier() {
        return retryMultiplier;
    }

    public void setRetryMultiplier(double retryMultiplier) {
        this.retryMultiplier = retryMultiplier;
    }

    public double getRetryJitterRatio() {
        return retryJitterRatio;
    }

    public void setRetryJitterRatio(double retryJitterRatio) {
        this.retryJitterRatio = retryJitterRatio;
    }

    public Tls getTls() {
        return tls;
    }

    public void setTls(Tls tls) {
        this.tls = tls;
    }

    /** TLS 信任库和可选客户端密钥库的外部路径配置。 */
    public static class Tls {
        private boolean enabled = true;
        private boolean allowPlaintextForTests;
        private String trustStore;
        private String trustStorePassword;
        private String trustStoreType = "PKCS12";
        private String keyStore;
        private String keyStorePassword;
        private String keyStoreType = "PKCS12";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAllowPlaintextForTests() { return allowPlaintextForTests; }
        public void setAllowPlaintextForTests(boolean value) {
            this.allowPlaintextForTests = value;
        }
        public String getTrustStore() { return trustStore; }
        public void setTrustStore(String trustStore) { this.trustStore = trustStore; }
        public String getTrustStorePassword() { return trustStorePassword; }
        public void setTrustStorePassword(String value) { this.trustStorePassword = value; }
        public String getTrustStoreType() { return trustStoreType; }
        public void setTrustStoreType(String value) { this.trustStoreType = value; }
        public String getKeyStore() { return keyStore; }
        public void setKeyStore(String keyStore) { this.keyStore = keyStore; }
        public String getKeyStorePassword() { return keyStorePassword; }
        public void setKeyStorePassword(String value) { this.keyStorePassword = value; }
        public String getKeyStoreType() { return keyStoreType; }
        public void setKeyStoreType(String value) { this.keyStoreType = value; }
    }
}
