package com.platform.energy.parameters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.carbon.CarbonCalculationCore;
import com.platform.carbon.CarbonEvidence;
import com.platform.carbon.CarbonModels.DenominatorVersion;
import com.platform.carbon.CarbonModels.FactorSourceVersion;
import com.platform.carbon.CarbonModels.FactorVersion;
import com.platform.carbon.CarbonRuleService;
import com.platform.carbon.ElectricityDefaultFactorPolicy;
import com.platform.carbon.api.CarbonContracts.CreateFactorSourceRequest;
import com.platform.carbon.api.CarbonContracts.CreateFactorVersionRequest;
import com.platform.carbon.api.CarbonContracts.FactorComponentRequest;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.framework.exception.BusinessException;
import com.platform.energy.catalog.api.EnergyCatalogContracts.ItemVersionView;
import com.platform.energy.catalog.api.EnergyCatalogContracts.UnitVersionView;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.system.service.BuildingScopeService;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateFormulaVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateParameterVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateStandardCoalVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.FormulaVersionView;
import com.platform.energy.conversion.api.EnergyConversionContracts.ParameterVersionView;
import com.platform.energy.conversion.api.EnergyConversionContracts.StandardCoalVersionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.platform.energy.parameters.EnergyParameterPackageContracts.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 目录只固化已经给出的数值和量纲；导入只创建待审核版本，绝不替代专业审核或激活流程。
 */
public class EnergyParameterPackageService {
    public static final String B01 = "B01";
    public static final String B02 = "B02";

    public static final String STANDARD_COAL_LHV = "STANDARD_COAL_LHV";
    public static final String NATURAL_GAS_LHV_FORMULA = "NATURAL_GAS_LHV_FORMULA";
    public static final String NATURAL_GAS_LHV_PARAMETER = "NATURAL_GAS_LHV_PARAMETER";
    public static final String RAW_COAL_DIRECT_TCE_FORMULA = "RAW_COAL_DIRECT_TCE_FORMULA";
    public static final String RAW_COAL_DIRECT_TCE_PARAMETER = "RAW_COAL_DIRECT_TCE_PARAMETER";
    public static final String NATURAL_GAS_STATIONARY_FACTOR = "NATURAL_GAS_STATIONARY_FACTOR";
    public static final String ELECTRICITY_FACTOR = "ELECTRICITY_FACTOR";
    public static final String HUBEI_ELECTRICITY_FACTOR_2021 = "HUBEI_ELECTRICITY_FACTOR_2021";
    public static final String HUBEI_ELECTRICITY_FACTOR_2022 = "HUBEI_ELECTRICITY_FACTOR_2022";
    public static final String HUBEI_ELECTRICITY_FACTOR_2023 = "HUBEI_ELECTRICITY_FACTOR_2023";
    public static final String HEAT_FACTOR = "HEAT_FACTOR";

    private static final BigDecimal STANDARD_COAL_GJ_PER_TCE = new BigDecimal("29.3076");
    private static final BigDecimal NATURAL_GAS_GJ_PER_TEN_THOUSAND_NM3 = new BigDecimal("389.31");
    private static final BigDecimal RAW_COAL_TCE_PER_KG = new BigDecimal("0.0007143");
    private static final BigDecimal NATURAL_GAS_MJ_PER_NM3 = new BigDecimal("38.931");
    private static final BigDecimal NATURAL_GAS_KG_C_PER_MJ = new BigDecimal("0.0153");
    private static final BigDecimal NATURAL_GAS_OXIDATION_RATE = new BigDecimal("0.99");
    private static final BigDecimal ELECTRICITY_KG_CO2_PER_MWH = ElectricityDefaultFactorPolicy.KG_CO2_PER_MWH;
    private static final BigDecimal HUBEI_2021_KG_CO2_PER_MWH = new BigDecimal("367.2");
    private static final BigDecimal HUBEI_2022_KG_CO2_PER_MWH = new BigDecimal("436.4");
    private static final BigDecimal HUBEI_2023_KG_CO2_PER_MWH = new BigDecimal("404.4");
    private static final BigDecimal HEAT_KG_CO2_PER_GJ = new BigDecimal("110");
    private static final String RAW_COAL_ITEM_CODE = "RAW_COAL";
    private static final String ROUNDING_POLICY = "CRP_DECIMAL128_V1";

    private final BackendDutyService dutyService;
    private final EnergyCatalogService catalogService;
    private final EnergyConversionService conversionService;
    private final CarbonRuleService carbonRuleService;
    private final EnergyParameterPackageImportRepository importRepository;
    private final ObjectMapper objectMapper;
    private final BuildingScopeService buildingScopeService;

    public CatalogView catalog(long userId, Collection<String> roles, String buildingId) {
        List<ItemVersionView> itemVersions = catalogService.listItems(roles);
        List<UnitVersionView> unitVersions = catalogService.listUnits(roles);
        List<FactorVersion> factors = visibleCatalogFactors(userId, roles, buildingId,
                carbonRuleService.listFactors(userId, roles));
        List<VersionContextView> versions = new ArrayList<>();
        itemVersions.forEach(value -> versions.add(new VersionContextView("ENERGY_ITEM", value.itemCode(),
                value.versionId(), value.status(), null, value.effectiveFrom(), value.effectiveTo())));
        unitVersions.forEach(value -> versions.add(new VersionContextView("MEASUREMENT_UNIT", value.unitCode(),
                value.versionId(), value.status(), null, value.effectiveFrom(), value.effectiveTo())));
        conversionService.listStandardCoalVersions(roles).forEach(value -> versions.add(new VersionContextView(
                "STANDARD_COAL", value.lhvCode(), value.versionId(), value.status(), null,
                value.effectiveFrom(), value.effectiveTo())));
        conversionService.listFormulaVersions(roles).forEach(value -> versions.add(new VersionContextView(
                "CONVERSION_FORMULA", value.formulaCode(), value.versionId(), value.status(), null,
                value.effectiveFrom(), value.effectiveTo())));
        conversionService.listParameterVersions(roles).forEach(value -> versions.add(new VersionContextView(
                "CONVERSION_PARAMETER", value.parameterCode(), value.versionId(), value.status(),
                value.usageScope(), value.effectiveFrom(), value.effectiveTo())));
        factors.forEach(value -> versions.add(new VersionContextView(
                "CARBON_FACTOR", value.factorCode(), value.factorVersionId(), value.status().name(),
                value.usageNature().name(), value.effectiveFrom(), value.effectiveTo())));

        List<DenominatorContextView> denominators = blank(buildingId) ? List.of()
                : carbonRuleService.listDenominators(userId, roles, buildingId.trim()).stream()
                .map(EnergyParameterPackageService::denominatorView).toList();
        return new CatalogView(List.of(b01Definition(), b02Definition()), versions,
                catalogRequirements(itemVersions, unitVersions), denominators);
    }
    public PreviewView preview(long userId, Collection<String> roles, PackageRequest request) {
        return preview(userId, roles, request, selectedItems(request, false));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ImportView importPackage(long userId, Collection<String> roles, PackageRequest request) {
        String requestedPackageCode = packageCode(request);
        requireImportAuthority(userId, roles, requestedPackageCode);
        String idempotencyKey = idempotencyKey(request);
        String requestHash = requestHash(userId, request);
        importRepository.lockGlobalScope();
        EnergyParameterPackageImportRepository.Receipt receipt = importRepository.receipt(idempotencyKey);
        if (receipt != null) {
            if (receipt.actorId() != userId || !receipt.requestHash().equals(requestHash)
                    || !"SUCCEEDED".equals(receipt.status())) {
                throw EnergyParameterPackageErrors.error(409, EnergyParameterPackageErrors.IDEMPOTENCY_CONFLICT,
                        "幂等键已绑定其他操作者或请求内容");
            }
            return decodeReceipt(receipt.responseJson());
        }

        List<String> selected = selectedItems(request, true);
        PreviewView preview = preview(userId, roles, request, selected);
        if (!preview.readyForImport()) {
            throw EnergyParameterPackageErrors.error(409, EnergyParameterPackageErrors.PREVIEW_BLOCKED,
                    "参数包预检未通过；请逐项补齐来源、适用期和已审核依赖");
        }
        List<CreatedVersionView> created = B01.equals(preview.packageCode())
                ? importB01(userId, roles, request, selected)
                : importB02(userId, roles, request, selected);
        String completionStatus = selected.size() == definition(preview.packageCode()).items().size()
                ? "CREATED_PENDING_REVIEW" : "PARTIAL";
        String notice = "PARTIAL".equals(completionStatus)
                ? "仅创建已选择且预检通过的条目；其余条目未导入，不代表整包完成。"
                : "已创建待审核版本，尚未批准或激活，须由独立角色完成后续审核。";
        ImportView result = new ImportView(preview.packageCode(), completionStatus, true, created,
                List.of(new IssueView(null, "NOTICE", "INDEPENDENT_REVIEW_REQUIRED", notice)));
        importRepository.success(idempotencyKey, requestHash, userId, preview.packageCode(), json(result));
        return result;
    }
    private PreviewView preview(long userId, Collection<String> roles, PackageRequest request, List<String> selected) {
        String packageCode = packageCode(request);
        InspectionContext context = new InspectionContext(catalogService.listItems(roles), catalogService.listUnits(roles),
                conversionService.listStandardCoalVersions(roles), conversionService.listFormulaVersions(roles),
                conversionService.listParameterVersions(roles), carbonRuleService.options(userId, roles),
                globalFactors(carbonRuleService.listFactors(userId, roles)));
        List<IssueView> commonIssues = commonIssues(request, packageCode);
        List<ItemPreviewView> items = new ArrayList<>();
        for (String itemCode : selected) {
            items.add(B01.equals(packageCode) ? previewB01Item(itemCode, request, context, commonIssues)
                    : previewB02Item(itemCode, request, context, commonIssues));
        }
        List<IssueView> allIssues = new ArrayList<>(commonIssues);
        items.forEach(item -> allIssues.addAll(item.issues()));
        boolean ready = allIssues.stream().noneMatch(issue -> "BLOCKER".equals(issue.severity()));
        return new PreviewView(packageCode, ready, items, allIssues);
    }

    private List<CreatedVersionView> importB01(long userId, Collection<String> roles,
                                                PackageRequest request, List<String> selected) {
        List<CreatedVersionView> created = new ArrayList<>();
        for (String itemCode : selected) {
            switch (itemCode) {
                case STANDARD_COAL_LHV -> {
                    StandardCoalVersionView value = conversionService.createStandardCoalVersion(userId, roles,
                            new CreateStandardCoalVersionRequest(STANDARD_COAL_LHV, STANDARD_COAL_GJ_PER_TCE,
                                    "GJ_PER_TCE", conversionSourceType(request), sourceReference(request),
                                    request.effectiveFrom(), request.effectiveTo()));
                    created.add(created(itemCode, "STANDARD_COAL_LHV", value.versionId(), value.status(), List.of()));
                }
                case NATURAL_GAS_LHV_FORMULA -> {
                    FormulaVersionView value = conversionService.createFormulaVersion(userId, roles,
                            new CreateFormulaVersionRequest("LHV_PER_TEN_THOUSAND_NM3", "LOWER_HEATING_VALUE",
                                    "CALORIFIC_EQUIVALENT", "LOWER_HEATING_VALUE_V1", "TEN_THOUSAND_NM3", "TCE",
                                    "GJ_PER_INPUT_UNIT", conversionSourceType(request), sourceReference(request),
                                    request.effectiveFrom(), request.effectiveTo()));
                    created.add(created(itemCode, "CONVERSION_FORMULA", value.versionId(), value.status(), List.of()));
                }
                case RAW_COAL_DIRECT_TCE_FORMULA -> {
                    FormulaVersionView value = conversionService.createFormulaVersion(userId, roles,
                            new CreateFormulaVersionRequest("RAW_COAL_DIRECT_TCE_FACTOR", "DIRECT_TCE_FACTOR",
                                    "CALORIFIC_EQUIVALENT", "DIRECT_TCE_FACTOR_V1", "KG", "TCE",
                                    "TCE_PER_INPUT_UNIT", conversionSourceType(request), sourceReference(request),
                                    request.effectiveFrom(), request.effectiveTo()));
                    created.add(created(itemCode, "CONVERSION_FORMULA", value.versionId(), value.status(), List.of()));
                }
                case NATURAL_GAS_LHV_PARAMETER -> created.add(importNaturalGasLhvParameter(userId, roles, request));
                case RAW_COAL_DIRECT_TCE_PARAMETER -> created.add(importRawCoalParameter(userId, roles, request));
                default -> throw validation("未知 B01 条目：" + itemCode);
            }
        }
        return created;
    }
    private CreatedVersionView importNaturalGasLhvParameter(long userId, Collection<String> roles,
                                                             PackageRequest request) {
        ParameterVersionView value = conversionService.createParameterVersion(userId, roles,
                new CreateParameterVersionRequest("NATURAL_GAS_LHV", "NATURAL_GAS",
                        request.dependencies().naturalGasFormulaVersionId(), NATURAL_GAS_GJ_PER_TEN_THOUSAND_NM3,
                        "GJ_PER_INPUT_UNIT", request.dependencies().standardCoalLhvVersionId(),
                        "STATIONARY_COMBUSTION", "GLOBAL", "PRODUCTION", conversionSourceType(request),
                        sourceReference(request), request.effectiveFrom(), request.effectiveTo()));
        return created(NATURAL_GAS_LHV_PARAMETER, "CONVERSION_PARAMETER", value.versionId(), value.status(),
                List.of(value.formulaVersionId(), value.standardCoalLhvVersionId()));
    }

    private CreatedVersionView importRawCoalParameter(long userId, Collection<String> roles,
                                                       PackageRequest request) {
        ParameterVersionView value = conversionService.createParameterVersion(userId, roles,
                new CreateParameterVersionRequest("RAW_COAL_DIRECT_TCE", RAW_COAL_ITEM_CODE,
                        request.dependencies().rawCoalFormulaVersionId(), RAW_COAL_TCE_PER_KG,
                        "TCE_PER_INPUT_UNIT", null, "STATIONARY_COMBUSTION", "GLOBAL", "PRODUCTION",
                        conversionSourceType(request), sourceReference(request), request.effectiveFrom(),
                        request.effectiveTo()));
        return created(RAW_COAL_DIRECT_TCE_PARAMETER, "CONVERSION_PARAMETER", value.versionId(), value.status(),
                List.of(value.formulaVersionId()));
    }

    private List<CreatedVersionView> importB02(long userId, Collection<String> roles,
                                                PackageRequest request, List<String> selected) {
        List<CreatedVersionView> created = new ArrayList<>();
        FactorSourceVersion source = carbonRuleService.createSource(userId, roles, new CreateFactorSourceRequest(
                request.source().sourceCode(), request.source().sourceName(), request.source().publisher(),
                sourceDocumentReference(request), request.source().publicationYear(), request.source().publishedOn(),
                request.source().applicabilityNote(), request.source().evidenceReference(), "FORMAL"));
        created.add(created("FACTOR_SOURCE", "CARBON_FACTOR_SOURCE", source.sourceVersionId(),
                "FORMAL_SOURCE_CREATED", List.of()));
        CarbonRuleService.RuleOptions options = carbonRuleService.options(userId, roles);
        for (String itemCode : selected) {
            FactorVersion value = switch (itemCode) {
                case NATURAL_GAS_STATIONARY_FACTOR -> carbonRuleService.createFactor(userId, roles,
                        naturalGasFactor(request, source, options));
                case ELECTRICITY_FACTOR, HUBEI_ELECTRICITY_FACTOR_2021, HUBEI_ELECTRICITY_FACTOR_2022,
                        HUBEI_ELECTRICITY_FACTOR_2023 -> carbonRuleService.createFactor(userId, roles,
                        electricityFactor(itemCode, request, source, options));
                case HEAT_FACTOR -> carbonRuleService.createFactor(userId, roles,
                        heatFactor(request, source, options));
                default -> throw validation("未知 B02 条目：" + itemCode);
            };
            created.add(created(itemCode, "CARBON_FACTOR", value.factorVersionId(), value.status().name(),
                    List.of(value.sourceVersionId(), value.formulaVersionId(), value.roundingPolicyVersionId())));
        }
        return created;
    }

    private CreateFactorVersionRequest naturalGasFactor(PackageRequest request, FactorSourceVersion source,
                                                         CarbonRuleService.RuleOptions options) {
        String formula = formulaVersion(options, "STATIONARY_COMBUSTION_CO2", "GAS_MASS");
        return new CreateFactorVersionRequest("NATURAL_GAS_STATIONARY_CO2", "SCOPE_1", "NATURAL_GAS",
                "STATIONARY_COMBUSTION", "GAS_MASS", "CO2", "CO2_ONLY_FOR_STATIONARY_COMBUSTION",
                source.sourceVersionId(), "NOT_REGION_SPECIFIC", null, null, "NM3", request.standardConditionCode(),
                "FORMAL", request.effectiveFrom(), request.effectiveTo(), formula, roundingVersion(options),
                List.of(component("LOWER_HEATING_VALUE", NATURAL_GAS_MJ_PER_NM3, "MJ/NM3", source),
                        component("CARBON_CONTENT_PER_HEAT", NATURAL_GAS_KG_C_PER_MJ, "KG_C_PER_MJ", source),
                        component("OXIDATION_RATE", NATURAL_GAS_OXIDATION_RATE, "RATIO", source)), null, null);
    }

    private CreateFactorVersionRequest electricityFactor(String itemCode, PackageRequest request,
                                                          FactorSourceVersion source,
                                                          CarbonRuleService.RuleOptions options) {
        ElectricityContextRequest electricity = request.electricity();
        ElectricityCandidate candidate = electricityCandidate(itemCode);
        String formula = formulaVersion(options, "PURCHASED_ELECTRICITY_LOCATION_CO2", "GAS_MASS");
        return new CreateFactorVersionRequest(candidate.factorCode(), "SCOPE_2", "ELECTRICITY",
                "PURCHASED_ELECTRICITY_LOCATION", "GAS_MASS", "CO2", "CO2_ONLY_ELECTRICITY",
                source.sourceVersionId(), normalized(electricity.applicabilityLevel()), null,
                blank(electricity.regionCode()) ? null : electricity.regionCode().trim(), "MWH", null, "FORMAL",
                request.effectiveFrom(), request.effectiveTo(), formula, roundingVersion(options),
                List.of(component("DIRECT_EMISSION_FACTOR", candidate.kgCo2PerMwh(), "KG_CO2/MWH", source)),
                electricity.dataYear(), electricity.accountingYear());
    }
    private CreateFactorVersionRequest heatFactor(PackageRequest request, FactorSourceVersion source,
                                                   CarbonRuleService.RuleOptions options) {
        String formula = formulaVersion(options, "PURCHASED_HEAT_CO2", "GAS_MASS");
        return new CreateFactorVersionRequest("PURCHASED_HEAT_CO2_011", "SCOPE_2", "HEAT", "PURCHASED_HEAT",
                "GAS_MASS", "CO2", "CO2_ONLY_HEAT", source.sourceVersionId(), "NATIONAL",
                null, null, "GJ", null, "FORMAL", request.effectiveFrom(), request.effectiveTo(), formula,
                roundingVersion(options), List.of(component("DIRECT_EMISSION_FACTOR", HEAT_KG_CO2_PER_GJ,
                        "KG_CO2/GJ", source)), null, null);
    }
    private ItemPreviewView previewB01Item(String itemCode, PackageRequest request, InspectionContext context,
                                            List<IssueView> commonIssues) {
        List<IssueView> issues = new ArrayList<>();
        List<DependencyView> dependencies = new ArrayList<>();
        switch (itemCode) {
            case STANDARD_COAL_LHV -> requireUnits(itemCode, request, context, issues, "GJ", "TCE");
            case NATURAL_GAS_LHV_FORMULA -> requireUnits(itemCode, request, context, issues,
                    "TEN_THOUSAND_NM3", "TCE");
            case RAW_COAL_DIRECT_TCE_FORMULA -> requireUnits(itemCode, request, context, issues, "KG", "TCE");
            case NATURAL_GAS_LHV_PARAMETER -> {
                dependencies.add(formulaDependency(itemCode, request, context, issues,
                        "naturalGasFormulaVersionId", "NATURAL_GAS_LHV_FORMULA"));
                dependencies.add(standardCoalDependency(itemCode, request, context, issues));
                energyItemDependency(itemCode, "NATURAL_GAS", "NATURAL_GAS_ENERGY_ITEM_REQUIRED",
                        "NATURAL_GAS_ENERGY_ITEM_NOT_APPROVED", "天然气能源品种", request, context,
                        dependencies, issues);
                issues.add(issue(itemCode, "WARNING", "NATURAL_GAS_TCE_RANGE_NOT_AVERAGED",
                        "天然气 1.1–1.33 折标范围不能取中值；本条仅按给定低位热值创建候选参数。"));
                parameterConflict(itemCode, "NATURAL_GAS_LHV", request, context, issues);
            }

            case RAW_COAL_DIRECT_TCE_PARAMETER -> {
                dependencies.add(formulaDependency(itemCode, request, context, issues,
                        "rawCoalFormulaVersionId", "RAW_COAL_DIRECT_TCE_FORMULA"));
                rawCoalItemDependency(itemCode, request, context, dependencies, issues);
                parameterConflict(itemCode, "RAW_COAL_DIRECT_TCE", request, context, issues);
            }
            default -> throw validation("未知 B01 条目：" + itemCode);
        }
        return itemPreview(itemCode, b01NormalizedValue(itemCode), b01NormalizedUnit(itemCode), dependencies, issues,
                commonIssues.stream().anyMatch(value -> "BLOCKER".equals(value.severity())));
    }

    private ItemPreviewView previewB02Item(String itemCode, PackageRequest request, InspectionContext context,
                                            List<IssueView> commonIssues) {
        List<IssueView> issues = new ArrayList<>();
        List<DependencyView> dependencies = new ArrayList<>();
        dependencies.add(carbonFormulaDependency(itemCode, context, issues, itemCode));
        dependencies.add(roundingDependency(itemCode, context, issues));
        switch (itemCode) {
            case NATURAL_GAS_STATIONARY_FACTOR -> {
                if (blank(request.standardConditionCode())) {
                    issues.add(issue(itemCode, "BLOCKER", "NATURAL_GAS_STANDARD_CONDITION_REQUIRED",
                            "天然气因子必须逐项给出标准状态依据，不能从单位或历史参数推断。"));
                }
                factorConflict(itemCode, "NATURAL_GAS_STATIONARY_CO2", request, context, issues);
            }
            case ELECTRICITY_FACTOR, HUBEI_ELECTRICITY_FACTOR_2021, HUBEI_ELECTRICITY_FACTOR_2022,
                    HUBEI_ELECTRICITY_FACTOR_2023 -> {
                electricityContext(itemCode, request, issues);
                factorConflict(itemCode, electricityCandidate(itemCode).factorCode(), request, context, issues);
            }
            case HEAT_FACTOR -> factorConflict(itemCode, "PURCHASED_HEAT_CO2_011", request, context, issues);
            default -> throw validation("未知 B02 条目：" + itemCode);
        }
        return itemPreview(itemCode, b02NormalizedValue(itemCode), b02NormalizedUnit(itemCode), dependencies, issues,
                commonIssues.stream().anyMatch(value -> "BLOCKER".equals(value.severity())));
    }
    private List<IssueView> commonIssues(PackageRequest request, String packageCode) {
        List<IssueView> issues = new ArrayList<>();
        if (request.effectiveFrom() == null) {
            issues.add(issue(null, "BLOCKER", "EFFECTIVE_FROM_REQUIRED", "必须逐项给出生效时间，不能从导入时间推断。"));
        } else if (request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            issues.add(issue(null, "BLOCKER", "EFFECTIVE_RANGE_INVALID", "失效时间必须晚于生效时间。"));
        }
        SourceMetadataRequest source = request.source();
        if (source == null) {
            issues.add(issue(null, "BLOCKER", "SOURCE_REQUIRED", "必须提供来源、标准版本、适用说明和证据引用。"));
            return issues;
        }
        requiredSourceField(issues, "SOURCE_CODE_REQUIRED", source.sourceCode(), "来源编码缺失。");
        if (!blank(source.sourceCode())
                && !normalized(source.sourceCode()).matches("[A-Z][A-Z0-9_]{1,63}")) {
            issues.add(issue(null, "BLOCKER", "SOURCE_CODE_INVALID",
                    "来源编码归一后必须为 2–64 位大写字母、数字或下划线，且以字母开头。"));
        }
        requiredSourceField(issues, "SOURCE_NAME_REQUIRED", source.sourceName(), "来源名称缺失。");
        requiredSourceField(issues, "SOURCE_PUBLISHER_REQUIRED", source.publisher(), "发布机构缺失。");
        requiredSourceField(issues, "SOURCE_DOCUMENT_REQUIRED", source.documentReference(), "来源文件或标准文号缺失。");
        requiredSourceField(issues, "SOURCE_DOCUMENT_VERSION_REQUIRED", source.sourceDocumentVersion(),
                "来源标准版本缺失，不能以旧版或未标注版本替代。 ");
        requiredSourceField(issues, "SOURCE_APPLICABILITY_REQUIRED", source.applicabilityNote(), "适用范围或适用条件缺失。");
        requiredSourceField(issues, "SOURCE_EVIDENCE_REQUIRED", source.evidenceReference(), "来源证据引用缺失。");
        if (source.publicationYear() == null) {
            issues.add(issue(null, "BLOCKER", "SOURCE_PUBLICATION_YEAR_REQUIRED", "来源发布年份缺失。"));
        }
        if (B01.equals(packageCode) && !Set.of("STANDARD", "EXCEL", "MANUAL")
                .contains(normalized(source.energySourceType()))) {
            issues.add(issue(null, "BLOCKER", "CONVERSION_SOURCE_TYPE_INVALID",
                    "折标候选必须明确来源类型为 STANDARD、EXCEL 或 MANUAL。"));
        }
        return issues;
    }

    private void requiredSourceField(List<IssueView> issues, String code, String value, String message) {
        if (blank(value)) issues.add(issue(null, "BLOCKER", code, message));
    }

    private void requireUnits(String itemCode, PackageRequest request, InspectionContext context,
                              List<IssueView> issues, String... codes) {
        for (String code : codes) {
            List<UnitVersionView> candidates = context.units().stream()
                    .filter(value -> normalized(code).equals(normalized(value.unitCode()))).toList();
            boolean ready = candidates.stream().anyMatch(value -> "APPROVED".equals(value.status())
                    && covers(value.effectiveFrom(), value.effectiveTo(), request));
            if (candidates.isEmpty()) {
                issues.add(issue(itemCode, "BLOCKER", "REQUIRED_UNIT_MISSING",
                        "受控单位 " + code + " 尚无可用版本；请先通过 /v1/energy-catalog/unit-versions 建立待审核版本。"));
            } else if (!ready) {
                issues.add(issue(itemCode, "BLOCKER", "REQUIRED_UNIT_NOT_APPROVED",
                        "受控单位 " + code + " 没有覆盖适用期的 APPROVED 版本；请先完成独立审核。"));
            }
        }
    }

    private DependencyView formulaDependency(String itemCode, PackageRequest request,
                                             InspectionContext context, List<IssueView> issues,
                                             String field, String expectedItem) {
        DependenciesRequest dependencies = request.dependencies();
        String versionId = dependencies == null ? null : ("naturalGasFormulaVersionId".equals(field)
                ? dependencies.naturalGasFormulaVersionId() : dependencies.rawCoalFormulaVersionId());
        FormulaVersionView value = blank(versionId) ? null : context.formulasByVersion().get(versionId.trim());
        if (value == null) {
            issues.add(issue(itemCode, "BLOCKER", "FORMULA_DEPENDENCY_REQUIRED",
                    expectedItem + " 需要指定已审核公式版本，不能引用本次刚创建的待审核公式。"));
            return new DependencyView("CONVERSION_FORMULA", versionId, "MISSING", "缺少已审核公式依赖");
        }
        if (!"APPROVED".equals(value.status()) || !covers(value.effectiveFrom(), value.effectiveTo(), request)) {
            issues.add(issue(itemCode, "BLOCKER", "FORMULA_DEPENDENCY_NOT_APPROVED",
                    "引用公式版本必须为 APPROVED 且覆盖参数适用期。"));
        }
        return new DependencyView("CONVERSION_FORMULA", value.versionId(), value.status(), value.formulaCode());
    }

    private DependencyView standardCoalDependency(String itemCode, PackageRequest request,
                                                  InspectionContext context, List<IssueView> issues) {
        DependenciesRequest dependencies = request.dependencies();
        String versionId = dependencies == null ? null : dependencies.standardCoalLhvVersionId();
        StandardCoalVersionView value = blank(versionId) ? null : context.standardCoalByVersion().get(versionId.trim());
        if (value == null) {
            issues.add(issue(itemCode, "BLOCKER", "STANDARD_COAL_DEPENDENCY_REQUIRED",
                    "低位热值折标参数必须指定已审核的标准煤低位热值版本。"));
            return new DependencyView("STANDARD_COAL_LHV", versionId, "MISSING", "缺少已审核标准煤版本");
        }
        if (!"APPROVED".equals(value.status()) || !covers(value.effectiveFrom(), value.effectiveTo(), request)) {
            issues.add(issue(itemCode, "BLOCKER", "STANDARD_COAL_DEPENDENCY_NOT_APPROVED",
                    "标准煤低位热值版本必须为 APPROVED 且覆盖参数适用期。"));
        }
        return new DependencyView("STANDARD_COAL_LHV", value.versionId(), value.status(), value.lhvCode());
    }

    private void rawCoalItemDependency(String itemCode, PackageRequest request, InspectionContext context,
                                       List<DependencyView> dependencies, List<IssueView> issues) {
        String code = request.dependencies() == null ? null : request.dependencies().rawCoalEnergyItemCode();
        if (blank(code)) {
            issues.add(issue(itemCode, "BLOCKER", "RAW_COAL_ENERGY_ITEM_REQUIRED",
                    "目录必须先建立并独立审核受控 RAW_COAL 身份；系统不会把烟煤、无烟煤、褐煤或其他煤种推断为原煤。"));
            dependencies.add(new DependencyView("ENERGY_ITEM", RAW_COAL_ITEM_CODE, "MISSING", "缺少已审核 RAW_COAL 能源品种"));
            return;
        }
        if (!RAW_COAL_ITEM_CODE.equals(normalized(code))) {
            issues.add(issue(itemCode, "BLOCKER", "RAW_COAL_ENERGY_ITEM_IDENTITY_INVALID",
                    "原煤直接折标只接受明确受控的 RAW_COAL 身份，不接受其他煤种或天然气替代。"));
            dependencies.add(new DependencyView("ENERGY_ITEM", normalized(code), "INVALID", "必须选择 RAW_COAL"));
            return;
        }
        energyItemDependency(itemCode, RAW_COAL_ITEM_CODE, "RAW_COAL_ENERGY_ITEM_REQUIRED",
                "RAW_COAL_ENERGY_ITEM_NOT_APPROVED", "RAW_COAL 能源品种", request, context, dependencies, issues);
    }

    private void energyItemDependency(String itemCode, String code, String missingCode, String notApprovedCode,
                                      String displayName, PackageRequest request, InspectionContext context,
                                      List<DependencyView> dependencies, List<IssueView> issues) {
        List<ItemVersionView> candidates = context.items().stream()
                .filter(value -> normalized(code).equals(normalized(value.itemCode()))).toList();
        ItemVersionView value = candidates.stream().filter(candidate -> "APPROVED".equals(candidate.status())
                        && covers(candidate.effectiveFrom(), candidate.effectiveTo(), request)
                        && candidate.usageScopes().contains("STATIONARY_COMBUSTION"))
                .findFirst().orElse(null);
        if (value == null) {
            String errorCode = candidates.isEmpty() ? missingCode : notApprovedCode;
            issues.add(issue(itemCode, "BLOCKER", errorCode,
                    displayName + "必须存在覆盖适用期、适用于固定燃烧且已独立审核的版本。"));
            dependencies.add(new DependencyView("ENERGY_ITEM", normalized(code), "MISSING", displayName));
            return;
        }
        dependencies.add(new DependencyView("ENERGY_ITEM", value.versionId(), value.status(), value.itemCode()));
    }

    private DependencyView carbonFormulaDependency(String itemCode, InspectionContext context,
                                                    List<IssueView> issues, String selectedItem) {
        String formulaCode = switch (selectedItem) {
            case NATURAL_GAS_STATIONARY_FACTOR -> "STATIONARY_COMBUSTION_CO2";
            case ELECTRICITY_FACTOR, HUBEI_ELECTRICITY_FACTOR_2021, HUBEI_ELECTRICITY_FACTOR_2022,
                    HUBEI_ELECTRICITY_FACTOR_2023 -> "PURCHASED_ELECTRICITY_LOCATION_CO2";
            case HEAT_FACTOR -> "PURCHASED_HEAT_CO2";
            default -> throw validation("未知碳因子条目：" + selectedItem);
        };
        String basis = "GAS_MASS";
        CarbonRuleService.FormulaOption value = context.ruleOptions().formulas().stream()
                .filter(option -> formulaCode.equals(option.code()) && basis.equals(option.resultBasis())
                        && "FORMAL".equals(option.usageNature())).findFirst().orElse(null);
        if (value == null) {
            issues.add(issue(itemCode, "BLOCKER", "CARBON_FORMULA_REQUIRED",
                    "未找到已激活的正式碳公式 " + formulaCode + "。"));
            return new DependencyView("CARBON_FORMULA", null, "MISSING", formulaCode);
        }
        return new DependencyView("CARBON_FORMULA", value.versionId(), "ACTIVE", value.code());
    }

    private DependencyView roundingDependency(String itemCode, InspectionContext context, List<IssueView> issues) {
        CarbonRuleService.RoundingOption value = context.ruleOptions().roundingPolicies().stream()
                .filter(option -> ROUNDING_POLICY.equals(option.versionId())).findFirst().orElse(null);
        if (value == null) {
            issues.add(issue(itemCode, "BLOCKER", "ROUNDING_POLICY_REQUIRED",
                    "未找到已激活的 DECIMAL128 舍入策略。"));
            return new DependencyView("ROUNDING_POLICY", ROUNDING_POLICY, "MISSING", "CRP_DECIMAL128_V1");
        }
        return new DependencyView("ROUNDING_POLICY", value.versionId(), "ACTIVE", value.code());
    }

    private void electricityContext(String itemCode, PackageRequest request, List<IssueView> issues) {
        ElectricityContextRequest electricity = request.electricity();
        if (electricity == null) {
            issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_CONTEXT_REQUIRED",
                    "电力因子必须明确适用层级、地区（如适用）及核算年；年度平均因子另须真实数据年。"));
            return;
        }
        ElectricityCandidate candidate = electricityCandidate(itemCode);
        String level = normalized(electricity.applicabilityLevel());
        if (!candidate.applicabilityLevel().equals(level)) {
            issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_APPLICABILITY_MISMATCH",
                    "该年度电力候选仅接受明确的 " + candidate.applicabilityLevel() + " 适用层级。"));
        }
        if (candidate.regionCode() == null && !blank(electricity.regionCode())) {
            issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_REGION_UNEXPECTED", "全国适用因子不得填写地区编码。"));
        }
        if (candidate.regionCode() != null && !candidate.regionCode().equals(electricity.regionCode())) {
            issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_REGION_MISMATCH",
                    "湖北年度候选必须明确使用地区编码 420000，系统不会替换为其他地区。"));
        }
        boolean undatedDefault = ElectricityDefaultFactorPolicy.supports(candidate.factorCode(), level, electricity.accountingYear());
        if (electricity.dataYear() == null && !undatedDefault || electricity.accountingYear() == null) {
            issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_YEAR_REQUIRED", "必须提供核算年；仅2021/2022年全国0.5703默认因子可省略数据年。"));
        } else {
            if (electricity.accountingYear() < 2000 || electricity.accountingYear() > 2200
                    || electricity.dataYear() != null && (electricity.dataYear() < 1900 || electricity.dataYear() > 2200)) {
                issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_YEAR_INVALID", "核算年须为2000–2200，填写的数据年须为1900–2200。"));
                return;
            }
            if (candidate.dataYear() != null && !candidate.dataYear().equals(electricity.dataYear())) {
                issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_DATA_YEAR_MISMATCH",
                        "该候选只能使用资料给定的数据年 " + candidate.dataYear() + "；核算年仍须由请求明确给出。"));
            }
            if (request.effectiveTo() == null) {
                issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_EFFECTIVE_PERIOD_REQUIRED", "电力因子须明确有效期结束，不能无限期适用。"));
            } else if (request.effectiveFrom() != null) {
                LocalDateTime start = LocalDateTime.of(electricity.accountingYear(), 1, 1, 0, 0);
                if (request.effectiveFrom().isBefore(start) || request.effectiveTo().isAfter(start.plusYears(1))) {
                    issues.add(issue(itemCode, "BLOCKER", "ELECTRICITY_EFFECTIVE_PERIOD_MISMATCH",
                            "电力因子适用期必须完全位于明确给出的核算年度内。"));
                }
            }
        }
    }
    private void parameterConflict(String itemCode, String parameterCode, PackageRequest request,
                                   InspectionContext context, List<IssueView> issues) {
        boolean conflict = context.parameters().stream().anyMatch(value -> parameterCode.equals(value.parameterCode())
                && "PRODUCTION".equals(value.usageScope())
                && overlaps(value.effectiveFrom(), value.effectiveTo(), request));
        if (conflict) {
            issues.add(issue(itemCode, "BLOCKER", "EXISTING_PARAMETER_CONFLICT",
                    "同一正式参数已有重叠适用期版本；必须先由专业人员确认新旧参数关系，系统不会自动替换。"));
        }
    }

    private void factorConflict(String itemCode, String factorCode, PackageRequest request,
                                InspectionContext context, List<IssueView> issues) {
        boolean conflict = context.factors().stream().anyMatch(value -> factorCode.equals(value.factorCode())
                && "FORMAL".equals(value.usageNature().name())
                && overlaps(value.effectiveFrom(), value.effectiveTo(), request));
        if (conflict) {
            issues.add(issue(itemCode, "BLOCKER", "EXISTING_FACTOR_CONFLICT",
                    "同一正式因子已有重叠适用期版本；必须先确认新旧因子的替代关系。"));
        }
    }

    private ItemPreviewView itemPreview(String itemCode, BigDecimal value, String unit,
                                        List<DependencyView> dependencies, List<IssueView> issues,
                                        boolean commonBlocked) {
        boolean blocked = commonBlocked || issues.stream().anyMatch(issue -> "BLOCKER".equals(issue.severity()));
        return new ItemPreviewView(itemCode, blocked ? "BLOCKED" : "READY", value, unit, dependencies, issues);
    }

    private static boolean covers(LocalDateTime referenceFrom, LocalDateTime referenceTo, PackageRequest request) {
        if (request == null || request.effectiveFrom() == null || referenceFrom == null) return false;
        return !referenceFrom.isAfter(request.effectiveFrom()) && (referenceTo == null
                || (request.effectiveTo() != null && !referenceTo.isBefore(request.effectiveTo())));
    }

    private static boolean overlaps(LocalDateTime existingFrom, LocalDateTime existingTo, PackageRequest request) {
        if (request == null || request.effectiveFrom() == null || existingFrom == null) return false;
        LocalDateTime requestedTo = request.effectiveTo();
        return (existingTo == null || existingTo.isAfter(request.effectiveFrom()))
                && (requestedTo == null || requestedTo.isAfter(existingFrom));
    }

    private static DenominatorContextView denominatorView(DenominatorVersion value) {
        return new DenominatorContextView(value.type().name(), value.denominatorVersionId(), value.value(),
                value.unitCode(), value.status().name(), value.effectiveFrom(), value.effectiveTo());
    }

    private static CreatedVersionView created(String itemCode, String objectType, String versionId,
                                              String status, List<String> dependencies) {
        return new CreatedVersionView(itemCode, objectType, versionId, status,
                dependencies.stream().filter(value -> !blank(value)).toList());
    }

    private static IssueView issue(String itemCode, String severity, String code, String message) {
        return new IssueView(itemCode, severity, code, message);
    }

    private static BigDecimal b01NormalizedValue(String itemCode) {
        return switch (itemCode) {
            case STANDARD_COAL_LHV -> STANDARD_COAL_GJ_PER_TCE;
            case NATURAL_GAS_LHV_FORMULA, NATURAL_GAS_LHV_PARAMETER -> NATURAL_GAS_GJ_PER_TEN_THOUSAND_NM3;
            case RAW_COAL_DIRECT_TCE_FORMULA, RAW_COAL_DIRECT_TCE_PARAMETER -> RAW_COAL_TCE_PER_KG;
            default -> throw validation("未知 B01 条目：" + itemCode);
        };
    }

    private static String b01NormalizedUnit(String itemCode) {
        return switch (itemCode) {
            case STANDARD_COAL_LHV -> "GJ_PER_TCE";
            case NATURAL_GAS_LHV_FORMULA, NATURAL_GAS_LHV_PARAMETER -> "GJ_PER_TEN_THOUSAND_NM3";
            case RAW_COAL_DIRECT_TCE_FORMULA, RAW_COAL_DIRECT_TCE_PARAMETER -> "TCE_PER_KG";
            default -> throw validation("未知 B01 条目：" + itemCode);
        };
    }

    private static BigDecimal b02NormalizedValue(String itemCode) {
        return switch (itemCode) {
            case NATURAL_GAS_STATIONARY_FACTOR -> NATURAL_GAS_MJ_PER_NM3;
            case ELECTRICITY_FACTOR, HUBEI_ELECTRICITY_FACTOR_2021, HUBEI_ELECTRICITY_FACTOR_2022,
                    HUBEI_ELECTRICITY_FACTOR_2023 -> electricityCandidate(itemCode).kgCo2PerMwh();
            case HEAT_FACTOR -> HEAT_KG_CO2_PER_GJ;
            default -> throw validation("未知 B02 条目：" + itemCode);
        };
    }
    private static String b02NormalizedUnit(String itemCode) {
        return switch (itemCode) {
            case NATURAL_GAS_STATIONARY_FACTOR -> "MJ/NM3; KG_C_PER_MJ; RATIO";
            case ELECTRICITY_FACTOR, HUBEI_ELECTRICITY_FACTOR_2021, HUBEI_ELECTRICITY_FACTOR_2022,
                    HUBEI_ELECTRICITY_FACTOR_2023 -> "KG_CO2/MWH";
            case HEAT_FACTOR -> "KG_CO2/GJ";
            default -> throw validation("未知 B02 条目：" + itemCode);
        };
    }

    private record ElectricityCandidate(String factorCode, BigDecimal kgCo2PerMwh, Integer dataYear,
                                        String applicabilityLevel, String regionCode) {
    }

    private static ElectricityCandidate electricityCandidate(String itemCode) {
        return switch (itemCode) {
            case ELECTRICITY_FACTOR -> new ElectricityCandidate(ElectricityDefaultFactorPolicy.FACTOR_CODE,
                    ELECTRICITY_KG_CO2_PER_MWH, null, "NATIONAL", null);
            case HUBEI_ELECTRICITY_FACTOR_2021 -> new ElectricityCandidate("HUBEI_ELECTRICITY_CO2_2021",
                    HUBEI_2021_KG_CO2_PER_MWH, 2021, "PROVINCE", "420000");
            case HUBEI_ELECTRICITY_FACTOR_2022 -> new ElectricityCandidate("HUBEI_ELECTRICITY_CO2_2022",
                    HUBEI_2022_KG_CO2_PER_MWH, 2022, "PROVINCE", "420000");
            case HUBEI_ELECTRICITY_FACTOR_2023 -> new ElectricityCandidate("HUBEI_ELECTRICITY_CO2_2023",
                    HUBEI_2023_KG_CO2_PER_MWH, 2023, "PROVINCE", "420000");
            default -> throw validation("未知电力因子候选：" + itemCode);
        };
    }
    private static PackageDefinitionView b01Definition() {
        return new PackageDefinitionView(B01, "B01 折标准备参数", List.of(
                new PackageItemDefinitionView(STANDARD_COAL_LHV, "标准煤低位热值", "STANDARD_COAL_LHV",
                        new BigDecimal("29307.6"), "KJ/KG", STANDARD_COAL_GJ_PER_TCE, "GJ_PER_TCE",
                        List.of("source.documentReference", "source.sourceDocumentVersion", "effectiveFrom")),
                new PackageItemDefinitionView(NATURAL_GAS_LHV_FORMULA, "天然气低位热值公式", "CONVERSION_FORMULA",
                        NATURAL_GAS_GJ_PER_TEN_THOUSAND_NM3, "GJ/TEN_THOUSAND_NM3",
                        NATURAL_GAS_GJ_PER_TEN_THOUSAND_NM3, "GJ_PER_INPUT_UNIT",
                        List.of("approved unit TEN_THOUSAND_NM3", "approved unit TCE")),
                new PackageItemDefinitionView(NATURAL_GAS_LHV_PARAMETER, "天然气低位热值参数", "CONVERSION_PARAMETER",
                        NATURAL_GAS_GJ_PER_TEN_THOUSAND_NM3, "GJ/TEN_THOUSAND_NM3",
                        NATURAL_GAS_GJ_PER_TEN_THOUSAND_NM3, "GJ_PER_INPUT_UNIT",
                        List.of("dependencies.naturalGasFormulaVersionId", "dependencies.standardCoalLhvVersionId")),
                new PackageItemDefinitionView(RAW_COAL_DIRECT_TCE_FORMULA, "原煤直接折标公式", "CONVERSION_FORMULA",
                        new BigDecimal("0.7143"), "KGCE/KG", RAW_COAL_TCE_PER_KG, "TCE_PER_KG",
                        List.of("approved unit KG", "approved unit TCE")),
                new PackageItemDefinitionView(RAW_COAL_DIRECT_TCE_PARAMETER, "原煤直接折标参数", "CONVERSION_PARAMETER",
                        new BigDecimal("0.7143"), "KGCE/KG", RAW_COAL_TCE_PER_KG, "TCE_PER_KG",
                        List.of("dependencies.rawCoalEnergyItemCode=RAW_COAL", "dependencies.rawCoalFormulaVersionId"))));
    }

    private static PackageDefinitionView b02Definition() {
        return new PackageDefinitionView(B02, "B02 正式碳因子候选", List.of(
                new PackageItemDefinitionView(NATURAL_GAS_STATIONARY_FACTOR, "天然气固定燃烧 CO2 因子", "CARBON_FACTOR",
                        NATURAL_GAS_MJ_PER_NM3, "MJ/NM3", NATURAL_GAS_MJ_PER_NM3,
                        "MJ/NM3; KG_C_PER_MJ; RATIO", List.of("standardConditionCode", "source metadata")),
                new PackageItemDefinitionView(ELECTRICITY_FACTOR, "全国电力位置法 CO2 因子候选", "CARBON_FACTOR",
                        new BigDecimal("0.5703"), "TCO2/MWH", ELECTRICITY_KG_CO2_PER_MWH, "KG_CO2/MWH",
                        List.of("electricity.applicabilityLevel=NATIONAL", "electricity.accountingYear", "effectiveFrom/effectiveTo", "source metadata；2021/2022年默认因子数据年可留空")),
                new PackageItemDefinitionView(HUBEI_ELECTRICITY_FACTOR_2021, "湖北 2021 电力位置法 CO2 因子候选", "CARBON_FACTOR",
                        new BigDecimal("0.3672"), "KG_CO2/KWH", HUBEI_2021_KG_CO2_PER_MWH, "KG_CO2/MWH",
                        List.of("electricity.applicabilityLevel=PROVINCE", "electricity.regionCode=420000",
                                "electricity.dataYear=2021", "electricity.accountingYear", "source metadata: 已确认截图资料，导入时填写")),
                new PackageItemDefinitionView(HUBEI_ELECTRICITY_FACTOR_2022, "湖北 2022 电力位置法 CO2 因子候选", "CARBON_FACTOR",
                        new BigDecimal("0.4364"), "KG_CO2/KWH", HUBEI_2022_KG_CO2_PER_MWH, "KG_CO2/MWH",
                        List.of("electricity.applicabilityLevel=PROVINCE", "electricity.regionCode=420000",
                                "electricity.dataYear=2022", "electricity.accountingYear", "source metadata: 已确认截图资料，导入时填写")),
                new PackageItemDefinitionView(HUBEI_ELECTRICITY_FACTOR_2023, "湖北 2023 电力位置法 CO2 因子候选", "CARBON_FACTOR",
                        new BigDecimal("0.4044"), "KG_CO2/KWH", HUBEI_2023_KG_CO2_PER_MWH, "KG_CO2/MWH",
                        List.of("electricity.applicabilityLevel=PROVINCE", "electricity.regionCode=420000",
                                "electricity.dataYear=2023", "electricity.accountingYear",
                                "source.documentReference=https://www.mee.gov.cn/xxgk2018/xxgk/xxgk01/202512/t20251231_1139517.html")),
                new PackageItemDefinitionView(HEAT_FACTOR, "外购热力 CO2 因子候选", "CARBON_FACTOR",
                        new BigDecimal("0.11"), "TCO2/GJ", HEAT_KG_CO2_PER_GJ, "KG_CO2/GJ",
                        List.of("source metadata"))));
    }
    private List<FactorVersion> visibleCatalogFactors(long userId, Collection<String> roles, String buildingId,
                                                       List<FactorVersion> values) {
        assertFactorListComplete(values);
        if (!blank(buildingId)) buildingScopeService.checkAccess(userId, roles, buildingId.trim());
        return values.stream().filter(value -> value.buildingId() == null
                || (!blank(buildingId) && buildingId.trim().equals(value.buildingId()))).toList();
    }

    private List<FactorVersion> globalFactors(List<FactorVersion> values) {
        assertFactorListComplete(values);
        return values.stream().filter(value -> value.buildingId() == null).toList();
    }

    private static void assertFactorListComplete(List<FactorVersion> values) {
        if (values.size() >= 500) {
            throw EnergyParameterPackageErrors.error(409, EnergyParameterPackageErrors.LIST_LIMIT_REACHED,
                    "碳因子列表达到服务上限，拒绝以截断结果执行目录或预检。");
        }
    }
    private static List<CatalogRequirementView> catalogRequirements(List<ItemVersionView> items,
                                                                    List<UnitVersionView> units) {
        List<CatalogRequirementView> requirements = new ArrayList<>();
        for (String code : List.of("NATURAL_GAS", "ELECTRICITY", "HEAT")) {
            requirements.add(itemRequirement(code, items));
        }
        requirements.add(itemRequirement(RAW_COAL_ITEM_CODE, items));
        for (String code : List.of("GJ", "TCE", "TEN_THOUSAND_NM3", "KG", "NM3", "MWH")) {
            requirements.add(unitRequirement(code, units));
        }
        return List.copyOf(requirements);
    }

    private static CatalogRequirementView itemRequirement(String code, List<ItemVersionView> values) {
        return new CatalogRequirementView("ENERGY_ITEM", code, "/v1/energy-catalog/items",
                "/v1/energy-catalog/item-versions", values.stream().filter(value -> code.equals(value.itemCode()))
                .map(value -> new VersionContextView("ENERGY_ITEM", value.itemCode(), value.versionId(),
                        value.status(), null, value.effectiveFrom(), value.effectiveTo())).toList());
    }

    private static CatalogRequirementView unitRequirement(String code, List<UnitVersionView> values) {
        return new CatalogRequirementView("MEASUREMENT_UNIT", code, "/v1/energy-catalog/units",
                "/v1/energy-catalog/unit-versions", values.stream().filter(value -> code.equals(value.unitCode()))
                .map(value -> new VersionContextView("MEASUREMENT_UNIT", value.unitCode(), value.versionId(),
                        value.status(), null, value.effectiveFrom(), value.effectiveTo())).toList());
    }
    private void requireImportAuthority(long userId, Collection<String> roles, String packageCode) {
        if (roles == null || roles.stream().noneMatch(role -> Set.of("ENERGY_MANAGER", "PLATFORM_ADMIN")
                .contains(role))) {
            throw EnergyParameterPackageErrors.error(403, EnergyParameterPackageErrors.FORBIDDEN,
                    "正式参数包导入仅允许能效管理方或平台管理员执行。");
        }
        BackendDuty duty = B01.equals(packageCode) ? BackendDuty.ENERGY_RULE_MAINTAIN
                : BackendDuty.CARBON_RULE_MAINTAIN;
        if (!dutyService.hasDuty(userId, duty)) {
            throw EnergyParameterPackageErrors.error(403, EnergyParameterPackageErrors.FORBIDDEN,
                    "当前操作者缺少参数包导入所需动态职责。");
        }
    }
    private String idempotencyKey(PackageRequest request) {
        String key = request == null ? null : request.idempotencyKey();
        if (blank(key) || key.trim().length() > 100) {
            throw validation("幂等键必填且最多100字符");
        }
        return key.trim();
    }

    private String requestHash(long userId, PackageRequest request) {
        return CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(List.of(userId, request))));
    }

    private String json(ImportView value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw EnergyParameterPackageErrors.error(400, EnergyParameterPackageErrors.VALIDATION_FAILED,
                    "参数包导入回执无法保存");
        }
    }

    private ImportView decodeReceipt(String value) {
        try {
            return objectMapper.readValue(value, ImportView.class);
        } catch (Exception exception) {
            throw EnergyParameterPackageErrors.error(409, EnergyParameterPackageErrors.RECEIPT_UNREADABLE,
                    "参数包导入回执无法读取");
        }
    }
    private List<String> selectedItems(PackageRequest request, boolean required) {
        String packageCode = packageCode(request);
        List<String> available = definition(packageCode).items().stream()
                .map(PackageItemDefinitionView::itemCode).toList();
        if (request.itemCodes().isEmpty()) {
            if (required) throw validation("导入必须明确选择条目，不能隐式导入整包");
            return available;
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String value : request.itemCodes()) {
            String code = normalized(value);
            if (!available.contains(code)) throw validation("参数包不包含条目：" + code);
            if (!selected.add(code)) throw validation("参数包条目不得重复：" + code);
        }
        return List.copyOf(selected);
    }

    private static String packageCode(PackageRequest request) {
        if (request == null) throw validation("参数包请求不能为空");
        String value = normalized(request.packageCode());
        if (!B01.equals(value) && !B02.equals(value)) throw validation("参数包编码只支持 B01 或 B02");
        return value;
    }

    private static PackageDefinitionView definition(String packageCode) {
        return B01.equals(packageCode) ? b01Definition() : b02Definition();
    }

    private static String conversionSourceType(PackageRequest request) {
        return normalized(request.source().energySourceType());
    }

    private static String sourceReference(PackageRequest request) {
        return sourceDocumentReference(request) + ";evidence=" + request.source().evidenceReference().trim();
    }

    private static String sourceDocumentReference(PackageRequest request) {
        return request.source().documentReference().trim() + ";version="
                + request.source().sourceDocumentVersion().trim();
    }

    private static FactorComponentRequest component(String type, BigDecimal value, String unit,
                                                    FactorSourceVersion source) {
        return new FactorComponentRequest(type, value, unit, source.sourceVersionId(), source.evidenceReference());
    }

    private static String formulaVersion(CarbonRuleService.RuleOptions options, String formulaCode,
                                         String resultBasis) {
        return options.formulas().stream().filter(value -> formulaCode.equals(value.code())
                        && resultBasis.equals(value.resultBasis()) && "FORMAL".equals(value.usageNature()))
                .findFirst().map(CarbonRuleService.FormulaOption::versionId)
                .orElseThrow(() -> validation("未找到已激活正式碳公式：" + formulaCode));
    }

    private static String roundingVersion(CarbonRuleService.RuleOptions options) {
        return options.roundingPolicies().stream().filter(value -> ROUNDING_POLICY.equals(value.versionId()))
                .findFirst().map(CarbonRuleService.RoundingOption::versionId)
                .orElseThrow(() -> validation("未找到已激活 DECIMAL128 舍入策略"));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException validation(String message) {
        return EnergyParameterPackageErrors.error(400, EnergyParameterPackageErrors.VALIDATION_FAILED, message);
    }

    private record InspectionContext(
            List<ItemVersionView> items,
            List<UnitVersionView> units,
            List<StandardCoalVersionView> standardCoal,
            List<FormulaVersionView> formulas,
            List<ParameterVersionView> parameters,
            CarbonRuleService.RuleOptions ruleOptions,
            List<FactorVersion> factors) {
        Map<String, ItemVersionView> itemsByCode() {
            return items.stream().collect(Collectors.toMap(value -> normalized(value.itemCode()), value -> value,
                    (left, right) -> left.versionNo() >= right.versionNo() ? left : right));
        }

        Map<String, UnitVersionView> unitsByCode() {
            return units.stream().collect(Collectors.toMap(value -> normalized(value.unitCode()), value -> value,
                    (left, right) -> left.versionNo() >= right.versionNo() ? left : right));
        }

        Map<String, StandardCoalVersionView> standardCoalByVersion() {
            return standardCoal.stream().collect(Collectors.toMap(StandardCoalVersionView::versionId, value -> value));
        }

        Map<String, FormulaVersionView> formulasByVersion() {
            return formulas.stream().collect(Collectors.toMap(FormulaVersionView::versionId, value -> value));
        }
    }
}
