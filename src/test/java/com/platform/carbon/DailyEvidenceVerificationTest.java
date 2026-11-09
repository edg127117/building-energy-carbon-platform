package com.platform.carbon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyEvidenceVerificationTest {
    JdbcTemplate jdbc;
    HourlyCarbonRepository hourly=mock(HourlyCarbonRepository.class);
    DailyStaticCarbonRepository daily=mock(DailyStaticCarbonRepository.class);
    DailyEvidenceVerification audit;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:daily-audit-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","") );
        for(String table:new String[]{"biz_carbon_hourly_batch","biz_carbon_daily_static_batch"})jdbc.execute("CREATE TABLE "+table+"(batch_id VARCHAR(32),building_id VARCHAR(32),day_date DATE)");
        jdbc.execute("CREATE TABLE biz_daily_evidence_verification(evidence_kind VARCHAR(8),batch_id VARCHAR(32),building_id VARCHAR(32),day_date DATE,last_verified_at BIGINT,integrity_error VARCHAR(64),PRIMARY KEY(evidence_kind,batch_id))");
        audit=new DailyEvidenceVerification(jdbc,hourly,daily);
    }
    @Test void invalidEvidenceIsRecordedAndOtherRowsAreStillChecked() {
        jdbc.update("INSERT INTO biz_carbon_hourly_batch VALUES ('one','B','2026-09-01'),('two','B','2026-09-02')");
        when(hourly.byId("one")).thenThrow(new IllegalStateException("integrity"));
        assertThat(audit.verify(2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daily_evidence_verification WHERE integrity_error='DAILY_EVIDENCE_INTEGRITY'",Integer.class)).isEqualTo(2);
        clearInvocations(hourly);assertThat(audit.verify(2)).isZero();verifyNoInteractions(hourly);
    }
    @Test void databaseOutageDoesNotLabelEvidenceAsCorrupt() {
        jdbc.update("INSERT INTO biz_carbon_hourly_batch VALUES ('one','B','2026-09-01')");
        when(hourly.byId("one")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->audit.verify(2)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daily_evidence_verification",Integer.class)).isZero();
    }
}
