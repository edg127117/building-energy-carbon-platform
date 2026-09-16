package com.platform.iot.temporal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Clock;
import java.util.UUID;

/**
 * 定时执行 HVAC 逐条原始事件的保留期清理。
 *
 * <p>保留天数和 Cron 均由配置提供，任务只删除 {@code st_raw_event} 中设备采集时间
 * 早于截止点的证据，不接触正式分钟、质量任务或指标结果。关闭
 * {@code data-retention.cleanup-enabled} 后不注册该外部资源任务。</p>
 */
@Service
@ConditionalOnProperty(name = "data-retention.cleanup-enabled",
        havingValue = "true", matchIfMissing = true)
public class HvacRawEventRetentionService {
    private static final Logger log = LoggerFactory.getLogger(HvacRawEventRetentionService.class);

    static final int DAIKIN_RETENTION_DAYS = 90;
    static final String DAIKIN_SOURCE_SYSTEM = "DAIKIN_V2";

    private final HvacRawEventRepository repository;
    private final JdbcTemplate mysql;
    private final int retentionDays;
    private final int pointBatchSize;
    private final long leaseMillis;
    private final Clock clock;

    @Autowired
    public HvacRawEventRetentionService(
            HvacRawEventRepository repository,
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
            @Value("${data-retention.raw-event-days:90}") int retentionDays,
            @Value("${data-retention.point-batch-size:100}") int pointBatchSize,
            @Value("${data-retention.lease-seconds:120}") long leaseSeconds) {
        this(repository, mysql, retentionDays, pointBatchSize, leaseSeconds, Clock.systemUTC());
    }

    HvacRawEventRetentionService(HvacRawEventRepository repository, JdbcTemplate mysql, int retentionDays,
                                 int pointBatchSize, long leaseSeconds, Clock clock) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("原始事件保留天数必须大于等于1");
        }
        if (pointBatchSize < 1 || pointBatchSize > 500 || leaseSeconds < 1 || leaseSeconds > 3600) {
            throw new IllegalArgumentException("原始事件清理分页和租约无效");
        }
        this.repository = repository;
        this.mysql = mysql;
        this.retentionDays = retentionDays;
        this.pointBatchSize = pointBatchSize;
        this.leaseMillis = leaseSeconds * 1000L;
        this.clock = clock;
    }

    HvacRawEventRetentionService(HvacRawEventRepository repository, JdbcTemplate mysql, int retentionDays) {
        this(repository, mysql, retentionDays, 100, 120, Clock.systemUTC());
    }

    @Scheduled(cron = "${data-retention.cleanup-cron:0 30 3 * * ?}", zone = "Asia/Shanghai")
    public void cleanup() {
        cleanup(System.currentTimeMillis());
    }

    /** 使用同一个服务器时间计算本轮保留截止点，并交由原始事件仓储执行删除。 */
    public void cleanup(long now) {
        long cutoff = now - Duration.ofDays(retentionDays).toMillis();
        long daikinCutoff = now - Duration.ofDays(DAIKIN_RETENTION_DAYS).toMillis();
        String token = UUID.randomUUID().toString();
        int claimed = mysql.update("""
                UPDATE biz_daikin_raw_retention_cursor SET lease_token=?,lease_until_ms=?
                WHERE task_name='HVAC_RAW' AND lease_until_ms<=?
                """, token, now + leaseMillis, now);
        if (claimed != 1) return;
        log.info("开始清理HVAC逐条原始事件: retentionDays={}, cutoff={}", retentionDays, cutoff);
        try {
            String cursor = mysql.queryForObject("""
                    SELECT cursor_point_id FROM biz_daikin_raw_retention_cursor
                    WHERE task_name='HVAC_RAW' AND lease_token=?
                    """, String.class, token);
            var points = repository.findRetentionPoints(cursor, pointBatchSize, DAIKIN_SOURCE_SYSTEM);
            for (var point : points) {
                long leaseNow = clock.millis();
                int renewed = mysql.update("""
                        UPDATE biz_daikin_raw_retention_cursor SET lease_until_ms=?
                        WHERE task_name='HVAC_RAW' AND lease_token=? AND lease_until_ms>?
                        """, leaseNow + leaseMillis, token, leaseNow);
                if (renewed != 1) return;
                int aliases = mysql.queryForObject("""
                        SELECT COUNT(*) FROM biz_point_alias WHERE source_system=? AND point_id=?
                        """, Integer.class, DAIKIN_SOURCE_SYSTEM, point.pointId());
                long pointCutoff = point.protectedSourcePresent() || aliases > 0
                        ? Math.min(cutoff, daikinCutoff) : cutoff;
                repository.deletePointBefore(point.tableName(), pointCutoff);
                int advanced = mysql.update("""
                        UPDATE biz_daikin_raw_retention_cursor SET cursor_point_id=?
                        WHERE task_name='HVAC_RAW' AND lease_token=? AND lease_until_ms>?
                        """, point.tableName(), token, clock.millis());
                if (advanced != 1) return;
            }
            if (points.size() < pointBatchSize) {
                mysql.update("""
                        UPDATE biz_daikin_raw_retention_cursor SET cursor_point_id=NULL
                        WHERE task_name='HVAC_RAW' AND lease_token=? AND lease_until_ms>?
                        """, token, clock.millis());
            }
            log.info("HVAC逐条原始事件清理完成: retentionDays={}, cutoff={}, points={}",
                    retentionDays, cutoff, points.size());
        } finally {
            mysql.update("""
                    UPDATE biz_daikin_raw_retention_cursor SET lease_token=NULL,lease_until_ms=0
                    WHERE task_name='HVAC_RAW' AND lease_token=?
                    """, token);
        }
    }
}
