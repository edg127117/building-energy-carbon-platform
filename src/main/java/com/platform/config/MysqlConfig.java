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
 * 业务主数据 MySQL 的数据源和 JdbcTemplate 装配。
 *
 * <p>项目同时连接 MySQL 与 TDengine：用户、权限、设备档案等结构化数据默认使用这里的
 * {@code @Primary} 数据源；时序查询必须显式使用 TDengine 组件。命名 Bean 和 Qualifier 共同
 * 防止 MySQL SQL 被发送到 TDengine，或因同类型 Bean 歧义导致应用启动失败。</p>
 */
@Configuration
public class MysqlConfig {
    /**
     * 绑定 {@code spring.datasource}，由 Spring Boot 负责把通用 {@code url} 等属性转换成
     * 实际连接池需要的配置字段。
     */
    @Primary
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 构建并标记默认 MySQL DataSource，供 MyBatis 和未显式限定的数据访问组件使用。 */
    @Primary
    @Bean(name = "dataSource")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 显式创建 MySQL 专用的 JdbcTemplate。
     *
     * <p>项目里同时存在 MySQL 和 TDengine 两个 JdbcTemplate。如果不指定名称，
     * Spring 无法仅按类型可靠判断目标数据库。这里用 {@code @Qualifier("dataSource")}
     * 固定到 MySQL，并用 {@code @Primary} 作为结构化业务数据的默认 JdbcTemplate。</p>
     */
    @Primary
    @Bean(name = "mysqlJdbcTemplate")
    public JdbcTemplate mysqlJdbcTemplate(
            @Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
