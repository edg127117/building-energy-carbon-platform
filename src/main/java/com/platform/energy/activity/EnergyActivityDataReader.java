package com.platform.energy.activity;

import java.util.List;
import java.util.Set;

/**
 * 多能源活动数据对 TDengine 原始事件的只读端口。
 *
 * <p>调用方只能按已完成建筑权限校验的建筑和测点集合读取；实现必须使用半开时间区间、
 * 稳定游标和有界页大小，不能把 HVAC 查询 DTO 或 TDengine 表结构泄漏给下游。</p>
 */
public interface EnergyActivityDataReader {

    RawEventPage readRawEvents(
            String buildingId,
            Set<String> pointIds,
            long fromInclusive,
            long toExclusive,
            Cursor after,
            int limit);

    record Cursor(long eventTime, String pointId) {
        public Cursor {
            if (pointId == null || pointId.isBlank()) {
                throw new IllegalArgumentException("游标测点不能为空");
            }
        }
    }

    record RawEvent(
            String pointId,
            String pointCode,
            String buildingId,
            String sourceSystem,
            String sourcePointCode,
            String sourceDeviceId,
            double rawValue,
            long eventTime,
            long receivedTime,
            int dataQuality,
            boolean late) {
    }

    record RawEventPage(
            List<RawEvent> items,
            boolean truncated,
            Cursor nextCursor) {
        public RawEventPage {
            items = List.copyOf(items);
        }
    }
}
