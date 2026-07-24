package com.platform.iot.temporal;

import java.util.List;
import java.util.Map;

/**
 * HVAC 空调系统时序数据仓储接口
 * 与 {@link TimeSeriesRepository} 职责分离：前者管电表，本接口管空调原始测点 + COP 指标
 */
public interface HvacTimeSeriesRepository {

    /**
     * 写入 COP 性能指标数据（直写 st_indicator_minute_{indicatorId} 子表）
     * @param indicatorId   平台内部指标ID，决定子表身份
     * @param indicatorCode 指标编码，如 WCR_COP
     * @param buildingId    建筑ID
     * @param equipId       设备ID
     * @param systemGroupId 系统分组ID
     * @param val           指标值
     */
    void insertIndicator(String indicatorId, String indicatorCode, String buildingId,
                         String equipId, String systemGroupId, double val);

    /**
     * 查询原始测点历史数据
     * @param pointId   平台内部测点ID
     * @param startMs   起始时间戳(ms)
     * @param endMs     结束时间戳(ms)
     */
    List<Map<String, Object>> queryRawData(String pointId, long startMs, long endMs);

    /**
     * 查询最新一条原始测点数据
     */
    Map<String, Object> queryLatestRawData(String pointId);

    /**
     * 查询 COP 指标历史数据
     * @param indicatorId   平台内部指标ID
     * @param startMs       起始时间戳(ms)
     * @param endMs         结束时间戳(ms)
     */
    List<Map<String, Object>> queryIndicator(String indicatorId, long startMs, long endMs);

    /**
     * 查询最新一条指标数据
     */
    Map<String, Object> queryLatestIndicator(String indicatorId);
}
