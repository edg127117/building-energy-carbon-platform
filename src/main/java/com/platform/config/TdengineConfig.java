package com.platform.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;


import javax.sql.DataSource;
import java.util.*;

/**
 * TDengine 数据源核心配置与自动化构建类
 * 与主 MySQL 数据源完全物理隔离，互不影响。
 * 利用 Spring 启动周期，全自动完成时序库和超级表的基建
 */
@Configuration
@Slf4j
public class TdengineConfig {

    private final TdengineProperties properties;

    public TdengineConfig(TdengineProperties properties) {
        this.properties = properties;
    }

    @Bean( name="taosDataSource")
    public DataSource taosDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName("com.taosdata.jdbc.rs.RestfulDriver");
        config.setMaximumPoolSize(20);
        config.setConnectionTimeout(30000);
        // 可选：连接池名称，方便后期使用 Prometheus 监控时定位排查
        config.setPoolName("TDengine-HikariPool");
        return new HikariDataSource(config);
    }

    @Bean(name = "taosJdbcTemplate")
    public JdbcTemplate taosJdbcTemplate(@Qualifier("taosDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 指数退避重试机制 (应对 Docker 启动竞态条件)
     * 在 Docker Compose 环境下，Spring Boot 启动速度远快于 TDengine 数据库。
     * 如果直连会报错崩溃。这里引入了 RetryTemplate，采用“指数退避”算法（等1秒、2秒、4秒...最大30秒），
     */
    @Bean
    public CommandLineRunner initTaosDb(@Qualifier("taosJdbcTemplate") JdbcTemplate template) {
        return args -> {
            try {
                RetryTemplate retryTemplate = RetryTemplate.builder()
                        .maxAttempts(2)
                        .exponentialBackoff(1000, 2, 5000)
                        .retryOn(DataAccessException.class)
                        .retryOn(Exception.class)
                        .build();

                retryTemplate.execute(ctx -> {
                    log.info("尝试连接并初始化 TDengine (第 {} 次尝试)...", ctx.getRetryCount() + 1);
                    doInitialize(template);
                    return null;
                });
            } catch (Exception e) {
                log.warn("⚠️ TDengine 不可用，时序存储功能已跳过: {}", e.getMessage());
            }
        };
    }

    /**
     * 初始化业务逻辑 (建库、建表、验证)
     */
    private void doInitialize(JdbcTemplate template) {
        String database = properties.getDatabase();
        String stableName = properties.getStableName();

        // 1. 自动建库
        // KEEP 3650 代表自带 10 年的数据生命周期 ，过期数据自动抹除，
        // fsync=3000：每 3 秒刷盘一次，削减磁盘 IO 峰值
        // walLevel=1：只写 WAL 不立即 fsync，写入吞吐提升明显
        String createDb = String.format(
                "CREATE DATABASE IF NOT EXISTS %s KEEP %d DURATION %s WAL_LEVEL 1;",
                database, properties.getKeep(), properties.getDuration()
        );
        template.execute(createDb);
        log.info("数据库 [{}] 已创建/已存在", database);

        // 2. 自动建超级表
        // 采用 schema.table 的指定方式，避免了复杂的 session 切换问题。
        // 以后新接十万台设备，底层会自动继承它建子表，代码零改动。
        String createStable = String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s (" +
                        "ts TIMESTAMP, " +
                        "voltage_a FLOAT, voltage_b FLOAT, voltage_c FLOAT, " +
                        "current_a FLOAT, current_b FLOAT, current_c FLOAT, " +
                        "active_power FLOAT, frequency FLOAT, quality TINYINT" +
                        ") TAGS (device_id VARCHAR(64), location VARCHAR(128));",
                database, stableName
        );
        template.execute(createStable);

        // 3. 自动创建 HVAC 逐条事件和分钟汇总结构，并兼容已经存在的旧分钟表。
        initializeHvacSchema(template);

        // 4. 自动建性能指标超级表 st_indicator_minute（设计书 §2.2）
        initStIndicatorMinute(template);

        log.info("✅ TDengine 初始化完成: 数据库[{}], 超级表[{}/{}/{}/{}]",
                database, stableName, properties.getStRawEvent(),
                properties.getStRawMinute(), properties.getStIndicatorMinute());

        // 验证所有超级表
        verifyInitialization(template, database, stableName);

        // 验证 HVAC 超级表
        String rawMinute = properties.getStRawMinute();
        verifyInitialization(template, database, properties.getStRawEvent());
        String indicatorMinute = properties.getStIndicatorMinute();
        verifyInitialization(template, database, rawMinute);
        verifyInitialization(template, database, indicatorMinute);
    }

    /**
     * 初始化 HVAC 原始事件和分钟汇总结构。
     *
     * <p>该方法保持包可见，自动化测试可以直接核验生成的 SQL。</p>
     */
    void initializeHvacSchema(JdbcTemplate template) {
        initStRawEvent(template);
        initStRawMinute(template);
        ensureRawEventIdentityFields(template);
        ensureRawMinuteColumns(template);
    }

    /**
     * 初始化逐条真实上报表。事件时间是主时间轴，接收时间只用于延迟审计。
     */
    private void initStRawEvent(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStRawEvent();
        String sql = String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s (" +
                        "ts TIMESTAMP, received_time TIMESTAMP, val DOUBLE, " +
                        "data_quality TINYINT, late_flag TINYINT, " +
                        "source_system NCHAR(50), source_point_code NCHAR(100), " +
                        "source_device_id NCHAR(50)" +
                        ") TAGS (point_id NCHAR(32), point_code NCHAR(100), " +
                        "building_id NCHAR(32), system_group_id NCHAR(32), " +
                        "equip_id NCHAR(32), equip_code NCHAR(50), " +
                        "family_code NCHAR(20), component_code NCHAR(20), " +
                        "suffix_code NCHAR(20), is_for_calc TINYINT);",
                db, stable
        );
        template.execute(sql);
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    /**
     * 初始化 st_raw_minute 超级表（原始测点分钟数据）
     */
    private void initStRawMinute(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStRawMinute();
        String createRaw = String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s (" +
                        "ts TIMESTAMP, " +
                        "val DOUBLE, " +
                        "data_quality TINYINT, " +
                        "avg_val DOUBLE, min_val DOUBLE, max_val DOUBLE, sample_count INT, " +
                        "first_received_time TIMESTAMP, last_received_time TIMESTAMP, finalized_at TIMESTAMP" +
                        ") TAGS (point_id NCHAR(32), point_code NCHAR(100), " +
                        "building_id NCHAR(32), system_group_id NCHAR(32), " +
                        "equip_id NCHAR(32), equip_code NCHAR(50), " +
                        "family_code NCHAR(20), component_code NCHAR(20), " +
                        "suffix_code NCHAR(20), is_for_calc TINYINT);",
                db, stable
        );
        template.execute(createRaw);
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    /**
     * 对已经创建过的 st_raw_minute 做非破坏式增量迁移。
     *
     * <p>TDengine 的 CREATE STABLE IF NOT EXISTS 不会补充新列，因此先 DESCRIBE，
     * 只添加缺失字段，避免每次启动重复执行 ALTER。</p>
     */
    private void ensureRawMinuteColumns(JdbcTemplate template) {
        String qualified = properties.getDatabase() + "." + properties.getStRawMinute();
        Map<String, String> requiredColumns = new LinkedHashMap<>();
        requiredColumns.put("avg_val", "DOUBLE");
        requiredColumns.put("min_val", "DOUBLE");
        requiredColumns.put("max_val", "DOUBLE");
        requiredColumns.put("sample_count", "INT");
        requiredColumns.put("first_received_time", "TIMESTAMP");
        requiredColumns.put("last_received_time", "TIMESTAMP");
        requiredColumns.put("finalized_at", "TIMESTAMP");
        ensureFields(template, qualified, requiredColumns, identityTags());
    }

    private void ensureRawEventIdentityFields(JdbcTemplate template) {
        String qualified = properties.getDatabase() + "." + properties.getStRawEvent();
        Map<String, String> sourceColumns = new LinkedHashMap<>();
        sourceColumns.put("source_system", "NCHAR(50)");
        sourceColumns.put("source_point_code", "NCHAR(100)");
        sourceColumns.put("source_device_id", "NCHAR(50)");
        ensureFields(template, qualified, sourceColumns, identityTags());
    }

    private Map<String, String> identityTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("point_id", "NCHAR(32)");
        tags.put("building_id", "NCHAR(32)");
        tags.put("system_group_id", "NCHAR(32)");
        tags.put("equip_code", "NCHAR(50)");
        tags.put("family_code", "NCHAR(20)");
        tags.put("component_code", "NCHAR(20)");
        return tags;
    }

    private void ensureFields(
            JdbcTemplate template,
            String qualified,
            Map<String, String> requiredColumns,
            Map<String, String> requiredTags) {
        try {
            List<Map<String, Object>> description = template.queryForList("DESCRIBE " + qualified);
            Set<String> existing = new HashSet<>();
            for (Map<String, Object> row : description) {
                Object field = row.get("field");
                if (field == null) field = row.get("Field");
                if (field == null && !row.isEmpty()) field = row.values().iterator().next();
                if (field != null) existing.add(field.toString().toLowerCase(Locale.ROOT));
            }
            for (Map.Entry<String, String> column : requiredColumns.entrySet()) {
                if (!existing.contains(column.getKey())) {
                    template.execute("ALTER STABLE " + qualified + " ADD COLUMN "
                            + column.getKey() + " " + column.getValue());
                    log.info("TDengine超级表新增字段: {}.{}", qualified, column.getKey());
                }
            }
            for (Map.Entry<String, String> tag : requiredTags.entrySet()) {
                if (!existing.contains(tag.getKey())) {
                    template.execute("ALTER STABLE " + qualified + " ADD TAG "
                            + tag.getKey() + " " + tag.getValue());
                    log.info("TDengine超级表新增标签: {}.{}", qualified, tag.getKey());
                }
            }
        } catch (Exception exception) {
            log.warn("TDengine超级表增量迁移失败，保留启动流程: table={}, error={}",
                    qualified, exception.getMessage());
        }
    }

    /**
     * 初始化 st_indicator_minute 超级表（COP 指标分钟数据）
     */
    private void initStIndicatorMinute(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStIndicatorMinute();
        String createIndicator = String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s (" +
                        "ts TIMESTAMP, " +
                        "val DOUBLE" +
                        ") TAGS (indicator_id NCHAR(32), indicator_code NCHAR(100), " +
                        "building_id NCHAR(32), system_group_id NCHAR(32), equip_id NCHAR(32));",
                db, stable
        );
        template.execute(createIndicator);
        ensureFields(template, db + "." + stable, Map.of(),
                Map.of("indicator_id", "NCHAR(32)"));
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    /**
     * 验证数据库和超级表是否真的创建成功
     */
    private void verifyInitialization(JdbcTemplate template, String database, String stableName) {
        try {
            // 检查数据库
            String checkDb = "SELECT name FROM information_schema.ins_databases WHERE name = '" + database + "';";
            List<Map<String, Object>> dbResult = template.queryForList(checkDb);
            if (dbResult.isEmpty()) {
                log.warn("⚠️ 数据库 [{}] 验证失败：未找到", database);
            } else {
                log.info("✅ 数据库 [{}] 验证通过", database);
            }

            // 检查超级表（使用 库名.表名 ）
            String checkStable = "DESCRIBE " + database + "." + stableName + ";";
            List<Map<String, Object>> stableResult = template.queryForList(checkStable);
            if (stableResult.isEmpty()) {
                log.warn("⚠️ 超级表 [{}] 验证失败：未找到", stableName);
            } else {
                log.info("✅ 超级表 [{}] 验证通过 (包含 {} 个字段)", stableName, stableResult.size());
            }
        } catch (Exception e) {
            // 验证失败只记录日志，不中断启动
            log.warn("初始化验证失败，但可能不影响后续使用: {}", e.getMessage());
        }
    }

}
