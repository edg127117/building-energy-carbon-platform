package com.platform.energy.conversion;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.energy.catalog.EnergyCatalogLookup.ApprovedEnergyItem;
import com.platform.energy.catalog.EnergyCatalogLookup.ApprovedUnit;
import com.platform.energy.catalog.EnergyCatalogLookup.UnitConversion;
import com.platform.energy.catalog.EnergyCatalogModels.SourceType;
import com.platform.energy.catalog.EnergyCatalogModels.UsageScope;
import com.platform.energy.conversion.EnergyConversionRepository.FormulaRow;
import com.platform.energy.conversion.EnergyConversionRepository.ParameterRow;
import com.platform.energy.conversion.EnergyConversionRepository.StandardCoalRow;
import com.platform.energy.conversion.api.EnergyConversionContracts.ApproveRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateFormulaVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateParameterVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateStandardCoalVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.FormulaVersionView;
import com.platform.energy.conversion.api.EnergyConversionContracts.OptionsView;
import com.platform.energy.conversion.api.EnergyConversionContracts.ParameterVersionView;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationResultView;
import com.platform.energy.conversion.api.EnergyConversionContracts.StandardCoalVersionView;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.platform.energy.conversion.EnergyConversionModels.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 统一治理折标参数和公式版本，并以固定十进制算法生成可追溯的研发模拟 tce。 */
public class EnergyConversionService {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;
    private static final String STANDARD_COAL_CODE = "STANDARD_COAL_LHV";

    private final EnergyConversionAuthorization authorization;
    private final EnergyConversionRepository repository;
    private final EnergyCatalogLookup catalogLookup;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    public List<StandardCoalVersionView> listStandardCoalVersions(Collection<String> roles) {
        authorization.requireReader(roles);
        return repository.listStandardCoalVersions().stream().map(EnergyConversionService::standardView).toList();
    }

    public List<FormulaVersionView> listFormulaVersions(Collection<String> roles) {
        authorization.requireReader(roles);
        return repository.listFormulaVersions().stream().map(EnergyConversionService::formulaView).toList();
    }

    public List<ParameterVersionView> listParameterVersions(Collection<String> roles) {
        authorization.requireReader(roles);
        return repository.listParameterVersions().stream().map(EnergyConversionService::parameterView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public StandardCoalVersionView createStandardCoalVersion(
            long userId, Collection<String> roles, CreateStandardCoalVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String lhvCode = code(request.lhvCode(), "标准煤低位热值编码无效");
        if (!STANDARD_COAL_CODE.equals(lhvCode)) validation("首版只允许标准煤低位热值身份");
        ParameterUnit unit = parse(ParameterUnit.class, request.parameterUnit(), "标准煤低位热值单位无效");
        if (unit != ParameterUnit.GJ_PER_TCE) validation("标准煤低位热值必须使用 GJ_PER_TCE");
        SourceType source = parse(SourceType.class, request.sourceType(), "来源类型无效");
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        ApprovedUnit energyUnit = approvedUnit("GJ", range.from());
        ApprovedUnit coalUnit = approvedUnit("TCE", range.from());
        LocalDateTime now = LocalDateTime.now();
        String lhvId = repository.findStandardCoalIdForUpdate(lhvCode);
        if (lhvId == null) {
            lhvId = id();
            try {
                repository.insertStandardCoalIdentity(lhvId, lhvCode, userId, now);
            } catch (DuplicateKeyException ex) {
                versionConflict();
            }
        }
        StandardCoalRow value = new StandardCoalRow(lhvId, lhvCode, id(),
                repository.nextStandardCoalVersion(lhvId), positive(request.value(), "低位热值必须大于0"),
                energyUnit.versionId(), coalUnit.versionId(), unit.name(), RuleStatus.PENDING_EXPERT.name(),
                source.name(), text(request.sourceReference(), 500, "来源引用无效"), range.from(), range.to(),
                0, userId, now, null, null);
        repository.insertStandardCoalVersion(value);
        audit(userId, "CREATE_STANDARD_COAL_LHV_VERSION", "STANDARD_COAL_LHV", value.lhvId(),
                value.versionId(), null, standardSummary(value), false, null);
        return standardView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public FormulaVersionView createFormulaVersion(
            long userId, Collection<String> roles, CreateFormulaVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String formulaCode = code(request.formulaCode(), "公式编码无效");
        ConversionMethod method = parse(ConversionMethod.class, request.method(), "折标方法无效");
        ConversionPerspective perspective = parse(ConversionPerspective.class, request.perspective(), "折标口径无效");
        FormulaAlgorithm algorithm = parse(FormulaAlgorithm.class, request.algorithmCode(), "公式算法无效");
        ParameterUnit parameterUnit = parse(ParameterUnit.class, request.parameterUnit(), "参数单位无效");
        SourceType source = parse(SourceType.class, request.sourceType(), "来源类型无效");
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        ApprovedUnit inputUnit = approvedUnit(code(request.applicableInputUnitCode(), "适用输入单位无效"), range.from());
        ApprovedUnit resultUnit = approvedUnit(code(request.resultUnitCode(), "结果单位无效"), range.from());
        validateFormula(method, perspective, algorithm, parameterUnit, inputUnit, resultUnit);
        LocalDateTime now = LocalDateTime.now();
        String formulaId = repository.findFormulaIdForUpdate(formulaCode);
        if (formulaId == null) {
            formulaId = id();
            try {
                repository.insertFormulaIdentity(formulaId, formulaCode, userId, now);
            } catch (DuplicateKeyException ex) {
                versionConflict();
            }
        }
        FormulaRow value = new FormulaRow(formulaId, formulaCode, id(),
                repository.nextFormulaVersion(formulaId), method.name(), perspective.name(), algorithm.name(),
                inputUnit.versionId(), inputUnit.unitCode(), resultUnit.versionId(), resultUnit.unitCode(),
                parameterUnit.name(), RuleStatus.PENDING_EXPERT.name(), source.name(),
                text(request.sourceReference(), 500, "来源引用无效"), range.from(), range.to(),
                0, userId, now, null, null);
        repository.insertFormulaVersion(value);
        audit(userId, "CREATE_TCE_FORMULA_VERSION", "ENERGY_CONVERSION_FORMULA", value.formulaId(),
                value.versionId(), null, formulaSummary(value), false, null);
        return formulaView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public ParameterVersionView createParameterVersion(
            long userId, Collection<String> roles, CreateParameterVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String parameterCode = code(request.parameterCode(), "折标参数编码无效");
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        ApprovedEnergyItem item = approvedItem(request.energyItemCode(), range.from());
        UsageScope consumptionScope = parse(UsageScope.class, request.consumptionScope(), "能源使用范围无效");
        validateConsumptionScope(item, consumptionScope);
        RuleUsageScope usageScope = parse(RuleUsageScope.class, request.usageScope(), "规则使用范围无效");
        SourceType source = parse(SourceType.class, request.sourceType(), "来源类型无效");
        FormulaRow formula = requireFormula(request.formulaVersionId());
        requireApproved(formula.status(), "公式版本尚未审核");
        requireRange(formula.effectiveFrom(), formula.effectiveTo(), range, "参数有效期超出公式版本范围");
        if (!formula.parameterUnit().equals(parse(ParameterUnit.class, request.parameterUnit(), "参数单位无效").name())) {
            validation("参数单位与公式版本不一致");
        }
        StandardCoalRow standardCoal = resolveStandardCoal(formula, request.standardCoalLhvVersionId(), range);
        LocalDateTime now = LocalDateTime.now();
        String parameterId = repository.findParameterIdForUpdate(parameterCode);
        if (parameterId == null) {
            parameterId = id();
            try {
                repository.insertParameterIdentity(parameterId, parameterCode, item.itemId(), userId, now);
            } catch (DuplicateKeyException ex) {
                versionConflict();
            }
        } else if (!Objects.equals(repository.findParameterItemId(parameterId), item.itemId())) {
            validation("同一折标参数身份不能变更能源品种");
        }
        ParameterRow value = new ParameterRow(parameterId, parameterCode, id(),
                repository.nextParameterVersion(parameterId), item.itemId(), item.itemCode(), item.versionId(),
                formula.versionId(), formula.formulaId(), formula.formulaCode(), formula.method(),
                formula.perspective(), formula.algorithmCode(), formula.inputUnitVersionId(),
                formula.inputUnitCode(), formula.resultUnitVersionId(), formula.resultUnitCode(),
                positive(request.parameterValue(), "折标参数值必须大于0"), formula.parameterUnit(),
                standardCoal == null ? null : standardCoal.versionId(), consumptionScope.name(),
                region(request.regionCode()), usageScope.name(), RuleStatus.PENDING_EXPERT.name(),
                source.name(), text(request.sourceReference(), 500, "来源引用无效"),
                range.from(), range.to(), 0, userId, now, null, null);
        repository.insertParameterVersion(value);
        audit(userId, "CREATE_TCE_PARAMETER_VERSION", "ENERGY_CONVERSION_PARAMETER",
                value.parameterId(), value.versionId(), null, parameterSummary(value), false, null);
        return parameterView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public StandardCoalVersionView approveStandardCoal(
            long userId, Collection<String> roles, String versionId, ApproveRequest request) {
        authorization.requireReviewer(userId, roles);
        StandardCoalRow value = requireStandardCoal(versionId);
        requirePending(value.status());
        authorization.requireSeparation(value.createdBy(), userId);
        requireUnitReference("GJ", value.energyUnitVersionId(), value.effectiveFrom());
        requireUnitReference("TCE", value.coalUnitVersionId(), value.effectiveFrom());
        StandardCoalRow previous = repository.findLatestApprovedStandardCoal(value.lhvId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeStandardCoalVersion);
        if (repository.approveStandardCoal(versionId, request.expectedRevision(), userId, LocalDateTime.now()) != 1) {
            versionConflict();
        }
        StandardCoalRow approved = requireStandardCoal(versionId);
        audit(userId, "APPROVE_STANDARD_COAL_LHV_VERSION", "STANDARD_COAL_LHV", approved.lhvId(),
                approved.versionId(), standardSummary(value), reviewed(standardSummary(approved), request),
                selfApproval(value.createdBy(), userId), null);
        return standardView(approved);
    }

    @Transactional(rollbackFor = Exception.class)
    public FormulaVersionView approveFormula(
            long userId, Collection<String> roles, String versionId, ApproveRequest request) {
        authorization.requireReviewer(userId, roles);
        FormulaRow value = requireFormula(versionId);
        requirePending(value.status());
        authorization.requireSeparation(value.createdBy(), userId);
        ApprovedUnit input = requireUnitReference(value.inputUnitCode(), value.inputUnitVersionId(), value.effectiveFrom());
        ApprovedUnit result = requireUnitReference(value.resultUnitCode(), value.resultUnitVersionId(), value.effectiveFrom());
        validateFormula(parse(ConversionMethod.class, value.method(), "折标方法无效"),
                parse(ConversionPerspective.class, value.perspective(), "折标口径无效"),
                parse(FormulaAlgorithm.class, value.algorithmCode(), "公式算法无效"),
                parse(ParameterUnit.class, value.parameterUnit(), "参数单位无效"), input, result);
        FormulaRow previous = repository.findLatestApprovedFormula(value.formulaId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeFormulaVersion);
        if (repository.approveFormula(versionId, request.expectedRevision(), userId, LocalDateTime.now()) != 1) {
            versionConflict();
        }
        FormulaRow approved = requireFormula(versionId);
        audit(userId, "APPROVE_TCE_FORMULA_VERSION", "ENERGY_CONVERSION_FORMULA",
                approved.formulaId(), approved.versionId(), formulaSummary(value),
                reviewed(formulaSummary(approved), request), selfApproval(value.createdBy(), userId), null);
        return formulaView(approved);
    }

    @Transactional(rollbackFor = Exception.class)
    public ParameterVersionView approveParameter(
            long userId, Collection<String> roles, String versionId, ApproveRequest request) {
        authorization.requireReviewer(userId, roles);
        ParameterRow value = requireParameter(versionId);
        requirePending(value.status());
        authorization.requireSeparation(value.createdBy(), userId);
        validateParameterReferences(value, new TimeRange(value.effectiveFrom(), value.effectiveTo()));
        ParameterRow previous = repository.findLatestApprovedParameter(value.parameterId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeParameterVersion);
        if (repository.approveParameter(versionId, request.expectedRevision(), userId, LocalDateTime.now()) != 1) {
            versionConflict();
        }
        ParameterRow approved = requireParameter(versionId);
        audit(userId, "APPROVE_TCE_PARAMETER_VERSION", "ENERGY_CONVERSION_PARAMETER",
                approved.parameterId(), approved.versionId(), parameterSummary(value),
                reviewed(parameterSummary(approved), request), selfApproval(value.createdBy(), userId), null);
        return parameterView(approved);
    }

    @Transactional(rollbackFor = Exception.class)
    public SimulationResultView simulate(
            long userId, Collection<String> roles, SimulationRequest request) {
        authorization.requireCalculationRunner(userId, roles);
        authorization.checkBuilding(userId, roles, text(request.buildingId(), 32, "建筑编码无效"));
        LocalDateTime effectiveAt = requiredTime(request.effectiveAt());
        ApprovedEnergyItem item = approvedItem(request.energyItemCode(), effectiveAt);
        ConversionMethod method = parse(ConversionMethod.class, request.method(), "折标方法无效");
        ConversionPerspective perspective = parse(ConversionPerspective.class, request.perspective(), "折标口径无效");
        UsageScope consumptionScope = parse(UsageScope.class, request.consumptionScope(), "能源使用范围无效");
        validateConsumptionScope(item, consumptionScope);
        List<ParameterRow> matches = repository.findMatchingParameters(item.itemId(), method.name(),
                perspective.name(), consumptionScope.name(), region(request.regionCode()),
                RuleUsageScope.DEVELOPMENT_SIMULATION.name(), effectiveAt);
        if (matches.isEmpty()) {
            throw EnergyConversionErrors.error(409, EnergyConversionErrors.RULE_MISSING,
                    "指定业务时间没有唯一可用的研发折标规则");
        }
        if (matches.size() != 1) {
            throw EnergyConversionErrors.error(409, EnergyConversionErrors.RULE_CONFLICT,
                    "指定范围匹配到多条折标规则，拒绝自动选择最新版本");
        }
        ParameterRow parameter = matches.getFirst();
        validateParameterReferences(parameter, new TimeRange(effectiveAt, effectiveAt.plusNanos(1)));
        BigDecimal quantity = nonNegative(request.quantity(), "活动量不能为负数");
        UnitConversion conversion = catalogLookup.convert(quantity,
                        code(request.inputUnitCode(), "输入单位无效"), parameter.inputUnitCode(), effectiveAt)
                .orElseThrow(() -> EnergyConversionErrors.error(409,
                        EnergyConversionErrors.UNIT_INCOMPATIBLE, "输入单位不能换算到折标规则适用单位"));
        if (!Objects.equals(conversion.targetUnit().versionId(), parameter.inputUnitVersionId())) {
            referenceConflict("折标规则引用的输入单位版本已不再有效");
        }
        StandardCoalRow standardCoal = parameter.standardCoalVersionId() == null
                ? null : requireStandardCoal(parameter.standardCoalVersionId());
        BigDecimal tce = calculate(parameter, standardCoal, conversion.convertedQuantity());
        SimulationResultView result = new SimulationResultView(ResultNature.DEVELOPMENT_SIMULATION.name(),
                request.buildingId().trim(), item.itemCode(), item.versionId(), quantity,
                conversion.inputUnit().unitCode(), conversion.inputUnit().versionId(),
                conversion.convertedQuantity(), conversion.targetUnit().unitCode(),
                conversion.targetUnit().versionId(), parameter.method(), parameter.perspective(),
                parameter.parameterValue(), parameter.parameterUnit(), parameter.versionId(),
                parameter.formulaVersionId(), parameter.algorithmCode(), parameter.standardCoalVersionId(),
                tce, parameter.resultUnitCode(), effectiveAt);
        audit(userId, "RUN_TCE_DEVELOPMENT_SIMULATION", "ENERGY_CONVERSION_SIMULATION",
                parameter.parameterId(), parameter.versionId(), null, simulationSummary(result), false,
                request.buildingId().trim());
        return result;
    }

    public OptionsView options(Collection<String> roles) {
        authorization.requireReader(roles);
        return new OptionsView(names(RuleStatus.values()), names(ConversionMethod.values()),
                names(ConversionPerspective.values()), names(FormulaAlgorithm.values()),
                names(ParameterUnit.values()), names(RuleUsageScope.values()), names(ResultNature.values()));
    }

    /** 算法版本固定使用 DECIMAL128 中间精度，不执行展示或正式结算舍入。 */
    private static BigDecimal calculate(ParameterRow parameter, StandardCoalRow standardCoal,
                                        BigDecimal convertedQuantity) {
        FormulaAlgorithm algorithm = parse(FormulaAlgorithm.class, parameter.algorithmCode(), "公式算法无效");
        BigDecimal numerator = convertedQuantity.multiply(parameter.parameterValue(), CALCULATION_CONTEXT);
        BigDecimal value = switch (algorithm) {
            case DIRECT_TCE_FACTOR_V1 -> numerator;
            case LOWER_HEATING_VALUE_V1 -> numerator.divide(requireStandardValue(standardCoal), CALCULATION_CONTEXT);
            case ENERGY_EQUIVALENT_V1 -> numerator.divide(
                    requireStandardValue(standardCoal).multiply(new BigDecimal("1000")), CALCULATION_CONTEXT);
        };
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private void validateParameterReferences(ParameterRow value, TimeRange range) {
        ApprovedEnergyItem item = approvedItem(value.itemCode(), range.from());
        if (!Objects.equals(item.versionId(), value.energyItemVersionId())) {
            referenceConflict("折标参数引用的能源品种版本已不再有效");
        }
        validateConsumptionScope(item,
                parse(UsageScope.class, value.consumptionScope(), "能源使用范围无效"));
        FormulaRow formula = requireFormula(value.formulaVersionId());
        requireApproved(formula.status(), "折标参数引用的公式尚未审核");
        requireRange(formula.effectiveFrom(), formula.effectiveTo(), range, "折标参数超出公式有效期");
        if (!Objects.equals(formula.parameterUnit(), value.parameterUnit())) {
            referenceConflict("折标参数单位与公式版本不一致");
        }
        requireUnitReference(formula.inputUnitCode(), formula.inputUnitVersionId(), range.from());
        requireUnitReference(formula.resultUnitCode(), formula.resultUnitVersionId(), range.from());
        if (value.standardCoalVersionId() != null) {
            StandardCoalRow standard = requireStandardCoal(value.standardCoalVersionId());
            requireApproved(standard.status(), "标准煤低位热值版本尚未审核");
            requireRange(standard.effectiveFrom(), standard.effectiveTo(), range,
                    "折标参数超出标准煤低位热值有效期");
        } else if (!FormulaAlgorithm.DIRECT_TCE_FACTOR_V1.name().equals(formula.algorithmCode())) {
            referenceConflict("当前公式必须引用标准煤低位热值版本");
        }
    }

    private StandardCoalRow resolveStandardCoal(FormulaRow formula, String versionId, TimeRange range) {
        if (FormulaAlgorithm.DIRECT_TCE_FACTOR_V1.name().equals(formula.algorithmCode())) {
            if (versionId != null && !versionId.isBlank()) validation("直接折标系数不能引用标准煤低位热值");
            return null;
        }
        if (versionId == null || versionId.isBlank()) validation("当前公式必须引用标准煤低位热值版本");
        StandardCoalRow value = requireStandardCoal(versionId.trim());
        requireApproved(value.status(), "标准煤低位热值版本尚未审核");
        requireRange(value.effectiveFrom(), value.effectiveTo(), range, "参数有效期超出标准煤低位热值范围");
        return value;
    }

    /** 算法、量纲和参数单位是代码硬边界，防止把密度、碳因子或功率积分混入折标公式。 */
    private static void validateFormula(ConversionMethod method, ConversionPerspective perspective,
                                        FormulaAlgorithm algorithm, ParameterUnit parameterUnit,
                                        ApprovedUnit inputUnit, ApprovedUnit resultUnit) {
        if (!"TCE".equals(resultUnit.unitCode())) validation("折标公式结果单位必须是 TCE");
        switch (algorithm) {
            case DIRECT_TCE_FACTOR_V1 -> {
                if (method != ConversionMethod.DIRECT_TCE_FACTOR
                        || parameterUnit != ParameterUnit.TCE_PER_INPUT_UNIT
                        || !List.of("MASS", "NORMAL_VOLUME", "ENERGY").contains(inputUnit.dimensionCode())) {
                    validation("直接折标公式的方法、参数单位或输入量纲不一致");
                }
            }
            case LOWER_HEATING_VALUE_V1 -> {
                if (method != ConversionMethod.LOWER_HEATING_VALUE
                        || perspective != ConversionPerspective.CALORIFIC_EQUIVALENT
                        || parameterUnit != ParameterUnit.GJ_PER_INPUT_UNIT
                        || !List.of("MASS", "NORMAL_VOLUME").contains(inputUnit.dimensionCode())) {
                    validation("低位热值公式的方法、口径、参数单位或输入量纲不一致");
                }
            }
            case ENERGY_EQUIVALENT_V1 -> {
                if (method != ConversionMethod.ENERGY_EQUIVALENT
                        || parameterUnit != ParameterUnit.MJ_PER_INPUT_UNIT
                        || !"ENERGY".equals(inputUnit.dimensionCode())) {
                    validation("能源当量公式的方法、参数单位或输入量纲不一致");
                }
            }
        }
    }

    private static void validateConsumptionScope(ApprovedEnergyItem item, UsageScope scope) {
        if (scope == UsageScope.MOBILE_COMBUSTION) {
            throw EnergyConversionErrors.error(400, EnergyConversionErrors.MOBILE_SCOPE_REJECTED,
                    "第七闭环不计算移动源折标结果");
        }
        if (!item.usageScopes().contains(scope.name())) validation("能源品种不适用于请求的能源使用范围");
    }

    private ApprovedEnergyItem approvedItem(String code, LocalDateTime at) {
        return catalogLookup.findApprovedItem(code(code, "能源品种编码无效"), at)
                .orElseThrow(() -> EnergyConversionErrors.error(404,
                        EnergyConversionErrors.NOT_FOUND, "指定业务时间没有已审核能源品种版本"));
    }

    private ApprovedUnit approvedUnit(String code, LocalDateTime at) {
        return catalogLookup.findApprovedUnit(code, at)
                .orElseThrow(() -> EnergyConversionErrors.error(404,
                        EnergyConversionErrors.NOT_FOUND, "指定业务时间没有已审核单位版本"));
    }

    private ApprovedUnit requireUnitReference(String unitCode, String versionId, LocalDateTime at) {
        ApprovedUnit value = approvedUnit(unitCode, at);
        if (!Objects.equals(value.versionId(), versionId)) referenceConflict("规则引用的单位版本已不再有效");
        return value;
    }

    private StandardCoalRow requireStandardCoal(String versionId) {
        StandardCoalRow value = repository.findStandardCoalVersion(versionId);
        if (value == null) notFound("标准煤低位热值版本不存在");
        return value;
    }

    private FormulaRow requireFormula(String versionId) {
        FormulaRow value = repository.findFormulaVersion(versionId);
        if (value == null) notFound("折标公式版本不存在");
        return value;
    }

    private ParameterRow requireParameter(String versionId) {
        ParameterRow value = repository.findParameterVersion(versionId);
        if (value == null) notFound("折标参数版本不存在");
        return value;
    }

    private static BigDecimal requireStandardValue(StandardCoalRow value) {
        if (value == null || value.value() == null || value.value().signum() <= 0) {
            referenceConflict("标准煤低位热值版本缺失或无效");
        }
        return value.value();
    }

    private void audit(long operatorId, String action, String objectType, String objectId,
                       String versionId, String before, String after, boolean selfApproval,
                       String buildingId) {
        auditWriter.append(new AuditEvidence("ENERGY_CONVERSION", buildingId, "USER", operatorId,
                action, objectType, objectId, versionId, null, before, after, "SUCCESS", null,
                TraceContext.current(), LocalDateTime.now(), auditProperties.getEnvironmentMode(), selfApproval));
    }

    private static void closePrevious(String previousVersionId, LocalDateTime previousFrom,
                                      LocalDateTime nextFrom, VersionCloser closer) {
        if (previousVersionId == null) return;
        if (!nextFrom.isAfter(previousFrom)) {
            throw EnergyConversionErrors.error(409, EnergyConversionErrors.VERSION_CONFLICT,
                    "新审核版本的生效时间必须晚于当前已审核版本");
        }
        closer.close(previousVersionId, nextFrom);
    }

    private static void requireRange(LocalDateTime referenceFrom, LocalDateTime referenceTo,
                                     TimeRange requested, String message) {
        boolean startsBefore = !referenceFrom.isAfter(requested.from());
        boolean endsAfter = referenceTo == null
                || (requested.to() != null && !referenceTo.isBefore(requested.to()));
        if (!startsBefore || !endsAfter) referenceConflict(message);
    }

    private static void requirePending(String status) {
        if (!RuleStatus.PENDING_EXPERT.name().equals(status)) {
            throw EnergyConversionErrors.error(409, EnergyConversionErrors.STATUS_CONFLICT,
                    "只有待专业确认版本可以审核");
        }
    }

    private static void requireApproved(String status, String message) {
        if (!RuleStatus.APPROVED.name().equals(status)) referenceConflict(message);
    }

    private static TimeRange range(LocalDateTime from, LocalDateTime to) {
        LocalDateTime requiredFrom = requiredTime(from);
        if (to != null && !to.isAfter(requiredFrom)) validation("失效时间必须晚于生效时间");
        return new TimeRange(requiredFrom, to);
    }

    private static LocalDateTime requiredTime(LocalDateTime value) {
        if (value == null) validation("业务生效时间不能为空");
        return value;
    }

    private static String code(String value, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) validation(message);
        return normalized;
    }

    private static String region(String value) {
        String normalized = text(value, 32, "地区编码无效").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{2,32}")) validation("地区编码无效");
        return normalized;
    }

    private static String text(String value, int max, String message) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > max) validation(message);
        return normalized;
    }

    private static BigDecimal positive(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) validation(message);
        return value.stripTrailingZeros();
    }

    private static BigDecimal nonNegative(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) validation(message);
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw EnergyConversionErrors.error(400, EnergyConversionErrors.VALIDATION_FAILED, message);
        }
    }

    private static void validation(String message) {
        throw EnergyConversionErrors.error(400, EnergyConversionErrors.VALIDATION_FAILED, message);
    }

    private static void notFound(String message) {
        throw EnergyConversionErrors.error(404, EnergyConversionErrors.NOT_FOUND, message);
    }

    private static void versionConflict() {
        throw EnergyConversionErrors.error(409, EnergyConversionErrors.VERSION_CONFLICT,
                "版本已被其他操作修改");
    }

    private static void referenceConflict(String message) {
        throw EnergyConversionErrors.error(409, EnergyConversionErrors.REFERENCE_VERSION_CONFLICT, message);
    }

    private boolean selfApproval(long createdBy, long reviewerId) {
        return createdBy == reviewerId && auditProperties.isAllowSelfApproval();
    }

    private static String reviewed(String summary, ApproveRequest request) {
        return summary + ";reviewComment=" + text(request.reviewComment(), 500, "审核意见无效");
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static StandardCoalVersionView standardView(StandardCoalRow value) {
        return new StandardCoalVersionView(value.lhvId(), value.lhvCode(), value.versionId(), value.versionNo(),
                value.value(), value.parameterUnit(), value.energyUnitVersionId(), value.coalUnitVersionId(),
                value.status(), value.sourceType(), value.sourceReference(), value.effectiveFrom(),
                value.effectiveTo(), value.configRevision(), value.createdBy(), value.createdAt(),
                value.approvedBy(), value.approvedAt());
    }

    private static FormulaVersionView formulaView(FormulaRow value) {
        return new FormulaVersionView(value.formulaId(), value.formulaCode(), value.versionId(),
                value.versionNo(), value.method(), value.perspective(), value.algorithmCode(),
                value.inputUnitCode(), value.inputUnitVersionId(), value.resultUnitCode(),
                value.resultUnitVersionId(), value.parameterUnit(), value.status(), value.sourceType(),
                value.sourceReference(), value.effectiveFrom(), value.effectiveTo(), value.configRevision(),
                value.createdBy(), value.createdAt(), value.approvedBy(), value.approvedAt());
    }

    private static ParameterVersionView parameterView(ParameterRow value) {
        return new ParameterVersionView(value.parameterId(), value.parameterCode(), value.versionId(),
                value.versionNo(), value.itemCode(), value.energyItemVersionId(), value.formulaCode(),
                value.formulaVersionId(), value.method(), value.perspective(), value.parameterValue(),
                value.parameterUnit(), value.standardCoalVersionId(), value.consumptionScope(),
                value.regionCode(), value.usageScope(), value.status(), value.sourceType(),
                value.sourceReference(), value.effectiveFrom(), value.effectiveTo(), value.configRevision(),
                value.createdBy(), value.createdAt(), value.approvedBy(), value.approvedAt());
    }

    private static String standardSummary(StandardCoalRow value) {
        return "code=" + value.lhvCode() + ";version=" + value.versionNo() + ";value=" + value.value()
                + ";unit=" + value.parameterUnit() + ";status=" + value.status();
    }

    private static String formulaSummary(FormulaRow value) {
        return "code=" + value.formulaCode() + ";version=" + value.versionNo() + ";method="
                + value.method() + ";perspective=" + value.perspective() + ";algorithm="
                + value.algorithmCode() + ";status=" + value.status();
    }

    private static String parameterSummary(ParameterRow value) {
        return "code=" + value.parameterCode() + ";version=" + value.versionNo() + ";item="
                + value.itemCode() + ";formulaVersion=" + value.formulaVersionId() + ";status="
                + value.status() + ";usageScope=" + value.usageScope();
    }

    private static String simulationSummary(SimulationResultView value) {
        return "nature=" + value.resultNature() + ";item=" + value.energyItemCode()
                + ";parameterVersion=" + value.parameterVersionId() + ";formulaVersion="
                + value.formulaVersionId() + ";tce=" + value.tce();
    }

    private record TimeRange(LocalDateTime from, LocalDateTime to) {
    }

    @FunctionalInterface
    private interface VersionCloser {
        void close(String versionId, LocalDateTime effectiveTo);
    }
}
