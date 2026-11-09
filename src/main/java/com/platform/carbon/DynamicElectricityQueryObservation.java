package com.platform.carbon;

import com.platform.audit.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 请求线程内的只读观测；不持有数据正文、授权或连接，也不建立调度器或查询期限。 */
final class DynamicElectricityQueryObservation implements AutoCloseable {
    enum Phase {
        STORED_RESULTS, STORED_HEADERS, STORED_BODY, STORED_VALIDATION, STORED_GET_BYTES, STORED_COMPRESSED_DECODE, DEVICE_READS,
        METADATA, FACTORS, CANDIDATE_HEADERS, CURRENT_RAW_BODIES, CURRENT_SOURCE_LEGACY,
        DAY_LOAD, VIEW_ASSEMBLY
    }
    enum Count { STORED_ROWS, STORED_BYTES, FACTOR_ROWS, CANDIDATE_ROWS, CURRENT_RAW_ROWS, DAY_FALLBACKS, STORED_PAIR_QUERIES, STORED_COMPRESSED_ROWS, STORED_FRAME_BYTES }

    static final int MAX_EVENTS = 32;
    static final long PROGRESS_NANOS = Duration.ofSeconds(10).toNanos();
    private static final int MAX_PROGRESS_EVENTS = MAX_EVENTS - 2 - 2 * Phase.values().length;
    private static final Logger LOG = LoggerFactory.getLogger(DynamicElectricityQueryObservation.class);
    private static final ThreadLocal<DynamicElectricityQueryObservation> CURRENT = new ThreadLocal<>();

    private final DynamicElectricityQueryObservation previous;
    private final LongSupplier clock;
    private final Consumer<String> sink;
    private final String traceId;
    private final String queryId = UUID.randomUUID().toString();
    private final long startedAt;
    private final long[] started = new long[Phase.values().length];
    private final long[] completed = new long[Phase.values().length];
    private final long[] failed = new long[Phase.values().length];
    private final long[] inclusiveNanos = new long[Phase.values().length];
    private final long[] counts = new long[Count.values().length];
    private long lastEventAt;
    private int progressEvents;
    private String periodType = "UNVALIDATED";
    private String period = "UNVALIDATED";
    private boolean successful;
    private boolean closed;

    static DynamicElectricityQueryObservation open() {
        return open(System::nanoTime, message -> LOG.info("dynamic_electricity_query {}", message), TraceContext.current());
    }

    static DynamicElectricityQueryObservation open(LongSupplier clock, Consumer<String> sink, String traceId) {
        return new DynamicElectricityQueryObservation(clock, sink, traceId);
    }

    private DynamicElectricityQueryObservation(LongSupplier clock, Consumer<String> sink, String traceId) {
        this.previous = CURRENT.get();
        this.clock = clock;
        this.sink = sink;
        this.traceId = traceId != null && traceId.matches("[A-Za-z0-9_-]{1,128}") ? traceId : "unavailable";
        this.startedAt = clock.getAsLong();
        this.lastEventAt = startedAt;
        CURRENT.set(this);
        emit("request_begin", null, startedAt);
    }

    static <T> T observe(Phase phase, Supplier<T> action) {
        var observation = CURRENT.get();
        return observation == null ? action.get() : observation.measure(phase, action::get);
    }

    @FunctionalInterface private interface CheckedSupplier<T, E extends Exception> { T get() throws E; }
    @FunctionalInterface interface JdbcSupplier<T> { T get() throws java.sql.SQLException; }
    static <T> T observeJdbc(Phase phase, JdbcSupplier<T> action) throws java.sql.SQLException {
        var observation = CURRENT.get();
        return observation == null ? action.get() : observation.measure(phase, action::get);
    }

    // Fixed aggregate fields only: no additional events or thread-local state.
    enum JdbcBoundary { QUERY_ENTRY_TO_STATEMENT_READY, STATEMENT_READY_TO_FIRST_ROW }
    private final long[][] jdbcStats = new long[JdbcBoundary.values().length][5];
    static JdbcTiming storedJdbcTiming() { return new JdbcTiming(CURRENT.get()); }

    /** A single statement/batch, never a per-row timer. These are mixed JDBC boundaries,
     * not pure connection/SQL costs. The setter ends after Spring's normal argument binding.
     * The second boundary ends before the first row's business validation. Empty results
     * have their own terminal count and do not claim that a first row was observed. */
    static final class JdbcTiming {
        private final DynamicElectricityQueryObservation owner;
        private long begin;
        private int boundary;
        private boolean done;
        private JdbcTiming(DynamicElectricityQueryObservation owner) {
            this.owner = owner;
            if (owner != null) { begin = owner.clock.getAsLong(); owner.jdbcStats[0][0]++; }
        }
        void statementReady() {
            if (owner == null || done || boundary != 0) return;
            end(1); boundary = 1; begin = owner.clock.getAsLong(); owner.jdbcStats[1][0]++;
        }
        void firstRow() {
            if (owner == null || done || boundary != 1) return;
            end(1); done = true;
        }
        void finish(boolean returned) {
            if (owner == null || done) return;
            // completed=1, failed=2, no-row=3; elapsed nanos=4. A mock that skips
            // the setter must not manufacture a successful preparation boundary.
            end(returned && boundary == 1 ? 3 : 2); done = true;
        }
        private void end(int outcome) {
            long[] stats = owner.jdbcStats[boundary]; stats[outcome]++;
            stats[4] += Math.max(0, owner.clock.getAsLong() - begin);
        }
    }

    static void count(Count count, long amount) {
        var observation = CURRENT.get();
        if (observation != null) observation.counts[count.ordinal()] += amount;
    }

    static void countRows(Count count, Collection<?> rows) {
        var observation = CURRENT.get();
        // 只观察已有集合；无观测范围或无返回值时，不求值也不改变原返回路径。
        if (observation != null && rows != null) observation.counts[count.ordinal()] += rows.size();
    }

    private <T, E extends Exception> T measure(Phase phase, CheckedSupplier<T, E> action) throws E {
        int index = phase.ordinal();
        long begin = clock.getAsLong();
        // started 在动作前增加。节流的 begin 仅表示最近一次观测，不保证是当前正在执行的 SQL。
        if (++started[index] == 1) emit("phase_begin", phase, begin);
        else if (progressEvents < MAX_PROGRESS_EVENTS && begin - lastEventAt >= PROGRESS_NANOS) {
            progressEvents++;
            emit("progress_before_operation", phase, begin);
        }
        boolean returned = false;
        try {
            T value = action.get();
            returned = true;
            completed[index]++;
            return value;
        } finally {
            if (!returned) failed[index]++;
            long end = clock.getAsLong();
            inclusiveNanos[index] += Math.max(0, end - begin);
            if (completed[index] + failed[index] == 1) emit("phase_end", phase, end);
        }
    }

    void success() { successful = true; }

    void validatedPeriod(String type, Instant firstBucketStart) {
        // 从已获授权且校验通过的桶边界重建标签，不把原始请求参数写入日志。
        var local = firstBucketStart.atZone(ZoneId.of("Asia/Shanghai"));
        switch (type) {
            case "YEAR" -> { periodType = "YEAR"; period = Integer.toString(local.getYear()); }
            case "MONTH" -> { periodType = "MONTH"; period = YearMonth.from(local).toString(); }
            case "DAY" -> { periodType = "DAY"; period = local.toLocalDate().toString(); }
            default -> { }
        }
    }

    private void emit(String event, Phase phase, long now) {
        lastEventAt = now;
        try {
            var message = new StringBuilder(1024).append("event=").append(event)
                    .append(" traceId=").append(traceId).append(" queryId=").append(queryId)
                    .append(" periodType=").append(periodType).append(" period=").append(period)
                    .append(" elapsedMs=").append(Math.max(0, now - startedAt) / 1_000_000)
                    .append(" observedPhase=").append(phase == null ? "NONE" : phase)
                    .append(" success=").append(successful)
                    .append(" timing=inclusive_not_additive phaseStatsFormat=started/completed/failed/inclusiveMs phaseStats=");
            // 固定枚举限定内容与大小；只复制数字，不编码或解析任何业务正文。
            for (var item : Phase.values()) {
                int index = item.ordinal();
                message.append(item).append(':').append(started[index]).append('/')
                        .append(completed[index]).append('/').append(failed[index]).append('/')
                        .append(inclusiveNanos[index] / 1_000_000).append(',');
            }
            message.append(" jdbcBoundaryStatsFormat=started/completed/failed/noRow/inclusiveMs jdbcBoundaryStats=");
            for (var boundary : JdbcBoundary.values()) {
                long[] stats = jdbcStats[boundary.ordinal()];
                message.append(boundary).append(':').append(stats[0]).append('/')
                        .append(stats[1]).append('/').append(stats[2]).append('/')
                        .append(stats[3]).append('/').append(stats[4] / 1_000_000).append(',');
            }
            message.append(" countStats=");
            for (var item : Count.values()) message.append(item).append(':').append(counts[item.ordinal()]).append(',');
            sink.accept(message.toString());
        } catch (RuntimeException ignored) {
            // 日志接收器失败不能改变业务结果或掩盖原始异常。
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            emit("request_end", null, clock.getAsLong());
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
