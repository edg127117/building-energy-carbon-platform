package com.platform.adapter.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** 协议模板唯一来源及远程发布连接参数。 */
@ConfigurationProperties(prefix = "adapter.profile")
public class AdapterProfileProperties {

    private String mode = "jdbc";
    private String outputVersion = "V2";
    private long refreshMillis = 60_000L;
    private Remote remote = new Remote();
    private boolean exportEnabled;
    private Path exportFile = Path.of("protocol-migration-export.json");

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getOutputVersion() { return outputVersion; }
    public void setOutputVersion(String outputVersion) { this.outputVersion = outputVersion; }
    public long getRefreshMillis() { return refreshMillis; }
    public void setRefreshMillis(long refreshMillis) { this.refreshMillis = refreshMillis; }
    public Remote getRemote() { return remote; }
    public void setRemote(Remote remote) { this.remote = remote; }
    public boolean isExportEnabled() { return exportEnabled; }
    public void setExportEnabled(boolean exportEnabled) { this.exportEnabled = exportEnabled; }
    public Path getExportFile() { return exportFile; }
    public void setExportFile(Path exportFile) { this.exportFile = exportFile; }

    /** HTTPS 配置服务、凭据和本地快照边界。 */
    public static class Remote {
        private String baseUrl;
        private String targetId;
        private String adapterKey;
        private Path snapshotFile = Path.of("data", "protocol-snapshot.json");
        private int connectTimeoutMillis = 5_000;
        private int requestTimeoutMillis = 10_000;
        private int maxResponseBytes = 2 * 1024 * 1024;
        private String trustStore;
        private String trustStorePassword;
        private String trustStoreType = "PKCS12";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }
        public String getAdapterKey() { return adapterKey; }
        public void setAdapterKey(String adapterKey) { this.adapterKey = adapterKey; }
        public Path getSnapshotFile() { return snapshotFile; }
        public void setSnapshotFile(Path snapshotFile) { this.snapshotFile = snapshotFile; }
        public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public void setConnectTimeoutMillis(int value) { this.connectTimeoutMillis = value; }
        public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
        public void setRequestTimeoutMillis(int value) { this.requestTimeoutMillis = value; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int value) { this.maxResponseBytes = value; }
        public String getTrustStore() { return trustStore; }
        public void setTrustStore(String trustStore) { this.trustStore = trustStore; }
        public String getTrustStorePassword() { return trustStorePassword; }
        public void setTrustStorePassword(String value) { this.trustStorePassword = value; }
        public String getTrustStoreType() { return trustStoreType; }
        public void setTrustStoreType(String value) { this.trustStoreType = value; }
    }
}
