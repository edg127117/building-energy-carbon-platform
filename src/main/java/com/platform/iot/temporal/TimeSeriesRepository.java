package com.platform.iot.temporal;

import java.util.List;
import java.util.Map;

/**
 * 时序数据存储抽象层 (Strategy Pattern / SPI)
 */
public interface TimeSeriesRepository {

    void save(String deviceId, Object payload);

    void saveBatch(List<Object> payloadList);

    /**
     * 将待写入数据放入内存缓冲队列（微秒级），由定时任务批量刷盘
     */
    void enqueue(Map<String, Object> entry);

    /**
     * 立即将缓冲队列中的所有数据刷入数据库
     */
    void flushBuffer();

    List<Object> queryHistory(String deviceId, long startMs, long endMs);

    String getEngineName();
}
