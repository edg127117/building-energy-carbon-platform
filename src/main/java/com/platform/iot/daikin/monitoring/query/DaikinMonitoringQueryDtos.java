package com.platform.iot.daikin.monitoring.query;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 大金只读监测查询的显式响应契约；温度当前值和历史由独立温度接口提供。 */
public final class DaikinMonitoringQueryDtos {
    private DaikinMonitoringQueryDtos() { }

    public record DeviceListItem(String identityId, String equipmentId, String pendingId,
                                 String buildingId, String spaceId, String systemGroupId,
                                 String deviceKind, int mappingVersion, boolean active,
                                 boolean stale, Long lastValidAt, StateSummaryView onOff,
                                 StateSummaryView mode, StateSummaryView unitStatus,
                                 boolean hasActiveException, String equipmentCode, String equipmentName) { }

    public record StateSummaryView(String value, String status, Long lastValidAt, boolean stale) { }

    @Schema(description = "非温度当前字段；roomTemp和temperature不在本响应中")
    public record CurrentFieldView(String fieldName, String rawJson, String normalizedValue,
                                   String status, Long lastValidAt, String lastAttemptRawJson,
                                   long lastAttemptAt, boolean valueVisible, boolean stale, int mappingVersion,
                                   int lastAttemptMappingVersion) { }

    public record DeviceCurrentView(String identityId, String equipmentId, String buildingId,
                                    String spaceId, String systemGroupId, int mappingVersion,
                                    boolean active, Long lastValidAt, List<CurrentFieldView> fields) { }

    public record StateEventView(long eventId, String fieldName, long roundId,
                                 String beforeRawJson, String beforeNormalizedValue,
                                 String afterRawJson, String afterNormalizedValue,
                                 long previousObservedAt, long observedAt, boolean afterGap,
                                 String buildingId, String spaceId, String systemGroupId,
                                 int mappingVersion) { }

    public record ExceptionView(long exceptionId, String type, String scopeType,
                                String sourceId, String identityId, String equipmentId,
                                String buildingId, String fieldName, long firstDetectedAt,
                                long lastDetectedAt, Long recoveredAt, Long lastRoundId) { }

    public record CursorPage<T>(List<T> items, String nextCursor) {
        public CursorPage { items = List.copyOf(items); }
    }
}
