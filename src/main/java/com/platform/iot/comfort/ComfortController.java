package com.platform.iot.comfort;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.platform.alarm.AlarmService;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.query.DaikinBuildingMonitoringDtos.RoomSummary;
import com.platform.iot.daikin.monitoring.query.DaikinBuildingMonitoringService;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 提供按建筑授权访问的房间舒适度监测与参数配置接口。 */
@RestController
@RequestMapping("/v1/comfort")
public class ComfortController {
    private final ComfortQueryService query;
    private final RoomSensorRoomQueryService sensors;
    private final DaikinBuildingMonitoringService hvac;
    private final ComfortParametersService parameters;
    private final ComfortPmvService pmv;
    private final ComfortPmvHistory pmvHistory;
    private final AlarmService alarms;
    private final ComfortElectricityService electricity;

    public ComfortController(ComfortQueryService query, RoomSensorRoomQueryService sensors,
                             DaikinBuildingMonitoringService hvac, ComfortParametersService parameters,
                             ComfortPmvService pmv, ComfortPmvHistory pmvHistory, AlarmService alarms,
                             ComfortElectricityService electricity) {
        this.query = query;
        this.sensors = sensors;
        this.hvac = hvac;
        this.parameters = parameters;
        this.pmv = pmv;
        this.pmvHistory = pmvHistory;
        this.alarms = alarms;
        this.electricity = electricity;
    }

    @GetMapping("/buildings/{buildingId}/spaces")
    public Result<List<SpaceOption>> spaces(Authentication authentication, @PathVariable String buildingId) {
        var user = SecurityUser.userId(authentication);
        var roles = SecurityUser.roles(authentication);
        var spaces = query.spaces(user, roles, buildingId);
        var ids = spaces.stream().map(ComfortQueryService.Space::spaceId).collect(Collectors.toSet());
        var doors = sensors.buildingDoorSummaries(buildingId, ids);
        Map<String, String> levels;
        try {
            levels = alarms.activeRoomLevels(user, roles, buildingId, ids);
        } catch (BusinessException failure) {
            // 能看舒适度但无报警授权的账号仍然能切换房间。
            if (failure.getCode() != 403) throw failure;
            levels = Map.of();
        }
        var authorizedLevels = levels;
        return Result.success(spaces.stream().map(space -> {
            var summary = doors.get(space.spaceId());
            return new SpaceOption(space, summary == null ? null : summary.singleOpen(), summary, authorizedLevels.get(space.spaceId()));
        }).toList());
    }

    public record SpaceOption(@JsonUnwrapped ComfortQueryService.Space space, Boolean doorOpen,
                              RoomSensorRoomQueryService.ContactSummary doorSummary, String alarmSeverity) {}

    @GetMapping("/spaces/{spaceId}/overview")
    public Result<RealtimeOverview> overview(Authentication authentication, @PathVariable String spaceId,
                                              @RequestParam(required = false) String equipmentId) {
        var room = query.overview(SecurityUser.userId(authentication), SecurityUser.roles(authentication), spaceId, equipmentId);
        return Result.success(new RealtimeOverview(room, equipmentId == null ? pmv.evaluate(room) : null));
    }

    public record RealtimeOverview(@JsonUnwrapped ComfortQueryService.Overview observation,
                                   ComfortPmvService.Measurement pmv) {}

    @GetMapping("/spaces/{spaceId}/trends")
    public Result<DailyTrends> trends(Authentication authentication, @PathVariable String spaceId,
                                                   @RequestParam(required = false) String equipmentId) {
        var trends = query.trends(SecurityUser.userId(authentication), SecurityUser.roles(authentication), spaceId, equipmentId);
        var history = equipmentId == null ? pmvHistory.hours(trends.space(), trends.date(), query.now()) : List.<ComfortPmvHistory.Hour>of();
        return Result.success(new DailyTrends(trends, history));
    }

    public record DailyTrends(@JsonUnwrapped ComfortQueryService.Trends temperatures,
                              List<ComfortPmvHistory.Hour> pmvHours) {}

    @GetMapping("/spaces/{spaceId}/electricity")
    public Result<ComfortElectricityService.Electricity> electricity(Authentication authentication, @PathVariable String spaceId) {
        var room = query.room(SecurityUser.userId(authentication), SecurityUser.roles(authentication), spaceId);
        return Result.success(electricity.read(room, query.now()));
    }

    @GetMapping("/spaces/{spaceId}/parameters")
    public Result<ComfortParametersService.Parameters> parameters(Authentication authentication, @PathVariable String spaceId) {
        return Result.success(parameters.parameters(SecurityUser.userId(authentication), SecurityUser.roles(authentication), spaceId));
    }

    @PutMapping("/spaces/{spaceId}/parameters")
    public Result<ComfortParametersService.Parameters> saveParameters(Authentication authentication, @PathVariable String spaceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ComfortParametersService.ParameterUpdate update) {
        return Result.success(parameters.save(SecurityUser.userId(authentication), SecurityUser.roles(authentication), spaceId, key, update));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalidParametersBody() {
        return Result.error(400, "房间参数格式无效");
    }

    @GetMapping("/spaces/{spaceId}/occupancy")
    public Result<RoomSensorRoomQueryService.Occupancy> occupancy(Authentication authentication, @PathVariable String spaceId) {
        var room = query.room(SecurityUser.userId(authentication), SecurityUser.roles(authentication), spaceId);
        return Result.success(sensors.occupancy(room.buildingId(), room.spaceId()));
    }

    @GetMapping("/spaces/{spaceId}/door-stats")
    public Result<RoomSensorRoomQueryService.DoorStats> doorStats(Authentication authentication, @PathVariable String spaceId,
                                                                 @RequestParam(required = false) String equipmentId) {
        var room = query.room(SecurityUser.userId(authentication), SecurityUser.roles(authentication), spaceId);
        return Result.success(sensors.doorStats(room.buildingId(), room.spaceId(), equipmentId));
    }

    @GetMapping("/spaces/{spaceId}/hvac-status")
    public Result<RoomSummary> hvacStatus(Authentication authentication, @PathVariable String spaceId) {
        var user = SecurityUser.userId(authentication);
        var roles = SecurityUser.roles(authentication);
        var room = query.room(user, roles, spaceId);
        return Result.success(hvac.roomSummary(user, roles, room.buildingId(), room.spaceId()));
    }
}
