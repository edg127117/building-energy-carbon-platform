package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.TimeSeriesRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TDengineRepositoryImpl implements TimeSeriesRepository {

    private static final Logger log = LoggerFactory.getLogger(TDengineRepositoryImpl.class);
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9:_-]{1,64}$");
    private static final int BATCH_SIZE = 500;    // 每批 SQL 条数，约 75KB，拆细以利用多 vgroup 并行写入
    private static final int MAX_DRAIN = 5000;    // 单次刷盘 drain 上限，防止持锁过久

    private final ConcurrentLinkedQueue<Map<String, Object>> writeBuffer = new ConcurrentLinkedQueue<>();

    @Autowired(required = false)
    @Qualifier("taosJdbcTemplate")
    private JdbcTemplate taosJdbcTemplate;

    @Autowired
    private TdengineProperties properties;

    @Override
    public void enqueue(Map<String, Object> entry) {
        if (!isAvailable()) return;
        writeBuffer.offer(entry);
    }

    @Override
    public void flushBuffer() {
        if (!isAvailable()) return;
        doFlush();
    }

    @Scheduled(fixedDelay = 200)
    public void scheduledFlush() {
        if (!isAvailable()) return;
        doFlush();
    }

    private boolean isAvailable() {
        return taosJdbcTemplate != null;
    }

    private void doFlush() {
        if (writeBuffer.isEmpty()) {
            return;
        }
        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> entry;
        int drained = 0;
        while ((entry = writeBuffer.poll()) != null && drained < MAX_DRAIN) {
            batch.add(entry);
            drained++;
        }
        if (batch.isEmpty()) {
            return;
        }
        List<Object> payloadList = new ArrayList<>(batch);
        saveBatch(payloadList);
        log.info("批量写入完成: {} 条, 队列剩余: {}", batch.size(), writeBuffer.size());
    }

    @Override
    public void save(String deviceId, Object payload) {
        if (!isAvailable()) return;
        // 安全拦截，确保传过来的是我们在 MQTT 解析好的 Map
        if (!(payload instanceof Map)) {
            return;
        }
        // 上游传入的 deviceId 不可信，这里做一次兜底校验，避免写入线程因非法 deviceId 失败
        if (!isValidDeviceId(deviceId)) {
            log.warn("❌ TDengine 写入跳过：deviceId 非法或为空: {}", deviceId);
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload;

            // 1. 提取电压、电流等特征数据 (带有安全的类型转换处理)
            Float voltageA = getFloatValue(data, "voltage_a");
            Float currentA = getFloatValue(data, "current_a");
            Float activePower = getFloatValue(data, "active_power");

            String dbName = properties.getDatabase();
            String stableName = properties.getStableName();

            // TDengine 子表名需要可作为标识符使用，这里做规范化映射（不改变原始 deviceId 的业务含义）
            String tableName = toSafeTableName(deviceId);

            // 2. 组装 SQL：自动建子表 + 插入数据
            // 如果子表不存在，TDengine 会依据超级表 st_electric_data 自动创建它！
            String sql =
                    "INSERT INTO " + dbName + "." + tableName +
                            " USING " + dbName + "." + stableName +
                            " TAGS('" + deviceId + "', '默认厂区') " +
                            "(ts, voltage_a, current_a, active_power, quality) " +
                            "VALUES (NOW, " + toSqlNumber(voltageA) + ", " + toSqlNumber(currentA) + ", " + toSqlNumber(activePower) + ", 1)";

            // 3. 执行写入
            taosJdbcTemplate.execute(sql);
            log.debug("✅ 成功将设备 {} 的时序数据落入 TDengine 磁盘!", deviceId);

        } catch (Exception e) {
            log.error("❌ 写入 TDengine 发生异常: ", e);
        }
    }

    // 安全地从 Map 中提取 Float 值，避免转型报错
    private Float getFloatValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        try {
            return Float.parseFloat(value.toString());
        } catch (NumberFormatException e) {
            return null; // 如果传来乱码，直接丢弃该字段，不影响整体崩溃
        }
    }

    private boolean isValidDeviceId(String deviceId) {
        if (deviceId == null) {
            return false;
        }
        return DEVICE_ID_PATTERN.matcher(deviceId).matches();
    }

    private String toSafeTableName(String deviceId) {
        // TDengine 表名不接受 '-' 等字符；同时把其它非法字符统一替换为 '_'，保证建表/写入稳定
        String normalized = deviceId.replace('-', '_');
        normalized = normalized.replaceAll("[^a-zA-Z0-9_]", "_");
        return "d_" + normalized;
    }

    private String toSqlNumber(Float value) {
        // SQL 数值字段兜底：null/NaN/Infinity 直接写 NULL，避免 String.format(%f) 触发异常导致丢数据
        if (value == null) {
            return "NULL";
        }
        if (!Float.isFinite(value)) {
            return "NULL";
        }
        return value.toString();
    }
    /**
     * 批量写入（并行分片版）
     * 将数据按 BATCH_SIZE 切片，每片拼一条 SQL，利用虚拟线程并行发送，
     * 充分利用 TDengine 多 vgroup 的并行写入能力。
     */
    @Override
    public void saveBatch(List<Object> payloadList) {
        if (payloadList == null || payloadList.isEmpty()) {
            return;
        }

        String dbName = properties.getDatabase();
        String stableName = properties.getStableName();

        // 按 BATCH_SIZE 切片
        List<List<Object>> chunks = new ArrayList<>();
        for (int i = 0; i < payloadList.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, payloadList.size());
            chunks.add(new ArrayList<>(payloadList.subList(i, end)));
        }

        // 每片拼 SQL + 虚拟线程并行发送到 TDengine
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (List<Object> chunk : chunks) {
                futures.add(executor.submit(() -> executeChunk(chunk, dbName, stableName)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("💥 并行分片写入异常: ", e);
                }
            }
        }

        log.info("🚀 批量写入完成: {} 条, 分 {} 片并行执行", payloadList.size(), chunks.size());
    }

    /** 将单个分片拼成一条 SQL 并执行 */
    private void executeChunk(List<Object> chunk, String dbName, String stableName) {
        StringBuilder sqlBuilder = new StringBuilder("INSERT ");
        int appended = 0;

        for (Object payload : chunk) {
            if (!(payload instanceof Map)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload;
            String deviceId = (String) data.get("deviceId");

            if (!isValidDeviceId(deviceId)) continue;

            Float voltageA = getFloatValue(data, "voltage_a");
            Float currentA = getFloatValue(data, "current_a");
            Float activePower = getFloatValue(data, "active_power");
            String tableName = toSafeTableName(deviceId);

            sqlBuilder.append(
                    "INTO " + dbName + "." + tableName +
                    " USING " + dbName + "." + stableName +
                    " TAGS('" + deviceId + "', '默认厂区') " +
                    "(ts, voltage_a, current_a, active_power, quality) " +
                    "VALUES (NOW, " + toSqlNumber(voltageA) + ", " + toSqlNumber(currentA) + ", " + toSqlNumber(activePower) + ", 1) "
            );
            appended++;
        }

        String finalSql = sqlBuilder.toString();
        if ("INSERT ".equals(finalSql)) return;

        taosJdbcTemplate.execute(finalSql);
        log.debug("  └ 并行分片写入 {} 条完成", appended);
    }


    @Override
    public List<Object> queryHistory(String deviceId, long startMs, long endMs) {
        if (!isAvailable()) return List.of();
        if (!isValidDeviceId(deviceId)) {
            log.warn("queryHistory 跳过：deviceId 非法: {}", deviceId);
            return List.of();
        }
        try {
            String dbName = properties.getDatabase();
            String tableName = toSafeTableName(deviceId);
            // 按时间范围查询子表数据
            String sql = String.format(
                    "SELECT ts, voltage_a, current_a, active_power FROM %s.%s WHERE ts >= %d AND ts <= %d ORDER BY ts",
                    dbName, tableName, startMs, endMs
            );
            List<Map<String, Object>> rows = taosJdbcTemplate.queryForList(sql);
            List<Object> result = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> point = new java.util.LinkedHashMap<>();
                Object tsObj = row.get("ts");
                if (tsObj != null) {
                    point.put("t", tsObj instanceof java.sql.Timestamp
                            ? ((java.sql.Timestamp) tsObj).getTime()
                            : tsObj);
                }
                point.put("voltage_a", row.get("voltage_a"));
                point.put("current_a", row.get("current_a"));
                point.put("active_power", row.get("active_power"));
                result.add(point);
            }
            log.debug("queryHistory: device={}, range=[{},{}], rows={}", deviceId, startMs, endMs, result.size());
            return result;
        } catch (Exception e) {
            log.error("queryHistory 异常: deviceId={}", deviceId, e);
            return List.of();
        }
    }

    @Override
    public String getEngineName() {
        return "TDengine";
    }
}
