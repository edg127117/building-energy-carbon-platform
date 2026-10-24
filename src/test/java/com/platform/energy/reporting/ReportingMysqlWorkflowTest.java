package com.platform.energy.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅调用方确认的专用临时MySQL实例：继承账单闭环、并发、回滚和权限测试。 */
@EnabledIfEnvironmentVariable(named="REPORTING_MYSQL_ISOLATED",matches="true")
class ReportingMysqlWorkflowTest extends ReportingWorkflowTest {
    private JdbcTemplate admin;private String database;
    @Override javax.sql.DataSource dataSource() throws Exception {
        String url=System.getenv("REPORTING_MYSQL_URL");
        assertThat(url).as("只允许本机本任务一次性实例").startsWith("jdbc:mysql://127.0.0.1:39487/");
        String user=System.getenv("REPORTING_MYSQL_USER"),password=System.getenv("REPORTING_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        database="reporting_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:39487/"+database+"?useSSL=false&allowPublicKeyRetrieval=true",user,password);
        var jdbc=new JdbcTemplate(ds);
        for(String sql:Files.readString(Path.of("src/env/init/V86__mysql_energy_reporting.sql")).split(";"))if(!sql.isBlank())jdbc.execute(sql);
        return ds;
    }
    @AfterEach void removeOnlyOwnTemporarySchema() {
        if(admin!=null&&database!=null&&database.matches("reporting_test_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+database);
    }
}
