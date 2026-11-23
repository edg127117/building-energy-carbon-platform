package com.platform.iot.comfort;

import com.platform.alarm.AlarmService;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.query.DaikinBuildingMonitoringDtos.RoomSetting;
import com.platform.iot.daikin.monitoring.query.DaikinBuildingMonitoringDtos.RoomSummary;
import com.platform.iot.daikin.monitoring.query.DaikinBuildingMonitoringService;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService;
import com.platform.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComfortControllerTest {
    private static final long USER = 1L;
    private static final Set<String> ROLES = Set.of("BUILDING_OWNER");
    private static final String SPACES = "/v1/comfort/buildings/B/spaces";
    private static final String ROOM = "/v1/comfort/spaces/r314";

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ComfortPmvHistory history;
    @MockBean TokenCacheService tokens;
    @MockBean ComfortQueryService comfort;
    @MockBean RoomSensorRoomQueryService sensors;
    @MockBean DaikinBuildingMonitoringService hvac;
    @MockBean AlarmService alarms;

    @Test
    void anonymousRequestCannotReachRoomQueries() throws Exception {
        mvc.perform(get(SPACES)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOM + "/occupancy")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOM + "/door-stats")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOM + "/hvac-status")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOM + "/electricity")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOM + "/parameters")).andExpect(status().isUnauthorized());
        verifyNoInteractions(comfort, sensors, hvac);
    }

    @Test
    @Sql("classpath:comfort-parameters-schema-test.sql")
    void savesOnlyTheAuthorizedRoomsCompleteTemperatureBandAndAllowsAnExplicitClear() throws Exception {
        Set<String> editorRoles = Set.of("ENERGY_MANAGER");
        var room = new ComfortQueryService.Space("r314", "B314", "B", "三层");
        var other = new ComfortQueryService.Space("r313", "B313", "B", "三层");
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(comfort.room(USER, editorRoles, "r314")).thenReturn(room);
        when(comfort.room(USER, editorRoles, "r313")).thenReturn(other);
        when(comfort.room(USER, ROLES, "r314")).thenReturn(room);
        when(comfort.room(USER, editorRoles, "other"))
                .thenThrow(new BusinessException(404, "COMFORT_SPACE_NOT_FOUND", "监测房间不可用"));
        String editor = "Bearer " + jwt.generateToken(USER, "comfort-editor", List.copyOf(editorRoles), 1L);
        String reader = "Bearer " + jwt.generateToken(USER, "comfort-reader", List.copyOf(ROLES), 1L);
        String path = ROOM + "/parameters";

        mvc.perform(get(path).header("Authorization", editor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comfortBand").isEmpty())
                .andExpect(jsonPath("$.data.editable").value(true));
        mvc.perform(put(path).header("Authorization", reader).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tLow\":22,\"tHigh\":26}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/v1/comfort/spaces/other/parameters").header("Authorization", editor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tLow\":22,\"tHigh\":26}"))
                .andExpect(status().isNotFound());
        for (String bounds : List.of("{\"tLow\":22,\"tHigh\":26}", "{\"tLow\":24.25,\"tHigh\":27.5}",
                "{\"tLow\":24.25,\"tHigh\":27.5}")) {
            mvc.perform(put(path).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON).content(bounds))
                    .andExpect(status().isOk());
        }
        for (String invalid : List.of("{}", "{\"tLow\":null,\"tHigh\":26}", "{\"tLow\":27,\"tHigh\":27}",
                "{\"tLow\":27,\"tHigh\":26}", "{\"tLow\":22.251,\"tHigh\":26}")) {
            mvc.perform(put(path).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.editable").value(false))
                .andExpect(jsonPath("$.data.comfortBand.tLow").value(24.25))
                .andExpect(jsonPath("$.data.comfortBand.tHigh").value(27.5));
        mvc.perform(get("/v1/comfort/spaces/r313/parameters").header("Authorization", editor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comfortBand").isEmpty());
        mvc.perform(put(path).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tLow\":null,\"tHigh\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comfortBand").isEmpty());
        mvc.perform(get(path).header("Authorization", editor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comfortBand").isEmpty());
    }

    @Test
    @Sql("classpath:comfort-parameters-schema-test.sql")
    void sameSaveKeyReturnsOriginalReceiptWithoutOverwritingNewerRoomSettings() throws Exception {
        var roles = Set.of("ENERGY_MANAGER");
        var firstRoom = new ComfortQueryService.Space("retry-r314", "B314", "B", "三层");
        var secondRoom = new ComfortQueryService.Space("retry-r313", "B313", "B", "三层");
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(comfort.room(USER, roles, firstRoom.spaceId())).thenReturn(firstRoom);
        when(comfort.room(USER, roles, secondRoom.spaceId())).thenReturn(secondRoom);
        String bearer = "Bearer " + jwt.generateToken(USER, "comfort-editor", List.copyOf(roles), 1L);
        String first = "/v1/comfort/spaces/" + firstRoom.spaceId() + "/parameters";
        String second = "/v1/comfort/spaces/" + secondRoom.spaceId() + "/parameters";
        String payload = "{\"manualInputs\":{\"relativeHumidity\":55}}";
        mvc.perform(put(first).header("Authorization", bearer).header("Idempotency-Key", "first-rh")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manualInputs.relativeHumidity").value(55));
        mvc.perform(put(first).header("Authorization", bearer).header("Idempotency-Key", "new-rh")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"manualInputs\":{\"relativeHumidity\":63}}"))
                .andExpect(status().isOk());
        mvc.perform(put(first).header("Authorization", bearer).header("Idempotency-Key", "first-rh")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manualInputs.relativeHumidity").value(55));
        mvc.perform(put(second).header("Authorization", bearer).header("Idempotency-Key", "first-rh")
                        .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        mvc.perform(get(first).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manualInputs.relativeHumidity").value(63));
        mvc.perform(get(second).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manualInputs.relativeHumidity").doesNotExist());
    }

    @Test
    @Sql("classpath:comfort-parameters-schema-test.sql")
    void pmvTrendReturnsLastSamplePerChinaHourAndPreservesOriginalInputs() throws Exception {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        String bearer = "Bearer " + jwt.generateToken(USER, "comfort-reader", List.copyOf(ROLES), 1L);
        var room = new ComfortQueryService.Space("pmv-r314", "B314", "B", "三层");
        var other = new ComfortQueryService.Space("pmv-r313", "B313", "B", "三层");
        LocalDate day = LocalDate.of(2026, 11, 19);
        for (var space : List.of(room, other)) {
            when(comfort.trends(USER, ROLES, space.spaceId(), null)).thenReturn(
                    new ComfortQueryService.Trends(space, null, "ROOM_AVERAGE", null, day, "Asia/Shanghai", List.of()));
        }
        Instant first = Instant.parse("2026-11-19T01:20:00Z");
        Instant next = first.plusSeconds(3600);
        when(comfort.now()).thenReturn(next.plusSeconds(1).toEpochMilli());
        history.record(room, new ComfortPmvService.Sample(new BigDecimal("0.21"), 24, first,
                Map.of("relativeHumidity", new BigDecimal("50"))), first.toEpochMilli());
        history.record(room, new ComfortPmvService.Sample(new BigDecimal("0.99"), 24, first,
                Map.of("relativeHumidity", new BigDecimal("80"))), next.toEpochMilli());
        history.record(room, new ComfortPmvService.Sample(new BigDecimal("0.64"), 28, next,
                Map.of("relativeHumidity", new BigDecimal("60"))), next.toEpochMilli());
        mvc.perform(get("/v1/comfort/spaces/pmv-r314/trends").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pmvHours.length()").value(24))
                .andExpect(jsonPath("$.data.pmvHours[8].observation").isEmpty())
                .andExpect(jsonPath("$.data.pmvHours[9].observation.value").value(0.21))
                .andExpect(jsonPath("$.data.pmvHours[9].observation.inputs.relativeHumidity").value(50))
                .andExpect(jsonPath("$.data.pmvHours[10].observation.value").value(0.64))
                .andExpect(jsonPath("$.data.pmvHours[11].observation").isEmpty());
        mvc.perform(get("/v1/comfort/spaces/pmv-r313/trends").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pmvHours[9].observation").isEmpty());
    }

    @Test
    @Sql("classpath:comfort-parameters-schema-test.sql")
    void manualParametersSupportPartialUpdatesWithoutClearingComfortBandOrOtherRooms() throws Exception {
        Set<String> editorRoles = Set.of("ENERGY_MANAGER");
        var room = new ComfortQueryService.Space("manual-r314", "B314", "B", "三层");
        var other = new ComfortQueryService.Space("manual-r313", "B313", "B", "三层");
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        when(comfort.room(USER, editorRoles, room.spaceId())).thenReturn(room);
        when(comfort.room(USER, editorRoles, other.spaceId())).thenReturn(other);
        when(comfort.room(USER, ROLES, room.spaceId())).thenReturn(room);
        String editor = "Bearer " + jwt.generateToken(USER, "comfort-editor", List.copyOf(editorRoles), 1L);
        String reader = "Bearer " + jwt.generateToken(USER, "comfort-reader", List.copyOf(ROLES), 1L);
        String path = "/v1/comfort/spaces/" + room.spaceId() + "/parameters";

        mvc.perform(get(path).header("Authorization", editor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manualInputs").isEmpty());
        mvc.perform(put(path).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tLow\":23,\"tHigh\":27,\"manualInputs\":{\"metabolicRate\":1.3,\"airVelocity\":0}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manualInputs.airVelocity").value(0.0))
                .andExpect(jsonPath("$.data.manualInputs.relativeHumidity").doesNotExist());
        mvc.perform(put(path).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualInputs\":{\"dynamicEmissionFactor\":0.527,\"relativeHumidity\":54}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comfortBand.tLow").value(23.0))
                .andExpect(jsonPath("$.data.manualInputs.metabolicRate").value(1.3))
                .andExpect(jsonPath("$.data.manualInputs.dynamicEmissionFactor").value(0.527));
        for (String invalid : List.of(
                "{\"tLow\":24,\"tHigh\":28,\"manualInputs\":{\"relativeHumidity\":81}}",
                "{\"manualInputs\":{\"metabolicRate\":1.35}}", "{\"manualInputs\":{\"airVelocity\":0.451}}",
                "{\"manualInputs\":{\"dynamicEmissionFactor\":2.001}}", "{\"manualInputs\":{\"unexpected\":null}}",
                "{\"tLow\":24,\"manualInputs\":{\"relativeHumidity\":55}}")) {
            mvc.perform(put(path).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(path).header("Authorization", reader).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualInputs\":{\"relativeHumidity\":60}}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.editable").value(false))
                .andExpect(jsonPath("$.data.comfortBand.tHigh").value(27.0))
                .andExpect(jsonPath("$.data.manualInputs.relativeHumidity").value(54.0));
        mvc.perform(get("/v1/comfort/spaces/" + other.spaceId() + "/parameters").header("Authorization", editor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manualInputs").isEmpty())
                .andExpect(jsonPath("$.data.comfortBand").isEmpty());
        mvc.perform(put(path).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tLow\":null,\"tHigh\":null,\"manualInputs\":{\"relativeHumidity\":null}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.comfortBand").isEmpty())
                .andExpect(jsonPath("$.data.manualInputs.relativeHumidity").doesNotExist())
                .andExpect(jsonPath("$.data.manualInputs.metabolicRate").value(1.3))
                .andExpect(jsonPath("$.data.manualInputs.airVelocity").value(0.0));
    }

    @Test
    void hvacStatusUsesTrustedRoomAndBuildingScope() throws Exception {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        String bearer = "Bearer " + jwt.generateToken(USER, "comfort-reader", List.copyOf(ROLES), 1L);
        when(comfort.room(USER, ROLES, "r314")).thenReturn(new ComfortQueryService.Space("r314", "B314", "B", "三层"));
        when(comfort.room(USER, ROLES, "other")).thenThrow(new BusinessException(404, "COMFORT_SPACE_NOT_FOUND", "监测房间不可用"));
        when(hvac.roomSummary(USER, ROLES, "B", "r314")).thenReturn(new RoomSummary(3, 2, 0, "AVAILABLE",
                1L, new RoomSetting<>(24.0, "VALUE"), new RoomSetting<>(null, "MIXED"), "high", false));
        mvc.perform(get(ROOM + "/hvac-status").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.running").value(2))
                .andExpect(jsonPath("$.data.airflowSpeed").value("high"))
                .andExpect(jsonPath("$.data.controlEntryAvailable").value(false));
        mvc.perform(get("/v1/comfort/spaces/other/hvac-status").header("Authorization", bearer)).andExpect(status().isNotFound());
        verify(hvac).roomSummary(USER, ROLES, "B", "r314");
        verifyNoMoreInteractions(hvac);
    }

    @Test
    void authenticatedRoutesKeepRoomAndSelectedEquipmentScope() throws Exception {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        String bearer = "Bearer " + jwt.generateToken(USER, "comfort-reader", List.copyOf(ROLES), 1L);
        var room = new ComfortQueryService.Space("r314", "B314", "B", "三层");
        var source = new ComfortQueryService.TemperatureSource("indoor-1", "内机1", 0L, 1);
        var time = Instant.parse("2026-11-19T01:00:00Z");
        var reading = new ComfortQueryService.TemperatureObservation(24.3, time, false);
        var other = new ComfortQueryService.Space("r313", "B313", "B", "三层");
        var roomIds = Set.of("r314", "r313");
        when(comfort.spaces(USER, ROLES, "B")).thenReturn(List.of(other, room));
        when(sensors.buildingDoorSummaries("B", roomIds)).thenReturn(Map.of("r314",
                new RoomSensorRoomQueryService.ContactSummary(0, 1, 0)));
        when(alarms.activeRoomLevels(USER, ROLES, "B", roomIds)).thenReturn(Map.of("r314", "IMPORTANT"));
        when(comfort.overview(USER, ROLES, "r314", "indoor-1")).thenReturn(
                new ComfortQueryService.Overview(room, List.of(source), source, "SELECTED", "point-1", "PRESENT", reading, null));
        when(comfort.overview(USER, ROLES, "r314", null)).thenReturn(
                new ComfortQueryService.Overview(room, List.of(source), null, "ROOM_AVERAGE", null, null, null,
                        new ComfortQueryService.TemperatureObservation(24.3, time, true)));
        when(comfort.trends(USER, ROLES, "r314", "indoor-1")).thenReturn(new ComfortQueryService.Trends(
                room, source, "SELECTED", "point-1", LocalDate.of(2026, 11, 19), "Asia/Shanghai",
                List.of(new ComfortQueryService.Hour(time, null))));
        when(comfort.overview(USER, ROLES, "r314", "another-room-unit"))
                .thenThrow(new BusinessException(404, "COMFORT_TEMPERATURE_SOURCE_NOT_FOUND", "无对应室温来源"));

        mvc.perform(get(SPACES).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].spaceName").value("B313"))
                .andExpect(jsonPath("$.data[0].doorOpen").isEmpty())
                .andExpect(jsonPath("$.data[1].spaceName").value("B314"))
                .andExpect(jsonPath("$.data[1].doorOpen").value(false))
                .andExpect(jsonPath("$.data[1].alarmSeverity").value("IMPORTANT"));
        mvc.perform(get(ROOM + "/overview").header("Authorization", bearer).param("equipmentId", "indoor-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.selectedSource.equipmentId").value("indoor-1"))
                .andExpect(jsonPath("$.data.indoorTemperature.celsius").value(24.3))
                .andExpect(jsonPath("$.data.pmv").isEmpty());
        mvc.perform(get(ROOM + "/overview").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.indoorTemperature").isEmpty())
                .andExpect(jsonPath("$.data.lastValidIndoorTemperature.celsius").value(24.3))
                .andExpect(jsonPath("$.data.lastValidIndoorTemperature.observedAt").value(time.toString()))
                .andExpect(jsonPath("$.data.lastValidIndoorTemperature.stale").value(true))
                .andExpect(jsonPath("$.data.pmv.status").value("TEMPERATURE_STALE"))
                .andExpect(jsonPath("$.data.pmv.value").isEmpty());
        mvc.perform(get(ROOM + "/trends").header("Authorization", bearer).param("equipmentId", "indoor-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.hours[0].indoorTemperature").isEmpty());
        mvc.perform(get(ROOM + "/overview").header("Authorization", bearer).param("equipmentId", "another-room-unit"))
                .andExpect(status().isNotFound());
    }

    @Test
    void roomSensorQueriesUseAuthorizedRoomAndKeepUnknownSeparateFromZero() throws Exception {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
        String bearer = "Bearer " + jwt.generateToken(USER, "comfort-reader", List.copyOf(ROLES), 1L);
        var room = new ComfortQueryService.Space("r314", "B314", "B", "三层");
        var time = Instant.parse("2026-11-19T01:00:00Z");
        var date = LocalDate.of(2026, 11, 19);
        var communication = new RoomSensorRoomQueryService.Communication("ONLINE", "RECENT_CONTACT", time);
        when(comfort.room(USER, ROLES, "r314")).thenReturn(room);
        when(comfort.room(USER, ROLES, "outside-scope"))
                .thenThrow(new BusinessException(404, "COMFORT_SPACE_NOT_FOUND", "监测房间不可用"));
        when(sensors.occupancy("B", "r314")).thenReturn(new RoomSensorRoomQueryService.Occupancy(
                date, "Asia/Shanghai", time, RoomSensorRoomQueryService.Status.AVAILABLE, communication,
                0, 0, time, List.of(new RoomSensorRoomQueryService.CountSlot(time, 0, time)), List.of(), List.of()));
        when(sensors.doorStats("B", "r314", null)).thenReturn(new RoomSensorRoomQueryService.DoorStats(
                date, "Asia/Shanghai", time, RoomSensorRoomQueryService.Status.STALE, communication, null, false,
                time.minusSeconds(300), null, null, List.of(), List.of(), List.of(), "door-1", List.of(),
                new RoomSensorRoomQueryService.ContactSummary(0, 0, 1),
                new RoomSensorRoomQueryService.ContactSummary(0, 0, 0)));
        when(sensors.doorStats("B", "r314", "other-door"))
                .thenThrow(new BusinessException(404, "COMFORT_DOOR_NOT_FOUND", "当前房间没有此门磁"));

        mvc.perform(get(ROOM + "/occupancy").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentCount").value(0))
                .andExpect(jsonPath("$.data.series1m[0].count").value(0));
        mvc.perform(get(ROOM + "/door-stats").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("STALE"))
                .andExpect(jsonPath("$.data.doorOpen").isEmpty())
                .andExpect(jsonPath("$.data.doors.unknown").value(1))
                .andExpect(jsonPath("$.data.communication.state").value("ONLINE"));
        mvc.perform(get(ROOM + "/door-stats").header("Authorization", bearer).param("equipmentId", "other-door"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/comfort/spaces/outside-scope/door-stats").header("Authorization", bearer))
                .andExpect(status().isNotFound());
        verify(sensors).occupancy("B", "r314");
        verify(sensors).doorStats("B", "r314", null);
        verify(sensors).doorStats("B", "r314", "other-door");
        verifyNoMoreInteractions(sensors);
    }
}
