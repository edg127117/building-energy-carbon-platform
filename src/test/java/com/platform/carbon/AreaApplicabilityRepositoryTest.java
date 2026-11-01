package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.util.UUID;
import static com.platform.carbon.CarbonModels.*;
import static org.assertj.core.api.Assertions.*;

/** 实际H2 SQL验证区间接续，不代表MySQL迁移验收。 */
class AreaApplicabilityRepositoryTest {
    JdbcTemplate jdbc; CarbonRuleRepository repository;
    final LocalDate start=LocalDate.of(2024,1,1), july=start.plusMonths(6), end=start.plusYears(1);
    @BeforeEach void setup() {
        jdbc=createJdbc();
        jdbc.execute("CREATE TABLE biz_carbon_denominator(denominator_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(100),denominator_type VARCHAR(100))");
        jdbc.execute("CREATE TABLE biz_carbon_denominator_version(denominator_version_id VARCHAR(32) PRIMARY KEY,denominator_id VARCHAR(100),version_no INT,denominator_value DECIMAL(38,18),unit_code VARCHAR(100),source_reference VARCHAR(100),evidence_reference VARCHAR(100),usage_nature VARCHAR(100),status VARCHAR(100),effective_from DATE,effective_to DATE,config_revision INT,created_by BIGINT,created_at TIMESTAMP,reviewed_by BIGINT,reviewed_at TIMESTAMP,review_comment VARCHAR(100),activated_by BIGINT,activated_at TIMESTAMP,disabled_by BIGINT,disabled_at TIMESTAMP)");
        createApplicabilitySchema();
        jdbc.update("INSERT INTO biz_carbon_denominator VALUES ('D','B','BUILDING_AREA')");
        repository=new CarbonRuleRepository(jdbc);
        insert("A","10000",start,null);
        repository.applyArea(repository.findDenominatorVersion("A"),3);
    }
    JdbcTemplate createJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:area"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    }
    void createApplicabilitySchema() {
        jdbc.execute("CREATE TABLE biz_carbon_area_applicability(applicability_id VARCHAR(32) PRIMARY KEY,denominator_version_id VARCHAR(32),effective_from DATE,effective_to DATE)");
    }
    void insert(String id,String area,LocalDate from,LocalDate to) {
        jdbc.update("INSERT INTO biz_carbon_denominator_version VALUES (?,'D',1,?,'M2','source','evidence','FORMAL','ACTIVE',?,?,1,1,CURRENT_TIMESTAMP,2,CURRENT_TIMESTAMP,'approved',3,CURRENT_TIMESTAMP,NULL,NULL)",id,area,from,to);
    }
    @Test void futureActivationKeepsHistoricalAndCurrentAreaAndOriginalEvidence() {
        insert("B","12000",july,null);repository.applyArea(repository.findDenominatorVersion("B"),3);
        assertThat(repository.findActiveDenominators("B",DenominatorType.BUILDING_AREA,UsageNature.FORMAL,start,july)).extracting(DenominatorVersion::denominatorVersionId).containsExactly("A");
        assertThat(repository.findDenominatorVersion("A").effectiveTo()).isNull();
        var slices=repository.areaSlices("B",UsageNature.FORMAL,start,end);
        assertThat(slices).hasSize(2);
        assertThat(AreaTimeline.weigh(slices,start,end).status()).isEqualTo("AVAILABLE");
        repository.removeArea("B");
        assertThat(AreaTimeline.weigh(repository.areaSlices("B",UsageNature.FORMAL,start,end),start,end).status()).isEqualTo("AREA_GAP");
        assertThat(repository.findActiveDenominators("B",DenominatorType.BUILDING_AREA,UsageNature.FORMAL,start,july)).hasSize(1);
    }
    @Test void middleCorrectionPreservesBothSidesAndDoesNotCrossBuildingOrNature() {
        insert("B","11000",start.plusMonths(2),july);repository.applyArea(repository.findDenominatorVersion("B"),3);
        assertThat(repository.areaSlices("B",UsageNature.FORMAL,start,end)).extracting(s->s.version().denominatorVersionId()).containsExactly("A","B","A");
        assertThat(repository.areaSlices("OTHER",UsageNature.FORMAL,start,end)).isEmpty();
        assertThat(repository.areaSlices("B",UsageNature.DEVELOPMENT_REFERENCE,start,end)).isEmpty();
        // 原全年单一版本选择不会拿不同版本拼成一个虚假的年度分母。
        assertThat(repository.findActiveDenominators("B",DenominatorType.BUILDING_AREA,UsageNature.FORMAL,start,end)).hasSize(2);
    }
    @Test void fullReplacementRetiresOldVersionAndTransactionFailureRestoresTimeline() {
        insert("B","12000",start,null);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(()->tx.execute(status->{
            repository.findDenominatorIdForUpdate("B",DenominatorType.BUILDING_AREA);
            repository.applyArea(repository.findDenominatorVersion("B"),3);
            throw new IllegalStateException("rollback");
        })).hasMessageContaining("rollback");
        assertThat(repository.areaSlices("B",UsageNature.FORMAL,start,end)).extracting(s->s.version().denominatorVersionId()).containsExactly("A");
        repository.applyArea(repository.findDenominatorVersion("B"),3);
        assertThat(repository.findDenominatorVersion("A").status()).isEqualTo(LifecycleStatus.DISABLED);
        assertThat(repository.areaSlices("B",UsageNature.FORMAL,start,end)).extracting(s->s.version().denominatorVersionId()).containsExactly("B");
    }
}
