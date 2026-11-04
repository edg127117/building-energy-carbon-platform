package com.platform.carbon;

import com.platform.audit.*;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.platform.carbon.DynamicElectricityContracts.*;
import static com.platform.carbon.DynamicElectricityRepository.*;
import static com.platform.carbon.DynamicElectricityCore.problem;

/** 独立动态监测命令与查询。来源变更使旧结果过期，读取不会触发计算或冒充已更新。 */
@Service
@RequiredArgsConstructor
public class DynamicElectricityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final CarbonAuthorization authorization;
    private final DynamicElectricityRepository repository;
    private final DynamicElectricityInputReader electricity;
    private final PlatformTransactionManager transactionManager;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    private final DynamicElectricityLiveRepository live;

    @Transactional(rollbackFor = Exception.class)
    public ImportReceipt ingest(long user, Collection<String> roles, String building, ImportRequest request) {
        identity(building);
        authorization.requireRuleMaintainer(user, roles, building);
        if (!repository.buildingExists(building)) throw problem("DYNAMIC_BUILDING_MISSING", "建筑不存在");
        String requestHash = hash(encode(request));
        String importId = hash(building + ":" + request.idempotencyKey());
        repository.lock(building);
        var replay = repository.imported(building, importId);
        if (!replay.isEmpty()) {
            if (!replay.getFirst().requestHash().equals(requestHash)) throw problem("DYNAMIC_IDEMPOTENCY_CONFLICT", "幂等键对应不同因子报文");
            return new ImportReceipt(importId, replay.size(), requestHash);
        }
        var pending = new ArrayList<FactorRow>();
        for (var observation : request.observations()) {
            Instant from = observation.startInclusive(), to = observation.endExclusive();
            window(from, to);
            if (!"*".equals(observation.pointId()) && !repository.pointBelongs(building, observation.pointId()))
                throw problem("DYNAMIC_POINT_SCOPE", "因子测点不属于建筑");
            var overlapping = repository.factors(building, from, to).stream()
                    .filter(f -> f.factor().pointId().equals(observation.pointId())).toList();
            boolean conflict = overlapping.stream().anyMatch(f -> !f.factor().start().equals(from) || !f.factor().end().equals(to));
            boolean duplicate = pending.stream().anyMatch(f -> f.factor().pointId().equals(observation.pointId())
                    && f.factor().start().isBefore(to) && f.factor().end().isAfter(from));
            if (conflict || duplicate) throw problem("DYNAMIC_FACTOR_OVERLAP", "同一测点因子时段重叠，修订必须保持原区间");
            int revision = overlapping.stream().mapToInt(FactorRow::revision).max().orElse(0) + 1;
            String id = hash(importId + ":" + observation.pointId() + ":" + from + ":" + to);
            var factor = new DynamicElectricityCore.Factor(id, observation.pointId(), from, to, observation.factorKgCo2PerKwh(),
                    request.sourceNature(), request.sourceReference(), request.sourceRegion(), request.scenario());
            pending.add(new FactorRow(factor, revision, importId, requestHash, ""));
        }
        pending.forEach(f -> {repository.insertFactor(building,f);live.invalidateLegacy(building,f.factor().start(),f.factor().end());});
        appendAudit(user, building, "IMPORT_DYNAMIC_ELECTRICITY_FACTOR", importId, requestHash);
        return new ImportReceipt(importId, pending.size(), requestHash);
    }

    /** 一次命令最多处理一个月。慢电量读取在发布事务外执行，发布前再次核对权威来源版本。 */
    public List<String> calculate(long user, Collection<String> roles, String building, CalculationRequest request) {
        identity(building);
        authorization.requireCalculationRunner(user, roles, building);
        if ("RAW_CUMULATIVE".equals(request.sourceKind()) && !"HOUR".equals(request.grain()))
            throw problem("DYNAMIC_SOURCE_GRAIN", "原始累计量路径按小时计算后汇总，日/月输入使用既有审核周期量");
        window(request.startInclusive(), request.endExclusive());
        var windows = split(request.startInclusive(), request.endExclusive(), request.grain());
        var session = electricity.session(request.sourceKind());
        String metadata=live.metadataVersion(building,repository);
        Map<Instant, String> versions = new HashMap<>();
        var results = new ArrayList<Result>();
        var deadline = CarbonCalculationDeadline.start(Duration.ofMinutes(5));
        try (var ignored = deadline.bind()) {
            for (var interval : windows) {
                deadline.requireRemaining();
                Instant from = interval[0], to = interval[1];
                String version = versions.computeIfAbsent(from, m -> sourceVersion(building, from, to, request.sourceKind(),metadata));
                var input = session.read(user, roles, building, from, to, request.grain());
                var factors = repository.factors(building, from, to);
                var values = DynamicElectricityCore.calculate(input.contributions(), factors.stream().map(FactorRow::factor).toList(), from, to);
                String factorHash = hash(encode(factors.stream().map(FactorRow::hash).toList()));
                String id = hash(encode(List.of(building, request.grain(), from, to, version)));
                results.add(new Result(id, building, request.grain(), from, to, version, request.sourceKind(), values,
                        input.evidenceHash(), input.evidence(), factorHash, Instant.now().toEpochMilli()));
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                repository.lock(building);
                authorization.requireCalculationRunner(user, roles, building);
                String publishMetadata=live.metadataVersion(building,repository);
                versions.forEach((from, version) -> {
                    var end=results.stream().filter(r->r.start().equals(from)).findFirst().orElseThrow().end();
                    if (!version.equals(sourceVersion(building, from, end, request.sourceKind(),publishMetadata))) throw problem("DYNAMIC_SOURCE_CHANGED", "来源已修订，请重新计算");
                });
                deadline.requireRemaining();
                results.forEach(r->{repository.save(r);live.registerCalculation(r,user);});
                appendAudit(user, building, "CALCULATE_DYNAMIC_ELECTRICITY", hash(encode(results.stream().map(Result::id).toList())),
                        hash(encode(versions.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Object::toString).toList())));
            });
        }
        return results.stream().map(Result::id).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View query(long user, Collection<String> roles, String building, String type, String period) {
        identity(building);
        authorization.requireReader(user, roles, building);
        List<Instant[]> buckets;
        try {
            if ("YEAR".equals(type) && period.matches("\\d{4}")) {
                int year = Integer.parseInt(period);
                if (year < 2000 || year > 2200) throw new DateTimeException("year");
                buckets = split(start(LocalDate.of(year, 1, 1)), start(LocalDate.of(year + 1, 1, 1)), "MONTH");
            } else if ("MONTH".equals(type) && period.matches("\\d{4}-\\d{2}")) {
                var month = YearMonth.parse(period);
                if (month.getYear() < 2000 || month.getYear() > 2200) throw new DateTimeException("year");
                buckets = split(start(month.atDay(1)), start(month.plusMonths(1).atDay(1)), "DAY");
            } else if ("DAY".equals(type) && period.matches("\\d{4}-\\d{2}-\\d{2}")) {
                var day = LocalDate.parse(period);
                if (day.getYear() < 2000 || day.getYear() > 2200) throw new DateTimeException("year");
                buckets = split(start(day), start(day.plusDays(1)), "HOUR");
            } else throw new DateTimeException("period");
        } catch (DateTimeException | NumberFormatException ex) { throw new BusinessException(400, "DYNAMIC_QUERY_INVALID", "查询期间无效"); }
        var stored = new ArrayList<>(repository.results(building, buckets.getFirst()[0], buckets.getLast()[1]));
        var deviceCoverage=live.deviceCoverage(building,buckets.getFirst()[0],buckets.getLast()[1]);
        stored.removeIf(r->deviceCoverage.stream().anyMatch(w->r.start().isBefore(w[1])&&r.end().isAfter(w[0])));
        String metadata=stored.isEmpty()&&!live.hasData(building,buckets.getFirst()[0],buckets.getLast()[1])?null:live.metadataVersion(building,repository);
        if(metadata!=null)
            stored.addAll(live.current(building,buckets.getFirst()[0],buckets.getLast()[1],metadata));
        Map<String, String> versions = new HashMap<>();
        var points = new ArrayList<Point>();
        for (var bucket : buckets) {
            String key = switch (type) {
                case "YEAR" -> YearMonth.from(bucket[0].atZone(ZONE)).toString();
                case "DAY" -> bucket[0].atZone(ZONE).toLocalDateTime().toString();
                default -> bucket[0].atZone(ZONE).toLocalDate().toString();
            };
            if (bucket[0].isAfter(Instant.now())) {
                points.add(empty(key, bucket, "NOT_ENDED", "PERIOD_NOT_ENDED"));
                continue;
            }
            var candidates = stored.stream().filter(r -> !r.start().isBefore(bucket[0]) && !r.end().isAfter(bucket[1])).toList();
            var current = candidates.stream().filter(r -> "DEVICE_INTERVAL".equals(r.sourceKind()) || r.sourceVersion().equals(versions.computeIfAbsent(r.start() + ":" + r.end() + ":" + r.sourceKind(),
                    keyVersion -> sourceVersion(building, r.start(), r.end(), r.sourceKind(),metadata)))).toList();
            // 优先选覆盖当前游标的最粗已存结果；同一电量不能同时累计月、日和小时三个粒度。
            Map<Instant,Result> byStart=new HashMap<>();
            current.forEach(r->byStart.merge(r.start(),r,(a,b)->a.end().isAfter(b.end())?a:b));
            var used = new ArrayList<Result>();
            Instant cursor = bucket[0];
            while (cursor.isBefore(bucket[1])) {
                Instant at = cursor;
                var next = byStart.get(at);
                if (next == null) break;
                used.add(next);
                cursor = next.end();
            }
            boolean accumulating=bucket[1].isAfter(Instant.now()) && cursor.isAfter(bucket[0]);
            if (!cursor.equals(bucket[1]) && !accumulating) {
                points.add(empty(key, bucket, candidates.isEmpty() ? "MISSING" : current.isEmpty() ? "STALE" : "PARTIAL", "COMPLETE_RESULT_REQUIRED"));
                continue;
            }
            BigDecimal quantity = used.stream().map(r -> r.values().quantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal emission = used.stream().map(r -> r.values().emissionKgCo2()).reduce(BigDecimal.ZERO, BigDecimal::add);
            points.add(new Point(key, bucket[0], bucket[1], accumulating?"ACCUMULATING":"COMPLETE", null, quantity, emission.movePointLeft(3),
                    quantity.signum() == 0 ? null : emission.divide(quantity, MathContext.DECIMAL128),
                    used.stream().map(Result::grain).distinct().sorted().toList(), used.stream().map(Result::id).toList(),
                    used.stream().flatMap(r -> r.values().sourceNatures().stream()).distinct().sorted().toList(), cursor));
        }
        return new View(building, type, period, ZONE.getId(), "tCO2", "kgCO2/kWh", "MONITORING", List.copyOf(points));
    }

    @Transactional(readOnly = true)
    public Result evidence(long user, Collection<String> roles, String building, String resultId) {
        identity(building);
        authorization.requireReader(user, roles, building);
        var result = repository.byId(building, resultId);
        if (result == null) throw new BusinessException(404, "DYNAMIC_RESULT_NOT_FOUND", "动态碳结果不存在");
        return result;
    }

    String sourceVersion(String building, Instant from, Instant to, String sourceKind) {
        return sourceVersion(building,from,to,sourceKind,live.metadataVersion(building,repository));
    }
    private String sourceVersion(String building,Instant from,Instant to,String sourceKind,String metadata) {
        return hash(encode(List.of("DYNAMIC_ELECTRICITY_V3",sourceKind,metadata,
                repository.electricityVersion(building,from,to,sourceKind),
                repository.factors(building,from,to).stream().map(FactorRow::hash).toList())));
    }

    static List<Instant[]> split(Instant from, Instant to, String grain) {
        var values = new ArrayList<Instant[]>();
        var cursor = from.atZone(ZONE);
        if (!Set.of("HOUR", "DAY", "MONTH").contains(grain) || cursor.getMinute() != 0 || cursor.getSecond() != 0 || cursor.getNano() != 0
                || !"HOUR".equals(grain) && cursor.getHour() != 0 || "MONTH".equals(grain) && cursor.getDayOfMonth() != 1)
            throw problem("DYNAMIC_GRAIN_INVALID", "计算区间必须按所选粒度对齐");
        while (cursor.toInstant().isBefore(to)) {
            var next = switch (grain) { case "HOUR" -> cursor.plusHours(1); case "DAY" -> cursor.plusDays(1); default -> cursor.plusMonths(1); };
            if (next.toInstant().isAfter(to)) throw problem("DYNAMIC_GRAIN_INVALID", "区间终点未对齐");
            values.add(new Instant[]{cursor.toInstant(), next.toInstant()});
            cursor = next;
            if (values.size() > 744) throw problem("DYNAMIC_CAPACITY", "单次区间超过744个计算时段");
        }
        return values;
    }

    private static void window(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from) || from.getNano() % 1_000_000 != 0 || to.getNano() % 1_000_000 != 0
                || from.atZone(ZONE).getYear() < 2000 || to.atZone(ZONE).getYear() > 2201
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0 || to.isAfter(Instant.now()))
            throw problem("DYNAMIC_WINDOW_INVALID", "仅接收已结束、毫秒对齐且最多31天的时段");
    }
    private static void identity(String building) {
        if (building == null || building.isBlank() || building.length() > 32) throw new BusinessException(400, "DYNAMIC_BUILDING_INVALID", "建筑标识无效");
    }
    private static Instant start(LocalDate date) { return date.atStartOfDay(ZONE).toInstant(); }
    private static Point empty(String key, Instant[] bucket, String status, String reason) {
        return new Point(key, bucket[0], bucket[1], status, reason, null, null, null, List.of(), List.of(), List.of());
    }
    private void appendAudit(long user, String building, String action, String id, String evidenceHash) {
        audit.append(new AuditEvidence("CARBON_MANAGEMENT", building, "USER", user, action, "DYNAMIC_ELECTRICITY", id,
                null, null, null, "evidenceHash=" + evidenceHash, "SUCCESS", null, TraceContext.current(),
                LocalDateTime.now(), auditProperties.getEnvironmentMode(), false));
    }
}
