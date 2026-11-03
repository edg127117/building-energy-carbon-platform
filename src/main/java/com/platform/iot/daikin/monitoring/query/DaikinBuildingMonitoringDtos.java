package com.platform.iot.daikin.monitoring.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 建筑内启用、正式绑定的大金内机汇总；分类计数不是在线数，时长为平台有效采样区间内的开机估算。 */
public final class DaikinBuildingMonitoringDtos {
    private DaikinBuildingMonitoringDtos() { }

    /** counts 保留协议全部已知类别；完全没有有效分类时为空，不能由客户端补零。unsupported 只计有明确协议证据的设备。 */
    public record Categories(Map<String, Long> counts, long observed, long unknown, long unsupported,
                             Map<String, Long> reasons) { }

    public record DeviceSummary(String buildingId, String scope, long asOf, long expiresAt,
                                long total, long on, long off, long unknown,
                                Map<String, Long> unknownReasons, Categories modes, Categories speeds) { }

    /**
     * coveragePercentage 是覆盖时长占当月已过去时长的百分比，仅作说明；percentage 只决定相对首名的条形长度。
     * 位置为当前正式档案，不做房间历史归因。
     */
    public record RuntimeRank(int rank, String equipmentId, String identityId, int mappingVersion,
                              String equipmentCode, String equipmentName, String spaceName, String floorName,
                              long onMillis, long coveredMillis, BigDecimal hours, BigDecimal coveredHours,
                              BigDecimal coveragePercentage, BigDecimal percentage,
                              String coverageStatus) { }

    public record RuntimeRanking(String buildingId, String scope, String source, String month,
                                 String statisticsZone, long asOf, long periodStart, long periodEnd,
                                 long elapsedMillis, long totalDevices, long rankedDevices,
                                 Map<String, Long> excludedReasons, List<RuntimeRank> items) { }
}
