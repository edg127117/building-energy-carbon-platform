package com.platform.energy.activity;

import com.platform.energy.activity.EnergyActivityDataContracts.ActivityCursor;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataPage;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.energy.activity.EnergyActivityDataReader.Cursor;
import com.platform.energy.activity.EnergyActivityDataReader.RawEvent;
import com.platform.energy.activity.EnergyActivityPointCatalog.PointProfile;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.qualityusage.QualityUsageSnapshotUnavailableException;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.platform.energy.activity.EnergyActivityDataErrors.*;
import static com.platform.iot.qualityusage.QualityUsageModels.ENERGY_ACTIVITY_AGGREGATION;

@Service
/**
 * 对原始活动数据统一执行角色、建筑、能源属性和 Q0/Q1/Q2 使用门禁。
 *
 * <p>下游聚合和折算只能消费本服务返回的公共 DTO，不能绕过门禁直接查询 MySQL 或 TDengine。
 * 没有已确认能源属性、策略快照不可用或底层事实越界时均失败关闭。</p>
 */
public class EnergyActivityDataService {
    static final int MAX_POINTS = 100;
    static final int MAX_LIMIT = 500;
    static final long MAX_RANGE_MILLIS = 31L * 24 * 60 * 60 * 1_000;

    private final BuildingScopeService buildingScopeService;
    private final EnergyActivityPointCatalog pointCatalog;
    private final EnergyActivityDataReader dataReader;
    private final QualityUsagePolicyResolver qualityResolver;

    public EnergyActivityDataService(
            BuildingScopeService buildingScopeService,
            EnergyActivityPointCatalog pointCatalog,
            EnergyActivityDataReader dataReader,
            QualityUsagePolicyResolver qualityResolver) {
        this.buildingScopeService = buildingScopeService;
        this.pointCatalog = pointCatalog;
        this.dataReader = dataReader;
        this.qualityResolver = qualityResolver;
    }

    public RawActivityDataPage rawEvents(
            Long userId,
            Collection<String> roles,
            String buildingId,
            List<String> requestedPointIds,
            long fromInclusive,
            long toExclusive,
            Long afterEventTime,
            String afterPointId,
            int limit) {
        requireReader(roles);
        String building = required(buildingId, "建筑不能为空");
        Set<String> pointIds = normalizePointIds(requestedPointIds);
        validateRange(fromInclusive, toExclusive);
        validateLimit(limit);
        Cursor after = validateCursor(afterEventTime, afterPointId, pointIds,
                fromInclusive, toExclusive);
        buildingScopeService.checkAccess(userId, roles, building);

        Map<String, PointProfile> profiles = requireProfiles(building, pointIds);
        ResolutionContext context;
        try {
            context = qualityResolver.historyContext(
                    pointIds, ENERGY_ACTIVITY_AGGREGATION, fromInclusive, toExclusive);
        } catch (QualityUsageSnapshotUnavailableException exception) {
            throw error(503, DEPENDENCY_UNAVAILABLE, "活动数据质量策略暂不可用");
        }

        EnergyActivityDataReader.RawEventPage rawPage;
        try {
            rawPage = dataReader.readRawEvents(
                    building, pointIds, fromInclusive, toExclusive, after, limit);
        } catch (DataAccessException | IllegalStateException exception) {
            throw error(503, DEPENDENCY_UNAVAILABLE, "活动数据源暂不可用");
        }

        List<RawActivityDataView> allowed = new ArrayList<>();
        int blocked = 0;
        for (RawEvent event : rawPage.items()) {
            PointProfile profile = profiles.get(event.pointId());
            if (!Objects.equals(building, event.buildingId()) || profile == null) {
                throw error(500, DATA_SCOPE_MISMATCH, "活动数据范围校验失败");
            }
            Resolution resolution = resolveQuality(context, event);
            if (resolution.decision() == Decision.BLOCK) {
                blocked++;
                continue;
            }
            allowed.add(toView(event, profile, resolution));
        }

        ActivityCursor next = rawPage.nextCursor() == null ? null
                : new ActivityCursor(rawPage.nextCursor().eventTime(),
                rawPage.nextCursor().pointId());
        return new RawActivityDataPage(
                building,
                "RAW_EVENT",
                "RAW_EVENT_V1",
                "POINT_ID_EVENT_TIME",
                "COALESCED_BY_IDENTITY",
                "NOT_RETAINED",
                ENERGY_ACTIVITY_AGGREGATION,
                fromInclusive,
                toExclusive,
                pointIds.size(),
                rawPage.items().size(),
                blocked,
                rawPage.truncated(),
                next,
                allowed);
    }

    private Map<String, PointProfile> requireProfiles(
            String buildingId, Set<String> pointIds) {
        Map<String, PointProfile> profiles = new HashMap<>();
        try {
            for (PointProfile profile : pointCatalog.find(buildingId, pointIds)) {
                profiles.put(profile.pointId(), profile);
            }
        } catch (DataAccessException exception) {
            throw error(503, DEPENDENCY_UNAVAILABLE, "能源测点档案暂不可用");
        }
        for (String pointId : pointIds) {
            PointProfile profile = profiles.get(pointId);
            if (profile == null || blank(profile.profileId()) || blank(profile.unit())
                    || blank(profile.energyType()) || blank(profile.valueSemantics())
                    || profile.profileRevision() == null) {
                throw error(409, POINT_PROFILE_REQUIRED,
                        "测点缺少完整能源专业属性: " + pointId);
            }
            if (!"CONFIRMED".equals(profile.confirmationStatus())) {
                throw error(409, POINT_PROFILE_UNCONFIRMED,
                        "测点能源专业属性尚未确认: " + pointId);
            }
        }
        return profiles;
    }

    /** 质量等级或场景快照异常时保持失败关闭，不把底层策略错误泄漏为未分类 500。 */
    private Resolution resolveQuality(ResolutionContext context, RawEvent event) {
        try {
            return qualityResolver.resolve(
                    context,
                    event.pointId(),
                    ENERGY_ACTIVITY_AGGREGATION,
                    QualityUsagePolicyResolver.alignMinute(event.eventTime()),
                    event.dataQuality());
        } catch (BusinessException | IllegalArgumentException exception) {
            throw error(503, DEPENDENCY_UNAVAILABLE, "活动数据质量判定暂不可用");
        }
    }

    private static RawActivityDataView toView(
            RawEvent event, PointProfile profile, Resolution resolution) {
        return new RawActivityDataView(
                event.pointId(),
                event.pointCode(),
                profile.unit(),
                profile.energyType(),
                profile.energySubtype(),
                profile.valueSemantics(),
                profile.confirmationStatus(),
                profile.profileRevision(),
                event.sourceSystem(),
                event.sourcePointCode(),
                event.sourceDeviceId(),
                event.rawValue(),
                event.eventTime(),
                event.receivedTime(),
                QualityLevel.fromCode(event.dataQuality()).name(),
                event.late(),
                resolution.policySource().name(),
                resolution.policyVersion(),
                resolution.configRevision());
    }

    private static Set<String> normalizePointIds(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw error(400, VALIDATION_FAILED, "至少选择一个测点");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String pointId : requested) {
            normalized.add(required(pointId, "测点不能为空"));
        }
        if (normalized.size() > MAX_POINTS) {
            throw error(400, VALIDATION_FAILED, "单次最多读取 100 个测点");
        }
        return Set.copyOf(normalized);
    }

    private static void validateRange(long fromInclusive, long toExclusive) {
        if (fromInclusive < 0 || toExclusive <= fromInclusive) {
            throw error(400, VALIDATION_FAILED, "时间范围必须为有效半开区间");
        }
        if (toExclusive - fromInclusive > MAX_RANGE_MILLIS) {
            throw error(400, VALIDATION_FAILED, "单次时间范围不能超过 31 天");
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw error(400, VALIDATION_FAILED, "单页数量必须在 1 到 500 之间");
        }
    }

    private static Cursor validateCursor(
            Long afterEventTime,
            String afterPointId,
            Set<String> pointIds,
            long fromInclusive,
            long toExclusive) {
        if (afterEventTime == null && blank(afterPointId)) {
            return null;
        }
        if (afterEventTime == null || blank(afterPointId)) {
            throw error(400, VALIDATION_FAILED, "游标时间和测点必须同时提供");
        }
        String pointId = afterPointId.trim();
        if (afterEventTime < fromInclusive || afterEventTime >= toExclusive
                || !pointIds.contains(pointId)) {
            throw error(400, VALIDATION_FAILED, "游标必须位于本次读取范围内");
        }
        return new Cursor(afterEventTime, pointId);
    }

    private static void requireReader(Collection<String> roles) {
        Set<String> actual = new HashSet<>();
        if (roles != null) {
            roles.stream().filter(Objects::nonNull)
                    .map(value -> value.trim().toUpperCase())
                    .forEach(actual::add);
        }
        if (!actual.contains(FormalRole.BUILDING_OWNER.name())
                && !actual.contains(FormalRole.ENERGY_MANAGER.name())
                && !actual.contains(FormalRole.PLATFORM_ADMIN.name())) {
            throw error(403, FORBIDDEN, "当前角色不能读取能源活动数据");
        }
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
}
