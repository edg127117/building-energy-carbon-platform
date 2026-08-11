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
    private int maxPayloadBytes = 64 * 1024;
    private long initialRetryMillis = 5_000L;

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
}
