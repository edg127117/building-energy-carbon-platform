package com.platform.iot.controller;

import com.platform.framework.common.Result;
import com.platform.iot.temporal.TimeSeriesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 遥测历史数据查询 API
 * 为大屏图表的时间范围选择器提供后端数据支撑
 */
@RestController
@RequestMapping("/telemetry")
public class TelemetryController {

    @Autowired
    private TimeSeriesRepository timeSeriesRepository;

    /**
     * 查询设备在指定时间范围内的历史遥测数据
     * GET: /api/telemetry/history?deviceId=xxx&hours=1
     */
    @GetMapping("/history")
    public Result<List<Object>> getHistory(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "1") int hours) {

        long endMs = System.currentTimeMillis();
        long startMs = endMs - hours * 3600_000L;
        List<Object> data = timeSeriesRepository.queryHistory(deviceId, startMs, endMs);
        return Result.success(data);
    }
}
