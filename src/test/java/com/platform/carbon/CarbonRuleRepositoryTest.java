package com.platform.carbon;

import com.platform.carbon.CarbonModels.FactorVersion;
import com.platform.carbon.CarbonModels.GwpVersion;
import com.platform.carbon.CarbonModels.UsageNature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 隔离 H2 验证小时查询保留全部相交候选，供服务层拒绝中途换版。 */
class CarbonRuleRepositoryTest {
    private static final LocalDateTime HOUR_START = LocalDateTime.of(2026, 10, 26, 0, 0);
    private static final LocalDateTime HOUR_END = HOUR_START.plusHours(1);

    private JdbcTemplate jdbc;
    private CarbonRuleRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:carbon_rule_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        repository = new CarbonRuleRepository(jdbc);
    }

    @Test
    void returnsEveryFactorVersionIntersectingTheHourIncludingAMidHourReplacement() {
        insertFactor("FACTOR_BEFORE", HOUR_START.minusHours(2), HOUR_START);
        insertFactor("FACTOR_OLD", HOUR_START.minusDays(1), HOUR_START.plusMinutes(30));
        insertFactor("FACTOR_NEW", HOUR_START.plusMinutes(30), null);
        insertFactor("FACTOR_AFTER", HOUR_END, null);

        List<FactorVersion> values = repository.hourlyFactors("ELECTRICITY", HOUR_START, HOUR_END);

        assertThat(values).extracting(FactorVersion::factorVersionId)
                .containsExactlyInAnyOrder("FACTOR_OLD", "FACTOR_NEW");
        assertThat(values).allSatisfy(value -> assertThat(value.components()).singleElement()
                .satisfies(component -> {
                    assertThat(component.value()).isEqualByComparingTo("0.5827");
                    assertThat(component.unit()).isEqualTo("KG_CO2E/KWH");
                }));
    }

    @Test
    void keepsAllFormalGwpCandidatesButDoesNotUseDevelopmentOnlyRowsForFormalCalculation() {
        insertGwp("GWP_BEFORE", UsageNature.FORMAL, HOUR_START.minusHours(2), HOUR_START);
        insertGwp("GWP_OLD", UsageNature.FORMAL, HOUR_START.minusDays(1), HOUR_START.plusMinutes(30));
        insertGwp("GWP_NEW", UsageNature.FORMAL, HOUR_START.plusMinutes(30), null);
        insertGwp("GWP_DEVELOPMENT", UsageNature.DEVELOPMENT_REFERENCE, HOUR_START.minusDays(1), null);
        insertGwp("GWP_AFTER", UsageNature.FORMAL, HOUR_END, null);

        List<GwpVersion> formal = repository.hourlyGwp("CO2", UsageNature.FORMAL, HOUR_START, HOUR_END);
        List<GwpVersion> development = repository.hourlyGwp("CO2", UsageNature.DEVELOPMENT_REFERENCE,
                HOUR_START, HOUR_END);

        assertThat(formal).extracting(GwpVersion::gwpVersionId)
                .containsExactlyInAnyOrder("GWP_OLD", "GWP_NEW");
        assertThat(development).extracting(GwpVersion::gwpVersionId)
                .containsExactlyInAnyOrder("GWP_OLD", "GWP_NEW", "GWP_DEVELOPMENT");
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE biz_carbon_factor (
                  factor_id VARCHAR(64) PRIMARY KEY,
                  factor_code VARCHAR(100) NOT NULL,
                  scope_type VARCHAR(32) NOT NULL,
                  energy_item_code VARCHAR(64) NOT NULL,
                  factor_category VARCHAR(64) NOT NULL,
                  result_basis VARCHAR(32) NOT NULL,
                  gas_code VARCHAR(32),
                  gas_coverage VARCHAR(64)
                )
                """);
        jdbc.execute("""
                CREATE TABLE biz_carbon_factor_version (
                  factor_version_id VARCHAR(64) PRIMARY KEY,
                  factor_id VARCHAR(64) NOT NULL,
                  version_no INT NOT NULL,
                  source_version_id VARCHAR(64) NOT NULL,
                  applicability_level VARCHAR(32) NOT NULL,
                  building_id VARCHAR(64),
                  region_code VARCHAR(64),
                  input_unit_code VARCHAR(32) NOT NULL,
                  standard_condition_code VARCHAR(64),
                  usage_nature VARCHAR(32) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  effective_from TIMESTAMP NOT NULL,
                  effective_to TIMESTAMP,
                  formula_version_id VARCHAR(64) NOT NULL,
                  rounding_policy_version_id VARCHAR(64) NOT NULL,
                  config_revision INT NOT NULL,
                  created_by BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  reviewed_by BIGINT,
                  reviewed_at TIMESTAMP,
                  review_comment VARCHAR(255),
                  activated_by BIGINT,
                  activated_at TIMESTAMP,
                  data_year INT,
                  accounting_year INT
                )
                """);
        jdbc.execute("""
                CREATE TABLE biz_carbon_factor_component (
                  component_id VARCHAR(64) PRIMARY KEY,
                  factor_version_id VARCHAR(64) NOT NULL,
                  component_type VARCHAR(64) NOT NULL,
                  component_value DECIMAL(30,10) NOT NULL,
                  component_unit VARCHAR(64) NOT NULL,
                  source_version_id VARCHAR(64) NOT NULL,
                  evidence_reference VARCHAR(255) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE biz_carbon_gwp_version (
                  gwp_version_id VARCHAR(64) PRIMARY KEY,
                  gas_code VARCHAR(32) NOT NULL,
                  gwp_value DECIMAL(30,10) NOT NULL,
                  source_reference VARCHAR(255) NOT NULL,
                  usage_nature VARCHAR(32) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  effective_from TIMESTAMP NOT NULL,
                  effective_to TIMESTAMP
                )
                """);
    }

    private void insertFactor(String versionId, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        String factorId = "IDENTITY_" + versionId;
        jdbc.update("""
                INSERT INTO biz_carbon_factor
                (factor_id,factor_code,scope_type,energy_item_code,factor_category,result_basis,gas_code,gas_coverage)
                VALUES (?,?,?,?,?,?,?,?)
                """, factorId, factorId, "SCOPE_2", "ELECTRICITY", "PURCHASED_ELECTRICITY_LOCATION",
                "CO2E_DIRECT", null, "DIRECT_FACTOR_GAS_COVERAGE");
        jdbc.update("""
                INSERT INTO biz_carbon_factor_version
                (factor_version_id,factor_id,version_no,source_version_id,applicability_level,building_id,
                 region_code,input_unit_code,standard_condition_code,usage_nature,status,effective_from,
                 effective_to,formula_version_id,rounding_policy_version_id,config_revision,created_by,
                 created_at,reviewed_by,reviewed_at,review_comment,activated_by,activated_at,data_year,accounting_year)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, versionId, factorId, 1, "SOURCE_V1", "NATIONAL", null, null, "KWH", null,
                "FORMAL", "ACTIVE", timestamp(effectiveFrom), timestamp(effectiveTo), "FORMULA_V1",
                "ROUNDING_V1", 1, 7L, timestamp(HOUR_START.minusDays(2)), 8L,
                timestamp(HOUR_START.minusDays(1)), "reviewed", 9L, timestamp(HOUR_START.minusDays(1)),
                2026, 2026);
        jdbc.update("""
                INSERT INTO biz_carbon_factor_component
                (component_id,factor_version_id,component_type,component_value,component_unit,source_version_id,evidence_reference)
                VALUES (?,?,?,?,?,?,?)
                """, "DIRECT_" + versionId, versionId, "DIRECT_EMISSION_FACTOR", new BigDecimal("0.5827"),
                "KG_CO2E/KWH", "SOURCE_V1", "formal source evidence");
    }

    private void insertGwp(String versionId, UsageNature usageNature, LocalDateTime effectiveFrom,
                           LocalDateTime effectiveTo) {
        jdbc.update("""
                INSERT INTO biz_carbon_gwp_version
                (gwp_version_id,gas_code,gwp_value,source_reference,usage_nature,status,effective_from,effective_to)
                VALUES (?,?,?,?,?,?,?,?)
                """, versionId, "CO2", BigDecimal.ONE, "formal GWP source", usageNature.name(), "ACTIVE",
                timestamp(effectiveFrom), timestamp(effectiveTo));
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
