package com.platform.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;


import javax.sql.DataSource;
import java.util.*;

/**
 * HVAC 与能源周期时序数据源的启动装配。
 *
 * <p>该数据源通过明确 Bean 名称与 MySQL 物理隔离，避免把两类 SQL 发往错误
 * 数据库。生产默认在启动期创建 HVAC Schema；普通自动化测试通过
 * {@code tdengine.initialization-enabled=false} 关闭外部连接。</p>
 */
@Configuration
@Slf4j
public class TdengineConfig {

    private final TdengineProperties properties;

    public TdengineConfig(TdengineProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建只服务时序仓储的连接池，并允许 TDengine 不可用时平台其他模块先启动。
     *
     * <p>最小空闲连接设为 0，避免应用启动时无业务访问也建立外部连接；Bean 名称固定为
     * {@code taosDataSource}，与 MySQL 数据源保持物理和注入边界。</p>
     */
    @Bean( name="taosDataSource")
    public DataSource taosDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName("com.taosdata.jdbc.rs.RestfulDriver");
        config.setMaximumPoolSize(20);
        // 启动时不预先创建空闲连接；只有业务真正查询 TDengine 时才申请连接。
        config.setMinimumIdle(0);
        // TDengine 暂时不可用时仍允许应用先启动，避免创建连接池阶段直接失败。
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(30000);
        // 固定连接池名称，便于当前日志、线程和连接池指标直接定位 TDengine 连接。
        config.setPoolName("TDengine-HikariPool");
        return new HikariDataSource(config);
    }

    /** 为时序 Repository 提供必须通过 {@code taosJdbcTemplate} 限定注入的访问入口。 */
    @Bean(name = "taosJdbcTemplate")
    public JdbcTemplate taosJdbcTemplate(@Qualifier("taosDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 应用启动时以有限重试初始化 TDengine Schema。
     *
     * <p>Docker Compose 中应用可能早于 TDengine 就绪，因此初始化最多尝试两次，
     * 两次之间按指数退避等待；仍失败时只记录告警，让登录和 MySQL 查询等无关模块
     * 保持可用。该降级不代表时序功能可用，后续时序读写仍会显式失败。</p>
     *
     * <p>{@code tdengine.initialization-enabled=false} 时不会创建这个启动任务，
     * 也就不会在应用启动阶段连接 TDengine、建库或建表。普通自动化测试会关闭它。</p>
     */
    @Bean
    // 默认开启以保留生产初始化流程；测试环境通过配置关闭。
    @ConditionalOnProperty(
            prefix = "tdengine",
            name = "initialization-enabled",
            havingValue = "true",
            matchIfMissing = true)
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
     * 创建时序数据库及 HVAC、公式和能源周期结果结构，并执行非破坏式
     * 字段补齐与存在性验证。
     */
    private void doInitialize(JdbcTemplate template) {
        String database = properties.getDatabase();

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

        // 2. 自动创建 HVAC 逐条事件和分钟汇总结构，并兼容已经存在的旧分钟表。
        initializeHvacSchema(template);

        // 3. 自动创建公式成功结果和异常审计结构，并迁移旧指标表。
        initializeFormulaSchema(template);

        // 4. 自动创建周期投影和封账快照的数值副本结构。
        initializeEnergyPeriodSchema(template);

        log.info("✅ TDengine 时序初始化完成: 数据库[{}], 超级表[{}/{}/{}/{}/{}/{}/{}/{}]",
                database, properties.getStRawEvent(),
                properties.getStRawMinute(), properties.getStIndicatorMinute(),
                properties.getStFormulaCalcException(),
                properties.getStFormulaCalcAttemptV2(),
                properties.getStFormulaResultRevision(),
                properties.getStIndicatorMinuteState(),
                properties.getStEnergyPeriodResult());

        // 启动验证覆盖当前运行会读写的全部超级表。
        List.of(
                properties.getStRawEvent(),
                properties.getStRawMinute(),
                properties.getStIndicatorMinute(),
                properties.getStFormulaCalcException(),
                properties.getStFormulaCalcAttemptV2(),
                properties.getStFormulaResultRevision(),
                properties.getStIndicatorMinuteState(),
                properties.getStEnergyPeriodResult()
        ).forEach(stable -> verifyInitialization(template, database, stable));
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
                        "first_received_time TIMESTAMP, last_received_time TIMESTAMP, " +
                        "finalized_at TIMESTAMP, quality_task_id NCHAR(32)" +
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
        // 生成分钟通过该字段关联 MySQL 补全批次；质量 0 真实分钟保持为空。
        requiredColumns.put("quality_task_id", "NCHAR(32)");
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

    /**
     * 通过 {@code DESCRIBE} 只补充缺失列和标签，不改写已有字段。
     *
     * <p>迁移失败被记录但不阻断启动；这只保证其他模块可用，依赖缺失字段的时序功能
     * 仍会在实际访问时暴露错误，不能把告警理解为迁移成功。</p>
     */
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
     * 初始化公式成功结果和异常审计超级表。
     *
     * <p>该方法保持包可见，自动化测试可以直接核验生成的 SQL。</p>
     */
    void initializeFormulaSchema(JdbcTemplate template) {
        initStIndicatorMinute(template);
        initStFormulaCalcException(template);
        initStFormulaCalcAttemptV2(template);
        initStFormulaResultRevision(template);
        initStIndicatorMinuteState(template);
    }

    /**
     * 初始化能源周期数值副本。
     *
     * <p>MySQL 保存封账、重算和可见性状态；TDengine 只保存可按周期定位的数值副本与
     * 精确十进制证据。启动自动建表避免新环境依赖人工执行 TDengine 脚本。</p>
     */
    void initializeEnergyPeriodSchema(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStEnergyPeriodResult();
        template.execute(String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s ("
                        + "ts TIMESTAMP, native_quantity DOUBLE, tce_value DOUBLE, "
                        + "coverage_ratio DOUBLE, native_quantity_decimal BINARY(48), "
                        + "tce_value_decimal BINARY(48), coverage_ratio_decimal BINARY(24), "
                        + "revision BIGINT, evidence_hash BINARY(64)"
                        + ") TAGS (result_key BINARY(64), building_id BINARY(32), "
                        + "point_id BINARY(32), native_unit_code BINARY(64), "
                        + "tce_unit_code BINARY(64), result_nature BINARY(32));",
                db, stable));
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    private void initStIndicatorMinute(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStIndicatorMinute();
        String createIndicator = String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s (" +
                        "ts TIMESTAMP, " +
                        "val DOUBLE, " +
                        "data_quality TINYINT, " +
                        "formula_version NCHAR(32), " +
                        "calculated_at TIMESTAMP" +
                        ") TAGS (indicator_id NCHAR(32), " +
                        "indicator_code NCHAR(100), " +
                        "building_id NCHAR(32), " +
                        "system_group_id NCHAR(32), equip_id NCHAR(32));",
                db, stable
        );
        template.execute(createIndicator);
        Map<String, String> requiredColumns = new LinkedHashMap<>();
        requiredColumns.put("data_quality", "TINYINT");
        requiredColumns.put("formula_version", "NCHAR(32)");
        requiredColumns.put("calculated_at", "TIMESTAMP");
        ensureFields(template, db + "." + stable, requiredColumns,
                Map.of("indicator_id", "NCHAR(32)"));
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    private void initStFormulaCalcException(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStFormulaCalcException();
        String createException = String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s (" +
                        "ts TIMESTAMP, " +
                        "calc_status NCHAR(32), " +
                        "reason_code NCHAR(64), " +
                        "missing_inputs NCHAR(512), " +
                        "formula_version NCHAR(32), " +
                        "calculated_at TIMESTAMP" +
                        ") TAGS (indicator_id NCHAR(32), " +
                        "indicator_code NCHAR(100), " +
                        "building_id NCHAR(32), " +
                        "system_group_id NCHAR(32), equip_id NCHAR(32));",
                db, stable
        );
        template.execute(createException);
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    private void initStFormulaCalcAttemptV2(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStFormulaCalcAttemptV2();
        template.execute(String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s ("
                        + "ts TIMESTAMP, attempt_id NCHAR(32), minute_start TIMESTAMP, "
                        + "calc_status NCHAR(40), reason_code NCHAR(64), "
                        + "scenario_code NCHAR(64), formula_version NCHAR(32), "
                        + "policy_evidence_json NCHAR(2048), parameter_evidence_json NCHAR(4096), "
                        + "config_revision BIGINT"
                        + ") TAGS (indicator_id NCHAR(32), indicator_code NCHAR(100), "
                        + "building_id NCHAR(32), system_group_id NCHAR(32), equip_id NCHAR(32));",
                db, stable));
        ensureFields(template, db + "." + stable,
                Map.of("parameter_evidence_json", "NCHAR(4096)"),
                Map.of("indicator_id", "NCHAR(32)"));
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    private void initStFormulaResultRevision(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStFormulaResultRevision();
        template.execute(String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s ("
                        + "ts TIMESTAMP, result_revision_id NCHAR(32), attempt_id NCHAR(32), "
                        + "minute_start TIMESTAMP, val DOUBLE, data_quality TINYINT, "
                        + "formula_version NCHAR(32), parameter_evidence_json NCHAR(4096), "
                        + "calculated_at TIMESTAMP"
                        + ") TAGS (indicator_id NCHAR(32), indicator_code NCHAR(100), "
                        + "building_id NCHAR(32), system_group_id NCHAR(32), equip_id NCHAR(32));",
                db, stable));
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    private void initStIndicatorMinuteState(JdbcTemplate template) {
        String db = properties.getDatabase();
        String stable = properties.getStIndicatorMinuteState();
        template.execute(String.format(
                "CREATE STABLE IF NOT EXISTS %s.%s ("
                        + "ts TIMESTAMP, current_status NCHAR(40), source_fact_id NCHAR(64), "
                        + "attempt_id NCHAR(32), state_updated_at TIMESTAMP, config_revision BIGINT"
                        + ") TAGS (indicator_id NCHAR(32), indicator_code NCHAR(100), "
                        + "building_id NCHAR(32), system_group_id NCHAR(32), equip_id NCHAR(32));",
                db, stable));
        log.info("超级表 [{}] 已创建/已存在", stable);
    }

    /** 验证目标数据库和超级表可被查询；失败只记录启动诊断，不改变初始化结果。 */
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
