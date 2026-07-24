package com.platform.hvac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.dto.HvacQueryDtos;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * HVAC 冻结分钟数据的只读查询服务。
 */
@Service
@RequiredArgsConstructor
public class HvacQueryService {

    private static final long MAX_HISTORY_SPAN = Duration.ofDays(31).toMillis();
    private static final int MAX_HISTORY_POINTS = 8;

    private final BuildingService buildingService;
    private final BuildingScopeService buildingScopeService;
    private final BizDataPointService dataPointService;
    private final BizEquipmentService equipmentService;
    private final HvacMinuteRepository minuteRepository;

    public HvacQueryDtos.SnapshotResponse snapshot(
            String buildingId,
            Long userId,
            Set<String> roles) {
        checkBuildingAccess(buildingId, userId, roles);

        List<BizDataPoint> points = new ArrayList<>(dataPointService.list(
                new LambdaQueryWrapper<BizDataPoint>()
                        .eq(BizDataPoint::getBuildingId, buildingId)
                        .eq(BizDataPoint::getStatus, "ONLINE")));
        points.sort(Comparator
                .comparing(BizDataPoint::getPointCode,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(BizDataPoint::getPointId));

        if (points.isEmpty()) {
            return new HvacQueryDtos.SnapshotResponse(
                    buildingId, System.currentTimeMillis(), List.of());
        }

        List<String> pointIds =
                points.stream().map(BizDataPoint::getPointId).toList();
        Map<String, HvacMinuteQueryRow> latestByPoint = queryTdengine(
                () -> minuteRepository.findLatestByPointIds(pointIds)).stream()
                .collect(Collectors.toMap(
                        HvacMinuteQueryRow::pointId,
                        row -> row,
                        (left, right) -> left.time() >= right.time() ? left : right));
        Map<String, String> equipmentCodes = equipmentCodes(points);

        List<HvacQueryDtos.SnapshotPoint> responsePoints = points.stream()
                .map(point -> snapshotPoint(
                        point, equipmentCodes.get(point.getEquipId()),
                        latestByPoint.get(point.getPointId())))
                .toList();
        return new HvacQueryDtos.SnapshotResponse(
                buildingId, System.currentTimeMillis(), responsePoints);
    }

    public HvacQueryDtos.HistoryResponse history(
            String buildingId,
            String rawPointIds,
            Long from,
            Long to,
            Long userId,
        Set<String> roles) {
        checkBuildingAccess(buildingId, userId, roles);
        long span = validateTimeRange(from, to);
        List<String> pointIds = parsePointIds(rawPointIds);
        Map<String, BizDataPoint> configuredPoints =
                validateConfiguredPoints(buildingId, pointIds);
        int resolutionMinutes = resolution(span);

        List<HvacMinuteQueryRow> rows = queryTdengine(
                () -> minuteRepository.findHistory(
                        pointIds, from, to, resolutionMinutes));
        Map<String, List<HvacMinuteQueryRow>> rowsByPoint = rows.stream()
                .collect(Collectors.groupingBy(HvacMinuteQueryRow::pointId));

        List<HvacQueryDtos.HistorySeries> series = pointIds.stream()
                .map(pointId -> historySeries(
                        configuredPoints.get(pointId),
                        rowsByPoint.getOrDefault(pointId, List.of())))
                .toList();
        return new HvacQueryDtos.HistoryResponse(
                buildingId, from, to, resolutionMinutes, series);
    }

    private void checkBuildingAccess(
            String buildingId, Long userId, Set<String> roles) {
        if (buildingService.getById(buildingId) == null) {
            throw new BusinessException(404, "建筑不存在");
        }
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    private long validateTimeRange(Long from, Long to) {
        if (from == null || to == null) {
            throw new BusinessException(400, "from 和 to 为必填毫秒时间戳");
        }
        if (from >= to) {
            throw new BusinessException(400, "from 必须小于 to");
        }
        long span;
        try {
            span = Math.subtractExact(to, from);
        } catch (ArithmeticException exception) {
            throw new BusinessException(400, "历史查询时间范围无效");
        }
        if (span > MAX_HISTORY_SPAN) {
            throw new BusinessException(400, "历史查询跨度不能超过 31 天");
        }
        return span;
    }

    private List<String> parsePointIds(String rawPointIds) {
        if (rawPointIds == null) {
            throw new BusinessException(400, "pointIds 不能为空");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String candidate : rawPointIds.split(",", -1)) {
            String pointId = candidate.trim();
            if (!pointId.isEmpty()) {
                unique.add(pointId);
            }
        }
        if (unique.isEmpty()) {
            throw new BusinessException(400, "pointIds 不能为空");
        }
        if (unique.size() > MAX_HISTORY_POINTS) {
            throw new BusinessException(400, "一次最多查询 8 个测点");
        }
        return List.copyOf(unique);
    }

    private Map<String, BizDataPoint> validateConfiguredPoints(
            String buildingId, List<String> pointIds) {
        List<BizDataPoint> points = dataPointService.listByIds(pointIds);
        Map<String, BizDataPoint> byId = points.stream().collect(Collectors.toMap(
                BizDataPoint::getPointId,
                point -> point,
                (left, right) -> left,
                LinkedHashMap::new));
        if (byId.size() != pointIds.size()) {
            throw invalidPointSelection();
        }
        for (String pointId : pointIds) {
            BizDataPoint point = byId.get(pointId);
            if (point == null
                    || !buildingId.equals(point.getBuildingId())
                    || !"ONLINE".equalsIgnoreCase(point.getStatus())) {
                throw invalidPointSelection();
            }
        }
        return byId;
    }

    private BusinessException invalidPointSelection() {
        return new BusinessException(
                400, "测点不存在、已停用或不属于目标建筑");
    }

    private int resolution(long span) {
        if (span <= Duration.ofHours(24).toMillis()) {
            return 1;
        }
        if (span <= Duration.ofDays(7).toMillis()) {
            return 5;
        }
        return 30;
    }

    private Map<String, String> equipmentCodes(List<BizDataPoint> points) {
        Set<String> equipmentIds = points.stream()
                .map(BizDataPoint::getEquipId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (equipmentIds.isEmpty()) {
            return Map.of();
        }
        return equipmentService.listByIds(equipmentIds).stream()
                .collect(Collectors.toMap(
                        BizEquipment::getEquipId,
                        BizEquipment::getEquipCode,
                        (left, right) -> left));
    }

    private HvacQueryDtos.SnapshotPoint snapshotPoint(
            BizDataPoint point,
            String equipmentCode,
            HvacMinuteQueryRow row) {
        if (row == null) {
            return new HvacQueryDtos.SnapshotPoint(
                    point.getPointId(),
                    point.getPointCode(),
                    point.getPointName(),
                    point.getEquipId(),
                    equipmentCode,
                    point.getUnit(),
                    null,
                    null,
                    null,
                    null,
                    0L,
                    null,
                    "NO_DATA");
        }
        return new HvacQueryDtos.SnapshotPoint(
                point.getPointId(),
                point.getPointCode(),
                point.getPointName(),
                point.getEquipId(),
                equipmentCode,
                point.getUnit(),
                row.time(),
                row.average(),
                row.minimum(),
                row.maximum(),
                row.sampleCount(),
                row.dataQuality(),
                "NORMAL");
    }

    private HvacQueryDtos.HistorySeries historySeries(
            BizDataPoint point, List<HvacMinuteQueryRow> rows) {
        List<HvacQueryDtos.HistoryRecord> records = rows.stream()
                .sorted(Comparator.comparingLong(HvacMinuteQueryRow::time))
                .map(row -> new HvacQueryDtos.HistoryRecord(
                        row.time(),
                        row.average(),
                        row.minimum(),
                        row.maximum(),
                        row.sampleCount(),
                        row.dataQuality()))
                .toList();
        return new HvacQueryDtos.HistorySeries(
                point.getPointId(),
                point.getPointCode(),
                point.getPointName(),
                point.getUnit(),
                records);
    }

    private <T> T queryTdengine(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    503, "HVAC 时序数据暂不可用，请稍后重试");
        }
    }
}
