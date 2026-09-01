package com.platform.energy.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.energy.catalog.EnergyCatalogRepository.BindingVersionRow;
import com.platform.energy.catalog.EnergyCatalogRepository.CompatibilityVersionRow;
import com.platform.energy.catalog.EnergyCatalogRepository.ItemVersionRow;
import com.platform.energy.catalog.EnergyCatalogRepository.UnitVersionRow;
import com.platform.energy.catalog.api.EnergyCatalogContracts.ApproveRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CompatibilityVersionView;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateBindingVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateCompatibilityVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateItemVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateUnitVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.ItemVersionView;
import com.platform.energy.catalog.api.EnergyCatalogContracts.OptionsView;
import com.platform.energy.catalog.api.EnergyCatalogContracts.UnitVersionView;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.iot.energymetadata.EnergyMetadataModels.EnergyType;
import com.platform.iot.energymetadata.EnergyMetadataModels.ValueSemantics;
import com.platform.iot.energymetadata.mapper.BizEnergyPointProfileMapper;
import com.platform.iot.energymetadata.model.entity.BizEnergyPointProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.platform.energy.catalog.EnergyCatalogModels.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 统一执行字典版本、专业审核、单位硬边界和测点绑定安全校验。 */
public class EnergyCatalogService {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Set<String> OUTPUT_ONLY_UNITS = Set.of("KGCE", "TCE");

    private final EnergyCatalogAuthorization authorization;
    private final EnergyCatalogRepository repository;
    private final BizDataPointMapper pointMapper;
    private final BizEnergyPointProfileMapper profileMapper;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    public List<ItemVersionView> listItems(Collection<String> roles) {
        authorization.requireReader(roles);
        return repository.listItems().stream().map(this::itemView).toList();
    }

    public List<UnitVersionView> listUnits(Collection<String> roles) {
        authorization.requireReader(roles);
        return repository.listUnits().stream().map(EnergyCatalogService::unitView).toList();
    }

    public List<CompatibilityVersionView> listCompatibilities(Collection<String> roles) {
        authorization.requireReader(roles);
        return repository.listCompatibilities().stream().map(EnergyCatalogService::compatibilityView).toList();
    }

    public List<BindingVersionView> listBindings(long userId, Collection<String> roles,
                                                  String buildingId, String pointId) {
        authorization.requireReader(roles);
        authorization.checkBuilding(userId, roles, buildingId);
        return repository.listBindings(buildingId, pointId).stream()
                .map(EnergyCatalogService::bindingView).toList();
    }

    public BindingVersionView effectiveBinding(long userId, Collection<String> roles,
                                                String buildingId, String pointId,
                                                LocalDateTime effectiveAt) {
        authorization.requireReader(roles);
        authorization.checkBuilding(userId, roles, buildingId);
        BizDataPoint point = requirePoint(pointId);
        requireBuilding(buildingId, point.getBuildingId());
        BindingVersionRow value = repository.findEffectiveBinding(pointId, requiredTime(effectiveAt));
        if (value == null) notFound("指定业务时间没有已确认能源品种绑定");
        return bindingView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public ItemVersionView createItemVersion(long userId, Collection<String> roles,
                                             CreateItemVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String itemCode = code(request.itemCode(), "能源品种编码无效");
        String category = parse(EnergyType.class, request.compatibleCategory(), "兼容粗分类无效").name();
        List<String> scopes = request.usageScopes().stream()
                .map(value -> parse(UsageScope.class, value, "能源使用范围无效").name())
                .distinct().sorted().toList();
        if (scopes.contains(UsageScope.MOBILE_COMBUSTION.name())) {
            throw EnergyCatalogErrors.error(400, EnergyCatalogErrors.MOBILE_SCOPE_REJECTED,
                    "第七闭环不接收移动源能源范围");
        }
        SourceType sourceType = parse(SourceType.class, request.sourceType(), "来源类型无效");
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        LocalDateTime now = LocalDateTime.now();
        String itemId = repository.findItemIdForUpdate(itemCode);
        if (itemId == null) {
            itemId = id();
            try {
                repository.insertItem(itemId, itemCode, userId, now);
            } catch (DuplicateKeyException ex) {
                versionConflict();
            }
        }
        ItemVersionRow value = new ItemVersionRow(itemId, itemCode, id(),
                repository.nextItemVersion(itemId), text(request.itemName(), 100, "能源品种名称无效"),
                category, CatalogStatus.PENDING_EXPERT.name(), sourceType.name(),
                text(request.sourceReference(), 500, "来源引用无效"), range.from(), range.to(),
                0, userId, now, null, null);
        repository.insertItemVersion(value, scopes);
        audit(userId, "CREATE_ITEM_VERSION", "ENERGY_ITEM", value.versionId(), value.versionId(),
                null, itemSummary(value, scopes), false, null);
        return itemView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public UnitVersionView createUnitVersion(long userId, Collection<String> roles,
                                             CreateUnitVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        String unitCode = code(request.unitCode(), "单位编码无效");
        String canonicalCode = code(request.canonicalUnitCode(), "基准单位编码无效");
        DimensionCode dimension = parse(DimensionCode.class, request.dimensionCode(), "单位量纲无效");
        ConversionType conversion = parse(ConversionType.class, request.conversionType(), "换算类型无效");
        SourceType sourceType = parse(SourceType.class, request.sourceType(), "来源类型无效");
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        validateUnitDefinition(unitCode, canonicalCode, dimension, request.scaleFactor(), conversion,
                request.standardConditionCode(), range.from());
        if ("NM3".equals(unitCode) && blank(request.standardConditionCode())) {
            validation("Nm3 必须填写标准状态依据");
        }
        if (request.precision() > 12) validation("单位精度不能超过12位");
        LocalDateTime now = LocalDateTime.now();
        String unitId = repository.findUnitIdForUpdate(unitCode);
        if (unitId == null) {
            unitId = id();
            try {
                repository.insertUnit(unitId, unitCode, userId, now);
            } catch (DuplicateKeyException ex) {
                versionConflict();
            }
        }
        UnitVersionRow value = new UnitVersionRow(unitId, unitCode, id(),
                repository.nextUnitVersion(unitId), text(request.symbol(), 32, "单位符号无效"),
                text(request.unitName(), 100, "单位名称无效"), dimension.name(), canonicalCode,
                request.scaleFactor(), conversion.name(), trim(request.standardConditionCode()),
                request.precision(), CatalogStatus.PENDING_EXPERT.name(), sourceType.name(),
                text(request.sourceReference(), 500, "来源引用无效"), range.from(), range.to(),
                0, userId, now, null, null);
        repository.insertUnitVersion(value);
        audit(userId, "CREATE_UNIT_VERSION", "MEASUREMENT_UNIT", value.versionId(), value.versionId(),
                null, unitSummary(value), false, null);
        return unitView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public CompatibilityVersionView createCompatibilityVersion(
            long userId, Collection<String> roles, CreateCompatibilityVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        ItemVersionRow item = repository.findApprovedItem(code(request.energyItemCode(), "能源品种编码无效"),
                range.from());
        if (item == null) notFound("兼容矩阵只能引用已审核能源品种版本");
        UnitVersionRow unit = repository.findApprovedUnitByCode(code(request.unitCode(), "单位编码无效"),
                range.from());
        if (unit == null) notFound("兼容矩阵只能引用已审核单位版本");
        if (OUTPUT_ONLY_UNITS.contains(unit.unitCode())) {
            throw EnergyCatalogErrors.error(400, EnergyCatalogErrors.UNIT_INCOMPATIBLE,
                    "kgce/tce 只能作为折标结果单位");
        }
        String semantics = parse(ValueSemantics.class, request.valueSemantics(), "活动数据语义无效").name();
        ConversionRequirement requirement = parse(ConversionRequirement.class,
                request.conversionRequirement(), "兼容换算要求无效");
        validateCompatibility(unit, semantics, requirement);
        SourceType sourceType = parse(SourceType.class, request.sourceType(), "来源类型无效");
        LocalDateTime now = LocalDateTime.now();
        String compatibilityId = repository.findCompatibilityIdForUpdate(
                item.itemId(), unit.unitId(), semantics);
        if (compatibilityId == null) {
            compatibilityId = id();
            try {
                repository.insertCompatibility(compatibilityId, item.itemId(), unit.unitId(), semantics,
                        userId, now);
            } catch (DuplicateKeyException ex) {
                versionConflict();
            }
        }
        CompatibilityVersionRow value = new CompatibilityVersionRow(compatibilityId, id(),
                repository.nextCompatibilityVersion(compatibilityId), item.itemCode(), unit.unitCode(),
                semantics, request.allowed(), requirement.name(), CatalogStatus.PENDING_EXPERT.name(),
                sourceType.name(), text(request.sourceReference(), 500, "来源引用无效"),
                range.from(), range.to(), 0, userId, now, null, null);
        repository.insertCompatibilityVersion(value);
        audit(userId, "CREATE_COMPATIBILITY_VERSION", "ENERGY_ITEM_UNIT_COMPATIBILITY",
                value.compatibilityId(), value.versionId(), null, compatibilitySummary(value), false, null);
        return compatibilityView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public BindingVersionView createBindingVersion(long userId, Collection<String> roles,
                                                   CreateBindingVersionRequest request) {
        authorization.requireMaintainer(userId, roles);
        authorization.checkBuilding(userId, roles, request.buildingId());
        TimeRange range = range(request.effectiveFrom(), request.effectiveTo());
        BizDataPoint point = pointMapper.selectByIdForUpdate(request.pointId());
        if (point == null) notFound("标准测点不存在");
        requireBuilding(request.buildingId(), point.getBuildingId());
        ValidatedBinding validated = validateBinding(point, code(request.energyItemCode(), "能源品种编码无效"),
                range.from());
        LocalDateTime now = LocalDateTime.now();
        String bindingId = repository.findBindingIdForUpdate(point.getPointId());
        if (bindingId == null) {
            bindingId = id();
            repository.insertBinding(bindingId, point.getBuildingId(), point.getPointId(), userId, now);
        }
        BindingVersionRow value = new BindingVersionRow(bindingId, id(),
                repository.nextBindingVersion(bindingId), point.getBuildingId(), point.getPointId(),
                point.getPointCode(), point.getUnit(), validated.item().itemCode(),
                validated.item().versionId(), range.from(), range.to(), BindingStatus.PENDING_EXPERT.name(),
                text(request.evidenceReference(), 500, "绑定依据无效"), 0, userId, now, null, null);
        repository.insertBindingVersion(value);
        audit(userId, "CREATE_POINT_ITEM_BINDING", "ENERGY_POINT_ITEM_BINDING", value.bindingId(),
                value.bindingVersionId(), null, bindingSummary(value), false, point.getBuildingId());
        return bindingView(value);
    }

    @Transactional(rollbackFor = Exception.class)
    public ItemVersionView approveItem(long userId, Collection<String> roles, String versionId,
                                       ApproveRequest request) {
        authorization.requireReviewer(userId, roles);
        ItemVersionRow value = requireItem(versionId);
        requirePending(value.status());
        authorization.requireSeparation(value.createdBy(), userId);
        ItemVersionRow previous = repository.findLatestApprovedItem(value.itemId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeItemVersion);
        if (repository.approveItem(versionId, request.expectedRevision(), userId, LocalDateTime.now()) != 1) {
            versionConflict();
        }
        ItemVersionRow approved = requireItem(versionId);
        audit(userId, "APPROVE_ITEM_VERSION", "ENERGY_ITEM", approved.itemId(), approved.versionId(),
                itemSummary(value, repository.itemScopes(value.versionId())),
                reviewed(itemSummary(approved, repository.itemScopes(approved.versionId())), request),
                selfApproval(value.createdBy(), userId), null);
        return itemView(approved);
    }

    @Transactional(rollbackFor = Exception.class)
    public UnitVersionView approveUnit(long userId, Collection<String> roles, String versionId,
                                       ApproveRequest request) {
        authorization.requireReviewer(userId, roles);
        UnitVersionRow value = requireUnit(versionId);
        requirePending(value.status());
        authorization.requireSeparation(value.createdBy(), userId);
        validateUnitDefinition(value.unitCode(), value.canonicalUnitCode(),
                parse(DimensionCode.class, value.dimensionCode(), "单位量纲无效"), value.scaleFactor(),
                parse(ConversionType.class, value.conversionType(), "换算类型无效"),
                value.standardConditionCode(), value.effectiveFrom());
        UnitVersionRow previous = repository.findLatestApprovedUnit(value.unitId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeUnitVersion);
        if (repository.approveUnit(versionId, request.expectedRevision(), userId, LocalDateTime.now()) != 1) {
            versionConflict();
        }
        UnitVersionRow approved = requireUnit(versionId);
        audit(userId, "APPROVE_UNIT_VERSION", "MEASUREMENT_UNIT", approved.unitId(), approved.versionId(),
                unitSummary(value), reviewed(unitSummary(approved), request),
                selfApproval(value.createdBy(), userId), null);
        return unitView(approved);
    }

    @Transactional(rollbackFor = Exception.class)
    public CompatibilityVersionView approveCompatibility(long userId, Collection<String> roles,
                                                          String versionId, ApproveRequest request) {
        authorization.requireReviewer(userId, roles);
        CompatibilityVersionRow value = requireCompatibility(versionId);
        requirePending(value.status());
        authorization.requireSeparation(value.createdBy(), userId);
        CompatibilityVersionRow previous = repository.findLatestApprovedCompatibility(value.compatibilityId());
        closePrevious(previous == null ? null : previous.versionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeCompatibilityVersion);
        if (repository.approveCompatibility(versionId, request.expectedRevision(), userId,
                LocalDateTime.now()) != 1) {
            versionConflict();
        }
        CompatibilityVersionRow approved = requireCompatibility(versionId);
        audit(userId, "APPROVE_COMPATIBILITY_VERSION", "ENERGY_ITEM_UNIT_COMPATIBILITY",
                approved.compatibilityId(), approved.versionId(), compatibilitySummary(value),
                reviewed(compatibilitySummary(approved), request),
                selfApproval(value.createdBy(), userId), null);
        return compatibilityView(approved);
    }

    @Transactional(rollbackFor = Exception.class)
    public BindingVersionView approveBinding(long userId, Collection<String> roles, String versionId,
                                             ApproveRequest request) {
        authorization.requireReviewer(userId, roles);
        BindingVersionRow value = requireBinding(versionId);
        authorization.checkBuilding(userId, roles, value.buildingId());
        if (!BindingStatus.PENDING_EXPERT.name().equals(value.confirmationStatus())) requirePending(value.confirmationStatus());
        authorization.requireSeparation(value.createdBy(), userId);
        BizDataPoint point = pointMapper.selectByIdForUpdate(value.pointId());
        if (point == null) notFound("标准测点不存在");
        requireBuilding(value.buildingId(), point.getBuildingId());
        ValidatedBinding validated = validateBinding(point, value.itemCode(), value.effectiveFrom());
        if (!Objects.equals(validated.item().versionId(), value.energyItemVersionId())) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.VERSION_CONFLICT,
                    "待审核绑定引用的能源品种版本已不再有效");
        }
        BindingVersionRow previous = repository.findLatestConfirmedBinding(value.bindingId());
        closePrevious(previous == null ? null : previous.bindingVersionId(),
                previous == null ? null : previous.effectiveFrom(), value.effectiveFrom(),
                repository::closeBindingVersion);
        if (repository.approveBinding(versionId, request.expectedRevision(), userId,
                LocalDateTime.now()) != 1) {
            versionConflict();
        }
        BindingVersionRow approved = requireBinding(versionId);
        audit(userId, "APPROVE_POINT_ITEM_BINDING", "ENERGY_POINT_ITEM_BINDING",
                approved.bindingId(), approved.bindingVersionId(), bindingSummary(value),
                reviewed(bindingSummary(approved), request), selfApproval(value.createdBy(), userId),
                approved.buildingId());
        return bindingView(approved);
    }

    public OptionsView options(Collection<String> roles) {
        authorization.requireReader(roles);
        return new OptionsView(names(CatalogStatus.values()), names(SourceType.values()),
                names(UsageScope.values()), names(DimensionCode.values()), names(ConversionType.values()),
                names(ConversionRequirement.values()), names(BindingStatus.values()));
    }

    private ValidatedBinding validateBinding(BizDataPoint point, String itemCode, LocalDateTime at) {
        BizEnergyPointProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<BizEnergyPointProfile>()
                .eq(BizEnergyPointProfile::getPointId, point.getPointId()));
        if (profile == null || !"CONFIRMED".equals(profile.getConfirmationStatus())) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.POINT_PROFILE_REQUIRED,
                    "测点能源专业属性必须先完成确认");
        }
        ItemVersionRow item = repository.findApprovedItem(itemCode, at);
        if (item == null) notFound("指定业务时间没有已审核能源品种版本");
        if (!Objects.equals(item.compatibleCategory(), profile.getEnergyType())) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.ITEM_CATEGORY_CONFLICT,
                    "能源品种与测点粗分类不一致");
        }
        List<String> scopes = repository.itemScopes(item.versionId());
        if (scopes.contains(UsageScope.MOBILE_COMBUSTION.name())) {
            throw EnergyCatalogErrors.error(400, EnergyCatalogErrors.MOBILE_SCOPE_REJECTED,
                    "第七闭环拒绝移动源能源品种绑定");
        }
        UnitVersionRow unit = repository.findApprovedUnitBySymbol(point.getUnit(), at);
        if (unit == null || OUTPUT_ONLY_UNITS.contains(unit.unitCode())) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.UNIT_INCOMPATIBLE,
                    "测点原始单位没有可用的受控单位版本");
        }
        CompatibilityVersionRow compatibility = repository.findApprovedCompatibility(
                item.itemId(), unit.unitId(), profile.getValueSemantics(), at);
        if (compatibility == null || !compatibility.allowed()) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.UNIT_INCOMPATIBLE,
                    "能源品种、原始单位和活动语义没有已审核兼容规则");
        }
        return new ValidatedBinding(item, unit, compatibility);
    }

    private void validateCompatibility(UnitVersionRow unit, String semantics,
                                       ConversionRequirement requirement) {
        if (DimensionCode.POWER.name().equals(unit.dimensionCode())) {
            if (!ValueSemantics.INSTANTANEOUS.name().equals(semantics)
                    || requirement != ConversionRequirement.TIME_INTEGRATION) {
                validation("功率单位只允许瞬时量，并必须声明时间积分要求");
            }
        } else if (requirement == ConversionRequirement.TIME_INTEGRATION) {
            validation("非功率单位不能声明时间积分要求");
        }
        if (DimensionCode.NORMAL_VOLUME.name().equals(unit.dimensionCode())
                && blank(unit.standardConditionCode())) {
            validation("标准体积单位缺少标准状态依据");
        }
    }

    /** 固定比例白名单防止把密度、焓值或时间积分误配置为普通单位换算。 */
    private void validateUnitDefinition(String unitCode, String canonicalCode, DimensionCode dimension,
                                        BigDecimal factor, ConversionType type,
                                        String standardConditionCode, LocalDateTime effectiveFrom) {
        if (factor == null || factor.signum() <= 0) validation("单位换算比例必须大于0");
        if (type == ConversionType.IDENTITY) {
            if (!unitCode.equals(canonicalCode) || factor.compareTo(BigDecimal.ONE) != 0) {
                validation("同一单位的基准编码必须等于自身且比例为1");
            }
            requireKnownDimension(unitCode, dimension);
            return;
        }
        if (type != ConversionType.FIXED_SCALE) {
            validation("首版单位字典只接受自身单位或设计冻结的固定比例换算");
        }
        boolean allowed = fixed(unitCode, canonicalCode, factor, "MWH", "KWH", "1000")
                || fixed(unitCode, canonicalCode, factor, "GJ", "MJ", "1000")
                || fixed(unitCode, canonicalCode, factor, "T", "KG", "1000")
                || fixed(unitCode, canonicalCode, factor, "TEN_THOUSAND_NM3", "NM3", "10000")
                || fixed(unitCode, canonicalCode, factor, "TCE", "KGCE", "1000");
        if (!allowed) validation("该换算不是首版允许的固定比例单位换算");
        requireKnownDimension(unitCode, dimension);
        UnitVersionRow canonical = repository.findApprovedUnitByCode(canonicalCode, effectiveFrom);
        if (canonical == null && !unitCode.equals(canonicalCode)) {
            notFound("固定比例换算引用的基准单位尚未审核");
        }
        if (canonical != null && !dimension.name().equals(canonical.dimensionCode())) {
            validation("换算单位与基准单位量纲不一致");
        }
        if (("NM3".equals(unitCode) || "TEN_THOUSAND_NM3".equals(unitCode))
                && blank(standardConditionCode)) {
            validation("标准体积单位必须填写标准状态依据");
        }
    }

    /** 已冻结单位的量纲不能通过新版本改写；未知自定义身份仍必须走专业审核。 */
    private static void requireKnownDimension(String unitCode, DimensionCode dimension) {
        DimensionCode expected = switch (unitCode) {
            case "KW" -> DimensionCode.POWER;
            case "KWH", "MWH", "MJ", "GJ" -> DimensionCode.ENERGY;
            case "M3" -> DimensionCode.ACTUAL_VOLUME;
            case "NM3", "TEN_THOUSAND_NM3" -> DimensionCode.NORMAL_VOLUME;
            case "KG", "T" -> DimensionCode.MASS;
            case "KGCE", "TCE" -> DimensionCode.STANDARD_COAL_EQUIVALENT;
            default -> null;
        };
        if (expected != null && expected != dimension) validation("已冻结单位不能变更量纲");
    }

    private static boolean fixed(String unitCode, String canonicalCode, BigDecimal factor,
                                 String expectedUnit, String expectedCanonical, String expectedFactor) {
        return expectedUnit.equals(unitCode) && expectedCanonical.equals(canonicalCode)
                && factor.compareTo(new BigDecimal(expectedFactor)) == 0;
    }

    private ItemVersionRow requireItem(String versionId) {
        ItemVersionRow value = repository.findItemVersion(versionId);
        if (value == null) notFound("能源品种版本不存在");
        return value;
    }

    private UnitVersionRow requireUnit(String versionId) {
        UnitVersionRow value = repository.findUnitVersion(versionId);
        if (value == null) notFound("单位版本不存在");
        return value;
    }

    private CompatibilityVersionRow requireCompatibility(String versionId) {
        CompatibilityVersionRow value = repository.findCompatibilityVersion(versionId);
        if (value == null) notFound("兼容矩阵版本不存在");
        return value;
    }

    private BindingVersionRow requireBinding(String versionId) {
        BindingVersionRow value = repository.findBindingVersion(versionId);
        if (value == null) notFound("测点能源品种绑定版本不存在");
        return value;
    }

    private BizDataPoint requirePoint(String pointId) {
        BizDataPoint value = pointMapper.selectById(pointId);
        if (value == null) notFound("标准测点不存在");
        return value;
    }

    private void audit(long operatorId, String action, String objectType, String objectId,
                       String versionId, String before, String after, boolean selfApproval,
                       String buildingId) {
        auditWriter.append(new AuditEvidence("ENERGY_CATALOG", buildingId, "USER", operatorId,
                action, objectType, objectId, versionId, null, before, after, "SUCCESS", null,
                TraceContext.current(), LocalDateTime.now(), auditProperties.getEnvironmentMode(),
                selfApproval));
    }

    private static void closePrevious(String previousVersionId, LocalDateTime previousFrom,
                                      LocalDateTime nextFrom, VersionCloser closer) {
        if (previousVersionId == null) return;
        if (!nextFrom.isAfter(previousFrom)) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.EFFECTIVE_TIME_CONFLICT,
                    "新审核版本的生效时间必须晚于当前已审核版本");
        }
        closer.close(previousVersionId, nextFrom);
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

    private static String text(String value, int max, String message) {
        String normalized = trim(value);
        if (blank(normalized) || normalized.length() > max) validation(message);
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw EnergyCatalogErrors.error(400, EnergyCatalogErrors.VALIDATION_FAILED, message);
        }
    }

    private static void requirePending(String status) {
        if (!CatalogStatus.PENDING_EXPERT.name().equals(status)
                && !BindingStatus.PENDING_EXPERT.name().equals(status)) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.STATUS_CONFLICT,
                    "只有待专业确认版本可以审核");
        }
    }

    private static void requireBuilding(String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.BUILDING_MISMATCH,
                    "测点与请求建筑不一致");
        }
    }

    private static void validation(String message) {
        throw EnergyCatalogErrors.error(400, EnergyCatalogErrors.VALIDATION_FAILED, message);
    }

    private static void notFound(String message) {
        throw EnergyCatalogErrors.error(404, EnergyCatalogErrors.NOT_FOUND, message);
    }

    private static void versionConflict() {
        throw EnergyCatalogErrors.error(409, EnergyCatalogErrors.VERSION_CONFLICT,
                "版本已被其他操作修改");
    }

    private boolean selfApproval(Long createdBy, long reviewerId) {
        return createdBy != null && createdBy == reviewerId && auditProperties.isAllowSelfApproval();
    }

    private ItemVersionView itemView(ItemVersionRow value) {
        return new ItemVersionView(value.itemId(), value.itemCode(), value.versionId(), value.versionNo(),
                value.itemName(), value.compatibleCategory(), repository.itemScopes(value.versionId()),
                value.status(), value.sourceType(), value.sourceReference(), value.effectiveFrom(),
                value.effectiveTo(), value.configRevision(), value.createdBy(), value.createdAt(),
                value.approvedBy(), value.approvedAt());
    }

    private static UnitVersionView unitView(UnitVersionRow value) {
        return new UnitVersionView(value.unitId(), value.unitCode(), value.versionId(), value.versionNo(),
                value.symbol(), value.unitName(), value.dimensionCode(), value.canonicalUnitCode(),
                value.scaleFactor(), value.conversionType(), value.standardConditionCode(), value.precision(),
                value.status(), value.sourceType(), value.sourceReference(), value.effectiveFrom(),
                value.effectiveTo(), value.configRevision(), value.createdBy(), value.createdAt(),
                value.approvedBy(), value.approvedAt());
    }

    private static CompatibilityVersionView compatibilityView(CompatibilityVersionRow value) {
        return new CompatibilityVersionView(value.compatibilityId(), value.versionId(), value.versionNo(),
                value.itemCode(), value.unitCode(), value.valueSemantics(), value.allowed(),
                value.conversionRequirement(), value.status(), value.sourceType(), value.sourceReference(),
                value.effectiveFrom(), value.effectiveTo(), value.configRevision(), value.createdBy(),
                value.createdAt(), value.approvedBy(), value.approvedAt());
    }

    private static BindingVersionView bindingView(BindingVersionRow value) {
        return new BindingVersionView(value.bindingId(), value.bindingVersionId(), value.bindingVersion(),
                value.buildingId(), value.pointId(), value.pointCode(), value.rawUnit(), value.itemCode(),
                value.energyItemVersionId(), value.effectiveFrom(), value.effectiveTo(),
                value.confirmationStatus(), value.evidenceReference(), value.configRevision(),
                value.createdBy(), value.createdAt(), value.approvedBy(), value.approvedAt());
    }

    private static String itemSummary(ItemVersionRow value, List<String> scopes) {
        return "itemCode=" + value.itemCode() + ";version=" + value.versionNo()
                + ";category=" + value.compatibleCategory() + ";scopes=" + String.join(",", scopes)
                + ";status=" + value.status() + ";sourceReferencePresent=true";
    }

    private static String unitSummary(UnitVersionRow value) {
        return "unitCode=" + value.unitCode() + ";version=" + value.versionNo()
                + ";dimension=" + value.dimensionCode() + ";canonical=" + value.canonicalUnitCode()
                + ";status=" + value.status() + ";sourceReferencePresent=true";
    }

    private static String compatibilitySummary(CompatibilityVersionRow value) {
        return "itemCode=" + value.itemCode() + ";unitCode=" + value.unitCode()
                + ";semantics=" + value.valueSemantics() + ";allowed=" + value.allowed()
                + ";version=" + value.versionNo() + ";status=" + value.status()
                + ";sourceReferencePresent=true";
    }

    private static String bindingSummary(BindingVersionRow value) {
        return "pointId=" + value.pointId() + ";itemCode=" + value.itemCode()
                + ";bindingVersion=" + value.bindingVersion() + ";status=" + value.confirmationStatus()
                + ";effectiveFrom=" + value.effectiveFrom() + ";evidencePresent=true";
    }

    private static String reviewed(String summary, ApproveRequest request) {
        return summary + ";reviewComment=" + request.reviewComment().trim();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record TimeRange(LocalDateTime from, LocalDateTime to) {
    }

    private record ValidatedBinding(ItemVersionRow item, UnitVersionRow unit,
                                    CompatibilityVersionRow compatibility) {
    }

    @FunctionalInterface
    private interface VersionCloser {
        void close(String versionId, LocalDateTime effectiveTo);
    }
}
