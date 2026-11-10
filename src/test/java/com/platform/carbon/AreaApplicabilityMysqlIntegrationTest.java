package com.platform.carbon;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;

/** 显式启动的一次性本机MySQL；复用同一组区间SQL行为测试并实际执行V95。 */
@EnabledIfEnvironmentVariable(named="AREA_MYSQL_URL",matches=".+")
class AreaApplicabilityMysqlIntegrationTest extends AreaApplicabilityRepositoryTest {
    @org.junit.jupiter.api.Test void migrationSeedsOnlyActiveAreasAndRetainsOriginalDates() {
        jdbc.execute("DROP TABLE biz_carbon_area_applicability");
        insert("retired","9000",start,null);
        jdbc.update("UPDATE biz_carbon_denominator_version SET status='DISABLED' WHERE denominator_version_id='retired'");
        createApplicabilitySchema();
        assertThat(repository.areaSlices("B",com.platform.carbon.CarbonModels.UsageNature.FORMAL,start,end))
                .extracting(s->s.version().denominatorVersionId()).containsExactly("A");
        assertThat(repository.findDenominatorVersion("A").effectiveTo()).isNull();
    }
    @Override JdbcTemplate createJdbc() {
        assertThat(System.getenv("AREA_MYSQL_ISOLATED")).isEqualTo("true");
        String url=System.getenv("AREA_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.+");
        String database="area_test_"+java.util.UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        admin.execute("CREATE DATABASE "+database);
        return new JdbcTemplate(new DriverManagerDataSource(url.replace("/?","/"+database+"?"),"root",""));
    }
    @Override void createApplicabilitySchema() {
        new ResourceDatabasePopulator(new FileSystemResource("src/env/init/V95__mysql_area_applicability.sql")).execute(jdbc.getDataSource());
    }
}
