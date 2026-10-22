package com.platform.carbon;

import com.platform.energy.classification.ClassificationService;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.platform.carbon.CarbonErrors.*;
import static com.platform.carbon.CarbonProjectContracts.*;

/** 只读串联各域版本和下一步；不代替独立审核，也不把结构配置视为正式计算就绪。 */
@Service
public class CarbonConfigurationGuideService {
    private final CarbonProjectService projects;
    private final ClassificationService classifications;
    private final RelationGovernanceService relations;

    public CarbonConfigurationGuideService(CarbonProjectService projects, ClassificationService classifications,
                                          RelationGovernanceService relations) {
        this.projects = projects;
        this.classifications = classifications;
        this.relations = relations;
    }

    public record Step(String domain, String reference, Long revision, String state,
                       String responsibility, String action, List<Issue> issues) { }
    public record Guide(String buildingId, String versionId, long revision, String calculationStatus,
                        List<Step> steps) { }

    public Guide guide(long user, Collection<String> roles, String building, String versionId) {
        // 先经项目公开服务核对角色与建筑范围，再读取任何跨域依赖。
        Version project = requireProject(user, roles, building, versionId);
        List<Step> steps = new ArrayList<>();
        String classificationId = project.configuration().classificationVersionId();
        ClassificationContracts.VersionView classificationSnapshot = null;
        if (classificationId == null) {
            steps.add(missing("RELATION", "RELATION_REQUIRED", "尚未关联真实计量关系版本"));
            steps.add(missing("CLASSIFICATION", "CLASSIFICATION_REQUIRED", "请先建立并审核分类版本"));
        } else {
            try {
                var classification = classifications.version(user, roles, building, classificationId);
                classificationSnapshot = classification;
                String relationId = classification.configuration().relationVersionId();
                steps.add(relation(user, roles, building, relationId));
                var issues = classification.issues().stream().map(i -> new Issue(i.code(), i.reference(), i.message())).toList();
                boolean blocked = classification.issues().stream().anyMatch(i -> !"WARNING".equals(i.severity()));
                steps.add(lifecycle("CLASSIFICATION", classificationId, classification.revision(), classification.status(),
                        blocked, issues));
            } catch (BusinessException exception) {
                rethrowAuthorization(exception);
                steps.add(missing("RELATION", "DEPENDENCY_UNAVAILABLE", "分类依赖不可用，请核对所引用版本"));
                steps.add(new Step("CLASSIFICATION", classificationId, null, "BLOCKED", "ENERGY_MANAGER",
                        "RESOLVE_ISSUES", List.of(new Issue("DEPENDENCY_UNAVAILABLE", classificationId, exception.getMessage()))));
            }
        }
        try {
            var readiness = projects.readiness(user, roles, building, versionId);
            steps.add(lifecycle("PROJECT", versionId, project.revision(), project.status(), !readiness.ready(), readiness.issues()));
        } catch (BusinessException exception) {
            rethrowAuthorization(exception);
            steps.add(new Step("PROJECT", versionId, project.revision(), "BLOCKED", "ENERGY_MANAGER", "RESOLVE_ISSUES",
                    List.of(new Issue("DEPENDENCY_UNAVAILABLE", versionId, exception.getMessage()))));
        }
        boolean enabled = project.configuration().energies().stream().anyMatch(e -> "ENABLED".equals(e.capability()));
        steps.add(new Step("SEALED_SOURCE", null, null, enabled ? "NOT_CHECKED" : "RESERVED", "ENERGY_MANAGER",
                "VERIFY_SEALED_MONTH", List.of(new Issue("PERIOD_VERIFICATION_REQUIRED", null,
                enabled ? "选择明确的完整封账月份做来源与排除预览；人工导入审核不等于正式封账" : "当前没有已启用的核算能源，预留项不产生核算结果"))));
        steps.add(new Step("FACTORS", null, null, enabled ? "NOT_CHECKED" : "RESERVED", "CARBON_RULE_MAINTAINER",
                "VERIFY_FACTORS", List.of(new Issue("FACTOR_VERIFICATION_REQUIRED", null,
                "核算时仍须核对能源、地区、单位和期间匹配的已生效因子；此引导未执行因子适用性计算"))));
        // 各域仍独立并发与审核；返回前复查依赖，不能混用分类旧快照与项目新就绪结果。
        if (classificationSnapshot != null) {
            try {
                var latest = classifications.version(user, roles, building, classificationId);
                if (!classificationSnapshot.equals(latest)
                        || !steps.getFirst().equals(relation(user, roles, building, latest.configuration().relationVersionId())))
                    throw error(409, VERSION_CONFLICT, "分类或关系版本已变化，请刷新配置引导");
            } catch (BusinessException exception) {
                rethrowAuthorization(exception);
                throw error(409, VERSION_CONFLICT, "分类或关系依赖已变化，请刷新配置引导");
            }
        }
        if (requireProject(user, roles, building, versionId).revision() != project.revision())
            throw error(409, VERSION_CONFLICT, "项目版本已变化，请刷新配置引导");
        return new Guide(building, versionId, project.revision(), "NOT_CHECKED", List.copyOf(steps));
    }

    private Step relation(long user, Collection<String> roles, String building, String id) {
        if (id == null) return missing("RELATION", "RELATION_REQUIRED", "请先关联真实计量关系版本");
        // 业主有项目只读权限，不因此获得关系历史权限；受限步骤交由原职责人员核对。
        if (Collections.disjoint(roles, List.of("ENERGY_MANAGER", "PLATFORM_ADMIN")))
            return new Step("RELATION", id, null, "NOT_CHECKED", "ENERGY_MANAGER", "VIEW",
                    List.of(new Issue("RELATION_HISTORY_RESTRICTED", id, "关系历史须由能源管理员核对；此处未读取受限关系详情")));
        try {
            var relation = relations.versionDetail(user, roles, building, id);
            // 草稿校验可能需要写锁；只读引导不触发它，正式发布继续由原领域服务重新校验。
            var metadata = relation.metadata();
            boolean blocked = metadata.errorCount() > 0 || metadata.pendingExpertCount() > 0 || metadata.unassignedCount() > 0;
            List<Issue> issues = blocked ? List.of(new Issue("RELATION_REVIEW_REQUIRED", id,
                    "关系版本仍有未归属、待专业确认或错误项，请进入关系配置处理")) : List.of();
            return lifecycle("RELATION", id, relation.version().revision(), relation.version().status(), blocked, issues);
        } catch (BusinessException exception) {
            rethrowAuthorization(exception);
            return new Step("RELATION", id, null, "BLOCKED", "ENERGY_MANAGER", "RESOLVE_ISSUES",
                    List.of(new Issue("DEPENDENCY_UNAVAILABLE", id, exception.getMessage())));
        }
    }

    private static Step lifecycle(String domain, String id, long revision, String status, boolean blocked, List<Issue> issues) {
        String state, action, responsibility = "ENERGY_MANAGER";
        if (blocked) { state = "BLOCKED"; action = "RESOLVE_ISSUES"; }
        else if (Set.of("ACTIVE", "EFFECTIVE", "RETIRED", "SUPERSEDED").contains(status)
                || (domain.equals("CLASSIFICATION") && status.equals("APPROVED"))) { state = "CONFIGURED"; action = "VIEW"; }
        else if (status.equals("APPROVED")) { state = "ACTIVATION_REQUIRED"; action = "ACTIVATE"; responsibility = domain.equals("RELATION") ? "PLATFORM_ADMIN" : "RULE_ACTIVATOR"; }
        else if (Set.of("PENDING_REVIEW", "SUBMITTED").contains(status)) { state = "REVIEW_REQUIRED"; action = "REVIEW"; responsibility = domain.equals("RELATION") ? "PLATFORM_ADMIN" : "INDEPENDENT_REVIEWER"; }
        else if(status.equals("DRAFT")) { state = "REVIEW_REQUIRED"; action = "SUBMIT"; }
        else { state="BLOCKED";action="VIEW";issues=List.of(new Issue("UNKNOWN_STATE",id,"版本状态未知，请由原领域核对后继续")); }
        return new Step(domain, id, revision, state, responsibility, action, issues);
    }

    private static Step missing(String domain, String code, String message) {
        return new Step(domain, null, null, "MISSING", "ENERGY_MANAGER", "CONFIGURE", List.of(new Issue(code, null, message)));
    }

    private Version requireProject(long user, Collection<String> roles, String building, String id) {
        return projects.versions(user, roles, building).stream().filter(v -> v.versionId().equals(id)).findFirst()
                .orElseThrow(() -> error(404, NOT_FOUND, "项目版本不存在"));
    }

    private static void rethrowAuthorization(BusinessException exception) {
        if (exception.getCode() == 401 || exception.getCode() == 403) throw exception;
    }
}
