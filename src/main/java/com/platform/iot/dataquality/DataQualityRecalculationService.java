package com.platform.iot.dataquality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.DataQualityProperties;
import com.platform.iot.aggregation.ManualRealMinuteAggregationService;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 执行一个已经被后台调度器领取的人工重算分块。
 *
 * <p>本服务位于 MySQL 重算游标、历史 Q0 聚合、Q1/Q2 选择和公式 READY 事件之间。
 * 每次调用最多处理一个自然小时：所有 TDengine 写入和公式事件全部成功后才原子
 * 推进游标；任一步失败都以原游标标记 FAILED，确保重复受理可以安全续跑。</p>
 *
 * <p>旧任务作废由调度器先通过 {@link RecalculationVoidService} 完成。本服务重新读取
 * 批次并只接受 {@code RUNNING/RECALCULATING}，避免跨库作废阶段被重复执行。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "recalculation-enabled"},
        havingValue = "true")
public class DataQualityRecalculationService {

    static final String EXECUTION_FAILURE_SUMMARY = "人工重算分块执行失败";

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final int MAX_POINTS = 100;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };
    private static final Comparator<RawMinuteAggregate> ROW_ORDER =
            Comparator.comparingLong(RawMinuteAggregate::minuteStart)
                    .thenComparing(RawMinuteAggregate::pointId);

    private final RecalculationJobRepository jobRepository;
    private final ManualRealMinuteAggregationService manualAggregator;
    private final ManualQualitySelectionService selectionService;
    private final HvacMinuteRepository minuteRepository;
    private final DataPointConfigProvider pointConfigProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final DataQualityProperties properties;
    private final ObjectMapper objectMapper;

    public DataQualityRecalculationService(
            RecalculationJobRepository jobRepository,
            ManualRealMinuteAggregationService manualAggregator,
            ManualQualitySelectionService selectionService,
            HvacMinuteRepository minuteRepository,
            DataPointConfigProvider pointConfigProvider,
            ApplicationEventPublisher eventPublisher,
            DataQualityProperties properties,
            ObjectMapper objectMapper) {
        this.jobRepository =
                Objects.requireNonNull(jobRepository, "jobRepository 不能为空");
        this.manualAggregator =
                Objects.requireNonNull(manualAggregator, "manualAggregator 不能为空");
        this.selectionService =
                Objects.requireNonNull(selectionService, "selectionService 不能为空");
        this.minuteRepository =
                Objects.requireNonNull(minuteRepository, "minuteRepository 不能为空");
        this.pointConfigProvider =
                Objects.requireNonNull(pointConfigProvider, "pointConfigProvider 不能为空");
        this.eventPublisher =
                Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 处理当前持久化游标指向的一个分块。
     *
     * <p>调用方必须先通过 MySQL 条件更新成功领取批次。方法内部发生的正常执行失败
     * 会自行写入固定公开摘要并返回；只有批次无法读取或失败状态也无法持久化时才向
     * 调度器抛出固定异常，由调度器执行最后兜底。</p>
     */
    public void processClaimedJob(String jobId, long now) {
        String normalizedJobId = requireText(jobId, "jobId");
        BizDataQualityRecalcJob job;
        try {
            job = jobRepository.findById(normalizedJobId)
                    .orElseThrow(() -> new IllegalStateException("人工重算批次不存在"));
        } catch (RuntimeException exception) {
            log.error("读取已领取人工重算批次失败: jobId={}", normalizedJobId, exception);
            throw new IllegalStateException(EXECUTION_FAILURE_SUMMARY);
        }

        LocalDateTime expectedCursor = job.getCursorMinute();
        try {
            processChunk(job, normalizedJobId, now);
        } catch (RuntimeException exception) {
            // SQL、JDBC、JSON 和证据原文只进入服务端错误日志；MySQL 审计字段固定摘要。
            log.error(
                    "人工重算分块执行失败: jobId={}, cursor={}",
                    normalizedJobId,
                    expectedCursor,
                    exception);
            if (expectedCursor == null) {
                throw new IllegalStateException(EXECUTION_FAILURE_SUMMARY);
            }
            try {
                jobRepository.markFailed(
                        normalizedJobId,
                        expectedCursor,
                        EXECUTION_FAILURE_SUMMARY);
            } catch (RuntimeException markException) {
                log.error(
                        "人工重算分块失败状态写入未生效: jobId={}, cursor={}",
                        normalizedJobId,
                        expectedCursor,
                        markException);
                throw new IllegalStateException(EXECUTION_FAILURE_SUMMARY);
            }
        }
    }

    private void processChunk(
            BizDataQualityRecalcJob job,
            String expectedJobId,
            long now) {
        ValidatedJob context = validateJob(job, expectedJobId, now);
        long chunkEnd = context.toExclusive() - context.cursor() > HOUR_MILLIS
                ? context.cursor() + HOUR_MILLIS
                : context.toExclusive();
        long padding = Math.multiplyExact(
                properties.getInterpolation().getMaxGapMinutes() + 1L,
                MINUTE_MILLIS);
        long contextFrom = context.cursor() - context.fromInclusive() > padding
                ? context.cursor() - padding
                : context.fromInclusive();
        long contextTo = context.toExclusive() - chunkEnd > padding
                ? chunkEnd + padding
                : context.toExclusive();

        List<RawMinuteAggregate> q0Rows = List.copyOf(
                Objects.requireNonNull(
                        manualAggregator.aggregate(
                                context.pointIds(),
                                contextFrom,
                                contextTo,
                                now),
                        "人工 Q0 聚合结果不能为空"));
        validateQ0Rows(q0Rows, context, contextFrom, contextTo);
        List<RawMinuteAggregate> q0RowsToWrite = q0Rows.stream()
                .filter(row -> row.minuteStart() >= context.cursor())
                .toList();
        // Q0 必须先按质量优先级写入，随后 selection 的唯一正式分钟查询才能把它
        // 作为短缺口真实端点。左侧上下文已经由前一成功 chunk 处理，不能重写其
        // finalizedAt 却不补发历史事件；这里只应用目标块和未来右端上下文。
        minuteRepository.saveAllWithQualityPriority(q0RowsToWrite, null);

        ManualQualitySelectionService.ChunkSelection selection =
                Objects.requireNonNull(
                        selectionService.selectAndPersist(
                                job,
                                context.points(),
                                context.cursor(),
                                chunkEnd,
                                contextFrom,
                                contextTo,
                                now),
                        "人工质量选择结果不能为空");
        validateStats(
                selection.finalStats(),
                context.pointIds().size(),
                context.cursor(),
                chunkEnd);

        publishTargetMinutes(
                context,
                context.cursor(),
                chunkEnd,
                now,
                q0Rows,
                selection.q1Rows(),
                selection.q2Rows());

        boolean finished = chunkEnd == context.toExclusive();
        jobRepository.advanceChunk(
                expectedJobId,
                job.getCursorMinute(),
                toLocal(chunkEnd),
                selection.finalStats(),
                finished,
                toLocal(now));
    }

    private ValidatedJob validateJob(
            BizDataQualityRecalcJob job,
            String expectedJobId,
            long now) {
        Objects.requireNonNull(job, "job 不能为空");
        if (!expectedJobId.equals(requireText(job.getJobId(), "job.jobId"))) {
            throw new IllegalStateException("重算批次ID与查询结果不一致");
        }
        if (job.getStatus() != RecalculationJobStatus.RUNNING
                || job.getPhase() != RecalculationJobPhase.RECALCULATING) {
            throw new IllegalStateException("重算批次尚未进入可重算阶段");
        }
        if (job.getJobType() == null) {
            throw new IllegalStateException("重算批次类型缺失");
        }
        if (job.getJobType() == RecalculationJobType.VOID_AND_RECALCULATE) {
            requireText(job.getSupersedesTaskId(), "supersedesTaskId");
        }
        String buildingId = requireText(job.getBuildingId(), "buildingId");
        long from = toEpoch(job.getFromMinute(), "fromMinute");
        long to = toEpoch(job.getToMinute(), "toMinute");
        long cursor = toEpoch(job.getCursorMinute(), "cursorMinute");
        requireAlignedRange(from, to, cursor);
        toLocal(now);

        List<String> requested = decodePointIds(job.getPointIdsJson());
        Map<String, PointRuntimeConfig> points =
                loadEligiblePoints(buildingId, requested);
        return new ValidatedJob(
                buildingId,
                new LinkedHashSet<>(requested),
                points,
                from,
                to,
                cursor);
    }

    private List<String> decodePointIds(String json) {
        try {
            List<String> decoded = objectMapper.readValue(json, STRING_LIST);
            if (decoded == null || decoded.isEmpty() || decoded.size() > MAX_POINTS) {
                throw new IllegalStateException("重算测点集合已损坏");
            }
            List<String> normalized = decoded.stream()
                    .map(pointId -> requireText(pointId, "pointId"))
                    .toList();
            TreeSet<String> sorted = new TreeSet<>(normalized);
            if (sorted.size() != normalized.size()
                    || !normalized.equals(List.copyOf(sorted))) {
                throw new IllegalStateException("重算测点集合未规范化");
            }
            return List.copyOf(normalized);
        } catch (JsonProcessingException
                 | IllegalArgumentException
                 | NullPointerException exception) {
            throw new IllegalStateException("重算测点集合已损坏");
        }
    }

    private Map<String, PointRuntimeConfig> loadEligiblePoints(
            String buildingId,
            List<String> requestedPointIds) {
        Collection<PointRuntimeConfig> configured = Objects.requireNonNull(
                pointConfigProvider.findAll(), "测点配置结果不能为空");
        Set<String> requested = Set.copyOf(requestedPointIds);
        Map<String, PointRuntimeConfig> candidates = new LinkedHashMap<>();
        for (PointRuntimeConfig point : configured) {
            if (point == null || !requested.contains(point.pointId())) {
                continue;
            }
            if (candidates.putIfAbsent(point.pointId(), point) != null) {
                throw new IllegalStateException("测点运行配置重复");
            }
        }

        Map<String, PointRuntimeConfig> eligible = new LinkedHashMap<>();
        for (String pointId : requestedPointIds) {
            PointRuntimeConfig point = candidates.get(pointId);
            if (point == null
                    || !buildingId.equals(point.buildingId())
                    || !"ONLINE".equalsIgnoreCase(point.status())
                    || !"ANALOG".equalsIgnoreCase(point.dataType())
                    || point.isForCalc() != 1) {
                // 受理后测点配置可能变化。静默忽略会使审计计数少于请求范围，
                // 因此当前分块失败，管理员修正配置后可从原游标恢复。
                throw new IllegalStateException("重算测点当前不具备计算资格");
            }
            eligible.put(pointId, point);
        }
        return Map.copyOf(eligible);
    }

    private void validateQ0Rows(
            List<RawMinuteAggregate> rows,
            ValidatedJob context,
            long contextFrom,
            long contextTo) {
        Set<PointMinute> seen = new LinkedHashSet<>();
        for (RawMinuteAggregate row : rows) {
            if (row == null
                    || row.dataQuality() != 0
                    || row.qualityTaskId() != null
                    || !context.pointIds().contains(row.pointId())
                    || !context.buildingId().equals(row.buildingId())
                    || row.minuteStart() < contextFrom
                    || row.minuteStart() >= contextTo
                    || Math.floorMod(row.minuteStart(), MINUTE_MILLIS) != 0L
                    || !seen.add(new PointMinute(
                    row.pointId(), row.minuteStart()))) {
                throw new IllegalStateException("人工 Q0 聚合结果不符合批次范围");
            }
        }
    }

    private void validateStats(
            RecalculationChunkStats stats,
            int pointCount,
            long targetFrom,
            long targetTo) {
        Objects.requireNonNull(stats, "finalStats 不能为空");
        long targetMinutes = (targetTo - targetFrom) / MINUTE_MILLIS;
        long expected = Math.multiplyExact(targetMinutes, pointCount);
        long actual = (long) stats.q0Count()
                + stats.q1Count()
                + stats.q2Count()
                + stats.missingCount();
        if (actual != expected) {
            throw new IllegalStateException("重算分块最终分类计数不完整");
        }
    }

    private void publishTargetMinutes(
            ValidatedJob context,
            long targetFrom,
            long targetTo,
            long finalizedAt,
            List<RawMinuteAggregate> q0Rows,
            List<RawMinuteAggregate> q1Rows,
            List<RawMinuteAggregate> q2Rows) {
        Map<Long, List<RawMinuteAggregate>> eventRows = new LinkedHashMap<>();
        addEventRows(eventRows, q0Rows, context, targetFrom, targetTo);
        addEventRows(eventRows, q1Rows, context, targetFrom, targetTo);
        addEventRows(eventRows, q2Rows, context, targetFrom, targetTo);
        eventRows.values().forEach(rows -> rows.sort(ROW_ORDER));

        for (long minute = targetFrom; minute < targetTo; minute += MINUTE_MILLIS) {
            eventPublisher.publishEvent(new HvacMinuteQualityReadyEvent(
                    minute,
                    finalizedAt,
                    QualityEventSource.MANUAL_RECALCULATION,
                    Set.of(context.buildingId()),
                    eventRows.getOrDefault(minute, List.of()),
                    context.pointIds()));
        }
    }

    private void addEventRows(
            Map<Long, List<RawMinuteAggregate>> eventRows,
            List<RawMinuteAggregate> rows,
            ValidatedJob context,
            long targetFrom,
            long targetTo) {
        for (RawMinuteAggregate row : Objects.requireNonNull(
                rows, "质量选择行不能为空")) {
            if (row != null
                    && context.pointIds().contains(row.pointId())
                    && context.buildingId().equals(row.buildingId())
                    && row.minuteStart() >= targetFrom
                    && row.minuteStart() < targetTo) {
                eventRows.computeIfAbsent(
                        row.minuteStart(), ignored -> new ArrayList<>()).add(row);
            }
        }
    }

    private void requireAlignedRange(long from, long to, long cursor) {
        if (Math.floorMod(from, MINUTE_MILLIS) != 0L
                || Math.floorMod(to, MINUTE_MILLIS) != 0L
                || Math.floorMod(cursor, MINUTE_MILLIS) != 0L
                || from >= to
                || cursor < from
                || cursor >= to) {
            throw new IllegalStateException("重算批次游标或时间范围无效");
        }
    }

    private long toEpoch(LocalDateTime value, String field) {
        if (value == null) {
            throw new IllegalStateException(field + " 缺失");
        }
        try {
            return value.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(field + " 超出允许范围");
        }
    }

    private LocalDateTime toLocal(long epochMillis) {
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("时间戳超出允许范围");
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " 不能为空");
        }
        return value.trim();
    }

    private record ValidatedJob(
            String buildingId,
            Set<String> pointIds,
            Map<String, PointRuntimeConfig> points,
            long fromInclusive,
            long toExclusive,
            long cursor) {

        private ValidatedJob {
            pointIds = Set.copyOf(pointIds);
            points = Map.copyOf(points);
        }
    }

    private record PointMinute(String pointId, long minuteStart) {
    }
}
