package com.platform.iot.ingest;

import com.platform.iot.dataquality.event.HvacLateRealEventStoredEvent;
import com.platform.iot.quality.TelemetryQualityValidator;
import com.platform.iot.quality.TelemetryValidationResult;
import com.platform.iot.quality.ValidatedHvacTelemetry;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 把 MQTT 上报转换为可进入分钟链路的 HVAC 原始真实事件。
 *
 * <p>上游 {@link HvacMqttMessageHandler} 传入未校验载荷；本服务先调用
 * {@link TelemetryQualityValidator} 解析 19 测点身份和质量边界，再按设备采集时间
 * 判断所属分钟及是否迟到，最后通过 {@link HvacRawEventRepository} 幂等写入
 * TDengine。返回结果由 MQTT 回调决定 QoS 1 确认：业务拒绝、重复和冲突更新均可
 * 确认，只有原始事件存储失败保留重投机会。</p>
 *
 * <p>成功写入的迟到事件还会发布 {@link HvacLateRealEventStoredEvent}，触发质量修正
 * 链路重新聚合受影响分钟；事件通知失败不会否定已经完成的原始证据落盘。</p>
 */
@Slf4j
@Service
public class HvacIngestionService {

    private final TelemetryQualityValidator validator;
    private final HvacRawEventRepository repository;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final long finalizationDelayMillis;
    private final String sourceSystem;
    private final RetryTemplate retryTemplate;

    @Autowired
    public HvacIngestionService(
            TelemetryQualityValidator validator,
            HvacRawEventRepository repository,
            MeterRegistry meterRegistry,
            ApplicationEventPublisher eventPublisher,
            @Value("${aggregation.finalization-delay-seconds:30}") int finalizationDelaySeconds,
            @Value("${ingestion.source-system:MQTT_FREEZE_V1}") String sourceSystem) {
        this.validator = validator;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
        this.finalizationDelayMillis = finalizationDelaySeconds * 1_000L;
        this.sourceSystem = sourceSystem;
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(100, 2, 400)
                .retryOn(RuntimeException.class)
                .build();
    }

    /**
     * 执行单条设备上报的校验、迟到判定和 TDengine 幂等写入。
     *
     * <p>设备只能提供建筑、设备、来源点码、数值和采集时间；可信来源命名空间由
     * 服务端配置注入。TDengine 写入最多重试三次，仍失败时返回
     * {@link IngestionOutcome#STORAGE_FAILED}，让 MQTT 不确认并等待 Broker 重投。</p>
     */
    public HvacIngestionResult ingest(Map<String, Object> payload, long receivedTime) {
        // 来源系统由服务端配置，不允许设备载荷伪造命名空间。
        TelemetryValidationResult validation = validator.validate(payload, receivedTime, sourceSystem);
        if (!validation.accepted()) {
            meterRegistry.counter("iot.hvac.ingestion.rejected",
                    "reason", validation.reason().name()).increment();
            log.warn("HVAC数据拒绝: reason={}, detail={}, deviceId={}, pointCode={}, receivedTime={}",
                    validation.reason(), validation.detail(),
                    payload == null ? null : payload.get("deviceId"),
                    payload == null ? null : payload.get("pointCode"), receivedTime);
            return HvacIngestionResult.rejected(validation.reason(), validation.detail());
        }

        ValidatedHvacTelemetry telemetry = validation.telemetry();
        long minuteStart = telemetry.eventTime() - Math.floorMod(telemetry.eventTime(), 60_000L);
        // 30秒仅决定窗口何时冻结；事件是否属于该分钟始终只看设备采集时间。
        boolean late = telemetry.receivedTime()
                >= minuteStart + 60_000L + finalizationDelayMillis;
        RawTelemetryEvent event = new RawTelemetryEvent(
                telemetry.pointId(), telemetry.pointCode(),
                telemetry.sourceSystem(), telemetry.sourcePointCode(), telemetry.sourceDeviceId(),
                telemetry.buildingId(), telemetry.systemGroupId(),
                telemetry.equipId(), telemetry.equipCode(),
                telemetry.familyCode(), telemetry.componentCode(), telemetry.suffixCode(),
                telemetry.value(), telemetry.eventTime(),
                telemetry.receivedTime(), 0, telemetry.isForCalc(), late);

        try {
            RawEventWriteResult writeResult = retryTemplate.execute(context -> repository.upsert(event));
            return mapOutcome(writeResult, event);
        } catch (RuntimeException exception) {
            meterRegistry.counter("iot.hvac.ingestion.storage_failed").increment();
            log.error("HVAC原始事件写入TDengine失败，保留MQTT重投: pointCode={}, eventTime={}, error={}",
                    event.pointCode(), event.eventTime(), exception.getMessage());
            return HvacIngestionResult.storageFailed(exception.getMessage());
        }
    }

    /**
     * 把 TDengine 的幂等写入语义转换为 MQTT 可消费的业务结果。
     *
     * <p>新插入和同时间戳冲突更新会触发迟到修正通知；完全重复的数据已经存在，
     * 只需确认而不重复发布修正事件。</p>
     */
    private HvacIngestionResult mapOutcome(RawEventWriteResult result, RawTelemetryEvent event) {
        return switch (result) {
            case INSERTED -> {
                meterRegistry.counter("iot.hvac.ingestion.accepted").increment();
                publishLateStored(event);
                yield HvacIngestionResult.of(IngestionOutcome.ACCEPTED);
            }
            case DUPLICATE -> {
                meterRegistry.counter("iot.hvac.ingestion.duplicate").increment();
                yield HvacIngestionResult.of(IngestionOutcome.DUPLICATE);
            }
            case CONFLICT_UPDATED -> {
                meterRegistry.counter("iot.hvac.ingestion.conflict").increment();
                log.warn("同一测点同一设备时间出现冲突值，最后收到值生效: pointCode={}, eventTime={}, value={}",
                        event.pointCode(), event.eventTime(), event.value());
                publishLateStored(event);
                yield HvacIngestionResult.of(IngestionOutcome.CONFLICT_UPDATED);
            }
        };
    }

    /**
     * 在迟到真实事件已经落盘后发布精确到“测点 + 分钟”的修正线索。
     *
     * <p>通知属于落盘后的派生副作用；发布失败只记录指标和日志，由低频迟到证据
     * 扫描补偿，不能改写为存储失败，否则 MQTT 重投只会命中重复行。</p>
     */
    private void publishLateStored(RawTelemetryEvent event) {
        if (!event.late()) {
            return;
        }
        long minuteStart = event.eventTime()
                - Math.floorMod(event.eventTime(), 60_000L);
        try {
            eventPublisher.publishEvent(new HvacLateRealEventStoredEvent(
                    event.pointId(),
                    event.buildingId(),
                    minuteStart,
                    event.receivedTime()));
            meterRegistry.counter("iot.hvac.ingestion.late_stored").increment();
        } catch (RuntimeException exception) {
            // 原始证据已经成功落盘，不能把事件总线异常伪装成 TDengine 写失败并触发
            // MQTT 重投；重投只会命中 DUPLICATE，无法替代后台补偿。
            meterRegistry.counter("iot.hvac.ingestion.late_dispatch_failed").increment();
            log.error("迟到真实事件通知失败，原始证据已保留: pointId={}, minute={}",
                    event.pointId(), minuteStart, exception);
        }
    }
}
