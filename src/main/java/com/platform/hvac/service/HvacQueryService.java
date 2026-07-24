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
 * HVAC 冻结分钟数据的只读查询编排服务。
 *
 * <p>MySQL 中的建筑、设备和测点配置是权限与业务元数据的真相来源，
 * TDengine 只提供冻结分钟数值。该服务先完成建筑数据权限和请求边界校验，
 * 再批量查询两个数据源并组装稳定 DTO，避免跨建筑读取和逐测点 N+1 查询。</p>
 */
@Service
@RequiredArgsConstructor
public class HvacQueryService {

    /** 单次历史查询允许的最大连续时间跨度。 */
    private static final long MAX_HISTORY_SPAN = Duration.ofDays(31).toMillis();

    /** 单次历史查询最多展示的测点数，防止返回曲线和 TDengine 查询无限膨胀。 */
    private static final int MAX_HISTORY_POINTS = 8;

    private final BuildingService buildingService;
    private final BuildingScopeService buildingScopeService;
    private final BizDataPointService dataPointService;
    private final BizEquipmentService equipmentService;
    private final HvacMinuteRepository minuteRepository;

    /**
     * 生成建筑全部在线测点的最新快照。
     *
     * <p>先从 MySQL 取得完整测点配置，再批量匹配 TDengine 最新行。
     * 因此没有时序数据的合法测点也不会丢失，而是以 {@code NO_DATA} 返回。</p>
     *
     * @param buildingId 目标建筑 ID
     * @param userId 当前用户 ID
     * @param roles 当前用户角色集合
     * @return 按测点编码稳定排序的快照
     */
    public HvacQueryDtos.SnapshotResponse snapshot(
            String buildingId,
            Long userId,
            Set<String> roles) {
        checkBuildingAccess(buildingId, userId, roles);

        // 先以 MySQL 配置确定“应该出现哪些测点”，TDengine 只负责补充最新数值。
        List<BizDataPoint> points = new ArrayList<>(dataPointService.list(
                new LambdaQueryWrapper<BizDataPoint>()
                        .eq(BizDataPoint::getBuildingId, buildingId)
                        .eq(BizDataPoint::getStatus, "ONLINE")));
        // 固定输出顺序，避免数据库无序返回导致前端图表每次重新排列。
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
        // 一次批量读取全部测点。合并函数是防御性处理：若底层意外返回重复测点，
        // 仍只保留时间更新的一条。
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

    /**
     * 查询用户选定测点的历史趋势。
     *
     * <p>测点列表会去空白、去重并保留首次出现顺序；查询区间固定为
     * {@code [from, to)}。合法但没有时序数据的测点仍返回空序列，便于前端保持
     * 用户选择的曲线槽位。</p>
     *
     * @param buildingId 目标建筑 ID
     * @param rawPointIds 逗号分隔的测点内部 ID
     * @param from 包含的起始时间（Unix 毫秒）
     * @param to 不包含的结束时间（Unix 毫秒）
     * @param userId 当前用户 ID
     * @param roles 当前用户角色集合
     * @return 含自动分辨率和多测点序列的历史响应
     */
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

        // 不依赖 MySQL 或 TDengine 的返回顺序，严格按清理后的用户输入顺序组装曲线。
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
        // 先区分建筑不存在，再校验当前用户的数据范围；Controller 的角色白名单
        // 不能替代这里的具体建筑授权。
        if (buildingService.getById(buildingId) == null) {
            throw new BusinessException(404, "建筑不存在");
        }
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    private long validateTimeRange(Long from, Long to) {
        // 所有历史查询统一使用半开区间 [from, to)，相邻时间段不会重复边界数据。
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
            // 极端时间戳可能在相减时溢出，统一作为非法请求而不是系统错误。
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
        // LinkedHashSet 同时完成去重和首次出现顺序保留，最终顺序也用于响应 series。
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
        // 全部测点必须真实存在、属于路径中的建筑且处于启用状态，防止通过 pointId
        // 绕过建筑范围读取其他建筑的数据。
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
        // 使用统一提示，不向调用方泄露某个测点是否存在于其他建筑。
        return new BusinessException(
                400, "测点不存在、已停用或不属于目标建筑");
    }

    private int resolution(long span) {
        // 查询越长返回粒度越粗，控制单条曲线的数据点数量和前端绘制成本。
        if (span <= Duration.ofHours(24).toMillis()) {
            return 1;
        }
        if (span <= Duration.ofDays(7).toMillis()) {
            return 5;
        }
        return 30;
    }

    private Map<String, String> equipmentCodes(List<BizDataPoint> points) {
        // 设备编码只用于快照展示；先去重后批量读取，避免逐测点查询 MySQL。
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
            // 保留测点和设备元数据，让调用方区分“没有配置”和“已配置但暂无数据”。
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
        // Repository 不承诺返回顺序，DTO 层统一按窗口起始时间升序输出。
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
            // 只屏蔽 TDengine/JDBC 读取异常，不吞掉 MySQL、权限或代码错误；
            // 对外使用稳定 503 文案，避免泄露 SQL、连接地址等内部信息。
            throw new BusinessException(
                    503, "HVAC 时序数据暂不可用，请稍后重试");
        }
    }
}
