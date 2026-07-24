package com.platform.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 修复 MySQL 数据库级字符集为 utf8mb4
 * 原因：docker-compose 首次创建 iot_platform 库时可能使用了非 utf8mb4 的默认字符集，
 * 即使容器命令里加了 --character-set-server=utf8mb4，已存在的库不会自动变更。
 * 这个组件在应用启动时执行一次 ALTER DATABASE，确保后续写入的中文不变成乱码。
 */
@Component
@Slf4j
public class DatabaseCharsetFix {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
