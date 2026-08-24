package com.platform.iot.qualityusage;

import com.platform.iot.qualityusage.QualityUsageModels.PolicyInterval;
import com.platform.iot.qualityusage.QualityUsageModels.PolicyKey;
import com.platform.iot.qualityusage.QualityUsageModels.QualityLevel;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeSnapshot;
import com.platform.iot.qualityusage.QualityUsageModels.Scenario;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
/**
 * 从 MySQL 批量构建质量使用策略区间索引。
 *
 * <p>运行快照和单次历史请求都只执行有界批量 SQL；不会按测点分钟访问 MySQL，
 * 也不会与 TDengine 跨库关联。等级子行允许为空，因此必须用 LEFT JOIN 保留显式
 * 完全禁止的正式版本。</p>
 */
public class JdbcQualityUsagePolicyRepository implements QualityUsagePolicyRepository {
    private final JdbcTemplate jdbc;

    public JdbcQualityUsagePolicyRepository(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long currentRevision() {
        Long revision = jdbc.queryForObject(
                "SELECT config_revision FROM biz_quality_usage_config_revision WHERE singleton_id=1",
                Long.class);
        if (revision == null) {
            throw new IllegalStateException("Quality usage config revision row is missing");
        }
        return revision;
    }

    @Override
    public RuntimeSnapshot loadRuntimeSnapshot(long recoveryFromInclusiveMs) {
        long revision = currentRevision();
        Map<String, Scenario> scenarios = loadScenarios(null);
        List<Object> args = new ArrayList<>();
        args.add(recoveryFromInclusiveMs);
        Map<PolicyKey, List<PolicyInterval>> policies = loadPolicies(
                "AND (v.effective_to_ms IS NULL OR v.effective_to_ms > ?)", args);
        if (currentRevision() != revision) {
            throw new IllegalStateException("Quality usage revision changed during snapshot load");
        }
        return new RuntimeSnapshot(revision, scenarios, policies);
    }

    @Override
    public RuntimeSnapshot loadRange(
            Set<String> pointIds,
            String scenarioCode,
            long fromInclusive,
            long toExclusive) {
        if (pointIds == null || pointIds.isEmpty()) {
            long revision = currentRevision();
            Map<String, Scenario> scenarios = loadScenarios(scenarioCode);
            if (currentRevision() != revision) {
                throw new IllegalStateException("Quality usage revision changed during range load");
            }
            return new RuntimeSnapshot(revision, scenarios, Map.of());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(pointIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(scenarioCode);
        args.addAll(pointIds);
        args.add(toExclusive);
        args.add(fromInclusive);
        String predicate = "AND s.scenario_code=? AND p.point_id IN (" + placeholders + ") "
                + "AND (v.effective_from_ms IS NULL OR v.effective_from_ms < ?) "
                + "AND (v.effective_to_ms IS NULL OR v.effective_to_ms > ?)";
        long revision = currentRevision();
        Map<String, Scenario> scenarios = loadScenarios(scenarioCode);
        Map<PolicyKey, List<PolicyInterval>> policies = loadPolicies(predicate, args);
        if (currentRevision() != revision) {
            throw new IllegalStateException("Quality usage revision changed during range load");
        }
        return new RuntimeSnapshot(revision, scenarios, policies);
    }

    @Override
    public Set<PolicyKey> affectedPolicies(long fromExclusiveRevision, long toInclusiveRevision) {
        if (toInclusiveRevision <= fromExclusiveRevision) {
            return Set.of();
        }
        return Set.copyOf(jdbc.query(
                "SELECT DISTINCT p.point_id,s.scenario_code "
                        + "FROM biz_quality_usage_policy_version v "
                        + "JOIN biz_quality_usage_policy p ON p.policy_id=v.policy_id "
                        + "JOIN biz_quality_usage_scenario s ON s.scenario_id=p.scenario_id "
                        + "WHERE v.published_config_revision>? AND v.published_config_revision<=?",
                (rs, row) -> new PolicyKey(rs.getString(1), rs.getString(2)),
                fromExclusiveRevision, toInclusiveRevision));
    }

    @Override
    public Long earliestAffectedMinute(long fromExclusiveRevision, long toInclusiveRevision) {
        if (toInclusiveRevision <= fromExclusiveRevision) {
            return null;
        }
        return jdbc.queryForObject(
                "SELECT MIN(effective_from_ms) FROM biz_quality_usage_policy_version "
                        + "WHERE published_config_revision>? AND published_config_revision<=?",
                Long.class, fromExclusiveRevision, toInclusiveRevision);
    }

    private Map<String, Scenario> loadScenarios(String scenarioCode) {
        String sql = "SELECT scenario_code,adapter_type,status FROM biz_quality_usage_scenario";
        Object[] args = new Object[0];
        if (scenarioCode != null) {
            sql += " WHERE scenario_code=?";
            args = new Object[]{scenarioCode};
        }
        Map<String, Scenario> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            Scenario scenario = new Scenario(
                    rs.getString("scenario_code"),
                    rs.getString("adapter_type"),
                    rs.getString("status"));
            result.put(scenario.code(), scenario);
        }, args);
        return result;
    }

    private Map<PolicyKey, List<PolicyInterval>> loadPolicies(
            String predicate, List<Object> args) {
        String sql = "SELECT p.policy_id,p.point_id,s.scenario_code,v.version_id,v.version_no,"
                + "v.effective_from_ms,v.effective_to_ms,l.quality_level "
                + "FROM biz_quality_usage_policy p "
                + "JOIN biz_quality_usage_scenario s ON s.scenario_id=p.scenario_id "
                + "JOIN biz_quality_usage_policy_version v ON v.policy_id=p.policy_id "
                + "LEFT JOIN biz_quality_usage_policy_level l ON l.version_id=v.version_id "
                + "WHERE v.status IN ('ACTIVE','RETIRED') " + predicate + " "
                + "ORDER BY p.point_id,s.scenario_code,v.version_no,l.quality_level";
        Map<VersionKey, MutableInterval> byVersion = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> accumulate(byVersion, rs), args.toArray());

        Map<PolicyKey, List<PolicyInterval>> result = new LinkedHashMap<>();
        byVersion.values().forEach(value -> result
                .computeIfAbsent(value.policyKey, ignored -> new ArrayList<>())
                .add(value.freeze()));
        result.values().forEach(intervals -> {
            intervals.sort(Comparator
                    .comparing(PolicyInterval::effectiveFromMs,
                            Comparator.nullsFirst(Long::compareTo))
                    .thenComparingInt(PolicyInterval::versionNo));
            for (int index = 1; index < intervals.size(); index++) {
                PolicyInterval previous = intervals.get(index - 1);
                PolicyInterval current = intervals.get(index);
                if (previous.effectiveToMs() == null
                        || current.effectiveFromMs() == null
                        || previous.effectiveToMs() > current.effectiveFromMs()) {
                    throw new IllegalStateException("Overlapping quality usage policy intervals");
                }
            }
        });
        return result;
    }

    private void accumulate(Map<VersionKey, MutableInterval> byVersion, ResultSet rs)
            throws SQLException {
        VersionKey versionKey = new VersionKey(
                rs.getString("policy_id"), rs.getString("version_id"));
        MutableInterval interval = byVersion.computeIfAbsent(versionKey, ignored ->
                new MutableInterval(
                        new PolicyKey(rsText(rs, "point_id"), rsText(rs, "scenario_code")),
                        rsText(rs, "policy_id"),
                        rsInt(rs, "version_no"),
                        nullableLong(rs, "effective_from_ms"),
                        nullableLong(rs, "effective_to_ms")));
        String level = rs.getString("quality_level");
        if (level != null) {
            interval.levels.add(QualityLevel.valueOf(level));
        }
    }

    private static String rsText(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int rsInt(ResultSet rs, String column) {
        try {
            return rs.getInt(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) {
        try {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record VersionKey(String policyId, String versionId) {
    }

    private static final class MutableInterval {
        private final PolicyKey policyKey;
        private final String policyId;
        private final int versionNo;
        private final Long effectiveFromMs;
        private final Long effectiveToMs;
        private final Set<QualityLevel> levels = new LinkedHashSet<>();

        private MutableInterval(
                PolicyKey policyKey,
                String policyId,
                int versionNo,
                Long effectiveFromMs,
                Long effectiveToMs) {
            this.policyKey = policyKey;
            this.policyId = policyId;
            this.versionNo = versionNo;
            this.effectiveFromMs = effectiveFromMs;
            this.effectiveToMs = effectiveToMs;
        }

        private PolicyInterval freeze() {
            if (!levels.isEmpty() && !levels.contains(QualityLevel.Q0)) {
                throw new IllegalStateException(
                        "Non-empty quality usage policy must include Q0");
            }
            return new PolicyInterval(
                    policyId, versionNo, effectiveFromMs, effectiveToMs, levels);
        }
    }
}
