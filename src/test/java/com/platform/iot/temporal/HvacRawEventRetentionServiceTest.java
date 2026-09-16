package com.platform.iot.temporal;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class HvacRawEventRetentionServiceTest {

    @Test
    void enabledConfigurationBuildsServiceWithQualifiedMysqlAndRepository() {
        HvacRawEventRepository repository = mock(HvacRawEventRepository.class);
        JdbcTemplate mysql = mock(JdbcTemplate.class);

        new ApplicationContextRunner()
                .withBean(HvacRawEventRepository.class, () -> repository)
                .withBean("mysqlJdbcTemplate", JdbcTemplate.class, () -> mysql)
                .withPropertyValues("data-retention.cleanup-enabled=true")
                .withUserConfiguration(HvacRawEventRetentionService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HvacRawEventRetentionService.class);
                });
    }

    @Test
    void deletesOnlyRawEventsBeforeConfiguredNinetyDayCutoff() {
        HvacRawEventRepository repository = mock(HvacRawEventRepository.class);
        JdbcTemplate mysql = mysql();
        when(repository.findRetentionPoints(null, 100, "DAIKIN_V2"))
                .thenReturn(java.util.List.of(new HvacRawEventRepository.RetentionPoint(
                        "raw_custom_1", "POINT001", true)));
        long now = Instant.parse("2026-07-23T00:00:00Z").toEpochMilli();
        HvacRawEventRetentionService service = service(repository, mysql, 90, now);

        service.cleanup(now);

        verify(repository).deletePointBefore("raw_custom_1", now - Duration.ofDays(90).toMillis());
    }

    @Test
    void shorterGlobalRetentionStillProtectsDaikinForNinetyDays() {
        HvacRawEventRepository repository = mock(HvacRawEventRepository.class);
        JdbcTemplate mysql = mysql();
        when(repository.findRetentionPoints(null, 100, "DAIKIN_V2")).thenReturn(java.util.List.of(
                new HvacRawEventRepository.RetentionPoint("raw_custom_1", "POINT001", true),
                new HvacRawEventRepository.RetentionPoint("raw_custom_2", "POINT002", false)));
        when(mysql.queryForObject(contains("FROM biz_point_alias"), eq(Integer.class),
                eq("DAIKIN_V2"), eq("POINT001"))).thenReturn(0);
        when(mysql.queryForObject(contains("FROM biz_point_alias"), eq(Integer.class),
                eq("DAIKIN_V2"), eq("POINT002"))).thenReturn(0);
        long now = Instant.parse("2026-07-23T00:00:00Z").toEpochMilli();
        HvacRawEventRetentionService service = service(repository, mysql, 30, now);

        service.cleanup(now);

        verify(repository).deletePointBefore("raw_custom_1", now - Duration.ofDays(90).toMillis());
        verify(repository).deletePointBefore("raw_custom_2", now - Duration.ofDays(30).toMillis());
    }

    @Test
    void rejectsInvalidRetentionInsteadOfDeletingEverything() {
        HvacRawEventRepository repository = mock(HvacRawEventRepository.class);
        JdbcTemplate mysql = mock(JdbcTemplate.class);

        assertThatThrownBy(() -> service(repository, mysql, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static JdbcTemplate mysql() {
        JdbcTemplate mysql = mock(JdbcTemplate.class);
        when(mysql.update(startsWith("UPDATE biz_daikin_raw_retention_cursor SET lease_token=?"),
                any(), any(), any())).thenReturn(1);
        when(mysql.update(contains("SET lease_until_ms=?"), any(), any(), any())).thenReturn(1);
        when(mysql.update(contains("SET cursor_point_id=?"), any(), any(), any())).thenReturn(1);
        when(mysql.queryForObject(contains("SELECT cursor_point_id"), eq(String.class), any()))
                .thenReturn(null);
        when(mysql.queryForObject(contains("FROM biz_point_alias"), eq(Integer.class), any(), any()))
                .thenReturn(0);
        return mysql;
    }

    private static HvacRawEventRetentionService service(HvacRawEventRepository repository,
                                                         JdbcTemplate mysql, int days, long now) {
        return new HvacRawEventRetentionService(repository, mysql, days, 100, 120,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    }
}
