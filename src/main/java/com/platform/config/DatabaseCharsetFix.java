package com.platform.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 应用启动时修正既有 {@code iot_platform} MySQL 库的默认字符集。
 *
 * <p>容器服务端改成 utf8mb4 不会自动改变已经创建的数据库，本组件因此通过明确限定为
 * {@code mysqlJdbcTemplate} 的连接执行 {@code ALTER DATABASE}，只影响此后新建表/字段采用的
 * 默认字符集，不转换已有列和历史数据。</p>
 *
 * <p>{@code database.charset-fix.enabled=false} 时组件完全不创建；自动化测试关闭该开关，避免
 * 测试启动时连接或修改真实 MySQL。执行失败只记录告警，不阻断其他不依赖该修正的模块启动。</p>
 */
@Component
@Slf4j
// 默认开启以保留原来的生产行为；测试配置会把它关闭。
@ConditionalOnProperty(
        prefix = "database.charset-fix",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DatabaseCharsetFix {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCharsetFix(
            // 必须明确使用 MySQL；如果误用 taosJdbcTemplate，这条 MySQL SQL 会发给 TDengine。
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在 Bean 初始化后执行一次 MySQL 数据库级字符集声明。
     * 权限不足或数据库不可用时保留启动流程并记录原因；该降级不代表已有乱码已经被修复。
     */
    @PostConstruct
    public void fixDatabaseCharset() {
        try {
            jdbcTemplate.execute("ALTER DATABASE iot_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            log.info("[CharsetFix] 数据库 iot_platform 字符集已设置为 utf8mb4");
        } catch (Exception e) {
            log.warn("[CharsetFix] 修改数据库字符集失败，可能已经正确或权限不足: {}", e.getMessage());
        }
    }
}
