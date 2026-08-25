package com.platform.iot.qualityusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.websocket.RealtimeMessageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.platform.iot.qualityusage.QualityUsageModels.POINT_REALTIME_VIEW;

@Service
/**
 * 将测点分钟事实经统一门禁后发布到现有建筑 WebSocket 通道。
 *
 * <p>被禁止的值在进入 JSON 序列化前即置空。正常质量事件和策略刷新纠正都通过
 * 同一有界执行器；队列满只记录脱敏告警，权威 HTTP 查询和持久化链路不受影响。</p>
 */
public class QualityUsagePointRealtimeService {
    private static final Logger log = LoggerFactory.getLogger(QualityUsagePointRealtimeService.class);

    private final QualityUsagePolicyResolver resolver;
    private final HvacMinuteRepository minuteRepository;
    private final BizDataPointService pointService;
    private final RealtimeMessageGateway gateway;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;
    private Counter droppedCounter;

    public QualityUsagePointRealtimeService(
            QualityUsagePolicyResolver resolver,
            HvacMinuteRepository minuteRepository,
            BizDataPointService pointService,
            RealtimeMessageGateway gateway,
            ObjectMapper objectMapper,
            @Qualifier("qualityUsageRealtimeExecutor") ThreadPoolTaskExecutor executor) {
        this.resolver = resolver;
        this.minuteRepository = minuteRepository;
        this.pointService = pointService;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        Gauge.builder("quality.usage.realtime.correction.backlog", executor,
                        value -> value.getThreadPoolExecutor().getQueue().size())
                .register(registry);
        droppedCounter = registry.counter("quality.usage.realtime.correction.dropped");
    }

    @EventListener
    public void onMinuteReady(HvacMinuteQualityReadyEvent event) {
        submit(() -> publishMinute(event), "MINUTE_READY");
    }

    @EventListener
    public void onRuntimeRefreshed(QualityUsageRuntimeRefreshedEvent event) {
        Set<String> pointIds = event.affectedPolicies().stream()
                .filter(key -> POINT_REALTIME_VIEW.equals(key.scenarioCode()))
                .map(PolicyKey::pointId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!pointIds.isEmpty()) {
            submit(() -> correctLatest(pointIds), "POLICY_REFRESH");
        }
    }

    private void publishMinute(HvacMinuteQualityReadyEvent event) {
        List<RawMinuteAggregate> rows = event.affectedPointIds().isEmpty()
                ? event.aggregates()
                : minuteRepository.findByMinute(event.minuteStart(), event.buildingIds());
        ResolutionContext context;
        try {
            context = resolver.runtimeContext();
        } catch (QualityUsageSnapshotUnavailableException exception) {
            publishUnavailable(event.buildingIds());
            return;
        }
        for (RawMinuteAggregate row : rows) {
            publish(row.buildingId(), row.pointId(), row.pointCode(), row.minuteStart(),
                    row.averageValue(), row.minimumValue(), row.maximumValue(),
                    row.sampleCount(), row.dataQuality(), context);
        }
    }

    private void correctLatest(Set<String> pointIds) {
        Map<String, BizDataPoint> points = pointService.listByIds(pointIds).stream()
                .collect(Collectors.toMap(BizDataPoint::getPointId, point -> point));
        ResolutionContext context;
        try {
            context = resolver.runtimeContext();
        } catch (QualityUsageSnapshotUnavailableException exception) {
            publishUnavailable(points.values().stream()
                    .map(BizDataPoint::getBuildingId)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            return;
        }
        for (HvacMinuteQueryRow row : minuteRepository.findLatestByPointIds(List.copyOf(pointIds))) {
            BizDataPoint point = points.get(row.pointId());
            if (point != null && "ONLINE".equalsIgnoreCase(point.getStatus())) {
                publish(point.getBuildingId(), point.getPointId(), point.getPointCode(), row.time(),
                        row.average(), row.minimum(), row.maximum(), row.sampleCount(),
                        row.dataQuality(), context);
            }
        }
    }

    private void publishUnavailable(Set<String> buildingIds) {
        for (String buildingId : buildingIds) {
            try {
                gateway.sendToBuilding(buildingId, objectMapper.writeValueAsString(Map.of(
                        "type", "HVAC_POINT_POLICY_STATUS",
                        "data", Map.of(
                                "usageStatus", "POLICY_SNAPSHOT_UNAVAILABLE",
                                "reason", QualityUsageErrors.SNAPSHOT_UNAVAILABLE))));
            } catch (Exception exception) {
                log.warn("Unable to deliver point policy unavailable state: buildingId={}",
                        buildingId, exception);
            }
        }
    }

    private void publish(
            String buildingId,
            String pointId,
            String pointCode,
            long minuteStart,
            double average,
            double minimum,
            double maximum,
            long sampleCount,
            int actualQuality,
            ResolutionContext context) {
        Resolution decision = resolver.resolve(
                context, pointId, POINT_REALTIME_VIEW, minuteStart, actualQuality);
        boolean allowed = decision.decision() == Decision.ALLOW;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessKey", pointId + ':' + minuteStart);
        data.put("pointId", pointId);
        data.put("pointCode", pointCode);
        data.put("minute", minuteStart);
        data.put("average", allowed ? average : null);
        data.put("minimum", allowed ? minimum : null);
        data.put("maximum", allowed ? maximum : null);
        data.put("sampleCount", sampleCount);
        data.put("usageStatus", decision.usageStatus().name());
        data.put("actualQuality", actualQuality);
        data.put("policySource", decision.policySource().name());
        data.put("policyVersion", decision.policyVersion());
        data.put("configRevision", decision.configRevision());
        data.put("reason", decision.reason());
        try {
            gateway.sendToBuilding(buildingId,
                    objectMapper.writeValueAsString(Map.of("type", "HVAC_POINT", "data", data)));
        } catch (RuntimeException exception) {
            log.warn("Unable to deliver gated point state: pointId={}, minuteStart={}",
                    pointId, minuteStart, exception);
        } catch (Exception exception) {
            log.warn("Unable to serialize gated point state: pointId={}", pointId, exception);
        }
    }

    private void submit(Runnable task, String source) {
        try {
            executor.execute(task);
        } catch (TaskRejectedException exception) {
            if (droppedCounter != null) {
                droppedCounter.increment();
            }
            log.warn("Quality usage realtime correction queue is full: source={}", source);
        }
    }
}
