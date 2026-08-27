package com.platform.audit.query;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.TraceContext;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
/** 跨模块审计查询的职责、建筑范围、时间窗和稳定游标边界。 */
public class AuditQueryService {
    private static final Pattern KEY = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern TRACE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Comparator<AuditEvidenceRecord> ORDER = Comparator
            .comparing(AuditEvidenceRecord::operationTime)
            .thenComparing(AuditEvidenceRecord::auditId)
            .thenComparing(AuditEvidenceRecord::sourceModule)
            .reversed();

    private final List<AuditEvidenceProvider> providers;
    private final BackendDutyService dutyService;
    private final BuildingScopeService buildingScopeService;
    private final AuditEvidenceWriter evidenceWriter;
    private final AuditGovernanceProperties properties;

    public AuditQueryPage query(
            long userId, Collection<String> roles, AuditQueryFilter request,
            String encodedCursor, Integer requestedSize) {
        PreparedAuditQuery prepared = prepare(userId, roles, request);
        int size = normalizeSize(requestedSize);
        AuditQueryPage page = queryPrepared(prepared, AuditQueryCursor.decode(encodedCursor), size);
        recordQuery(userId, prepared.filter(), page.items().size());
        return page;
    }

    /** 导出创建时冻结已授权范围；下载时仍会重新校验用户职责，不能靠旧任务恢复已撤销权限。 */
    public PreparedAuditQuery prepare(long userId, Collection<String> roles, AuditQueryFilter request) {
        dutyService.requireDuty(userId, BackendDuty.AUDIT_EVIDENCE_VIEWER);
        AuditQueryFilter filter = normalize(request);
        Set<String> accessible = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        Set<String> allowed;
        if (filter.buildingId() != null) {
            if (!buildingScopeService.canAccess(userId, roles, filter.buildingId())) {
                throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.QUERY_FORBIDDEN);
            }
            allowed = Set.of(filter.buildingId());
        } else {
            allowed = accessible == null ? null : Set.copyOf(accessible);
        }
        return new PreparedAuditQuery(filter, allowed);
    }

    public AuditQueryPage queryPrepared(
            PreparedAuditQuery prepared, AuditQueryCursor cursor, int size) {
        List<AuditEvidenceRecord> merged = new ArrayList<>();
        for (AuditEvidenceProvider provider : selectedProviders(prepared.filter())) {
            merged.addAll(provider.query(prepared.filter(), prepared.allowedBuildingIds(), cursor, size + 1));
        }
        merged.sort(ORDER);
        boolean hasMore = merged.size() > size;
        List<AuditEvidenceRecord> items = List.copyOf(merged.subList(0, Math.min(size, merged.size())));
        String next = hasMore && !items.isEmpty() ? cursor(items.get(items.size() - 1)).encode() : null;
        return new AuditQueryPage(items, next);
    }

    public long countUpTo(PreparedAuditQuery prepared, long limit) {
        long total = 0;
        for (AuditEvidenceProvider provider : selectedProviders(prepared.filter())) {
            long remaining = limit - total;
            if (remaining <= 0) return limit;
            total += provider.countUpTo(prepared.filter(), prepared.allowedBuildingIds(), remaining);
        }
        return total;
    }

    public List<AuditEvidenceRecord> exportRows(PreparedAuditQuery prepared, int maxRows) {
        List<AuditEvidenceRecord> values = new ArrayList<>();
        for (AuditEvidenceProvider provider : selectedProviders(prepared.filter())) {
            int remaining = maxRows - values.size();
            values.addAll(provider.query(
                    prepared.filter(), prepared.allowedBuildingIds(), null, remaining + 1));
            if (values.size() > maxRows) {
                throw AuditGovernanceErrors.conflict(
                        AuditGovernanceErrors.EXPORT_LIMIT_EXCEEDED, "审计导出记录数超过配置上限");
            }
        }
        values.sort(ORDER);
        return List.copyOf(values);
    }

    private List<AuditEvidenceProvider> selectedProviders(AuditQueryFilter filter) {
        if (filter.sourceModule() == null) return providers;
        return providers.stream().filter(provider -> provider.sourceModule().equals(filter.sourceModule())).toList();
    }

    private AuditQueryFilter normalize(AuditQueryFilter request) {
        Instant now = Instant.now();
        Instant to = request.to() == null ? now : request.to();
        Instant from = request.from() == null ? to.minus(properties.getQueryDefaultRange()) : request.from();
        if (!from.isBefore(to) || java.time.Duration.between(from, to).compareTo(properties.getQueryMaxRange()) > 0
                || to.isAfter(now.plusSeconds(60))) {
            throw AuditGovernanceErrors.invalidQuery("审计查询时间范围无效或超过配置上限");
        }
        String source = key(request.sourceModule(), "sourceModule");
        if (source != null && providers.stream().noneMatch(provider -> provider.sourceModule().equals(source))) {
            throw AuditGovernanceErrors.invalidQuery("审计来源模块无效");
        }
        return new AuditQueryFilter(source, text(request.buildingId(), 32, "buildingId"), request.operatorId(),
                key(request.actorType(), "actorType"), key(request.actionType(), "actionType"),
                key(request.objectType(), "objectType"), text(request.objectId(), 128, "objectId"),
                key(request.result(), "result"), key(request.reasonCode(), "reasonCode"),
                trace(request.traceId()), from, to);
    }

    private int normalizeSize(Integer requested) {
        int size = requested == null ? properties.getQueryDefaultSize() : requested;
        if (size < 1 || size > properties.getQueryMaxSize()) {
            throw AuditGovernanceErrors.invalidQuery("审计查询页大小无效");
        }
        return size;
    }

    private void recordQuery(long userId, AuditQueryFilter filter, int resultCount) {
        evidenceWriter.append(new AuditEvidence(
                "SYSTEM_SECURITY", filter.buildingId(), "USER", userId, "AUDIT_QUERY", "AUDIT_SCOPE",
                filter.buildingId() == null ? "AUTHORIZED_SCOPE" : filter.buildingId(), null, null,
                null, "source=" + value(filter.sourceModule()) + ";count=" + resultCount,
                "SUCCESS", null, TraceContext.current(), LocalDateTime.now(),
                properties.getEnvironmentMode(), false));
    }

    private static AuditQueryCursor cursor(AuditEvidenceRecord value) {
        return new AuditQueryCursor(value.operationTime(), value.auditId(), value.sourceModule());
    }

    private static String key(String value, String field) {
        String normalized = text(value, 64, field);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) {
            throw AuditGovernanceErrors.invalidQuery("审计查询字段 " + field + " 无效");
        }
        return normalized;
    }

    private static String trace(String value) {
        String normalized = text(value, 64, "traceId");
        if (normalized != null && !TRACE.matcher(normalized).matches()) {
            throw AuditGovernanceErrors.invalidQuery("审计查询 traceId 无效");
        }
        return normalized;
    }

    private static String text(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw AuditGovernanceErrors.invalidQuery("审计查询字段 " + field + " 无效");
        }
        return normalized;
    }

    private static String value(String value) { return value == null ? "ALL" : value; }

    public record PreparedAuditQuery(AuditQueryFilter filter, Set<String> allowedBuildingIds) {
        public PreparedAuditQuery {
            allowedBuildingIds = allowedBuildingIds == null ? null : Set.copyOf(new HashSet<>(allowedBuildingIds));
        }
    }
}
