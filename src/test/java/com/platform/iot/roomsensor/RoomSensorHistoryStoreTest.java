package com.platform.iot.roomsensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.TdengineProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomSensorHistoryStoreTest {
    private final JdbcTemplate td = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void writesOnlyFrozenObservationThenVerifiesBeforeReturning() throws Exception {
        var event = event("MSR01_RADAR");
        when(td.queryForList(anyString())).thenReturn(List.of(), row(event));
        store().write(event);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(td).execute(sql.capture());
        assertThat(sql.getValue()).startsWith("INSERT INTO iot_telemetry.rs_source USING iot_telemetry.st_radar_observation")
                .contains("target_count,device_seq", "VALUES (1001,1000,", "'device raw time'")
                .doesNotContain("coord", "st_raw_event");
    }

    @Test void retryWithExistingMatchingEvidenceDoesNotWriteAgain() throws Exception {
        var event = event("S4_DOOR");
        when(td.queryForList(anyString())).thenReturn(row(event));
        store().write(event);
        verify(td, never()).execute(anyString());
    }

    @Test void refusesCollisionWithoutOverwritingOrAcceptingUnverifiedWrite() {
        when(td.queryForList(anyString())).thenReturn(List.of(Map.of("event_id", "other", "data_hash", "other")));
        assertThatThrownBy(() -> store().write(event("MSR01_RADAR"))).isInstanceOf(IllegalStateException.class);
        verify(td, never()).execute(anyString());
        reset(td);
        when(td.queryForList(anyString())).thenReturn(List.of());
        assertThatThrownBy(() -> store().write(event("MSR01_RADAR"))).isInstanceOf(IllegalStateException.class);
    }

    @Test void retentionTargetsOnlyTwoModuleSuperTablesAndNeverChangesDatabaseKeep() {
        store().deleteBefore(1000);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(td, times(2)).execute(sql.capture());
        assertThat(sql.getAllValues()).containsExactly(
                "DELETE FROM iot_telemetry.st_radar_observation WHERE ts < 1000",
                "DELETE FROM iot_telemetry.st_door_observation WHERE ts < 1000");
    }

    private RoomSensorHistoryStore store() {
        var properties = new TdengineProperties(); properties.setDatabase("iot_telemetry");
        return new RoomSensorHistoryStore(td, properties, mapper);
    }

    private RoomSensorObservation event(String protocol) {
        return new RoomSensorObservation("event", "source", protocol, 1001, 1000, "device raw time",
                "identity", "equipment", "building", "room", "AC233FC2B716", "C300008A3BAE",
                protocol.equals("MSR01_RADAR") ? 2 : null, protocol.equals("MSR01_RADAR") ? 9 : null,
                protocol.equals("S4_DOOR") ? true : null);
    }

    private List<Map<String, Object>> row(RoomSensorObservation event) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)));
        return List.of(Map.of("event_id", event.eventId(), "data_hash", hash));
    }
}
