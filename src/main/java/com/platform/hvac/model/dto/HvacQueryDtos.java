package com.platform.hvac.model.dto;

import java.util.List;

/**
 * HVAC 查询 API 的稳定响应模型集合。
 *
 * <p>DTO 与 TDengine 查询行分离：Repository 只返回时序数值，Service 再补充
 * MySQL 中的测点名称、设备和单位等业务元数据。这样底层表结构不会直接泄露给调用方。</p>
 */
public final class HvacQueryDtos {
    private HvacQueryDtos() {
    }

    /**
     * 一个建筑当前全部在线测点的最新快照。
     *
     * @param buildingId 建筑 ID
     * @param generatedAt 服务端完成本次响应组装的时间，不代表所有测点的数据时间
     * @param points 按测点编码稳定排序的快照列表
     */
    public record SnapshotResponse(
            String buildingId,
            long generatedAt,
            List<SnapshotPoint> points) {
    }

    /**
     * 单个测点的最新冻结分钟数据。
     *
     * @param pointId 测点内部 ID
     * @param pointCode 面向业务展示的测点编码
     * @param pointName 测点名称
     * @param equipId 所属设备 ID
     * @param equipCode 所属设备编码
     * @param unit 工程单位
     * @param minute 最新数据对应的分钟起始时间；无数据时为 {@code null}
     * @param average 平均值；无数据时为 {@code null}
     * @param minimum 最小值；无数据时为 {@code null}
     * @param maximum 最大值；无数据时为 {@code null}
     * @param sampleCount 原始采样数；无数据时为 0
     * @param dataQuality 数据质量等级；无数据时为 {@code null}
     * @param status {@code NORMAL} 表示记录仍可作为实时值，{@code STALE} 表示保留的
     *               最后记录已过期，{@code NO_DATA} 表示仅有测点配置且从未查询到分钟记录
     */
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
            String status,
            String usageStatus,
            Integer actualQuality,
            String policySource,
            Integer policyVersion,
            Long configRevision,
            String reason) {

        /** 保留旧调用方的构造契约；新增字段由正式查询服务统一填充。 */
        public SnapshotPoint(
                String pointId, String pointCode, String pointName,
                String equipId, String equipCode, String unit,
                Long minute, Double average, Double minimum, Double maximum,
                long sampleCount, Integer dataQuality, String status) {
            this(pointId, pointCode, pointName, equipId, equipCode, unit,
                    minute, average, minimum, maximum, sampleCount, dataQuality, status,
                    "AVAILABLE", dataQuality, null, null, null,
                    minute == null ? "NO_DATA" : "LEGACY_CONSTRUCTOR");
        }
    }

    /**
     * 多测点历史趋势查询结果。
     *
     * @param buildingId 建筑 ID
     * @param from 查询起始时间（包含）
     * @param to 查询结束时间（不包含）
     * @param resolutionMinutes 服务端根据跨度自动选择的 1、5 或 30 分钟分辨率
     * @param series 按调用方首次传入的测点顺序返回的趋势序列
     */
    public record HistoryResponse(
            String buildingId,
            long from,
            long to,
            int resolutionMinutes,
            List<HistorySeries> series) {
    }

    /**
     * 一个测点的历史趋势序列。
     *
     * @param pointId 测点内部 ID
     * @param pointCode 测点编码
     * @param pointName 测点名称
     * @param unit 工程单位
     * @param records 按时间升序排列的记录；合法测点无数据时为空列表
     */
    public record HistorySeries(
            String pointId,
            String pointCode,
            String pointName,
            String unit,
            List<HistoryRecord> records) {
    }

    /**
     * 一个冻结分钟或降采样窗口的趋势记录。
     *
     * @param time 分钟或窗口起始时间（Unix 毫秒）
     * @param average 按原始采样数计算的平均值
     * @param minimum 分钟或窗口内最小值
     * @param maximum 分钟或窗口内最大值
     * @param sampleCount 参与计算的原始采样总数
     * @param dataQuality 最差数据质量等级，数值越大质量越差
     */
    public record HistoryRecord(
            long time,
            Double average,
            Double minimum,
            Double maximum,
            long sampleCount,
            Integer dataQuality,
            int usableCount,
            int blockedCount,
            int missingCount,
            String status,
            String policySource,
            Integer policyVersion,
            Long configRevision,
            String reason) {

        /** 保留旧测试和内部调用的 1 分钟可用数据构造方式。 */
        public HistoryRecord(
                long time, double average, double minimum, double maximum,
                long sampleCount, int dataQuality) {
            this(time, average, minimum, maximum, sampleCount, dataQuality,
                    1, 0, 0, "AVAILABLE", null, null, null, "LEGACY_CONSTRUCTOR");
        }
    }
}
