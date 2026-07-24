package com.platform.hvac.model.dto;

import java.util.List;

/**
 * HVAC 查询 API 的稳定响应模型。
 */
public final class HvacQueryDtos {
    private HvacQueryDtos() {
    }

    public record SnapshotResponse(
            String buildingId,
            long generatedAt,
            List<SnapshotPoint> points) {
    }

    public record SnapshotPoint(
            String pointId,
            String pointCode,
            String pointName,
            String equipId,
            String equipCode,
            String unit,
            Long minute,
            Double average,
            Double minimum,
            Double maximum,
            long sampleCount,
            Integer dataQuality,
            String status) {
    }

    public record HistoryResponse(
            String buildingId,
            long from,
            long to,
            int resolutionMinutes,
            List<HistorySeries> series) {
    }

    public record HistorySeries(
            String pointId,
            String pointCode,
            String pointName,
            String unit,
            List<HistoryRecord> records) {
    }

    public record HistoryRecord(
            long time,
            double average,
            double minimum,
            double maximum,
            long sampleCount,
            int dataQuality) {
    }
}
