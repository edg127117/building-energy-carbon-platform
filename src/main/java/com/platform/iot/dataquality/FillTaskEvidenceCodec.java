package com.platform.iot.dataquality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 补全任务强类型证据与 MySQL {@code evidence_json} 的唯一编解码边界。
 *
 * <p>这里同时校验来源类型和必填证据。若审计 JSON 已损坏或来源不匹配，
 * 恢复任务必须明确失败，不能带着不可信证据继续写入 TDengine。</p>
 */
@Component
public class FillTaskEvidenceCodec {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;

    private final ObjectMapper objectMapper;

    public FillTaskEvidenceCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 校验来源与强类型证据的一致性后编码为 MySQL 审计 JSON。
     * 无效证据不会进入持久化层，避免产生无法安全恢复的补全任务。
     */
    public String encode(FillSourceType sourceType, FillTaskEvidence evidence) {
        try {
            validate(sourceType, evidence);
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("补全任务证据无法序列化", exception);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("补全任务证据内容无效", exception);
        }
    }

    /**
     * 按任务来源恢复对应证据类型，并再次执行区间、版本和必填字段校验。
     * 空白、损坏或来源不匹配的 JSON 一律失败，不为恢复流程提供猜测值。
     */
    public FillTaskEvidence decode(FillSourceType sourceType, String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            throw new IllegalArgumentException("补全任务 evidenceJson 不能为空");
        }
        Class<? extends FillTaskEvidence> evidenceType = evidenceType(sourceType);
        try {
            FillTaskEvidence evidence = objectMapper.readValue(evidenceJson, evidenceType);
            validate(sourceType, evidence);
            return evidence;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("补全任务证据格式无效", exception);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("补全任务证据格式无效", exception);
        }
    }

    /** 来源类型决定唯一合法证据结构，再由各结构校验其审计边界。 */
    private void validate(FillSourceType sourceType, FillTaskEvidence evidence) {
        Objects.requireNonNull(sourceType, "sourceType 不能为空");
        Objects.requireNonNull(evidence, "evidence 不能为空");
        if (!evidenceType(sourceType).isInstance(evidence)) {
            throw new IllegalArgumentException(
                    "补全任务来源 " + sourceType + " 与证据类型不匹配");
        }
        switch (evidence) {
            case FillTaskEvidence.Typical typical -> validateTypical(typical);
            case FillTaskEvidence.Interpolation interpolation ->
                    validateInterpolation(interpolation);
        }
    }

    private Class<? extends FillTaskEvidence> evidenceType(FillSourceType sourceType) {
        Objects.requireNonNull(sourceType, "sourceType 不能为空");
        return switch (sourceType) {
            case TYPICAL_VALUE -> FillTaskEvidence.Typical.class;
            case INTERPOLATION -> FillTaskEvidence.Interpolation.class;
        };
    }

    private void validateTypical(FillTaskEvidence.Typical evidence) {
        requireText(evidence.configId(), "configId");
        if (evidence.version() <= 0) {
            throw new IllegalArgumentException("version 必须大于 0");
        }
        Objects.requireNonNull(evidence.value(), "value 不能为空");
        requireText(evidence.unit(), "unit");
        requireText(evidence.algorithmVersion(), "algorithmVersion");
        if (evidence.validTo() != null && evidence.validTo() <= evidence.validFrom()) {
            throw new IllegalArgumentException("validTo 必须晚于 validFrom");
        }
        if (Math.floorMod(evidence.hourStart(), HOUR_MILLIS) != 0L) {
            throw new IllegalArgumentException("hourStart 必须对齐到自然小时");
        }
        List<FillTaskEvidence.MinuteSegment> segments =
                Objects.requireNonNull(evidence.appliedSegments(),
                        "appliedSegments 不能为空");
        Long previousEnd = null;
        for (FillTaskEvidence.MinuteSegment segment : segments) {
            validateSegment(segment, evidence.hourStart());
            if (previousEnd != null && segment.fromInclusive() < previousEnd) {
                throw new IllegalArgumentException(
                        "典型值应用区间必须按时间排序且不能重叠");
            }
            previousEnd = segment.toExclusive();
        }
    }

    private void validateInterpolation(FillTaskEvidence.Interpolation evidence) {
        requireMinuteAligned(evidence.leftMinute(), "leftMinute");
        requireMinuteAligned(evidence.rightMinute(), "rightMinute");
        if (evidence.rightMinute() <= evidence.leftMinute()) {
            throw new IllegalArgumentException("rightMinute 必须晚于 leftMinute");
        }
        if (!Double.isFinite(evidence.leftValue())
                || !Double.isFinite(evidence.rightValue())) {
            throw new IllegalArgumentException("插值端点必须是有限数值");
        }
        requireText(evidence.algorithmVersion(), "algorithmVersion");
    }

    private void validateSegment(
            FillTaskEvidence.MinuteSegment segment,
            long hourStart) {
        Objects.requireNonNull(segment, "appliedSegments 不能包含 null");
        requireMinuteAligned(segment.fromInclusive(), "segment.fromInclusive");
        requireMinuteAligned(segment.toExclusive(), "segment.toExclusive");
        if (segment.toExclusive() <= segment.fromInclusive()) {
            throw new IllegalArgumentException("分钟区间终点必须晚于起点");
        }
        if (segment.fromInclusive() < hourStart
                || segment.toExclusive() > hourStart + HOUR_MILLIS) {
            throw new IllegalArgumentException("典型值应用区间必须位于任务自然小时内");
        }
    }

    private void requireMinuteAligned(long timestamp, String field) {
        if (Math.floorMod(timestamp, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException(field + " 必须对齐到分钟");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
