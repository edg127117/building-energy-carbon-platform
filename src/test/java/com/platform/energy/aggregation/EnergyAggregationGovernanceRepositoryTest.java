package com.platform.energy.aggregation;

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

/** 隔离 H2 证明原生累计事件查询按审批时点而不是当前状态回读。 */
class EnergyAggregationGovernanceRepositoryTest {
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 26, 0, 0);
    private static final LocalDateTime TO = FROM.plusHours(1);
    private static final LocalDateTime AS_OF = TO.plusHours(1);

    private JdbcTemplate jdbc;
    private EnergyAggregationGovernanceRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:energy_governance_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        repository = new EnergyAggregationGovernanceRepository(jdbc);
    }

    @Test
    void excludesAnApprovedEventWhoseApprovalIsLaterThanCalculationAsOf() {
        insertEvent("EVENT_INCLUDED", "EVENT_INCLUDED_V1", FROM.plusMinutes(20), AS_OF.minusSeconds(1));
        insertEvent("EVENT_LATE", "EVENT_LATE_V1", FROM.plusMinutes(40), AS_OF.plusSeconds(1));
        insertEvent("EVENT_END", "EVENT_END_V1", TO, AS_OF);

        List<EnergyAggregationGovernanceRepository.EventVersionRow> values = repository.listApprovedEventsAt(
                "BLD001", "POINT001", FROM, TO, AS_OF, 10);

        assertThat(values).extracting(EnergyAggregationGovernanceRepository.EventVersionRow::eventVersionId)
                .containsExactly("EVENT_INCLUDED_V1", "EVENT_END_V1");
        assertThat(values).noneMatch(value -> value.eventVersionId().equals("EVENT_LATE_V1"));
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE biz_energy_meter_event (
                  event_id VARCHAR(64) PRIMARY KEY,
                  building_id VARCHAR(64) NOT NULL,
                  meter_point_id VARCHAR(64) NOT NULL,
                  event_type VARCHAR(32) NOT NULL,
                  created_by BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE biz_energy_meter_event_version (
                  event_version_id VARCHAR(64) PRIMARY KEY,
                  event_id VARCHAR(64) NOT NULL,
                  version_no INT NOT NULL,
                  occurred_at TIMESTAMP NOT NULL,
                  pre_event_reading DECIMAL(30,10),
                  post_event_reading DECIMAL(30,10),
                  rollover_modulus DECIMAL(30,10),
                  old_meter_id VARCHAR(64),
                  new_meter_id VARCHAR(64),
                  relation_version_before VARCHAR(64),
                  relation_version_after VARCHAR(64),
                  status VARCHAR(32) NOT NULL,
                  source_type VARCHAR(64) NOT NULL,
                  evidence_reference VARCHAR(255) NOT NULL,
                  simulation_flag BOOLEAN NOT NULL,
                  config_revision INT NOT NULL,
                  created_by BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  approved_by BIGINT,
                  approved_at TIMESTAMP,
                  review_comment VARCHAR(255)
                )
                """);
    }

    private void insertEvent(String eventId, String versionId, LocalDateTime occurredAt,
                             LocalDateTime approvedAt) {
        jdbc.update("""
                INSERT INTO biz_energy_meter_event
                (event_id,building_id,meter_point_id,event_type,created_by,created_at)
                VALUES (?,?,?,?,?,?)
                """, eventId, "BLD001", "POINT001", "RESET", 7L, timestamp(FROM.minusHours(1)));
        jdbc.update("""
                INSERT INTO biz_energy_meter_event_version
                (event_version_id,event_id,version_no,occurred_at,pre_event_reading,post_event_reading,
                 rollover_modulus,old_meter_id,new_meter_id,relation_version_before,relation_version_after,status,
                 source_type,evidence_reference,simulation_flag,config_revision,created_by,created_at,approved_by,
                 approved_at,review_comment)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, versionId, eventId, 1, timestamp(occurredAt), new BigDecimal("100"), BigDecimal.ZERO,
                null, null, null, null, null, "APPROVED", "MANUAL", "approved event evidence", false,
                1, 7L, timestamp(FROM.minusHours(1)), 8L, timestamp(approvedAt), "approved");
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
