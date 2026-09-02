package com.platform.carbon;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.CarbonRuleRepository.FactorIdentity;
import com.platform.carbon.api.CarbonContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.platform.carbon.CarbonErrors.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 治理排放因子来源、完整因子组合和年度强度分母的不可覆盖版本。 */
public class CarbonRuleService {
    private static final int LIST_LIMIT = 500;

    private final CarbonAuthorization authorization;
    private final CarbonRuleRepository repository;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    @Transactional(rollbackFor = Exception.class)
    public FactorSourceVersion createSource(long userId, Collection<String> roles,
                                            CreateFactorSourceRequest request) {
        authorization.requireRuleMaintainer(userId, roles, null);
        String code = code(request.sourceCode(), "来源编码无效");
        UsageNature nature = usageNature(request.usageNature());
        LocalDateTime now = LocalDateTime.now();
        String sourceId = repository.findSourceIdForUpdate(code);
        if (sourceId == null) {
            sourceId = id();
            try {
                repository.insertSourceIdentity(sourceId, code, userId, now);
            } catch (DuplicateKeyException exception) {
                conflict();
            }
        }
        FactorSourceVersion value = new FactorSourceVersion(sourceId, code, id(),
                repository.nextSourceVersion(sourceId), text(request.sourceName(), 200,
                "来源名称无效"), text(request.publisher(), 200, "发布机构无效"),
                text(request.documentReference(), 500, "来源文号无效"),
                request.publicationYear(), request.publishedOn(), text(request.applicabilityNote(),
                1000, "适用说明无效"), text(request.evidenceReference(), 1000,
                "来源证据无效"), nature, userId, now);
        repository.insertSourceVersion(value);
        audit(userId, null, "CREATE_CARBON_FACTOR_SOURCE_VERSION", value.sourceId(),
                value.sourceVersionId(), null, sourceSummary(value), false);
        return value;
    }

    public List<FactorSourceVersion> listSources(long userId, Collection<String> roles) {
        requireCarbonRole(roles);
        return repository.listSourceVersions(LIST_LIMIT);
    }

    @Transactional(rollbackFor = Exception.class)
    public FactorVersion createFactor(long userId, Collection<String> roles,
                                      CreateFactorVersionRequest request) {
        FactorCategory category = enumValue(FactorCategory.class, request.factorCategory(),
                "因子类别无效");
        ScopeType scope = enumValue(ScopeType.class, request.scopeType(), "范围类型无效");
        ApplicabilityLevel level = enumValue(ApplicabilityLevel.class,
                request.applicabilityLevel(), "适用层级无效");
        UsageNature nature = usageNature(request.usageNature());
        String buildingId = nullableText(request.buildingId(), 32, "建筑编码无效");
        String region = nullableCode(request.regionCode(), 64, "地区编码无效");
        validateApplicability(level, buildingId, region);
        authorization.requireRuleMaintainer(userId, roles, buildingId);
        validateIdentity(scope, category, request.resultBasis(), request.gasCode(),
                request.gasCoverage());
        if (request.effectiveTo() != null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            validation("因子有效期无效");
        }
        FactorSourceVersion source = requireSource(request.sourceVersionId());
        requireSourceNature(source.usageNature(), nature);
        if (!repository.activeFormulaMatches(request.formulaVersionId(), category, nature)) {
            validation("公式版本不存在、未激活或与因子类别不一致");
        }
        if (!repository.activeRoundingPolicy(request.roundingPolicyVersionId())) {
            validation("舍入策略版本不存在或未激活");
        }
        List<FactorComponent> components = components(request.components(), nature);
        validateBundle(category, normalize(request.inputUnitCode()), components);
        String factorCode = code(request.factorCode(), "因子编码无效");
        String itemCode = code(request.energyItemCode(), "能源品种编码无效");
        LocalDateTime now = LocalDateTime.now();
        String factorId = repository.findFactorIdForUpdate(factorCode);
        FactorIdentity expected = new FactorIdentity(factorId, factorCode, scope, itemCode,
                category, normalize(request.resultBasis()), normalize(request.gasCode()),
                normalize(request.gasCoverage()), userId, now);
        if (factorId == null) {
            factorId = id();
            expected = new FactorIdentity(factorId, factorCode, scope, itemCode, category,
                    normalize(request.resultBasis()), normalize(request.gasCode()),
                    normalize(request.gasCoverage()), userId, now);
            try {
                repository.insertFactorIdentity(expected);
            } catch (DuplicateKeyException exception) {
                conflict();
            }
        } else {
            verifyIdentity(repository.findFactorIdentity(factorId), expected);
        }
        FactorVersion value = new FactorVersion(factorId, factorCode, id(),
                repository.nextFactorVersion(factorId), scope, itemCode, category,
                normalize(request.resultBasis()), normalize(request.gasCode()),
                normalize(request.gasCoverage()), source.sourceVersionId(), level, buildingId,
                region, normalize(request.inputUnitCode()),
                nullableCode(request.standardConditionCode(), 100, "标准状态编码无效"),
                nature, LifecycleStatus.PENDING_REVIEW, request.effectiveFrom(),
                request.effectiveTo(), request.formulaVersionId(),
                request.roundingPolicyVersionId(), 0, userId, now, null, null, null,
                null, null, components);
        repository.insertFactorVersion(value);
        repository.insertComponents(value.factorVersionId(), components);
        FactorVersion created = requireFactor(value.factorVersionId());
        audit(userId, buildingId, "CREATE_CARBON_FACTOR_VERSION", factorId,
                created.factorVersionId(), null, factorSummary(created), false);
        return created;
    }

    @Transactional(rollbackFor = Exception.class)
    public FactorVersion reviewFactor(long userId, Collection<String> roles, String versionId,
                                      ReviewRequest request) {
        FactorVersion value = requireFactor(versionId);
        authorization.requireRuleReviewer(userId, roles, value.buildingId());
        authorization.requireSeparation(value.createdBy(), userId);
        if (value.status() != LifecycleStatus.PENDING_REVIEW) conflict();
        LocalDateTime now = LocalDateTime.now();
        if (repository.reviewFactor(value.factorVersionId(), request.expectedRevision(), userId,
                now, text(request.reviewComment(), 500, "审核意见无效"),
                request.approved()) != 1) conflict();
        FactorVersion reviewed = requireFactor(versionId);
        audit(userId, reviewed.buildingId(), request.approved()
                        ? "APPROVE_CARBON_FACTOR_VERSION" : "REJECT_CARBON_FACTOR_VERSION",
                reviewed.factorId(), reviewed.factorVersionId(), factorSummary(value),
                factorSummary(reviewed), value.createdBy() == userId
                        && auditProperties.isAllowSelfApproval());
        return reviewed;
    }

    @Transactional(rollbackFor = Exception.class)
    public FactorVersion activateFactor(long userId, Collection<String> roles,
                                        String versionId, LifecycleRequest request) {
        FactorVersion value = requireFactor(versionId);
        authorization.requireRuleActivator(userId, roles, value.buildingId());
        if (value.status() != LifecycleStatus.APPROVED) conflict();
        boolean replaced = repository.hasOverlappingActiveFactor(value);
        LocalDateTime now = LocalDateTime.now();
        repository.disableOverlappingFactors(value, userId, now);
        if (repository.activateFactor(versionId, request.expectedRevision(), userId, now) != 1) {
            conflict();
        }
        String changeType = replaced ? "FACTOR" : "MISSING_FACTOR_FILLED";
        dependency(changeType, "CARBON_FACTOR_VERSION", value.factorId(), null,
                value.factorVersionId(), value.buildingId(), value.effectiveFrom(),
                value.effectiveTo(), userId, now);
        FactorVersion activated = requireFactor(versionId);
        audit(userId, activated.buildingId(), "ACTIVATE_CARBON_FACTOR_VERSION",
                activated.factorId(), activated.factorVersionId(), factorSummary(value),
                factorSummary(activated), false);
        return activated;
    }

    @Transactional(rollbackFor = Exception.class)
    public FactorVersion disableFactor(long userId, Collection<String> roles,
                                       String versionId, LifecycleRequest request) {
        FactorVersion value = requireFactor(versionId);
        authorization.requireRuleActivator(userId, roles, value.buildingId());
        LocalDateTime now = LocalDateTime.now();
        if (repository.disableFactor(versionId, request.expectedRevision(), userId, now) != 1) {
            conflict();
        }
        dependency("FACTOR", "CARBON_FACTOR_VERSION", value.factorId(),
                value.factorVersionId(), "DISABLED_" + value.factorVersionId(),
                value.buildingId(), value.effectiveFrom(), value.effectiveTo(), userId, now);
        FactorVersion disabled = requireFactor(versionId);
        audit(userId, disabled.buildingId(), "DISABLE_CARBON_FACTOR_VERSION",
                disabled.factorId(), disabled.factorVersionId(), factorSummary(value),
                factorSummary(disabled), false);
        return disabled;
    }

    public List<FactorVersion> listFactors(long userId, Collection<String> roles) {
        requireCarbonRole(roles);
        return repository.listFactorVersions(LIST_LIMIT);
    }

    @Transactional(rollbackFor = Exception.class)
    public DenominatorVersion createDenominator(long userId, Collection<String> roles,
                                                CreateDenominatorRequest request) {
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.requireRuleMaintainer(userId, roles, buildingId);
        DenominatorType type = enumValue(DenominatorType.class, request.denominatorType(),
                "分母类型无效");
        String unit = normalize(request.unitCode());
        String expectedUnit = type == DenominatorType.BUILDING_AREA ? "M2" : "PERSON";
        if (!expectedUnit.equals(unit) || request.value().signum() <= 0) {
            validation("分母数值或单位无效");
        }
        if (request.effectiveTo() != null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            validation("分母有效期无效");
        }
        LocalDateTime now = LocalDateTime.now();
        String denominatorId = repository.findDenominatorIdForUpdate(buildingId, type);
        if (denominatorId == null) {
            denominatorId = id();
            try {
                repository.insertDenominatorIdentity(denominatorId, buildingId, type, userId, now);
            } catch (DuplicateKeyException exception) {
                conflict();
            }
        }
        DenominatorVersion value = new DenominatorVersion(denominatorId, id(),
                repository.nextDenominatorVersion(denominatorId), buildingId, type,
                request.value(), unit, text(request.sourceReference(), 1000, "分母来源无效"),
                text(request.evidenceReference(), 1000, "分母证据无效"),
                usageNature(request.usageNature()), LifecycleStatus.PENDING_REVIEW,
                request.effectiveFrom(), request.effectiveTo(), 0, userId, now,
                null, null, null, null, null);
        repository.insertDenominatorVersion(value);
        audit(userId, buildingId, "CREATE_CARBON_DENOMINATOR_VERSION", denominatorId,
                value.denominatorVersionId(), null, denominatorSummary(value), false);
        return requireDenominator(value.denominatorVersionId());
    }

    @Transactional(rollbackFor = Exception.class)
    public DenominatorVersion reviewDenominator(long userId, Collection<String> roles,
                                                String versionId, ReviewRequest request) {
        DenominatorVersion value = requireDenominator(versionId);
        authorization.requireRuleReviewer(userId, roles, value.buildingId());
        authorization.requireSeparation(value.createdBy(), userId);
        if (repository.reviewDenominator(versionId, request.expectedRevision(), userId,
                LocalDateTime.now(), text(request.reviewComment(), 500, "审核意见无效"),
                request.approved()) != 1) conflict();
        DenominatorVersion reviewed = requireDenominator(versionId);
        audit(userId, reviewed.buildingId(), request.approved()
                        ? "APPROVE_CARBON_DENOMINATOR_VERSION"
                        : "REJECT_CARBON_DENOMINATOR_VERSION",
                reviewed.denominatorId(), reviewed.denominatorVersionId(),
                denominatorSummary(value), denominatorSummary(reviewed), false);
        return reviewed;
    }

    @Transactional(rollbackFor = Exception.class)
    public DenominatorVersion activateDenominator(long userId, Collection<String> roles,
                                                  String versionId, LifecycleRequest request) {
        DenominatorVersion value = requireDenominator(versionId);
        authorization.requireRuleActivator(userId, roles, value.buildingId());
        if (value.status() != LifecycleStatus.APPROVED) conflict();
        LocalDateTime now = LocalDateTime.now();
        repository.disableOverlappingDenominators(value, userId, now);
        if (repository.activateDenominator(versionId, request.expectedRevision(), userId, now) != 1) {
            conflict();
        }
        dependency("DENOMINATOR", "CARBON_DENOMINATOR_VERSION", value.denominatorId(),
                null, value.denominatorVersionId(), value.buildingId(),
                value.effectiveFrom().atStartOfDay(),
                value.effectiveTo() == null ? null : value.effectiveTo().atStartOfDay(),
                userId, now);
        DenominatorVersion activated = requireDenominator(versionId);
        audit(userId, activated.buildingId(), "ACTIVATE_CARBON_DENOMINATOR_VERSION",
                activated.denominatorId(), activated.denominatorVersionId(),
                denominatorSummary(value), denominatorSummary(activated), false);
        return activated;
    }

    @Transactional(rollbackFor = Exception.class)
    public DenominatorVersion disableDenominator(long userId, Collection<String> roles,
                                                 String versionId, LifecycleRequest request) {
        DenominatorVersion value = requireDenominator(versionId);
        authorization.requireRuleActivator(userId, roles, value.buildingId());
        LocalDateTime now = LocalDateTime.now();
        if (repository.disableDenominator(versionId, request.expectedRevision(), userId, now) != 1) {
            conflict();
        }
        dependency("DENOMINATOR", "CARBON_DENOMINATOR_VERSION", value.denominatorId(),
                value.denominatorVersionId(), "DISABLED_" + value.denominatorVersionId(),
                value.buildingId(), value.effectiveFrom().atStartOfDay(),
                value.effectiveTo() == null ? null : value.effectiveTo().atStartOfDay(),
                userId, now);
        DenominatorVersion disabled = requireDenominator(versionId);
        audit(userId, disabled.buildingId(), "DISABLE_CARBON_DENOMINATOR_VERSION",
                disabled.denominatorId(), disabled.denominatorVersionId(),
                denominatorSummary(value), denominatorSummary(disabled), false);
        return disabled;
    }

    public List<DenominatorVersion> listDenominators(long userId, Collection<String> roles,
                                                     String buildingId) {
        authorization.requireReader(userId, roles, buildingId);
        return repository.listDenominatorVersions(buildingId, LIST_LIMIT);
    }

    FactorVersion requireFactor(String versionId) {
        FactorVersion value = repository.findFactorVersion(text(versionId, 32, "因子版本标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "排放因子版本不存在");
        return value;
    }

    DenominatorVersion requireDenominator(String versionId) {
        DenominatorVersion value = repository.findDenominatorVersion(
                text(versionId, 32, "分母版本标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "分母版本不存在");
        return value;
    }

    private FactorSourceVersion requireSource(String versionId) {
        FactorSourceVersion value = repository.findSourceVersion(
                text(versionId, 32, "来源版本标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "排放因子来源版本不存在");
        return value;
    }

    private List<FactorComponent> components(List<FactorComponentRequest> requests,
                                             UsageNature factorNature) {
        Set<ComponentType> types = new LinkedHashSet<>();
        return requests.stream().map(request -> {
            ComponentType type = enumValue(ComponentType.class, request.componentType(),
                    "因子参数类型无效");
            if (!types.add(type)) validation("因子参数类型不得重复");
            FactorSourceVersion source = requireSource(request.sourceVersionId());
            requireSourceNature(source.usageNature(), factorNature);
            return new FactorComponent(id(), type, request.value(),
                    normalize(request.unit()), source.sourceVersionId(),
                    text(request.evidenceReference(), 1000, "因子参数证据无效"));
        }).toList();
    }

    private static void validateBundle(FactorCategory category, String inputUnit,
                                       List<FactorComponent> values) {
        if (category == FactorCategory.STATIONARY_COMBUSTION) {
            if (values.size() != 3 || value(values, ComponentType.LOWER_HEATING_VALUE).signum() <= 0
                    || value(values, ComponentType.CARBON_CONTENT_PER_HEAT).signum() <= 0
                    || value(values, ComponentType.OXIDATION_RATE).signum() <= 0
                    || value(values, ComponentType.OXIDATION_RATE).compareTo(BigDecimal.ONE) > 0
                    || !unit(values, ComponentType.LOWER_HEATING_VALUE).equals("MJ/" + inputUnit)
                    || !unit(values, ComponentType.CARBON_CONTENT_PER_HEAT).equals("KG_C_PER_MJ")
                    || !unit(values, ComponentType.OXIDATION_RATE).equals("RATIO")) {
                validation("固定燃烧因子必须包含完整且单位一致的三项参数");
            }
        } else if (values.size() != 1
                || value(values, ComponentType.DIRECT_EMISSION_FACTOR).signum() < 0
                || !unit(values, ComponentType.DIRECT_EMISSION_FACTOR)
                .equals("KG_CO2E/" + inputUnit)) {
            validation("电力或热力因子必须包含一个单位一致的直接排放因子");
        }
    }

    private static BigDecimal value(List<FactorComponent> values, ComponentType type) {
        return values.stream().filter(item -> item.type() == type).findFirst()
                .map(FactorComponent::value).orElse(BigDecimal.valueOf(-1));
    }

    private static String unit(List<FactorComponent> values, ComponentType type) {
        return values.stream().filter(item -> item.type() == type).findFirst()
                .map(FactorComponent::unit).orElse("");
    }

    private static void validateIdentity(ScopeType scope, FactorCategory category,
                                         String resultBasis, String gasCode,
                                         String gasCoverage) {
        boolean stationary = category == FactorCategory.STATIONARY_COMBUSTION;
        if (stationary && (scope != ScopeType.SCOPE_1
                || !"GAS_MASS".equals(normalize(resultBasis))
                || !"CO2".equals(normalize(gasCode))
                || !"CO2_ONLY_FOR_STATIONARY_COMBUSTION".equals(normalize(gasCoverage)))) {
            validation("固定燃烧首版只能使用范围一CO2质量口径");
        }
        if (!stationary && (scope != ScopeType.SCOPE_2
                || !"CO2E_DIRECT".equals(normalize(resultBasis)))) {
            validation("外购电力和外购热力首版只能使用范围二CO2e直接因子");
        }
    }

    private static void validateApplicability(ApplicabilityLevel level, String building,
                                              String region) {
        if ((level == ApplicabilityLevel.BUILDING_SPECIFIC) != (building != null)
                || (level == ApplicabilityLevel.PROVINCE) != (region != null)) {
            validation("因子适用层级与建筑或地区编码不一致");
        }
    }

    private static void verifyIdentity(FactorIdentity actual, FactorIdentity expected) {
        if (actual == null || actual.scopeType() != expected.scopeType()
                || !actual.energyItemCode().equals(expected.energyItemCode())
                || actual.category() != expected.category()
                || !actual.resultBasis().equals(expected.resultBasis())
                || !actual.gasCode().equals(expected.gasCode())
                || !actual.gasCoverage().equals(expected.gasCoverage())) {
            conflict();
        }
    }

    private static void requireSourceNature(UsageNature source, UsageNature factor) {
        if (factor == UsageNature.FORMAL && source != UsageNature.FORMAL) {
            validation("正式因子或分母不得引用研发来源");
        }
    }

    private void dependency(String type, String objectType, String objectId,
                            String oldVersionId, String newVersionId, String buildingId,
                            LocalDateTime from, LocalDateTime to, Long actorId,
                            LocalDateTime now) {
        String raw = type + '|' + objectType + '|' + objectId + '|' + oldVersionId + '|'
                + newVersionId + '|' + buildingId + '|' + from + '|' + to;
        repository.insertDependencyChange(id(), type, objectType, objectId,
                "依赖版本生效触发碳结果影响分析", oldVersionId,
                newVersionId, CarbonCalculationCore.sha256(raw), buildingId, null, from, to,
                actorId, now);
    }

    private void audit(long userId, String buildingId, String action, String objectId,
                       String versionId, String before, String after, boolean selfApproval) {
        auditWriter.append(new AuditEvidence("CARBON_MANAGEMENT", buildingId, "USER", userId,
                action, "CARBON_RULE", objectId, versionId, null, before, after,
                "SUCCESS", null, TraceContext.current(), LocalDateTime.now(),
                auditProperties.getEnvironmentMode(), selfApproval));
    }

    private static String sourceSummary(FactorSourceVersion value) {
        return "source=" + value.sourceCode() + ";version=" + value.versionNo()
                + ";nature=" + value.usageNature();
    }

    private static String factorSummary(FactorVersion value) {
        return "factor=" + value.factorCode() + ";version=" + value.versionNo()
                + ";status=" + value.status() + ";level=" + value.applicabilityLevel()
                + ";nature=" + value.usageNature();
    }

    private static String denominatorSummary(DenominatorVersion value) {
        return "type=" + value.type() + ";version=" + value.versionNo()
                + ";status=" + value.status() + ";nature=" + value.usageNature();
    }

    private static void requireCarbonRole(Collection<String> roles) {
        if (roles == null || roles.stream().noneMatch(value -> Set.of(
                "BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN").contains(value))) {
            throw error(403, FORBIDDEN, "角色无权访问碳管理数据");
        }
    }

    private static UsageNature usageNature(String value) {
        return enumValue(UsageNature.class, value, "使用性质无效");
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, normalize(value));
        } catch (RuntimeException exception) {
            validation(message);
            return null;
        }
    }

    private static String code(String value, String message) {
        String result = normalize(value);
        if (result == null || !result.matches("[A-Z][A-Z0-9_]{1,63}")) validation(message);
        return result;
    }

    private static String nullableCode(String value, int max, String message) {
        String result = nullableText(value, max, message);
        return result == null ? null : normalize(result);
    }

    private static String text(String value, int max, String message) {
        if (value == null || value.isBlank() || value.trim().length() > max) validation(message);
        return value.trim();
    }

    private static String nullableText(String value, int max, String message) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) validation(message);
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void validation(String message) {
        throw error(400, VALIDATION_FAILED, message);
    }

    private static void conflict() {
        throw error(409, VERSION_CONFLICT, "状态、版本或稳定身份与现有数据冲突");
    }
}
