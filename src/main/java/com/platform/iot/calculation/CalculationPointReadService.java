package com.platform.iot.calculation;

import com.platform.energy.activity.EnergyActivityDataReader;
import com.platform.energy.activity.EnergyActivityDataReader.Cursor;
import com.platform.energy.activity.EnergyActivityDataReader.RawEvent;
import com.platform.energy.activity.EnergyActivityDataReader.RawEventPage;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import com.platform.iot.calculation.CalculationPointReadContracts.CalculationPointFact;
import com.platform.iot.calculation.CalculationPointReadContracts.CalculationPointSnapshot;
import com.platform.iot.calculation.CalculationPointReadContracts.PointDescriptor;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.system.service.BuildingScopeService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.platform.iot.calculation.CalculationPointReadContracts.SOURCE_NATURE_EVIDENCE_UNAVAILABLE;
import static com.platform.iot.calculation.CalculationPointReadContracts.SOURCE_NATURE_UNKNOWN;
import static com.platform.iot.calculation.CalculationPointReadErrors.DATA_SCOPE_MISMATCH;
import static com.platform.iot.calculation.CalculationPointReadErrors.DEPENDENCY_UNAVAILABLE;
import static com.platform.iot.calculation.CalculationPointReadErrors.DUPLICATE_FACT_IDENTITY;
import static com.platform.iot.calculation.CalculationPointReadErrors.POINT_METADATA_REQUIRED;
import static com.platform.iot.calculation.CalculationPointReadErrors.QUALITY_POLICY_UNAVAILABLE;
import static com.platform.iot.calculation.CalculationPointReadErrors.RAW_FACT_INVALID;
import static com.platform.iot.calculation.CalculationPointReadErrors.READER_PROTOCOL_INVALID;
import static com.platform.iot.calculation.CalculationPointReadErrors.RESOURCE_LIMIT_EXCEEDED;
import static com.platform.iot.calculation.CalculationPointReadErrors.VALIDATION_FAILED;
import static com.platform.iot.calculation.CalculationPointReadErrors.error;
import static com.platform.iot.qualityusage.QualityUsageModels.INDICATOR_CALCULATION;

/**
 * 为指标计算提供已固定建筑范围、接收水位和质量证据的原始测点快照。
 *
 * <p>本服务只调用资产和活动数据的公共读取端口；它不访问其他模块内部表，也不把温度、
 * 流量等测点伪装成能源专业属性。质量拒绝行保留在结果中，防止下游积分跨越缺口。</p>
 */
@Service
public class CalculationPointReadService {
    static final int MAX_POINTS = 32;
    static final int PAGE_SIZE = 500;
    static final int MAX_FACTS = 20_000;
    static final long MAX_RANGE_MILLIS = 31L * 24 * 60 * 60 * 1_000;
    private static final String IDENTITY_RULE = "POINT_ID_EVENT_TIME";
    private static final String DUPLICATE_HANDLING = "COALESCED_BY_IDENTITY";
    private static final String CORRECTION_EVIDENCE = "NOT_RETAINED";
    private static final Comparator<Cursor> CURSOR_ORDER = Comparator
            .comparingLong(Cursor::eventTime)
            .thenComparing(Cursor::pointId);

    private final BuildingScopeService buildingScopeService;
    private final BizDataPointService dataPointService;
    private final EnergyActivityDataReader dataReader;
    private final QualityUsagePolicyResolver qualityResolver;

    public CalculationPointReadService(
            BuildingScopeService buildingScopeService,
            BizDataPointService dataPointService,
            EnergyActivityDataReader dataReader,
            QualityUsagePolicyResolver qualityResolver) {
        this.buildingScopeService = buildingScopeService;
        this.dataPointService = dataPointService;
        this.dataReader = dataReader;
        this.qualityResolver = qualityResolver;
    }

    /**
     * 读取闭区间内、在 {@code asOf} 接收水位可见的全部原始事实。
     *
     * <p>底层 TDengine 端口是半开区间，因此将精确末端扩成一毫秒的上界，并逐项校验返回
     * 事实仍在调用方给出的闭区间内。这样累计量锚点和积分末端不会被静默遗漏。</p>
     *
     * <p>本端口只负责返回 {@code toInclusive} 的末端锚点；下游积分或周期仍须按其自身的
     * 半开区间规则决定该锚点是否属于本段数值，而不能把读取边界解释为积分归属规则。</p>
     */
    public CalculationPointSnapshot read(
            long userId,
            Collection<String> roles,
            String buildingId,
            Set<String> pointIds,
            Instant fromInclusive,
            Instant toInclusive,
            Instant asOf) {
        String building = required(buildingId, "建筑不能为空");
        Set<String> requestedPointIds = normalizePointIds(pointIds);
        TimeRange range = validateTimeRange(fromInclusive, toInclusive, asOf);

        buildingScopeService.checkAccess(userId, roles, building);
        Map<String, PointDescriptor> descriptors = loadPointDescriptors(building, requestedPointIds);
        ResolutionContext context = loadQualityContext(
                requestedPointIds, range.fromInclusiveMillis(), range.toExclusiveMillis());

        List<CalculationPointFact> facts = new ArrayList<>();
        Set<String> factIdentities = new HashSet<>();
        Cursor cursor = null;
        int scannedFactCount = 0;
        int filteredAfterWatermarkCount = 0;

        do {
            RawEventPage page = readPage(building, requestedPointIds, range, cursor);
            validatePage(page, cursor, requestedPointIds, range);
            scannedFactCount = Math.addExact(scannedFactCount, page.items().size());
            if (scannedFactCount > MAX_FACTS) {
                throw error(400, RESOURCE_LIMIT_EXCEEDED, "计算测点事实超过单次安全上限");
            }

            for (RawEvent event : page.items()) {
                validateRawScopeAndRange(event, building, requestedPointIds, range);
                if (event.receivedTime() > range.asOfMillis()) {
                    filteredAfterWatermarkCount++;
                    continue;
                }
                if (!Double.isFinite(event.rawValue())) {
                    throw error(409, RAW_FACT_INVALID, "原始测点值必须是有限数值: " + event.pointId());
                }
                String identity = factIdentity(event);
                if (!factIdentities.add(identity)) {
                    throw error(409, DUPLICATE_FACT_IDENTITY, "原始事实身份重复: " + identity);
                }
                Resolution resolution = resolveQuality(context, event);
                PointDescriptor descriptor = descriptors.get(event.pointId());
                facts.add(toFact(event, descriptor, resolution));
            }
            cursor = page.nextCursor();
        } while (cursor != null);

        return new CalculationPointSnapshot(
                building,
                fromInclusive,
                toInclusive,
                asOf,
                IDENTITY_RULE,
                DUPLICATE_HANDLING,
                CORRECTION_EVIDENCE,
                INDICATOR_CALCULATION,
                context.configRevision(),
                scannedFactCount,
                filteredAfterWatermarkCount,
                descriptors.values().stream().toList(),
                facts);
    }

    private RawEventPage readPage(
            String buildingId, Set<String> pointIds, TimeRange range, Cursor cursor) {
        try {
            return dataReader.readRawEvents(
                    buildingId,
                    pointIds,
                    range.fromInclusiveMillis(),
                    range.toExclusiveMillis(),
                    cursor,
                    PAGE_SIZE);
        } catch (RuntimeException exception) {
            throw error(503, DEPENDENCY_UNAVAILABLE, "计算测点原始数据源暂不可用");
        }
    }

    /**
     * seek 分页协议必须与事实排序一致；否则继续读取可能跳过事实或无限循环，不能返回完整快照。
     */
    private static void validatePage(
            RawEventPage page, Cursor after, Set<String> pointIds, TimeRange range) {
        if (page == null || page.items().size() > PAGE_SIZE
                || page.truncated() != (page.nextCursor() != null)) {
            throw error(502, READER_PROTOCOL_INVALID, "计算测点读取分页协议无效");
        }
        Cursor previous = after;
        for (RawEvent event : page.items()) {
            if (event == null || blank(event.pointId())) {
                throw error(502, READER_PROTOCOL_INVALID, "计算测点读取返回空事实身份");
            }
            Cursor current = new Cursor(event.eventTime(), event.pointId());
            if (previous != null && CURSOR_ORDER.compare(current, previous) <= 0) {
                if (CURSOR_ORDER.compare(current, previous) == 0) {
                    throw error(409, DUPLICATE_FACT_IDENTITY,
                            "原始事实身份重复: " + factIdentity(event));
                }
                throw error(502, READER_PROTOCOL_INVALID, "计算测点读取事实顺序无效");
            }
            previous = current;
        }
        Cursor next = page.nextCursor();
        if (next != null) {
            if (page.items().isEmpty() || !pointIds.contains(next.pointId())
                    || next.eventTime() < range.fromInclusiveMillis()
                    || next.eventTime() > range.toInclusiveMillis()
                    || !next.equals(previous)) {
                throw error(502, READER_PROTOCOL_INVALID, "计算测点读取游标无效");
            }
        }
    }

    private static void validateRawScopeAndRange(
            RawEvent event, String buildingId, Set<String> pointIds, TimeRange range) {
        if (!Objects.equals(buildingId, event.buildingId()) || !pointIds.contains(event.pointId())) {
            throw error(500, DATA_SCOPE_MISMATCH, "计算测点原始数据范围校验失败");
        }
        if (event.eventTime() < range.fromInclusiveMillis()
                || event.eventTime() > range.toInclusiveMillis()) {
            throw error(502, READER_PROTOCOL_INVALID, "计算测点原始数据超出请求时间边界");
        }
    }

    private ResolutionContext loadQualityContext(
            Set<String> pointIds, long fromInclusive, long toExclusive) {
        try {
            return qualityResolver.historyContext(
                    pointIds, INDICATOR_CALCULATION, fromInclusive, toExclusive);
        } catch (RuntimeException exception) {
            throw error(503, QUALITY_POLICY_UNAVAILABLE, "指标计算质量策略暂不可用");
        }
    }

    private Resolution resolveQuality(ResolutionContext context, RawEvent event) {
        try {
            return qualityResolver.resolve(
                    context,
                    event.pointId(),
                    INDICATOR_CALCULATION,
                    QualityUsagePolicyResolver.alignMinute(event.eventTime()),
                    event.dataQuality());
        } catch (IllegalArgumentException exception) {
            throw error(409, RAW_FACT_INVALID, "原始测点质量等级无效: " + event.pointId());
        } catch (BusinessException exception) {
            throw error(503, QUALITY_POLICY_UNAVAILABLE, "指标计算质量策略暂不可用");
        }
    }

    private static CalculationPointFact toFact(
            RawEvent event, PointDescriptor descriptor, Resolution resolution) {
        return new CalculationPointFact(
                event.pointId(),
                factIdentity(event),
                descriptor.pointCode(),
                descriptor.dataType(),
                descriptor.unit(),
                event.sourceSystem(),
                event.sourcePointCode(),
                event.sourceDeviceId(),
                SOURCE_NATURE_UNKNOWN,
                SOURCE_NATURE_EVIDENCE_UNAVAILABLE,
                event.rawValue(),
                Instant.ofEpochMilli(event.eventTime()),
                Instant.ofEpochMilli(event.receivedTime()),
                QualityLevel.fromCode(event.dataQuality()).name(),
                resolution.decision().name(),
                resolution.policySource().name(),
                resolution.policyVersion(),
                resolution.configRevision(),
                resolution.reason());
    }

    private Map<String, PointDescriptor> loadPointDescriptors(
            String buildingId, Set<String> requestedPointIds) {
        Result<List<BizDataPoint>> response;
        try {
            response = dataPointService.listByBuilding(buildingId);
        } catch (RuntimeException exception) {
            throw error(503, DEPENDENCY_UNAVAILABLE, "计算测点档案暂不可用");
        }
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw error(503, DEPENDENCY_UNAVAILABLE, "计算测点档案暂不可用");
        }

        Map<String, PointDescriptor> descriptors = new LinkedHashMap<>();
        for (BizDataPoint point : response.getData()) {
            if (point == null || !Objects.equals(buildingId, point.getBuildingId())) {
                throw error(500, DATA_SCOPE_MISMATCH, "计算测点档案建筑归属校验失败");
            }
            String pointId = point.getPointId();
            if (!requestedPointIds.contains(pointId)) {
                continue;
            }
            PointDescriptor descriptor = toDescriptor(point);
            if (descriptors.putIfAbsent(pointId, descriptor) != null) {
                throw error(409, POINT_METADATA_REQUIRED, "计算测点档案身份重复: " + pointId);
            }
        }
        if (!descriptors.keySet().containsAll(requestedPointIds)) {
            throw error(409, POINT_METADATA_REQUIRED, "计算测点缺少建筑归属、类型或单位档案");
        }
        return descriptors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
    }

    private static PointDescriptor toDescriptor(BizDataPoint point) {
        String pointId = requiredMetadata(point.getPointId());
        String dataType = requiredMetadata(point.getDataType());
        String unit = requiredMetadata(point.getUnit());
        return new PointDescriptor(pointId, point.getPointCode(), dataType, unit);
    }

    private static String factIdentity(RawEvent event) {
        return event.pointId() + ":" + event.eventTime();
    }

    private static Set<String> normalizePointIds(Set<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            throw error(400, VALIDATION_FAILED, "至少选择一个计算测点");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String pointId : pointIds) {
            String value = required(pointId, "测点不能为空");
            if (!normalized.add(value)) {
                throw error(400, VALIDATION_FAILED, "请求测点身份重复: " + value);
            }
        }
        if (normalized.size() > MAX_POINTS) {
            throw error(400, RESOURCE_LIMIT_EXCEEDED, "单次最多读取 32 个计算测点");
        }
        return normalized.stream().sorted().collect(
                LinkedHashSet::new, Set::add, Set::addAll);
    }

    private static TimeRange validateTimeRange(
            Instant fromInclusive, Instant toInclusive, Instant asOf) {
        long from = epochMillis(fromInclusive, "起始时间不能为空且必须精确到毫秒");
        long to = epochMillis(toInclusive, "结束时间不能为空且必须精确到毫秒");
        long watermark = epochMillis(asOf, "读取水位不能为空且必须精确到毫秒");
        if (to < from) {
            throw error(400, VALIDATION_FAILED, "计算测点时间范围必须为有效闭区间");
        }
        long range;
        try {
            range = Math.subtractExact(to, from);
        } catch (ArithmeticException exception) {
            throw error(400, VALIDATION_FAILED, "计算测点时间范围无效");
        }
        if (range > MAX_RANGE_MILLIS) {
            throw error(400, RESOURCE_LIMIT_EXCEEDED, "单次计算测点时间范围不能超过 31 天");
        }
        try {
            return new TimeRange(from, to, Math.addExact(to, 1), watermark);
        } catch (ArithmeticException exception) {
            throw error(400, VALIDATION_FAILED, "计算测点结束时间超出可读取范围");
        }
    }

    private static long epochMillis(Instant value, String message) {
        if (value == null || value.getNano() % 1_000_000 != 0) {
            throw error(400, VALIDATION_FAILED, message);
        }
        try {
            return value.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw error(400, VALIDATION_FAILED, message);
        }
    }

    private static String requiredMetadata(String value) {
        if (blank(value)) {
            throw error(409, POINT_METADATA_REQUIRED, "计算测点缺少建筑归属、类型或单位档案");
        }
        return value.trim();
    }

    private static String required(String value, String message) {
        if (blank(value)) {
            throw error(400, VALIDATION_FAILED, message);
        }
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record TimeRange(
            long fromInclusiveMillis,
            long toInclusiveMillis,
            long toExclusiveMillis,
            long asOfMillis) {
    }
}
