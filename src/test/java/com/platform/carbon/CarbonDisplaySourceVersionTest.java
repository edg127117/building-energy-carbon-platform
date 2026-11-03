package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 独立最小版本头，不放置事实/冻结JSON表，防止快速版本检查意外退回大证据读取。 */
class CarbonDisplaySourceVersionTest {
    JdbcTemplate jdbc;CarbonDisplaySourceVersion versions;
    final CarbonDisplayQueryContracts.Query flow=CarbonDisplayQueryContracts.flow(new JointFlowContracts.Request("B","YEAR","2026","2026-10",null));
    final CarbonDisplayQueryContracts.Query intensity=CarbonDisplayQueryContracts.intensity("B","MONTHLY_YTD",2026,10,"Asia/Shanghai");
    @BeforeEach void setup(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:display-version-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE biz_carbon_calculation_batch(calculation_batch_id VARCHAR,building_id VARCHAR,period_type VARCHAR,period_start TIMESTAMP,period_end TIMESTAMP,timezone_id VARCHAR,result_nature VARCHAR,publication_status VARCHAR,status VARCHAR,rounding_policy_version_id VARCHAR,completed_at TIMESTAMP,detail_count INT)");
        jdbc.execute("CREATE TABLE biz_carbon_result_relation(old_calculation_batch_id VARCHAR,new_calculation_batch_id VARCHAR,relation_status VARCHAR)");
        jdbc.execute("CREATE TABLE biz_carbon_calculation_summary(calculation_batch_id VARCHAR,metric_code VARCHAR,dimension_code VARCHAR,evidence_hash VARCHAR)");
        jdbc.execute("CREATE TABLE biz_joint_flow_selection(building_id VARCHAR,version_id VARCHAR,revision INT)");
        jdbc.execute("CREATE TABLE biz_joint_flow_configuration(building_id VARCHAR,version_id VARCHAR,revision INT,status VARCHAR,configuration_hash VARCHAR)");
        for(String table:List.of("biz_energy_conversion_parameter_version","biz_energy_conversion_formula_version","biz_standard_coal_lhv_version","biz_energy_item_version","biz_measurement_unit_version","biz_energy_item_unit_compatibility_version"))
            jdbc.execute("CREATE TABLE "+table+"(version_id VARCHAR,config_revision INT,status VARCHAR,effective_from TIMESTAMP,effective_to TIMESTAMP)");
        jdbc.execute("CREATE TABLE building(building_id VARCHAR,region_code VARCHAR,building_type VARCHAR,carbon_evaluation_category VARCHAR,carbon_evaluation_subcategory VARCHAR,del_flag INT)");
        jdbc.execute("CREATE TABLE biz_carbon_intensity_standard_version(catalog_id VARCHAR,version_id VARCHAR,revision_no INT,evidence_hash VARCHAR)");
        jdbc.execute("CREATE TABLE biz_carbon_denominator(denominator_id VARCHAR,building_id VARCHAR)");
        jdbc.execute("CREATE TABLE biz_carbon_denominator_version(denominator_id VARCHAR,denominator_version_id VARCHAR,config_revision INT,status VARCHAR,effective_from DATE,effective_to DATE)");
        jdbc.execute("CREATE TABLE biz_carbon_area_applicability(applicability_id VARCHAR,denominator_version_id VARCHAR,effective_from DATE,effective_to DATE)");
        jdbc.execute("CREATE TABLE biz_energy_reporting_record(building_id VARCHAR,record_id VARCHAR,record_kind VARCHAR,status VARCHAR,revision INT,month_date VARCHAR)");
        versions=new CarbonDisplaySourceVersion(jdbc,new CarbonDisplayQueryProperties());
    }
    @Test void publicationSupersessionAndNewBatchInvalidateWithoutReadingEvidence(){
        String empty=versions.version(flow);
        jdbc.update("INSERT INTO biz_carbon_calculation_batch VALUES ('C','B','MONTH','2026-01-01','2026-02-01','Asia/Shanghai','FORMAL','DIRECT','COMPLETED_COMPLETE','R',NULL,1)");
        String complete=versions.version(flow);assertThat(complete).isNotEqualTo(empty);
        jdbc.update("INSERT INTO biz_carbon_result_relation VALUES ('C','NEW','CANDIDATE')");
        String candidate=versions.version(flow);assertThat(candidate).isNotEqualTo(complete);
        jdbc.update("UPDATE biz_carbon_result_relation SET relation_status='SUPERSEDED'");assertThat(versions.version(flow)).isNotEqualTo(candidate);
        assertThat(versions.version(CarbonDisplayQueryContracts.flow(new JointFlowContracts.Request("OTHER","YEAR","2026","2026-10",null)))).isNotEqualTo(empty);
    }
    @Test void mappingSelectionAndConversionOptionsInvalidateEvenWithoutNewCarbonBatch(){
        String initial=versions.version(flow);
        jdbc.update("INSERT INTO biz_joint_flow_selection VALUES ('B','D',1)");jdbc.update("INSERT INTO biz_joint_flow_configuration VALUES ('B','D',1,'APPROVED','hash')");
        String selected=versions.version(flow);assertThat(selected).isNotEqualTo(initial);
        jdbc.update("INSERT INTO biz_energy_conversion_parameter_version VALUES ('P',1,'APPROVED','2020-01-01',NULL)");
        String options=versions.version(flow);assertThat(options).isNotEqualTo(selected);
        jdbc.update("UPDATE biz_energy_conversion_parameter_version SET status='DISABLED',config_revision=2");assertThat(versions.version(flow)).isNotEqualTo(options);
        var explicit=CarbonDisplayQueryContracts.flow(new JointFlowContracts.Request("B","YEAR","2026","2026-10","D"));
        String pinned=versions.version(explicit);jdbc.update("UPDATE biz_joint_flow_selection SET version_id='OTHER',revision=2");assertThat(versions.version(explicit)).isEqualTo(pinned);
    }
    @Test void profileCatalogAreaTimelineAndLastYearSourcesInvalidateIntensity(){
        String initial=versions.version(intensity);
        jdbc.update("INSERT INTO building VALUES ('B','320113','教育',NULL,NULL,0)");String profile=versions.version(intensity);assertThat(profile).isNotEqualTo(initial);
        jdbc.update("INSERT INTO biz_carbon_intensity_standard_version VALUES ('CAT','V1',1,'hash')");String catalog=versions.version(intensity);assertThat(catalog).isNotEqualTo(profile);
        jdbc.update("INSERT INTO biz_carbon_denominator VALUES ('D','B')");jdbc.update("INSERT INTO biz_carbon_denominator_version VALUES ('D','A',1,'ACTIVE','2020-01-01',NULL)");
        String area=versions.version(intensity);jdbc.update("INSERT INTO biz_carbon_area_applicability VALUES ('AP','A','2020-01-01',NULL)");assertThat(versions.version(intensity)).isNotEqualTo(area);
        String current=versions.version(intensity);jdbc.update("INSERT INTO biz_energy_reporting_record VALUES ('B','R','BATCH','SEALED',1,'2025-02-01')");assertThat(versions.version(intensity)).isNotEqualTo(current);
    }
    @Test void versionCapacityNeverSilentlyTruncates(){
        var properties=new CarbonDisplayQueryProperties();properties.setMaximumVersionRows(1);versions=new CarbonDisplaySourceVersion(jdbc,properties);
        jdbc.update("INSERT INTO biz_carbon_intensity_standard_version VALUES ('C','V1',1,'h'),('C','V2',2,'h2')");
        assertThatThrownBy(()->versions.version(intensity)).isInstanceOf(com.platform.framework.exception.BusinessException.class).hasMessageContaining("容量");
    }
}
