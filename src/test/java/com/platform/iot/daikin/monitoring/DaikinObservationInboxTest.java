package com.platform.iot.daikin.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.model.*;
import com.platform.iot.daikin.monitoring.state.DaikinMonitoringStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaikinObservationInboxTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private DaikinObservationInbox inbox;
    private DaikinMonitoringTargets targets;
    private DaikinTemperatureIngestion temperature;
    private DaikinMonitoringStateService state;
    private DaikinMonitoringTargets.Target target;
    private MutableClock clock;
    private DaikinDeviceObservation observation;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:inbox" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE biz_daikin_source(source_id VARCHAR(200) PRIMARY KEY)");
        jdbc.update("INSERT INTO biz_daikin_source VALUES ('source')");
        new ResourceDatabasePopulator(new ClassPathResource("daikin-monitoring-checkpoint-test.sql")).execute(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        targets = mock(DaikinMonitoringTargets.class);
        temperature = mock(DaikinTemperatureIngestion.class);
        state = mock(DaikinMonitoringStateService.class);
        clock = new MutableClock();
        target = new DaikinMonitoringTargets.Target(new DaikinDeviceKey("source", "site", "controller", DaikinDeviceKey.Kind.INDOOR, "unit"),
                "pending", "identity", "value", "DAIKIN_INDOOR_V2", "equipment", "ECODE", "building", null, null, 1);
        observation = new DaikinDeviceObservation(target.key(), null, null, null, clock.instant(), null,
                Map.of("onOff", new DaikinFieldValue(DaikinFieldValue.Status.PRESENT, "\"on\"", "on")));
        when(targets.find("pending")).thenReturn(Optional.of(target));
        when(temperature.bindingSnapshot(target)).thenReturn(Map.of());
        when(temperature.persist(eq(target), any())).thenAnswer(call -> call.getArgument(1));
        inbox = new DaikinObservationInbox(jdbc, transaction, new ObjectMapper().findAndRegisterModules(), targets,
                temperature, state, new DaikinMonitoringProperties(), clock);
    }

    @Test
    void failureReplaysOriginalObservationAndSameRoundCannotCreateNewTimestamp() {
        when(temperature.persist(eq(target), any())).thenThrow(new IllegalStateException("isolated storage failure"))
                .thenAnswer(call -> call.getArgument(1));
        String id = stage();
        inbox.process(id);
        assertThat(status()).isEqualTo("PENDING");
        verifyNoInteractions(state);
        clock.now += 60000;
        assertThat(stage()).isEqualTo(id);
        inbox.replay();
        assertThat(status()).isEqualTo("DONE");
        verify(state).observe(DaikinObservationInbox.stateTarget(target), 100L, observation);
        inbox.process(id);
        verify(state, times(1)).observe(any(), anyLong(), any());
        assertThat(jdbc.queryForObject("SELECT observation_json FROM biz_daikin_monitor_inbox", String.class)).isNull();
    }

    @Test
    void disabledIdentityDiscardsCheckpointWithoutPublishingOldOwnership() {
        String id = stage();
        when(targets.find("pending")).thenReturn(Optional.empty());
        inbox.process(id);
        assertThat(status()).isEqualTo("DONE");
        verify(temperature, never()).persist(any(), any());
        verifyNoInteractions(state);
    }

    @Test
    void bindingChangeDoesNotReplayAnOldObservationIntoNewPoint() {
        String id = stage();
        var point = new com.platform.iot.quality.PointRuntimeConfig("new-point", "PC", "温度", "building", null,
                "equipment", "ECODE", "AHU", "MAIN", "T", "AI", "°C", "ONLINE", 0, null, null);
        when(temperature.bindingSnapshot(target)).thenReturn(Map.of("roomTemp", point));
        inbox.process(id);
        assertThat(status()).isEqualTo("DONE");
        verify(temperature, never()).persist(any(), any());
        verifyNoInteractions(state);
    }

    @Test
    void expiredWriterCannotPublishStateAndNextWriterCanRecover() {
        when(temperature.persist(eq(target), any())).thenAnswer(call -> {
            clock.now += 181000;
            return call.getArgument(1);
        });
        String id = stage();
        inbox.process(id);
        assertThat(status()).isEqualTo("WRITING");
        verifyNoInteractions(state);
        when(temperature.persist(eq(target), any())).thenAnswer(call -> call.getArgument(1));
        inbox.replay();
        assertThat(status()).isEqualTo("DONE");
        verify(state).observe(any(), eq(100L), eq(observation));
    }

    private String stage() { return transaction.execute(status -> inbox.stage(target, 100L, observation)); }
    private String status() { return jdbc.queryForObject("SELECT status FROM biz_daikin_monitor_inbox", String.class); }
    private static class MutableClock extends Clock {
        long now = Instant.parse("2026-09-16T00:00:00Z").toEpochMilli();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
