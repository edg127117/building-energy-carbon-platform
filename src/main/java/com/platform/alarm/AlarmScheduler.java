package com.platform.alarm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(name = "alarm.enabled", havingValue = "true")
/** 报警独立启用开关。每轮有界轮询建筑，各建筑失败隔离，不影响采集与核算调度。 */
public class AlarmScheduler implements SmartLifecycle {
    private final JdbcTemplate jdbc;
    private final AlarmService alarms;
    private String cursor = "";
    private final long scanMillis;
    private ScheduledExecutorService executor;

    public AlarmScheduler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AlarmService alarms,
            @Value("${alarm.scan-ms:30000}") long scanMillis) {
        this.jdbc = jdbc; this.alarms = alarms;
        if (scanMillis < 1000) throw new IllegalArgumentException("报警扫描周期不能小于1秒");
        this.scanMillis = scanMillis;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        // 不注册 TaskScheduler Bean，避免改变其他 @Scheduled 任务的默认调度器选择。
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("alarm-evaluation").daemon(true).factory());
        executor.scheduleWithFixedDelay(() -> {
            try { scan(); }
            catch (RuntimeException exception) { log.error("报警扫描不可用，异常类型={}", exception.getClass().getSimpleName()); }
        }, 0, scanMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
    }

    @Override
    public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }

    public void scan() {
        var buildings = jdbc.queryForList("SELECT building_id FROM building WHERE del_flag=0 AND building_id>? ORDER BY building_id LIMIT 100", String.class, cursor);
        for (String building : buildings) {
            try { alarms.evaluate(building); }
            catch (RuntimeException exception) { log.error("报警判定失败，建筑={}，异常类型={}", building, exception.getClass().getSimpleName()); }
        }
        cursor = buildings.size() < 100 ? "" : buildings.getLast();
    }
}
