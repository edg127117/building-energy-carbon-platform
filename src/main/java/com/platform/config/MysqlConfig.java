package com.platform.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * MySQL 主数据源配置
 * 作用：解决“双数据源冲突”，明确告诉 Spring Boot 和 MyBatis，
 * 绝大部分业务的增删改查默认走 MySQL，只有被特殊指定的才走 TDengine。
 */
@Configuration
public class MysqlConfig {
    /**
     * 1. 引入 Spring 的数据源属性配置类
     * 这一步极其关键：它能充当“翻译官”，自动把 application.yml 里的 url 映射成 HikariCP 需要的 jdbcUrl
     */
    @Primary
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 2. 利用配置类去构建真正的 MySQL 主数据源
     */
    @Primary
    @Bean(name = "dataSource")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 显式创建 MySQL 专用的 JdbcTemplate。
     *
     * <p>项目里同时存在 MySQL 和 TDengine 两个 JdbcTemplate。如果不指定名称，
     * Spring 可能不知道该注入哪一个。这里用 {@code @Qualifier("dataSource")}
     * 把它固定到 MySQL，并用 {@code @Primary} 表示：普通业务没有特别指定时，
     * 默认使用这个 MySQL JdbcTemplate。</p>
     */
    @Primary
    @Bean(name = "mysqlJdbcTemplate")
    public JdbcTemplate mysqlJdbcTemplate(
            @Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
