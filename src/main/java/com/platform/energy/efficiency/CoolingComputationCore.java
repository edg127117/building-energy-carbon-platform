package com.platform.energy.efficiency;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 以固定水物性参数计算制备冷量的无状态核心。
 *
 * <p>输入流量为 m3/h、温度为 degC，输出功率为 kW、冷量为 kWh。三路测点只在同一
 * 事件时间或已审核的有界前值保持规则下组合；被拒绝的样本会清除该路当前状态，不能被
 * 后续的“好点”跨越。</p>
 */
@Component
public class CoolingComputationCore {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MILLIS_PER_HOUR = new BigDecimal("3600000");

    /** 只允许三路精确同步，或使用已有审核证据的有界前值保持。 */
    public enum AlignmentMode {
        EXACT_SYNCHRONOUS,
        BOUNDED_PREVIOUS_HOLD
    }

    /** 用于保留三路输入在原始列表中的位置和物理角色。 */
    public enum InputRole {
        FLOW_M3_PER_H,
        SUPPLY_TEMPERATURE_DEG_C,
        RETURN_TEMPERATURE_DEG_C
    }

    /** 单路测点输入；{@code allowed=false} 的样本仍是时间断点。 */
    public record Sample(
            String id, Instant time, BigDecimal value, boolean allowed, String qualityEvidence) {
    }

    /**
     * 同一版本内固定的物性与时间处理规则。
     *
     * <p>选择 {@link AlignmentMode#BOUNDED_PREVIOUS_HOLD} 时，{@code approvalEvidence}
     * 必须引用已审核规则；本核心不会以默认水密度、比热或保持时长补齐缺失专业输入。</p>
     */
    @Schema(name = "EerpCoolingRules")
    public record Rules(
            String version,
            String approvalEvidence,
            BigDecimal rhoKgPerM3,
            BigDecimal cpKjPerKgK,
            BigDecimal minTemperatureDegC,
            BigDecimal maxTemperatureDegC,
            long maxHoldMillis,
            long maxSkewMillis,
            long maxGapMillis,
            boolean allowZeroFlow,
            boolean allowZeroDelta,
            AlignmentMode alignmentMode) {
    }

    /** 实际参与某个分段的输入位置、时间和保持时长。 */
    public record InputEvidence(
            InputRole role,
            int inputIndex,
            String sampleId,
            Instant eventTime,
            boolean previousValueHeld,
            long heldMillis,
            String qualityEvidence) {
    }

    /** 采用前值阶梯法的有效制冷功率半开区间。 */
    public record PowerInterval(
            Instant startInclusive,
            Instant endExclusive,
            BigDecimal powerKw,
            List<InputEvidence> inputs,
            String rulesVersion) {
        public PowerInterval {
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * 计算问题；有开始和结束时间的问题即一个明确无效半开区间。
     *
     * <p>{@code reasonCodes} 保留不计算该段的具体原因，{@code inputs} 保留当前有效值或
     * 断点样本在各自输入列表中的索引。</p>
     */
    public record Issue(
            String code,
            String message,
            Instant startInclusive,
            Instant endExclusive,
            List<String> reasonCodes,
            List<InputEvidence> inputs) {
        public Issue {
            reasonCodes = List.copyOf(reasonCodes);
            inputs = List.copyOf(inputs);
        }
    }

    /** 冷量、覆盖率、有效功率段和无效段证据组成的一次确定性计算结果。 */
    public record Calculation(
            BigDecimal totalKwh,
            BigDecimal coverageRatio,
            List<PowerInterval> powerIntervals,
            List<Issue> issues,
            boolean complete) {
        public Calculation {
            powerIntervals = List.copyOf(powerIntervals);
            issues = List.copyOf(issues);
        }
    }

    /**
     * 按三路事件、保持到期、最大积分间隔和查询边界拆分冷量。
     *
     * @param from 半开计算区间起点，必须具有毫秒精度
     * @param to 半开计算区间终点，必须具有毫秒精度
     */
    public Calculation calculate(
            Instant from,
            Instant to,
            List<Sample> flow,
            List<Sample> supply,
            List<Sample> returnSamples,
            Rules rules) {
        requireRange(from, to);
        if (!millisecondPrecision(from) || !millisecondPrecision(to)) {
            return invalidWhole(from, to, "TIME_PRECISION_UNSUPPORTED", "计算边界必须精确到毫秒");
        }
        List<String> ruleErrors = validateRules(rules);
        if (!ruleErrors.isEmpty()) {
            return invalidWhole(from, to, "RULES_INVALID", "水物性或时间处理规则不完整", ruleErrors);
        }
        if (flow == null || supply == null || returnSamples == null) {
            return invalidWhole(from, to, "INPUT_LIST_MISSING", "三路测点输入列表不能为空");
        }

        Normalization flowValues = normalize(InputRole.FLOW_M3_PER_H, flow, rules);
        Normalization supplyValues = normalize(InputRole.SUPPLY_TEMPERATURE_DEG_C, supply, rules);
        Normalization returnValues = normalize(InputRole.RETURN_TEMPERATURE_DEG_C, returnSamples, rules);
        List<Issue> globalIssues = new ArrayList<>();
        globalIssues.addAll(flowValues.globalIssues());
        globalIssues.addAll(supplyValues.globalIssues());
        globalIssues.addAll(returnValues.globalIssues());
        if (!globalIssues.isEmpty()) {
            List<Issue> scopedIssues = globalIssues.stream().map(issue -> new Issue(
                    issue.code(), issue.message(), from, to, issue.reasonCodes(), issue.inputs())).toList();
            return new Calculation(BigDecimal.ZERO, BigDecimal.ZERO, List.of(), scopedIssues, false);
        }

        Map<Instant, List<NormalizedSample>> events = eventsByTime(
                flowValues.samples(), supplyValues.samples(), returnValues.samples());
        TreeSet<Instant> nodes = nodes(from, to, events, rules);
        EnumMap<InputRole, ChannelState> states = new EnumMap<>(InputRole.class);
        for (InputRole role : InputRole.values()) states.put(role, new ChannelState(role));

        for (Map.Entry<Instant, List<NormalizedSample>> entry : events.entrySet()) {
            if (!entry.getKey().isBefore(from)) break;
            applyAt(states, entry.getValue());
        }

        List<PowerInterval> intervals = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        BigDecimal totalKwh = BigDecimal.ZERO;
        long coveredMillis = 0;
        List<Instant> orderedNodes = new ArrayList<>(nodes);
        for (int index = 0; index < orderedNodes.size() - 1; index++) {
            Instant start = orderedNodes.get(index);
            Instant end = orderedNodes.get(index + 1);
            applyAt(states, events.get(start));
            long durationMillis = millisBetween(start, end);
            SegmentDecision decision = decide(start, states, rules);
            if (!decision.valid()) {
                addIssue(issues, new Issue("INVALID_INTERVAL", "该时间段没有满足冷量计算门禁的三路输入",
                        start, end, decision.reasonCodes(), decision.inputs()));
                continue;
            }
            BigDecimal energy = decision.powerKw().multiply(BigDecimal.valueOf(durationMillis), MC)
                    .divide(MILLIS_PER_HOUR, MC);
            totalKwh = totalKwh.add(energy, MC);
            coveredMillis = Math.addExact(coveredMillis, durationMillis);
            intervals.add(new PowerInterval(start, end, decision.powerKw(), decision.inputs(), rules.version()));
        }
        long targetMillis = millisBetween(from, to);
        BigDecimal coverage = BigDecimal.valueOf(coveredMillis)
                .divide(BigDecimal.valueOf(targetMillis), MC);
        return new Calculation(totalKwh, coverage, intervals, issues,
                coveredMillis == targetMillis && issues.isEmpty());
    }

    public static List<String> validateRules(Rules rules) {
        List<String> errors = new ArrayList<>();
        if (rules == null) {
            errors.add("RULES_MISSING");
            return errors;
        }
        if (blank(rules.version())) errors.add("RULE_VERSION_MISSING");
        if (rules.alignmentMode() == null) errors.add("ALIGNMENT_MODE_MISSING");
        if (rules.rhoKgPerM3() == null || rules.rhoKgPerM3().signum() <= 0) errors.add("RHO_INVALID");
        if (rules.cpKjPerKgK() == null || rules.cpKjPerKgK().signum() <= 0) errors.add("CP_INVALID");
        if (rules.minTemperatureDegC() == null || rules.maxTemperatureDegC() == null
                || rules.minTemperatureDegC().compareTo(rules.maxTemperatureDegC()) > 0) {
            errors.add("TEMPERATURE_RANGE_INVALID");
        }
        if (rules.maxSkewMillis() < 0) errors.add("MAX_SKEW_INVALID");
        if (rules.maxGapMillis() <= 0) errors.add("MAX_GAP_INVALID");
        if (rules.maxHoldMillis() < 0) errors.add("MAX_HOLD_INVALID");
        if (rules.alignmentMode() == AlignmentMode.BOUNDED_PREVIOUS_HOLD) {
            if (rules.maxHoldMillis() <= 0) errors.add("MAX_HOLD_REQUIRED");
            if (blank(rules.approvalEvidence())) errors.add("HOLD_APPROVAL_EVIDENCE_MISSING");
        }
        return errors;
    }

    private static Normalization normalize(InputRole role, List<Sample> source, Rules rules) {
        List<NormalizedSample> values = new ArrayList<>();
        List<Issue> globalIssues = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Sample sample = source.get(index);
            if (sample == null || sample.time() == null || !millisecondPrecision(sample.time())) {
                globalIssues.add(globalIssue("INPUT_TIME_INVALID", role + " 存在缺失或非毫秒精度时间"));
                continue;
            }
            NormalizedSample value = new NormalizedSample(role, index, sample);
            String problem = sampleProblem(role, sample, rules);
            if (problem != null) value.reject(problem);
            values.add(value);
        }
        values.sort(Comparator.comparing(NormalizedSample::time).thenComparingInt(NormalizedSample::index));
        rejectDuplicates(values);
        return new Normalization(values, globalIssues);
    }

    private static String sampleProblem(InputRole role, Sample sample, Rules rules) {
        if (blank(sample.id())) return "SAMPLE_ID_MISSING";
        if (blank(sample.qualityEvidence())) return "QUALITY_EVIDENCE_MISSING";
        if (!sample.allowed()) return "QUALITY_REJECTED";
        if (sample.value() == null) return "VALUE_MISSING";
        if (role == InputRole.FLOW_M3_PER_H) {
            if (sample.value().signum() < 0) return "NEGATIVE_FLOW";
            if (sample.value().signum() == 0 && !rules.allowZeroFlow()) return "ZERO_FLOW_NOT_ALLOWED";
            return null;
        }
        if (sample.value().compareTo(rules.minTemperatureDegC()) < 0
                || sample.value().compareTo(rules.maxTemperatureDegC()) > 0) {
            return "TEMPERATURE_OUT_OF_RANGE";
        }
        return null;
    }

    private static void rejectDuplicates(List<NormalizedSample> values) {
        Map<String, List<NormalizedSample>> byId = new HashMap<>();
        Map<Instant, List<NormalizedSample>> byTime = new HashMap<>();
        for (NormalizedSample value : values) {
            if (!blank(value.sample().id())) byId.computeIfAbsent(value.sample().id(), ignored -> new ArrayList<>())
                    .add(value);
            byTime.computeIfAbsent(value.time(), ignored -> new ArrayList<>()).add(value);
        }
        byId.values().stream().filter(group -> group.size() > 1)
                .forEach(group -> group.forEach(value -> value.reject("DUPLICATE_SAMPLE_ID")));
        byTime.values().stream().filter(group -> group.size() > 1)
                .forEach(group -> group.forEach(value -> value.reject("DUPLICATE_TIMESTAMP")));
    }

    @SafeVarargs
    private static Map<Instant, List<NormalizedSample>> eventsByTime(
            List<NormalizedSample>... streams) {
        Map<Instant, List<NormalizedSample>> events = new HashMap<>();
        for (List<NormalizedSample> stream : streams) {
            for (NormalizedSample value : stream) {
                events.computeIfAbsent(value.time(), ignored -> new ArrayList<>()).add(value);
            }
        }
        return events.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().sorted(Comparator.comparing(NormalizedSample::role)
                                .thenComparingInt(NormalizedSample::index)).toList(),
                        (left, right) -> left, java.util.TreeMap::new));
    }

    private static TreeSet<Instant> nodes(
            Instant from, Instant to, Map<Instant, List<NormalizedSample>> events, Rules rules) {
        TreeSet<Instant> values = new TreeSet<>();
        values.add(from);
        values.add(to);
        for (Map.Entry<Instant, List<NormalizedSample>> entry : events.entrySet()) {
            Instant time = entry.getKey();
            if (!time.isBefore(from) && time.isBefore(to)) values.add(time);
            for (NormalizedSample sample : entry.getValue()) {
                if (!sample.valid() || !time.isBefore(to)) continue;
                if (rules.alignmentMode() == AlignmentMode.BOUNDED_PREVIOUS_HOLD) {
                    addExpiry(values, time, rules.maxHoldMillis(), from, to);
                }
                addExpiry(values, time, rules.maxGapMillis(), from, to);
            }
        }
        return values;
    }

    private static void addExpiry(
            TreeSet<Instant> nodes, Instant sampleTime, long durationMillis, Instant from, Instant to) {
        if (durationMillis <= 0 || !sampleTime.isBefore(to)) return;
        long remaining = millisBetween(sampleTime, to);
        if (durationMillis >= remaining) return;
        Instant expiry = sampleTime.plusMillis(durationMillis);
        if (expiry.isAfter(from)) nodes.add(expiry);
    }

    private static void applyAt(
            EnumMap<InputRole, ChannelState> states, List<NormalizedSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        for (InputRole role : InputRole.values()) {
            List<NormalizedSample> sameRole = samples.stream().filter(value -> value.role() == role).toList();
            if (!sameRole.isEmpty()) states.get(role).apply(sameRole);
        }
    }

    private static SegmentDecision decide(
            Instant start, EnumMap<InputRole, ChannelState> states, Rules rules) {
        ChannelState flow = states.get(InputRole.FLOW_M3_PER_H);
        ChannelState supply = states.get(InputRole.SUPPLY_TEMPERATURE_DEG_C);
        ChannelState returns = states.get(InputRole.RETURN_TEMPERATURE_DEG_C);
        List<String> reasons = new ArrayList<>();
        List<InputEvidence> inputs = new ArrayList<>();
        collectUnavailable(flow, start, reasons, inputs);
        collectUnavailable(supply, start, reasons, inputs);
        collectUnavailable(returns, start, reasons, inputs);
        if (!reasons.isEmpty()) return SegmentDecision.invalid(reasons, inputs);

        List<NormalizedSample> values = List.of(flow.active(), supply.active(), returns.active());
        for (NormalizedSample value : values) inputs.add(evidence(value, start));
        long latest = values.stream().mapToLong(value -> value.time().toEpochMilli()).max().orElseThrow();
        long earliest = values.stream().mapToLong(value -> value.time().toEpochMilli()).min().orElseThrow();
        boolean integrationGapExceeded = false;
        for (NormalizedSample value : values) {
            if (millisBetween(value.time(), start) >= rules.maxGapMillis()) {
                integrationGapExceeded = true;
                reasons.add(value.role().name() + "_INTEGRATION_GAP_EXCEEDED");
            }
        }
        if (integrationGapExceeded) {
            // 最大积分间隔约束每一路输入；一路更新不能延长另一条保持值的可用期。
            reasons.add("INTEGRATION_GAP_EXCEEDED");
        }
        if (rules.alignmentMode() == AlignmentMode.EXACT_SYNCHRONOUS) {
            if (latest != earliest) reasons.add("EXACT_SYNCHRONOUS_ALIGNMENT_REQUIRED");
        } else {
            if (latest - earliest > rules.maxSkewMillis()) reasons.add("ALIGNMENT_SKEW_EXCEEDED");
            for (NormalizedSample value : values) {
                if (millisBetween(value.time(), start) >= rules.maxHoldMillis()) {
                    reasons.add(value.role().name() + "_HOLD_EXPIRED");
                }
            }
        }
        BigDecimal delta = returns.active().sample().value().subtract(supply.active().sample().value(), MC);
        if (delta.signum() < 0) reasons.add("REVERSE_TEMPERATURE_DELTA");
        if (delta.signum() == 0 && !rules.allowZeroDelta()) reasons.add("ZERO_DELTA_NOT_ALLOWED");
        if (!reasons.isEmpty()) return SegmentDecision.invalid(reasons, inputs);
        BigDecimal power = rules.rhoKgPerM3().multiply(rules.cpKjPerKgK(), MC)
                .multiply(flow.active().sample().value(), MC)
                .multiply(delta, MC)
                .divide(new BigDecimal("3600"), MC);
        return SegmentDecision.valid(power, inputs);
    }

    private static void collectUnavailable(
            ChannelState state, Instant at, List<String> reasons, List<InputEvidence> inputs) {
        if (state.active() != null) return;
        if (state.blockers().isEmpty()) {
            reasons.add(state.role().name() + "_MISSING");
            return;
        }
        for (NormalizedSample blocker : state.blockers()) {
            reasons.add(state.role().name() + "_" + blocker.rejectionCode());
            inputs.add(evidence(blocker, at));
        }
    }

    private static InputEvidence evidence(NormalizedSample sample, Instant at) {
        long heldMillis = Math.max(0, millisBetween(sample.time(), at));
        return new InputEvidence(sample.role(), sample.index(), sample.sample().id(), sample.time(),
                heldMillis > 0, heldMillis, sample.sample().qualityEvidence());
    }

    private static Calculation invalidWhole(Instant from, Instant to, String code, String message) {
        return invalidWhole(from, to, code, message, List.of(code));
    }

    private static Calculation invalidWhole(
            Instant from, Instant to, String code, String message, List<String> reasons) {
        Issue issue = new Issue(code, message, from, to, reasons, List.of());
        return new Calculation(BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(issue), false);
    }

    private static Issue globalIssue(String code, String message) {
        return new Issue(code, message, null, null, List.of(code), List.of());
    }

    private static void addIssue(List<Issue> issues, Issue candidate) {
        if (issues.isEmpty()) {
            issues.add(candidate);
            return;
        }
        Issue previous = issues.getLast();
        if (Objects.equals(previous.code(), candidate.code())
                && Objects.equals(previous.message(), candidate.message())
                && Objects.equals(previous.endExclusive(), candidate.startInclusive())
                && previous.reasonCodes().equals(candidate.reasonCodes())
                && previous.inputs().equals(candidate.inputs())) {
            issues.set(issues.size() - 1, new Issue(previous.code(), previous.message(),
                    previous.startInclusive(), candidate.endExclusive(), previous.reasonCodes(), previous.inputs()));
            return;
        }
        issues.add(candidate);
    }

    private static void requireRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("计算区间必须是有效半开区间");
        }
    }

    private static boolean millisecondPrecision(Instant value) {
        return value.getNano() % 1_000_000 == 0;
    }

    private static long millisBetween(Instant from, Instant to) {
        long millis = Math.subtractExact(to.toEpochMilli(), from.toEpochMilli());
        if (millis < 0) throw new IllegalArgumentException("时间必须递增");
        return millis;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Normalization(List<NormalizedSample> samples, List<Issue> globalIssues) {
    }

    private static final class NormalizedSample {
        private final InputRole role;
        private final int index;
        private final Sample sample;
        private boolean valid = true;
        private String rejectionCode;

        private NormalizedSample(InputRole role, int index, Sample sample) {
            this.role = role;
            this.index = index;
            this.sample = sample;
        }

        private InputRole role() {
            return role;
        }

        private int index() {
            return index;
        }

        private Sample sample() {
            return sample;
        }

        private Instant time() {
            return sample.time();
        }

        private boolean valid() {
            return valid;
        }

        private String rejectionCode() {
            return rejectionCode;
        }

        private void reject(String code) {
            valid = false;
            rejectionCode = code;
        }
    }

    private static final class ChannelState {
        private final InputRole role;
        private NormalizedSample active;
        private List<NormalizedSample> blockers = List.of();

        private ChannelState(InputRole role) {
            this.role = role;
        }

        private void apply(List<NormalizedSample> samples) {
            List<NormalizedSample> rejected = samples.stream().filter(sample -> !sample.valid()).toList();
            if (!rejected.isEmpty()) {
                active = null;
                blockers = rejected;
                return;
            }
            active = samples.getFirst();
            blockers = List.of();
        }

        private InputRole role() {
            return role;
        }

        private NormalizedSample active() {
            return active;
        }

        private List<NormalizedSample> blockers() {
            return blockers;
        }
    }

    private record SegmentDecision(
            boolean valid, BigDecimal powerKw, List<String> reasonCodes, List<InputEvidence> inputs) {
        private static SegmentDecision valid(BigDecimal powerKw, List<InputEvidence> inputs) {
            return new SegmentDecision(true, powerKw, List.of(), List.copyOf(inputs));
        }

        private static SegmentDecision invalid(List<String> reasons, List<InputEvidence> inputs) {
            return new SegmentDecision(false, null, List.copyOf(reasons), List.copyOf(inputs));
        }
    }
}
