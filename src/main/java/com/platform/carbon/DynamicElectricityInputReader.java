package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationCore;
import com.platform.energy.classification.ClassificationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 动态因子计算的只读电量入口。它复用已生效的计量边界、总分表去重和项目排除，
 * 只产生外购电力的监测输入，不计算排放或把来源标记为现场验收。
 */
@Service
public class DynamicElectricityInputReader {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration STANDALONE_SESSION_TIMEOUT = Duration.ofMinutes(5);
    private static final String CONTRACT = "DYNAMIC_ELECTRICITY_INPUT_V1";

    private final HourlyCarbonInputService hourlyInputs;
    private final CarbonProjectService projects;
    private final CarbonProjectCore projectCore;
    private final ClassificationService classifications;

    public DynamicElectricityInputReader(HourlyCarbonInputService hourlyInputs,
                                         CarbonProjectService projects,
                                         CarbonProjectCore projectCore,
                                         ClassificationService classifications) {
        this.hourlyInputs = hourlyInputs;
        this.projects = projects;
        this.projectCore = projectCore;
        this.classifications = classifications;
    }

    public record Contribution(String pointId, Set<String> ancestors, BigDecimal electricityKwh,
                               List<String> evidenceIds) { }

    public record Input(List<Contribution> contributions, String evidenceHash, JsonNode evidence) { }

    public Input read(long user, Collection<String> roles, String building, Instant from, Instant to, String grain) {
        return session().read(user, roles, building, from, to, grain);
    }

    /**
     * 返回仅供一次命令使用的读取会话。小时路径按北京时间自然日缓存来源快照，
     * 因而同日逐小时读取不会重复读取整日原始事实，也不会跨日复用。
     */
    public Session session() {
        return session("REVIEWED_INTERVAL");
    }

    /** 仅选择既有小时来源策略；日/月投影仍始终复用封账/审核分类来源。 */
    public Session session(String sourceKind) {
        if (sourceKind == null || !Set.of("REVIEWED_INTERVAL", "RAW_CUMULATIVE").contains(sourceKind)) {
            throw problem("DYNAMIC_ELECTRICITY_SOURCE_KIND_INVALID", "动态电量小时来源类型无效");
        }
        return new Session(sourceKind);
    }

    public final class Session {
        private final String sourceKind;
        private final Instant asOf = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        private final Map<LocalDate, HourlyCarbonInputService.Capture> hourlyCaptures = new HashMap<>();
        private CarbonCalculationDeadline standaloneDeadline;
        private Long sessionUser;
        private Set<String> sessionRoles;
        private String sessionBuilding;
        private Instant reviewedCacheFrom;
        private Instant reviewedCacheTo;

        private Session(String sourceKind) {
            this.sourceKind = sourceKind;
        }

        /** 仅正式动态命令中完整覆盖的已结束自然日可共享审核来源；零散小时保留原读取边界。 */
        void cacheReviewedDays(Instant from,Instant to) {
            if(sessionUser!=null || reviewedCacheFrom!=null)
                throw problem("DYNAMIC_ELECTRICITY_SESSION_IDENTITY","动态电量会话读取开始后不能改变缓存范围");
            if(from==null || to==null || !to.isAfter(from))
                throw problem("DYNAMIC_ELECTRICITY_SESSION_IDENTITY","动态电量缓存范围无效");
            reviewedCacheFrom=from;reviewedCacheTo=to;
        }

        /** 顺序批量计算已经封存前一日证据后释放日捕获；同一日的逐小时读取仍复用同一快照。 */
        void releaseHourlyCapturesBefore(Instant from) {
            LocalDate day=from.atZone(ZONE).toLocalDate();
            hourlyCaptures.keySet().removeIf(value->value.isBefore(day));
        }

        void releaseHourlyCaptures(){hourlyCaptures.clear();}

        public Input read(long user, Collection<String> roles, String building,
                          Instant from, Instant to, String grain) {
            validateWindow(building, from, to, grain, asOf);
            pinIdentity(user, roles, building);
            CarbonCalculationDeadline deadline = deadline();
            try (var ignored = deadline.bind()) {
                deadline.requireRemaining();
                return "HOUR".equals(grain)
                        ? readHour(user, roles, building, from, to, deadline)
                        : readPeriod(user, roles, building, from, to, grain);
            }
        }

        private CarbonCalculationDeadline deadline() {
            Duration remaining = CarbonCalculationDeadline.currentRemaining();
            if (remaining != null) return CarbonCalculationDeadline.start(remaining);
            if (standaloneDeadline == null) {
                standaloneDeadline = CarbonCalculationDeadline.start(STANDALONE_SESSION_TIMEOUT);
            }
            return standaloneDeadline;
        }

        private Input readHour(long user, Collection<String> roles, String building,
                               Instant from, Instant to, CarbonCalculationDeadline deadline) {
            LocalDate day = from.atZone(ZONE).toLocalDate();
            Instant dayStart = day.atStartOfDay(ZONE).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(ZONE).toInstant();
            boolean cacheWholeDay="REVIEWED_INTERVAL".equals(sourceKind) && reviewedCacheFrom!=null
                    && !dayStart.isBefore(reviewedCacheFrom) && !dayEnd.isAfter(reviewedCacheTo) && !dayEnd.isAfter(asOf);
            HourlyCarbonInputService.Capture capture = hourlyCaptures.computeIfAbsent(day,
                    ignored -> new HourlyCarbonInputService.Capture(cacheWholeDay));
            var request = new HourlyCarbonContracts.Request(building, day, asOf,
                    "dynamic-electricity-read-" + day, sourceKind, "SOURCE_LABELLED",
                    "DYNAMIC_ELECTRICITY_READ", "DEVELOPMENT_REFERENCE");
            var upstream = hourlyInputs.read(user, roles, request, from, to, dayStart, dayEnd, capture, deadline);
            List<Contribution> contributions = hourlyContributions(upstream);

            ObjectNode evidence = baseEvidence("HOUR", from, to);
            evidence.put("sourceKind", sourceKind);
            evidence.put("calculationAsOf", asOf.toString());
            var source = evidence.putObject("hourlyInput");
            source.set("evidence", requireEvidence(upstream).deepCopy());
            source.set("activities", CarbonEvidence.tree(upstream.activities()));
            source.set("sourceSnapshotIds", CarbonEvidence.tree(sorted(upstream.sourceSnapshotIds())));
            source.set("qualityPolicyVersions", CarbonEvidence.tree(sorted(upstream.qualityPolicyVersions())));
            source.set("projectVersionIds", CarbonEvidence.tree(sorted(upstream.projectVersionIds())));
            source.set("relationVersionIds", CarbonEvidence.tree(sorted(upstream.relationVersionIds())));
            evidence.set("contexts", CarbonEvidence.tree(referencedContexts(upstream.evidence(), capture.contexts())));
            return result(evidence, contributions);
        }

        private Input readPeriod(long user, Collection<String> roles, String building,
                                 Instant from, Instant to, String grain) {
            var project = projects.effective(building, from, to);
            if (project == null) {
                throw problem("DYNAMIC_ELECTRICITY_PROJECT_REQUIRED", "该时段没有覆盖完整期间的外购电力项目配置");
            }
            List<String> energyCodes = project.configuration().energies().stream()
                    .filter(energy -> "ENABLED".equals(energy.capability())
                            && "PURCHASED_ELECTRICITY".equals(energy.pathway()))
                    .map(CarbonProjectContracts.Energy::energyItemCode)
                    .distinct().sorted().toList();
            if (energyCodes.isEmpty() || energyCodes.size() > 16) {
                throw problem("DYNAMIC_ELECTRICITY_PROJECT_REQUIRED", "外购电力未启用或超过16种能源");
            }
            String classificationId = project.configuration().classificationVersionId();
            var classification = classifications.effectiveVersion(user, roles, building, from, to, classificationId);
            if (!classificationId.equals(classification.versionId()) || classification.configuration() == null
                    || classification.configuration().schemes() == null || classification.configuration().schemes().isEmpty()) {
                throw problem("DYNAMIC_ELECTRICITY_CLASSIFICATION_REQUIRED", "碳项目引用的分类配置不可用于完整时段");
            }
            String scheme = classification.configuration().schemes().getFirst().code();
            ObjectNode evidence = baseEvidence(grain, from, to);
            evidence.set("projectVersion", CarbonEvidence.tree(project));
            evidence.set("classificationVersion", CarbonEvidence.tree(classification));
            var energies = evidence.putArray("energies");
            List<Contribution> contributions = new ArrayList<>();
            Set<String> points = new HashSet<>();
            for (String energyCode : energyCodes) {
                String unit = boundaryUnit(classification.configuration(), energyCode);
                var projection = classifications.queryProjection(user, roles, building,
                        new ClassificationContracts.QueryRequest(from, to, grain, energyCode, unit, scheme,
                                classification.versionId(), List.of()));
                validateProjection(projection, building, from, to);
                var applied = projectCore.apply(project.configuration(), energyCode, projection.slices());
                BigDecimal sourceTotal = sumSlices(projection.slices());
                if (sourceTotal.compareTo(projection.view().total()) != 0
                        || applied.included().add(applied.excluded()).compareTo(sourceTotal) != 0) {
                    throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "计量组成与分类总量或项目排除结果不一致");
                }
                var energy = energies.addObject();
                energy.put("energyItemCode", energyCode);
                energy.set("classificationProjection", CarbonEvidence.tree(projection.view()));
                energy.set("sourceSlices", CarbonEvidence.tree(projection.slices()));
                energy.set("projectApplied", CarbonEvidence.tree(applied));
                for (var slice : applied.includedSlices()) {
                    addContribution(contributions, points, slice);
                }
            }
            return result(evidence, contributions);
        }

        private void pinIdentity(long user, Collection<String> roles, String building) {
            if (roles == null) {
                throw problem("DYNAMIC_ELECTRICITY_SESSION_IDENTITY", "动态电量会话缺少角色身份");
            }
            Set<String> normalizedRoles = Set.copyOf(roles);
            if (sessionUser == null) {
                sessionUser = user;
                sessionRoles = normalizedRoles;
                sessionBuilding = building;
                return;
            }
            if (sessionUser.longValue() != user || !sessionRoles.equals(normalizedRoles)
                    || !sessionBuilding.equals(building)) {
                throw problem("DYNAMIC_ELECTRICITY_SESSION_IDENTITY", "动态电量会话不能跨用户、角色或建筑复用来源快照");
            }
        }
    }

    private static ObjectNode baseEvidence(String grain, Instant from, Instant to) {
        ObjectNode evidence = CarbonEvidence.object();
        evidence.put("contractVersion", CONTRACT);
        evidence.put("grain", grain);
        evidence.put("startInclusive", from.toString());
        evidence.put("endExclusive", to.toString());
        evidence.put("resultNature", "MONITORING");
        evidence.put("sourceNatureAssurance", "MONITORING_NOT_FIELD_ACCEPTANCE");
        return evidence;
    }

    private static Input result(ObjectNode evidence, List<Contribution> values) {
        List<Contribution> contributions = values.stream().sorted(java.util.Comparator.comparing(Contribution::pointId)).toList();
        evidence.set("contributions", CarbonEvidence.tree(contributions));
        String hash = CarbonCalculationCore.sha256(CarbonEvidence.canonical(evidence));
        return new Input(contributions, hash, evidence.deepCopy());
    }

    static List<Contribution> hourlyContributions(HourlyCarbonInputService.Input input) {
        JsonNode evidence = requireEvidence(input);
        if (input.activities() == null || !evidence.path("energies").isArray()) {
            throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时来源缺少活动量或项目组成证据");
        }
        Map<String, BigDecimal> activityTotals = new TreeMap<>();
        for (var activity : input.activities()) {
            if (activity == null || blank(activity.energyItemCode()) || activity.quantityKwh() == null
                    || activity.quantityKwh().signum() < 0 || activityTotals.put(activity.energyItemCode(), activity.quantityKwh()) != null) {
                throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时活动量身份或数量无效");
            }
        }
        Map<String, BigDecimal> sliceTotals = new TreeMap<>();
        List<Contribution> contributions = new ArrayList<>();
        Set<String> points = new HashSet<>();
        for (JsonNode energy : evidence.path("energies")) {
            String code = energy.path("energyItemCode").asText();
            if (blank(code) || sliceTotals.containsKey(code)) {
                throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时项目组成能源标识无效或重复");
            }
            JsonNode applied = energy.path("projectApplied");
            if (!applied.path("includedSlices").isArray()) {
                throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时项目组成缺少纳入来源");
            }
            BigDecimal included = decimal(applied.path("included"), "小时项目纳入量无效");
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode slice : applied.path("includedSlices")) {
                Contribution value = contribution(slice);
                if (!points.add(value.pointId())) {
                    throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时项目组成包含重复测点");
                }
                contributions.add(value);
                total = total.add(value.electricityKwh());
            }
            if (included.compareTo(total) != 0) {
                throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时项目纳入量与来源组成不一致");
            }
            sliceTotals.put(code, total);
        }
        if (!activityTotals.keySet().equals(sliceTotals.keySet())) {
            throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时活动量与项目组成能源不一致");
        }
        for (String code : activityTotals.keySet()) {
            if (activityTotals.get(code).compareTo(sliceTotals.get(code)) != 0) {
                throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时活动量与去重排除后的来源组成不一致");
            }
        }
        return contributions;
    }

    private static void validateProjection(ClassificationService.QueryProjection projection, String building,
                                           Instant from, Instant to) {
        if (projection == null || projection.view() == null || projection.slices() == null
                || !building.equals(projection.view().buildingId())
                || !from.equals(projection.view().startInclusive()) || !to.equals(projection.view().endExclusive())
                || !"KWH".equalsIgnoreCase(projection.view().unit())
                || !"CALCULABLE".equals(projection.view().status()) || projection.view().total() == null
                || projection.view().total().signum() < 0) {
            throw problem("DYNAMIC_ELECTRICITY_INCOMPLETE", "日/月计量边界、单位或质量状态不可用于动态电量");
        }
    }

    private static String boundaryUnit(ClassificationContracts.Configuration configuration, String energyCode) {
        if (configuration == null || configuration.boundaryRules() == null) {
            throw problem("DYNAMIC_ELECTRICITY_UNIT_REQUIRED", "分类配置缺少外购电力计量单位");
        }
        List<String> units = configuration.boundaryRules().stream()
                .filter(Objects::nonNull)
                .filter(rule -> energyCode.equals(rule.energyItemCode()))
                .map(ClassificationContracts.BoundaryRule::unit)
                .filter(unit -> unit != null && !unit.isBlank())
                .distinct().toList();
        if (units.size() != 1 || !"KWH".equalsIgnoreCase(units.getFirst())) {
            throw problem("DYNAMIC_ELECTRICITY_UNIT_REQUIRED", "外购电力需要唯一明确的千瓦时计量边界");
        }
        // 分类服务按原单位标识匹配边界；大小写等价仅用于确认其确为千瓦时，不擅自换算。
        return units.getFirst();
    }

    private static BigDecimal sumSlices(List<ClassificationCore.SourceSlice> slices) {
        BigDecimal total = BigDecimal.ZERO;
        for (var slice : slices) {
            if (slice == null || slice.quantity() == null || slice.quantity().signum() < 0) {
                throw problem("DYNAMIC_ELECTRICITY_INCOMPLETE", "计量组成包含无效电量");
            }
            total = total.add(slice.quantity());
        }
        return total;
    }

    private static void addContribution(List<Contribution> values, Set<String> points,
                                        ClassificationCore.SourceSlice slice) {
        if (slice == null || blank(slice.pointId()) || slice.quantity() == null || slice.quantity().signum() < 0
                || slice.ancestors() == null || !slice.ancestors().contains(slice.pointId()) || !points.add(slice.pointId())) {
            throw problem("DYNAMIC_ELECTRICITY_INCOMPLETE", "去重后的计量组成包含无效或重复测点");
        }
        values.add(new Contribution(slice.pointId(), orderedSet(slice.ancestors()), slice.quantity(), sorted(slice.evidenceIds())));
    }

    private static Contribution contribution(JsonNode source) {
        String pointId = source.path("pointId").asText();
        BigDecimal quantity = decimal(source.path("quantity"), "来源组成电量无效");
        Set<String> ancestors = textSet(source.path("ancestors"), "来源组成祖先证据无效");
        if (blank(pointId) || !ancestors.contains(pointId)) {
            throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "来源组成缺少测点身份或祖先证据");
        }
        return new Contribution(pointId, ancestors, quantity, textList(source.path("evidenceIds"), "来源组成证据标识无效"));
    }

    private static Map<String, JsonNode> referencedContexts(JsonNode source, Map<String, JsonNode> contexts) {
        Map<String, JsonNode> values = new TreeMap<>();
        for (JsonNode energy : source.path("energies")) {
            context(values, contexts, energy.path("contextId").asText());
            var pointInputs = energy.path("pointInputs");
            if (!pointInputs.isObject()) continue;
            pointInputs.fields().forEachRemaining(entry -> {
                JsonNode point = entry.getValue();
                context(values, contexts, point.path("bindingReference").asText());
                context(values, contexts, point.path("unitConversion").path("definitionReference").asText());
            });
        }
        return values;
    }

    private static void context(Map<String, JsonNode> values, Map<String, JsonNode> contexts, String reference) {
        if (blank(reference)) return;
        JsonNode value = contexts.get(reference);
        if (value == null) {
            throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时来源引用的上下文证据缺失");
        }
        values.put(reference, value.deepCopy());
    }

    private static JsonNode requireEvidence(HourlyCarbonInputService.Input input) {
        if (input == null || input.evidence() == null || !input.evidence().isObject()) {
            throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", "小时来源证据无效");
        }
        return input.evidence();
    }

    private static BigDecimal decimal(JsonNode value, String message) {
        if (value == null || value.isNull() || (!value.isNumber() && !value.isTextual())) {
            throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", message);
        }
        try {
            BigDecimal number = new BigDecimal(value.asText());
            if (number.signum() < 0) throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", message);
            return number;
        } catch (NumberFormatException exception) {
            throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", message);
        }
    }

    private static Set<String> textSet(JsonNode values, String message) {
        if (!values.isArray()) throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", message);
        Set<String> result = new TreeSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || blank(value.asText()) || !result.add(value.asText())) {
                throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", message);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static List<String> textList(JsonNode values, String message) {
        if (!values.isArray()) throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", message);
        Set<String> result = new TreeSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || blank(value.asText()) || !result.add(value.asText())) {
                throw problem("DYNAMIC_ELECTRICITY_EVIDENCE_MISMATCH", message);
            }
        }
        return List.copyOf(result);
    }

    private static Set<String> orderedSet(Collection<String> values) {
        if (values == null) throw problem("DYNAMIC_ELECTRICITY_INCOMPLETE", "来源祖先证据无效");
        Set<String> result = new TreeSet<>();
        for (String value : values) {
            if (blank(value) || !result.add(value)) throw problem("DYNAMIC_ELECTRICITY_INCOMPLETE", "来源祖先证据无效");
        }
        return Collections.unmodifiableSet(result);
    }

    private static List<String> sorted(Collection<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
    }

    private static void validateWindow(String building, Instant from, Instant to, String grain, Instant asOf) {
        if (blank(building) || building.length() > 32 || from == null || to == null || asOf == null
                || grain == null || !Set.of("HOUR", "DAY", "MONTH").contains(grain)
                || !to.isAfter(from) || from.getNano() % 1_000_000 != 0 || to.getNano() % 1_000_000 != 0
                || to.isAfter(asOf)) {
            throw problem("DYNAMIC_ELECTRICITY_WINDOW_INVALID", "动态电量只读取已结束、毫秒对齐的准确时段");
        }
        ZonedDateTime start = from.atZone(ZONE);
        if (start.getMinute() != 0 || start.getSecond() != 0 || start.getNano() != 0
                || ("DAY".equals(grain) && start.getHour() != 0)
                || ("MONTH".equals(grain) && (start.getHour() != 0 || start.getDayOfMonth() != 1))) {
            throw problem("DYNAMIC_ELECTRICITY_WINDOW_INVALID", "动态电量时段未按粒度精确对齐");
        }
        Instant expected = switch (grain) {
            case "HOUR" -> start.plusHours(1).toInstant();
            case "DAY" -> start.plusDays(1).toInstant();
            default -> start.plusMonths(1).toInstant();
        };
        if (!expected.equals(to)) {
            throw problem("DYNAMIC_ELECTRICITY_WINDOW_INVALID", "动态电量时段终点未按粒度精确对齐");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static com.platform.framework.exception.BusinessException problem(String code, String message) {
        return DynamicElectricityCore.problem(code, message);
    }
}
