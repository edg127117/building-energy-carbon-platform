package com.platform.carbon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

/** 仅在本任务一次性、回环MySQL空库执行V94增量；不接受现有业务库名。 */
@EnabledIfEnvironmentVariable(named="CARBON_INTENSITY_MYSQL_ISOLATED",matches="true")
class CarbonIntensityMysqlIntegrationTest {
    @Test void nativeMysqlMigrationPreservesBuildingSeedsPendingTimeAndEnforcesConstraints() {
        String url=System.getenv("CARBON_INTENSITY_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/carbon_intensity_it_[a-f0-9]{12}\\?.+");
        var ds=new DriverManagerDataSource(url,"root",System.getenv("CARBON_INTENSITY_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY, building_name VARCHAR(100))");
        jdbc.update("INSERT INTO building VALUES ('sentinel','原有建筑')");
        var script=new ResourceDatabasePopulator(new FileSystemResource("src/env/init/V94__mysql_carbon_intensity_catalog.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(ds);
        assertThat(jdbc.queryForObject("SELECT building_name FROM building WHERE building_id='sentinel'",String.class)).isEqualTo("原有建筑");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_intensity_standard_entry",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_intensity_standard_entry WHERE time_status='PENDING' AND applicable_from IS NULL AND applicable_to IS NULL",Integer.class)).isEqualTo(6);
        var versions=new CarbonIntensityCatalogRepository(jdbc).versions();
        var seed=versions.getFirst();
        assertThat(CarbonIntensityCatalogService.contentHash(seed.name(),seed.documentVersion(),seed.sourceReference(),seed.publishedOn(),seed.entries())).isEqualTo(seed.evidenceHash());
        assertThat(versions.getFirst().entries()).allSatisfy(r->{assertThat(r.constraintValue()).isEqualByComparingTo("56.71");assertThat(r.gasBasis()).isEqualTo("CO2_ONLY");});
        assertThatThrownBy(()->jdbc.update("INSERT INTO biz_carbon_intensity_standard_entry (version_id,entry_no,region_prefix,building_category,time_status,rule_json) VALUES ('JS_PUBLIC_202604_V1',99,'45','办公','KNOWN','{}')")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
