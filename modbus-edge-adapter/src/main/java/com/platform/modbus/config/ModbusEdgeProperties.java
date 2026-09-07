package com.platform.modbus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 承载部署方显式提供的设备身份、Modbus 点表和 MQTT/TLS 参数，不推断厂家私有语义。
 */
@ConfigurationProperties(prefix = "modbus-edge")
public class ModbusEdgeProperties {

    private boolean enabled;
    private int workerThreads = 4;
    private int maxAttempts = 3;
    private Duration retryDelay = Duration.ofMillis(500);
    private final Mqtt mqtt = new Mqtt();
    private List<Device> devices = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Mqtt getMqtt() {
        return mqtt;
    }

    public List<Device> getDevices() {
        return devices;
    }

    public void setDevices(List<Device> devices) {
        this.devices = devices == null ? new ArrayList<>() : new ArrayList<>(devices);
    }

    public enum TransportType {
        TCP,
        RTU
    }

    public enum ReadFunction {
        COIL(2000, true),
        DISCRETE_INPUT(2000, true),
        HOLDING_REGISTER(125, false),
        INPUT_REGISTER(125, false);

        private final int maximumQuantity;
        private final boolean bitFunction;

        ReadFunction(int maximumQuantity, boolean bitFunction) {
            this.maximumQuantity = maximumQuantity;
            this.bitFunction = bitFunction;
        }

        public int maximumQuantity() {
            return maximumQuantity;
        }

        public boolean bitFunction() {
            return bitFunction;
        }
    }

    public enum DataType {
        BOOLEAN(1),
        INT16(1),
        UINT16(1),
        INT32(2),
        UINT32(2),
        FLOAT32(2),
        FLOAT64(4);

        private final int registerCount;

        DataType(int registerCount) {
            this.registerCount = registerCount;
        }

        public int registerCount() {
            return registerCount;
        }
    }

    public enum ByteOrder {
        BIG_ENDIAN,
        LITTLE_ENDIAN
    }

    public enum WordOrder {
        HIGH_TO_LOW,
        LOW_TO_HIGH
    }

    public enum Parity {
        NONE,
        EVEN,
        ODD
    }

    public static class Device {
        private String name;
        private boolean enabled = true;
        private String profileCode;
        private int profileVersion = 1;
        private String identityType;
        private String identityValue;
        private Duration pollInterval = Duration.ofSeconds(15);
        private final Connection connection = new Connection();
        private List<Point> points = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProfileCode() {
            return profileCode;
        }

        public void setProfileCode(String profileCode) {
            this.profileCode = profileCode;
        }

        public int getProfileVersion() {
            return profileVersion;
        }

        public void setProfileVersion(int profileVersion) {
            this.profileVersion = profileVersion;
        }

        public String getIdentityType() {
            return identityType;
        }

        public void setIdentityType(String identityType) {
            this.identityType = identityType;
        }

        public String getIdentityValue() {
            return identityValue;
        }

        public void setIdentityValue(String identityValue) {
            this.identityValue = identityValue;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Connection getConnection() {
            return connection;
        }

        public List<Point> getPoints() {
            return points;
        }

        public void setPoints(List<Point> points) {
            this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        }
    }

    public static class Connection {
        private TransportType type;
        private String host;
        private int port = 502;
        private String serialPort;
        private int baudRate = 9600;
        private int dataBits = 8;
        private int stopBits = 1;
        private Parity parity = Parity.NONE;
        private int unitId = 1;
        private Duration timeout = Duration.ofSeconds(2);

        public TransportType getType() {
            return type;
        }

        public void setType(TransportType type) {
            this.type = type;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getSerialPort() {
            return serialPort;
        }

        public void setSerialPort(String serialPort) {
            this.serialPort = serialPort;
        }

        public int getBaudRate() {
            return baudRate;
        }

        public void setBaudRate(int baudRate) {
            this.baudRate = baudRate;
        }

        public int getDataBits() {
            return dataBits;
        }

        public void setDataBits(int dataBits) {
            this.dataBits = dataBits;
        }

        public int getStopBits() {
            return stopBits;
        }

        public void setStopBits(int stopBits) {
            this.stopBits = stopBits;
        }

        public Parity getParity() {
            return parity;
        }

        public void setParity(Parity parity) {
            this.parity = parity;
        }

        public int getUnitId() {
            return unitId;
        }

        public void setUnitId(int unitId) {
            this.unitId = unitId;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Point {
        private String code;
        private ReadFunction function;
        private int address;
        private DataType dataType;
        private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
        private WordOrder wordOrder = WordOrder.HIGH_TO_LOW;
        private BigDecimal scale = BigDecimal.ONE;
        private BigDecimal offset = BigDecimal.ZERO;
        private String unit;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public ReadFunction getFunction() {
            return function;
        }

        public void setFunction(ReadFunction function) {
            this.function = function;
        }

        public int getAddress() {
            return address;
        }

        public void setAddress(int address) {
            this.address = address;
        }

        public DataType getDataType() {
            return dataType;
        }

        public void setDataType(DataType dataType) {
            this.dataType = dataType;
        }

        public ByteOrder getByteOrder() {
            return byteOrder;
        }

        public void setByteOrder(ByteOrder byteOrder) {
            this.byteOrder = byteOrder;
        }

        public WordOrder getWordOrder() {
            return wordOrder;
        }

        public void setWordOrder(WordOrder wordOrder) {
            this.wordOrder = wordOrder;
        }

        public BigDecimal getScale() {
            return scale;
        }

        public void setScale(BigDecimal scale) {
            this.scale = scale;
        }

        public BigDecimal getOffset() {
            return offset;
        }

        public void setOffset(BigDecimal offset) {
            this.offset = offset;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }
    }

    public static class Mqtt {
        private boolean enabled = true;
        private String brokerUrl = "ssl://127.0.0.1:8883";
        private String clientId = "modbus-edge-adapter-01";
        private String username;
        private String password;
        private String standardTopic = "platform/telemetry/v2/up";
        private String applicationAckTopic = "platform/telemetry/v2/ack/modbus-edge";
        private Duration connectionTimeout = Duration.ofSeconds(10);
        private Duration operationTimeout = Duration.ofSeconds(10);
        private Duration ackTimeout = Duration.ofSeconds(30);
        private final Tls tls = new Tls();

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

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public Duration getOperationTimeout() {
            return operationTimeout;
        }

        public void setOperationTimeout(Duration operationTimeout) {
            this.operationTimeout = operationTimeout;
        }

        public Duration getAckTimeout() {
            return ackTimeout;
        }

        public void setAckTimeout(Duration ackTimeout) {
            this.ackTimeout = ackTimeout;
        }

        public Tls getTls() {
            return tls;
        }
    }

    public static class Tls {
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
}
