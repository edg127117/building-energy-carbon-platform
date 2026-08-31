package com.platform.iot.energymetadata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizPointAlias;
import com.platform.iot.collection.mapper.BizCollectionConfigAuditLogMapper;
import com.platform.iot.collection.mapper.BizCollectionPolicyMapper;
import com.platform.iot.collection.mapper.BizCollectionPolicyVersionMapper;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.entity.BizCollectionConfigAuditLog;
import com.platform.iot.collection.model.entity.BizCollectionPolicy;
import com.platform.iot.collection.model.entity.BizCollectionPolicyVersion;
import com.platform.iot.collection.model.entity.BizDataSource;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.CollectionContextView;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.CreateRequest;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.OptionsView;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.ProfileView;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.UpdateRequest;
import com.platform.iot.energymetadata.mapper.BizEnergyPointProfileMapper;
import com.platform.iot.energymetadata.model.entity.BizEnergyPointProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.platform.iot.energymetadata.EnergyMetadataModels.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 能源属性、父测点锁和采集域审计的单一 MySQL 事务边界。 */
public class EnergyPointProfileService {
    private static final Set<String> SUPPORTED_UNITS = Set.of("kW", "kWh", "m³", "Nm³", "GJ", "t");

    private final EnergyPointProfileAuthorization authorization;
    private final BizEnergyPointProfileMapper profileMapper;
    private final BizDataPointMapper pointMapper;
    private final BizDataSourceMapper sourceMapper;
    private final BizPointAliasMapper aliasMapper;
    private final BizCollectionPolicyMapper policyMapper;
    private final BizCollectionPolicyVersionMapper versionMapper;
    private final BizCollectionConfigAuditLogMapper auditMapper;
    private final AuditGovernanceProperties auditProperties;

    public PageResponse<ProfileView> list(Long userId, Collection<String> roles, String buildingId,
                                          String pointId, int page, int size) {
        authorization.requireReader(roles);
        authorization.checkBuilding(userId, roles, buildingId);
        validatePage(page, size);
        LambdaQueryWrapper<BizEnergyPointProfile> query = new LambdaQueryWrapper<BizEnergyPointProfile>()
                .eq(BizEnergyPointProfile::getBuildingId, buildingId)
                .orderByAsc(BizEnergyPointProfile::getProfileId);
        if (pointId != null && !pointId.isBlank()) query.eq(BizEnergyPointProfile::getPointId, pointId.trim());
        List<BizEnergyPointProfile> values = profileMapper.selectList(query);
        int from = Math.min((page - 1) * size, values.size());
        int to = Math.min(from + size, values.size());
        return new PageResponse<>(page, size, values.size(), values.subList(from, to).stream()
                .map(this::view).toList());
    }

    public ProfileView detail(Long userId, Collection<String> roles, String profileId) {
        authorization.requireReader(roles);
        BizEnergyPointProfile profile = requireProfile(profileId);
        authorization.checkBuilding(userId, roles, profile.getBuildingId());
        return view(profile);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileView create(Long userId, Collection<String> roles, CreateRequest request) {
        authorization.requireMaintainer(roles);
        authorization.checkBuilding(userId, roles, request.buildingId());
        BizDataPoint point = pointMapper.selectByIdForUpdate(request.pointId());
        if (point == null) {
            throw EnergyMetadataErrors.error(404, EnergyMetadataErrors.POINT_NOT_FOUND, "标准测点不存在");
        }
        requireSameBuilding(request.buildingId(), point.getBuildingId());
        requireSupportedUnit(point.getUnit());
        ValidatedFields fields = validate(request.energyType(), request.energySubtype(),
                request.valueSemantics(), request.reportingPeriod(), request.annualSummary(),
                request.confirmationStatus(), request.evidenceReference());
        if (profileMapper.selectCount(new LambdaQueryWrapper<BizEnergyPointProfile>()
                .eq(BizEnergyPointProfile::getPointId, point.getPointId())) > 0) {
            duplicate();
        }
        LocalDateTime now = LocalDateTime.now();
        BizEnergyPointProfile profile = new BizEnergyPointProfile();
        profile.setProfileId(id());
        profile.setPointId(point.getPointId());
        profile.setBuildingId(point.getBuildingId());
        apply(profile, fields);
        profile.setConfigRevision(0);
        profile.setCreateBy(userId);
        profile.setUpdateBy(userId);
        profile.setCreateTime(now);
        profile.setUpdateTime(now);
        try {
            profileMapper.insert(profile);
        } catch (DuplicateKeyException ex) {
            duplicate();
        }
        audit(userId, profile, "CREATE_ENERGY_POINT_PROFILE", null, summary(profile));
        return view(profile);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileView update(Long userId, Collection<String> roles, String profileId, UpdateRequest request) {
        authorization.requireMaintainer(roles);
        BizEnergyPointProfile observed = requireProfile(profileId);
        authorization.checkBuilding(userId, roles, observed.getBuildingId());
        BizDataPoint point = pointMapper.selectByIdForUpdate(observed.getPointId());
        if (point == null) {
            throw EnergyMetadataErrors.error(404, EnergyMetadataErrors.POINT_NOT_FOUND, "标准测点不存在");
        }
        BizEnergyPointProfile current = profileMapper.selectByIdForUpdate(profileId);
        if (current == null) {
            throw EnergyMetadataErrors.error(404, EnergyMetadataErrors.NOT_FOUND, "能源测点属性不存在");
        }
        requireSameBuilding(current.getBuildingId(), point.getBuildingId());
        requireSupportedUnit(point.getUnit());
        ValidatedFields fields = validate(request.energyType(), request.energySubtype(),
                request.valueSemantics(), request.reportingPeriod(), request.annualSummary(),
                request.confirmationStatus(), request.evidenceReference());
        String before = summary(current);
        int updated = profileMapper.updateByRevision(profileId, fields.energyType(), fields.energySubtype(),
                fields.valueSemantics(), fields.reportingPeriod(), fields.annualSummary(),
                fields.confirmationStatus(), fields.evidenceReference(), userId, request.expectedRevision());
        if (updated != 1) {
            throw EnergyMetadataErrors.error(409, EnergyMetadataErrors.VERSION_CONFLICT,
                    "能源测点属性已被其他操作修改");
        }
        BizEnergyPointProfile value = requireProfile(profileId);
        audit(userId, value, "UPDATE_ENERGY_POINT_PROFILE", before, summary(value));
        return view(value);
    }

    public OptionsView options(Collection<String> roles) {
        authorization.requireReader(roles);
        return new OptionsView(names(EnergyType.values()), names(EnergySubtype.values()),
                names(ValueSemantics.values()), names(ReportingPeriod.values()),
                names(ConfirmationStatus.values()), SUPPORTED_UNITS.stream().sorted().toList());
    }

    public CollectionContextView collectionContext(Long userId, Collection<String> roles,
                                                    String sourceId, String aliasId) {
        authorization.requireReader(roles);
        BizDataSource source = sourceMapper.selectById(sourceId);
        if (source == null) {
            throw EnergyMetadataErrors.error(404, EnergyMetadataErrors.NOT_FOUND, "数据源不存在");
        }
        authorization.checkBuilding(userId, roles, source.getBuildingId());
        BizPointAlias alias = aliasMapper.selectById(aliasId);
        if (alias == null) {
            throw EnergyMetadataErrors.error(404, EnergyMetadataErrors.NOT_FOUND, "现场点码不存在");
        }
        if (!Objects.equals(alias.getSourceId(), source.getSourceId())
                || !Objects.equals(alias.getBuildingId(), source.getBuildingId())) {
            throw EnergyMetadataErrors.error(409, EnergyMetadataErrors.REFERENCE_CONFLICT,
                    "现场点码不属于指定数据源");
        }
        BizDataPoint point = pointMapper.selectById(alias.getPointId());
        if (point == null || !Objects.equals(point.getBuildingId(), source.getBuildingId())) {
            throw EnergyMetadataErrors.error(409, EnergyMetadataErrors.BUILDING_MISMATCH,
                    "标准测点与数据源建筑不一致");
        }
        BizEnergyPointProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<BizEnergyPointProfile>()
                .eq(BizEnergyPointProfile::getPointId, point.getPointId()));
        BizCollectionPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<BizCollectionPolicy>()
                .eq(BizCollectionPolicy::getAliasId, aliasId));
        BizCollectionPolicyVersion version = selectedVersion(policy);
        return context(source, alias, point, profile, version);
    }

    private BizCollectionPolicyVersion selectedVersion(BizCollectionPolicy policy) {
        if (policy == null) return null;
        String versionId = policy.getActiveVersionId() != null
                ? policy.getActiveVersionId() : policy.getDraftVersionId();
        return versionId == null ? null : versionMapper.selectById(versionId);
    }

    private CollectionContextView context(BizDataSource source, BizPointAlias alias, BizDataPoint point,
                                          BizEnergyPointProfile profile, BizCollectionPolicyVersion version) {
        return new CollectionContextView(source.getSourceId(), source.getSourceCode(), alias.getAliasId(),
                alias.getSourcePointCode(), point.getPointId(), point.getPointCode(), point.getUnit(),
                profile != null, profile == null ? null : profile.getEnergyType(),
                profile == null ? null : profile.getEnergySubtype(),
                profile == null ? null : profile.getValueSemantics(),
                profile == null ? null : profile.getReportingPeriod(),
                profile == null ? null : profile.getAnnualSummary(),
                profile == null ? null : profile.getConfirmationStatus(),
                profile == null ? null : profile.getConfigRevision(),
                version == null ? null : version.getVersionId(), version == null ? null : version.getStatus(),
                version == null ? null : version.getExpectedIntervalSeconds(),
                version == null ? null : version.getAllowedDelaySeconds(),
                version == null ? null : version.getTimeSemantics());
    }

    private ProfileView view(BizEnergyPointProfile profile) {
        BizDataPoint point = pointMapper.selectById(profile.getPointId());
        return new ProfileView(profile.getProfileId(), profile.getPointId(),
                point == null ? null : point.getPointCode(), profile.getBuildingId(),
                profile.getEnergyType(), profile.getEnergySubtype(), profile.getValueSemantics(),
                point == null ? null : point.getUnit(), profile.getReportingPeriod(),
                Boolean.TRUE.equals(profile.getAnnualSummary()), profile.getConfirmationStatus(),
                profile.getEvidenceReference(), profile.getConfigRevision(), profile.getCreateBy(),
                profile.getUpdateBy(), profile.getCreateTime(), profile.getUpdateTime());
    }

    private ValidatedFields validate(String energyTypeValue, String subtypeValue, String semanticsValue,
                                     String periodValue, Boolean annualSummary, String statusValue,
                                     String evidenceValue) {
        EnergyType type = parse(EnergyType.class, energyTypeValue, "能源类型无效");
        EnergySubtype subtype = subtypeValue == null || subtypeValue.isBlank() ? null
                : parse(EnergySubtype.class, subtypeValue, "电力来源类型无效");
        ValueSemantics semantics = parse(ValueSemantics.class, semanticsValue, "数据性质无效");
        ReportingPeriod period = parse(ReportingPeriod.class, periodValue, "业务统计周期无效");
        ConfirmationStatus status = parse(ConfirmationStatus.class, statusValue, "专业确认状态无效");
        if (annualSummary == null) validation("年度汇总标识不能为空");
        if (type != EnergyType.ELECTRICITY && subtype != null) validation("非电力测点不能填写电力来源类型");
        if (type == EnergyType.ELECTRICITY && status == ConfirmationStatus.CONFIRMED && subtype == null) {
            validation("已确认电力测点必须填写电力来源类型");
        }
        String evidence = evidenceValue == null ? null : evidenceValue.trim();
        if (evidence == null || evidence.isEmpty() || evidence.length() > 500) validation("配置依据不能为空且不超过500字");
        return new ValidatedFields(type.name(), subtype == null ? null : subtype.name(), semantics.name(),
                period.name(), annualSummary, status.name(), evidence);
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim());
        } catch (IllegalArgumentException ex) {
            throw EnergyMetadataErrors.error(400, EnergyMetadataErrors.VALIDATION_FAILED, message);
        }
    }

    private void apply(BizEnergyPointProfile profile, ValidatedFields fields) {
        profile.setEnergyType(fields.energyType());
        profile.setEnergySubtype(fields.energySubtype());
        profile.setValueSemantics(fields.valueSemantics());
        profile.setReportingPeriod(fields.reportingPeriod());
        profile.setAnnualSummary(fields.annualSummary());
        profile.setConfirmationStatus(fields.confirmationStatus());
        profile.setEvidenceReference(fields.evidenceReference());
    }

    private BizEnergyPointProfile requireProfile(String profileId) {
        BizEnergyPointProfile value = profileMapper.selectById(profileId);
        if (value == null) throw EnergyMetadataErrors.error(404, EnergyMetadataErrors.NOT_FOUND,
                "能源测点属性不存在");
        return value;
    }

    private void requireSupportedUnit(String unit) {
        if (!SUPPORTED_UNITS.contains(unit)) {
            throw EnergyMetadataErrors.error(400, EnergyMetadataErrors.UNIT_UNSUPPORTED,
                    "标准测点单位暂不支持能源属性配置");
        }
    }

    private static void requireSameBuilding(String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw EnergyMetadataErrors.error(409, EnergyMetadataErrors.BUILDING_MISMATCH,
                    "能源属性与标准测点必须属于同一建筑");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) validation("分页参数无效");
    }

    private static void validation(String message) {
        throw EnergyMetadataErrors.error(400, EnergyMetadataErrors.VALIDATION_FAILED, message);
    }

    private static void duplicate() {
        throw EnergyMetadataErrors.error(409, EnergyMetadataErrors.DUPLICATE, "该标准测点已配置能源属性");
    }

    private void audit(Long operatorId, BizEnergyPointProfile profile, String action,
                       String before, String after) {
        BizCollectionConfigAuditLog audit = new BizCollectionConfigAuditLog();
        audit.setAuditId(id());
        audit.setBuildingId(profile.getBuildingId());
        audit.setActorType("USER");
        audit.setOperatorId(operatorId);
        audit.setActionType(action);
        audit.setObjectType("ENERGY_POINT_PROFILE");
        audit.setObjectId(profile.getProfileId());
        audit.setBeforeSummary(before);
        audit.setAfterSummary(after);
        audit.setResult("SUCCESS");
        audit.setTraceId(TraceContext.current());
        audit.setEnvironmentMode(auditProperties.getEnvironmentMode().name());
        audit.setSelfApprovalDevMode(false);
        audit.setOperationTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    /** 审计只保存枚举、修订号和依据存在性，避免把专业材料正文复制到审计域。 */
    private static String summary(BizEnergyPointProfile profile) {
        return "energyType=" + profile.getEnergyType()
                + ";energySubtype=" + Objects.toString(profile.getEnergySubtype(), "")
                + ";valueSemantics=" + profile.getValueSemantics()
                + ";reportingPeriod=" + profile.getReportingPeriod()
                + ";annualSummary=" + profile.getAnnualSummary()
                + ";confirmationStatus=" + profile.getConfirmationStatus()
                + ";configRevision=" + profile.getConfigRevision()
                + ";evidencePresent=true";
    }

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ValidatedFields(String energyType, String energySubtype, String valueSemantics,
                                   String reportingPeriod, Boolean annualSummary,
                                   String confirmationStatus, String evidenceReference) {
    }
}
