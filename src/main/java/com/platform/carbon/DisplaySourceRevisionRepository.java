package com.platform.carbon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/** 为展示读模型提供按建筑、来源域和月份递增的提交水位。 */
@Repository
public class DisplaySourceRevisionRepository {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readSnapshot;
    private final TransactionTemplate guardedPublish;

    public record Snapshot<T>(String version, T value) { }

    @org.springframework.beans.factory.annotation.Autowired
    public DisplaySourceRevisionRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this(jdbc, new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    DisplaySourceRevisionRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readSnapshot = new TransactionTemplate(transactionManager);
        this.readSnapshot.setReadOnly(true);
        this.readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readSnapshot.setTimeout(3);
        this.guardedPublish = new TransactionTemplate(transactionManager);
        this.guardedPublish.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.guardedPublish.setTimeout(3);
        this.guardedPublish.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** 供配置和全局规则使用；来源没有时间范围时，其星号水位覆盖所有月份。 */
    public String version(String building, String domain) {
        return version(building, domains(domain), List.of("*"));
    }

    /** 期间查询只合并相交的上海自然月以及全局配置水位。 */
    public String version(String building, String domain, Instant fromInclusive, Instant toExclusive) {
        return version(building, domains(domain), scopes(fromInclusive, toExclusive));
    }

    public <T> Snapshot<T> readSnapshot(String building, String domain, Supplier<T> read) {
        return readSnapshot.execute(status -> new Snapshot<>(version(building, domain), read.get()));
    }

    public <T> Snapshot<T> readSnapshot(String building, String domain, Instant fromInclusive,
                                        Instant toExclusive, Supplier<T> read) {
        List<String> scopes = scopes(fromInclusive, toExclusive);
        return readSnapshot.execute(status -> new Snapshot<>(version(building, domains(domain), scopes), read.get()));
    }

    /** 锁定所有影响该期间的水位后再发布，来源触发器与发布事务因此串行化。 */
    public boolean ifCurrent(String building, String domain, String expected, Runnable publish) {
        return ifCurrent(building, domains(domain), List.of("*"), expected, publish);
    }

    public boolean ifCurrent(String building, String domain, Instant fromInclusive, Instant toExclusive,
                             String expected, Runnable publish) {
        return ifCurrent(building, domains(domain), scopes(fromInclusive, toExclusive), expected, publish);
    }

    private boolean ifCurrent(String building, List<String> domains, List<String> scopes,
                              String expected, Runnable publish) {
        validateBuilding(building);
        return Boolean.TRUE.equals(guardedPublish.execute(status -> {
            List<String> orderedDomains = domains.stream().distinct().sorted().toList();
            ensureRows(building, orderedDomains, scopes);
            var values = lockAndRead(building, orderedDomains, scopes, true);
            if (!Objects.equals(expected, fingerprint(domains, scopes, values))) return false;
            publish.run();
            return true;
        }));
    }

    private String version(String building, List<String> domains, List<String> scopes) {
        validateBuilding(building);
        return fingerprint(domains, scopes, lockAndRead(building, domains, scopes, false));
    }

    private List<String> lockAndRead(String building, List<String> domains, List<String> scopes, boolean lock) {
        String query = """
                SELECT building_id,domain_key,scope_key,revision FROM biz_carbon_display_source_revision
                WHERE (building_id='*' OR building_id=?) AND domain_key IN (%s) AND scope_key IN (%s)
                ORDER BY building_id,domain_key,scope_key
                """.formatted(placeholders(domains.size()), placeholders(scopes.size())) + (lock ? " FOR UPDATE" : "");
        var found = jdbc.query(query, rs -> {
            var rows = new HashMap<String, Long>();
            while (rs.next()) rows.put(rs.getString(1) + "\u0000" + rs.getString(2) + "\u0000" + rs.getString(3), rs.getLong(4));
            return rows;
        }, parameters(building, domains, scopes));
        var rows = new ArrayList<String>();
        for (String owner : List.of("*", building))
            for (String domain : domains.stream().sorted().toList())
                for (String scope : scopes.stream().sorted().toList())
                    rows.add(owner + ":" + domain + ":" + scope + ":" + found.getOrDefault(owner + "\u0000" + domain + "\u0000" + scope, 0L));
        return rows;
    }

    private void ensureRows(String building, List<String> domains, List<String> scopes) {
        var values=new ArrayList<String>();var args=new ArrayList<Object>();
        for(String owner:List.of("*",building))for(String domain:domains.stream().sorted().toList())for(String scope:scopes.stream().sorted().toList()) {
            values.add("(?,?,?,0)");args.add(owner);args.add(domain);args.add(scope);
        }
        // INSERT IGNORE 对已存在行持有共享锁，两个发布者随后同时 FOR UPDATE 会发生锁升级死锁。
        // 空更新从一开始按相同主键顺序取得写锁，既不推进来源版本，也不改变更新时间。
        jdbc.update("INSERT INTO biz_carbon_display_source_revision(building_id,domain_key,scope_key,revision) VALUES "+String.join(",",values)
                +" ON DUPLICATE KEY UPDATE revision=revision",args.toArray());
    }

    private static String fingerprint(List<String> domains, List<String> scopes, List<String> rows) {
        var parts = new ArrayList<String>();
        parts.add("DISPLAY_SOURCE_REVISION_V1");
        domains.stream().sorted().forEach(v -> parts.add("domain=" + v));
        scopes.stream().sorted().forEach(v -> parts.add("scope=" + v));
        rows.forEach(v -> parts.add("revision=" + v));
        return CarbonCalculationCore.sha256(String.join("\n", parts));
    }

    private static List<String> domains(String domain) {
        return switch (Objects.toString(domain, "")) {
            case "DYNAMIC_INPUT", "DYNAMIC_RAW", "DYNAMIC_RESULT", "DAILY_INPUT", "DAILY_RESULT" -> List.of(domain);
            case "DYNAMIC" -> List.of("DYNAMIC_INPUT", "DYNAMIC_RESULT");
            case "DYNAMIC_WITH_RAW" -> List.of("DYNAMIC_INPUT", "DYNAMIC_RAW", "DYNAMIC_RESULT");
            case "DYNAMIC_RAW_INPUT" -> List.of("DYNAMIC_INPUT", "DYNAMIC_RAW");
            case "DAILY" -> List.of("DAILY_INPUT", "DAILY_RESULT");
            default -> throw new IllegalArgumentException("Unknown display source domain");
        };
    }

    private static List<String> scopes(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) throw new IllegalArgumentException("Invalid display source window");
        Instant lastInstant = to.minusNanos(1);
        YearMonth first = YearMonth.from(from.atZone(ZONE));
        YearMonth last = YearMonth.from(lastInstant.atZone(ZONE));
        YearMonth firstUtc = YearMonth.from(from.atZone(java.time.ZoneOffset.UTC));
        // RAW cumulative selection treats its right anchor as inclusive (event_ms <= end).
        YearMonth lastUtc = YearMonth.from(to.atZone(java.time.ZoneOffset.UTC));
        if (java.time.temporal.ChronoUnit.MONTHS.between(first, last) > 1200
                || java.time.temporal.ChronoUnit.MONTHS.between(firstUtc, lastUtc) > 1200)
            throw new IllegalArgumentException("Display source window exceeds 100 years");
        var result = new ArrayList<String>();
        result.add("*");
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) result.add("M:" + month);
        for (YearMonth month = firstUtc; !month.isAfter(lastUtc); month = month.plusMonths(1)) result.add("U:" + month);
        return result.stream().distinct().sorted().toList();
    }

    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }

    private static Object[] parameters(String building, List<String> domains, List<String> scopes) {
        var args = new ArrayList<Object>();
        args.add(building);
        args.addAll(domains);
        args.addAll(scopes);
        return args.toArray();
    }

    private static void validateBuilding(String building) {
        if (building == null || !building.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid building identity");
    }
}
