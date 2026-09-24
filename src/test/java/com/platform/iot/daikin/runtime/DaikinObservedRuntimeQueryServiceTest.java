package com.platform.iot.daikin.runtime;

import com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DaikinObservedRuntimeQueryServiceTest {
    @Test
    void returnsOnlyCurrentAuthorizedIdentityAndGroupsDaysWithoutInventingMissingTime() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:observed-runtime-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("daikin-monitoring-state-test.sql"))
                .execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        long day = Instant.parse("2026-09-16T16:00:00Z").toEpochMilli();
        jdbc.update("""
                INSERT INTO biz_daikin_observed_runtime_day
                  (identity_id,building_id,mapping_version,day_start_ms,on_ms,covered_ms)
                VALUES (?,?,?,?,?,?)
                """, "identity-A", "building-A", 3, day, 3_600_000, 7_200_000);
        jdbc.update("""
                INSERT INTO biz_daikin_observed_runtime_day
                  (identity_id,building_id,mapping_version,day_start_ms,on_ms,covered_ms)
                VALUES (?,?,?,?,?,?)
                """, "identity-A", "building-A", 3, day + 86_400_000, 1_800_000, 3_600_000);
        jdbc.update("""
                INSERT INTO biz_daikin_observed_runtime_day
                  (identity_id,building_id,mapping_version,day_start_ms,on_ms,covered_ms)
                VALUES (?,?,?,?,?,?)
                """, "identity-A", "building-B", 4, day, 9_999_999, 9_999_999);
        var access = mock(DaikinMonitoringQueryService.class);
        when(access.requireRuntimeEquipment(7L, Set.of("OPERATOR"), "equipment-A"))
                .thenReturn(new DaikinMonitoringQueryService.RuntimeScope("identity-A", "building-A", 3));
        var service = new DaikinObservedRuntimeQueryService(jdbc, access,
                Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC));

        var page = service.values(7L, Set.of("OPERATOR"), "equipment-A", "MONTH", null, 50);

        verify(access).requireRuntimeEquipment(7L, Set.of("OPERATOR"), "equipment-A");
        assertThat(page.source()).isEqualTo("PLATFORM_OBSERVED");
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().onMillis()).isEqualTo(5_400_000);
        assertThat(page.items().getFirst().coveredMillis()).isEqualTo(10_800_000);
        assertThat(page.items().getFirst().elapsedMillis()).isGreaterThan(page.items().getFirst().coveredMillis());
    }
}
