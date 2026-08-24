package com.platform.iot.collection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.framework.web.PageResponse;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizPointAlias;
import com.platform.iot.collection.api.CollectionPolicyContracts;
import com.platform.iot.collection.mapper.BizCollectionConfigAuditLogMapper;
import com.platform.iot.collection.mapper.BizCollectionPolicyMapper;
import com.platform.iot.collection.mapper.BizCollectionPolicyVersionMapper;
import com.platform.iot.collection.mapper.BizCollectionReviewRequestMapper;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.CollectionModels.AliasStatus;
import com.platform.iot.collection.model.CollectionModels.ChangeSource;
import com.platform.iot.collection.model.CollectionModels.ChangeType;
import com.platform.iot.collection.model.CollectionModels.PolicyVersionStatus;
import com.platform.iot.collection.model.CollectionModels.RetentionMode;
import com.platform.iot.collection.model.CollectionModels.ReviewStatus;
import com.platform.iot.collection.model.CollectionModels.ReviewTargetType;
import com.platform.iot.collection.model.CollectionModels.SourceStatus;
import com.platform.iot.collection.model.entity.BizCollectionConfigAuditLog;
import com.platform.iot.collection.model.entity.BizCollectionPolicy;
import com.platform.iot.collection.model.entity.BizCollectionPolicyVersion;
import com.platform.iot.collection.model.entity.BizCollectionReviewRequest;
import com.platform.iot.collection.model.entity.BizDataSource;
import com.platform.iot.collection.runtime.CollectionRuntimeStateService;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.platform.iot.collection.api.CollectionPolicyContracts.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 数据源、别名、策略版本、审核和审计的单一 MySQL 事务边界。
 *
 * <p>角色入口之外仍在服务层校验建筑归属和草稿所有者。发布路径统一锁定数据源，再锁定策略，
 * 避免两个管理员并发改变活动指针；审计写入失败会让同一事务整体回滚。</p>
 */
public class CollectionPolicyService {
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,49}");

    private final BizDataSourceMapper sourceMapper;
    private final BizPointAliasMapper aliasMapper;
    private final BizDataPointMapper pointMapper;
    private final BizCollectionPolicyMapper policyMapper;
    private final BizCollectionPolicyVersionMapper versionMapper;
    private final BizCollectionReviewRequestMapper reviewMapper;
    private final BizCollectionConfigAuditLogMapper auditMapper;
    private final BuildingScopeService buildingScopeService;
    private final CollectionRuntimeStateService runtimeStateService;

    public PageResponse<DataSourceView> listSources(Long userId, Collection<String> roles,
                                                    String buildingId, int page, int size) {
        requireReader(roles);
        validatePage(page, size);
        if (StringUtils.hasText(buildingId)) buildingScopeService.checkAccess(userId, roles, buildingId);
        Set<String> scope = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        List<BizDataSource> visible = sourceMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(source -> buildingId == null || buildingId.equals(source.getBuildingId()))
                .filter(source -> scope == null || scope.contains(source.getBuildingId()))
                .filter(source -> sourceVisible(source, userId, roles))
                .sorted(Comparator.comparing(BizDataSource::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((page - 1) * size, visible.size());
        int to = Math.min(from + size, visible.size());
        return new PageResponse<>(page, size, visible.size(),
                visible.subList(from, to).stream().map(source -> sourceView(source, roles)).toList());
    }

    public DataSourceView sourceDetail(Long userId, Collection<String> roles, String sourceId) {
        requireReader(roles);
        BizDataSource source = requireSource(sourceId);
        checkVisibleSource(userId, roles, source);
        return sourceView(source, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public DataSourceView createSource(Long userId, Collection<String> roles, SourceCreateRequest request) {
        requireMaintainer(roles);
        buildingScopeService.checkAccess(userId, roles, request.buildingId());
        String code = requireSourceCode(request.sourceCode());
        String transport = requireTransport(request.transportType());
        if (sourceMapper.selectCount(new LambdaQueryWrapper<BizDataSource>()
                .eq(BizDataSource::getSourceCode, code)) > 0) {
            throw CollectionErrors.error(409, CollectionErrors.DUPLICATE, "数据源编码已存在");
        }
        BizDataSource source = new BizDataSource();
        source.setSourceId(id());
        source.setSourceCode(code);
        source.setSourceName(requireText(request.sourceName(), "数据源名称不能为空"));
        source.setBuildingId(request.buildingId());
        source.setSourceCategory("DEVICE_ACCESS");
        source.setTransportType(transport);
        source.setStatus(SourceStatus.DRAFT.name());
        source.setDescription(trimToNull(request.description()));
        source.setConfigRevision(0);
        source.setRuntimeRevision(0L);
        source.setCreateBy(userId);
        source.setUpdateBy(userId);
        source.setCreateTime(now());
        source.setUpdateTime(source.getCreateTime());
        try {
            sourceMapper.insert(source);
        } catch (DataIntegrityViolationException exception) {
            throw CollectionErrors.error(409, CollectionErrors.DUPLICATE, "数据源编码已存在");
        }
        audit(userId, source.getBuildingId(), "CREATE_SOURCE", "DATA_SOURCE",
                source.getSourceId(), null, null, "status=DRAFT");
        return sourceView(source, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public DataSourceView updateSource(Long userId, Collection<String> roles, String sourceId,
                                       SourceUpdateRequest request) {
        requireMaintainer(roles);
        BizDataSource source = requireLockedSource(sourceId);
        buildingScopeService.checkAccess(userId, roles, source.getBuildingId());
        if (SourceStatus.DRAFT.name().equals(source.getStatus())) {
            requireDraftOwnerOrAdmin(source.getCreateBy(), userId, roles);
        } else {
            requireAdmin(roles);
        }
        if (!Objects.equals(source.getConfigRevision(), request.expectedRevision())) {
            throw versionConflict();
        }
        String before = sourceSummary(source);
        source.setSourceName(requireText(request.sourceName(), "数据源名称不能为空"));
        source.setDescription(trimToNull(request.description()));
        source.setUpdateBy(userId);
        source.setUpdateTime(now());
        source.setConfigRevision(source.getConfigRevision() + 1);
        sourceMapper.updateById(source);
        audit(userId, source.getBuildingId(), "UPDATE_SOURCE", "DATA_SOURCE",
                sourceId, null, before, sourceSummary(source));
        return sourceView(source, roles);
    }

    public List<AliasView> listAliases(Long userId, Collection<String> roles, String sourceId) {
        BizDataSource source = requireSource(sourceId);
        checkVisibleSource(userId, roles, source);
        return aliases(sourceId).stream()
                .filter(alias -> aliasVisible(alias, userId, roles))
                .map(alias -> aliasView(alias, roles)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public AliasView createAlias(Long userId, Collection<String> roles, String sourceId,
                                 AliasCreateRequest request) {
        requireMaintainer(roles);
        BizDataSource source = requireLockedSource(sourceId);
        buildingScopeService.checkAccess(userId, roles, source.getBuildingId());
        requireSourceEditableBy(source, userId, roles);
        requireNoPending(ReviewTargetType.SOURCE_ACTIVATION.name(), sourceId);
        BizDataPoint point = requirePoint(request.pointId());
        if (!source.getBuildingId().equals(point.getBuildingId())) {
            throw CollectionErrors.error(409, CollectionErrors.BUILDING_MISMATCH,
                    "数据源、别名和标准测点必须属于同一建筑");
        }
        String pointCode = requireText(request.sourcePointCode(), "来源点码不能为空");
        if (aliasMapper.selectCount(new LambdaQueryWrapper<BizPointAlias>()
                .eq(BizPointAlias::getBuildingId, source.getBuildingId())
                .eq(BizPointAlias::getSourceSystem, source.getSourceCode())
                .eq(BizPointAlias::getSourcePointCode, pointCode)) > 0) {
            throw CollectionErrors.error(409, CollectionErrors.DUPLICATE, "来源点码在该数据源内已存在");
        }
        validatePolicy(request.initialPolicy());
        LocalDateTime now = now();
        BizPointAlias alias = new BizPointAlias();
        alias.setAliasId(id());
        alias.setSourceId(sourceId);
        alias.setBuildingId(source.getBuildingId());
        alias.setSourceSystem(source.getSourceCode());
        alias.setSourcePointCode(pointCode);
        alias.setPointId(point.getPointId());
        alias.setStatus(2);
        alias.setRevision(0);
        alias.setCreateBy(userId);
        alias.setUpdateBy(userId);
        alias.setCreateTime(now);
        alias.setUpdateTime(now);
        aliasMapper.insert(alias);

        BizCollectionPolicy policy = new BizCollectionPolicy();
        policy.setPolicyId(id());
        policy.setSourceId(sourceId);
        policy.setAliasId(alias.getAliasId());
        policy.setBuildingId(source.getBuildingId());
        policy.setCreateBy(userId);
        policy.setCreateTime(now);
        policy.setUpdateTime(now);
        BizCollectionPolicyVersion version = draftVersion(policy, source, alias, point,
                request.initialPolicy(), request.changeReason(), ChangeType.CREATE, null, userId, 1);
        policy.setDraftVersionId(version.getVersionId());
        policyMapper.insert(policy);
        versionMapper.insert(version);
        bumpConfig(source, userId);
        audit(userId, source.getBuildingId(), "CREATE_ALIAS_WITH_POLICY", "POINT_ALIAS",
                alias.getAliasId(), version.getVersionId(), null, "status=DRAFT");
        return aliasView(alias, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReviewView submitSource(Long userId, Collection<String> roles, String sourceId, String comment) {
        requireEnergyManager(roles);
        BizDataSource source = requireLockedSource(sourceId);
        buildingScopeService.checkAccess(userId, roles, source.getBuildingId());
        requireDraftOwner(source.getCreateBy(), userId);
        if (!SourceStatus.DRAFT.name().equals(source.getStatus()) || aliases(sourceId).isEmpty()) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "草稿数据源配置包不完整");
        }
        aliases(sourceId).forEach(alias -> requireInitialDraft(alias.getAliasId()));
        return submitReview(userId, source, ReviewTargetType.SOURCE_ACTIVATION,
                sourceId, source.getConfigRevision(), comment);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReviewView submitAlias(Long userId, Collection<String> roles, String sourceId,
                                  String aliasId, String comment) {
        requireEnergyManager(roles);
        BizDataSource source = requireLockedSource(sourceId);
        BizPointAlias alias = requireLockedAlias(aliasId);
        requireAliasSource(alias, source);
        buildingScopeService.checkAccess(userId, roles, source.getBuildingId());
        requireDraftOwner(alias.getCreateBy(), userId);
        requireInitialDraft(aliasId);
        return submitReview(userId, source, ReviewTargetType.ALIAS_ACTIVATION,
                aliasId, source.getConfigRevision(), comment);
    }

    @Transactional(rollbackFor = Exception.class)
    public DataSourceView enableSource(Long userId, Collection<String> roles,
                                       String sourceId, String reason) {
        requireAdmin(roles);
        BizDataSource source = requireLockedSource(sourceId);
        if (SourceStatus.DRAFT.name().equals(source.getStatus())) {
            publishSourcePackage(source, userId, reason, null);
        } else if (SourceStatus.DISABLED.name().equals(source.getStatus())) {
            validateFormalConfiguration(source);
            source.setStatus(SourceStatus.ENABLED.name());
            bumpRuntime(source, userId);
            audit(userId, source.getBuildingId(), "REENABLE_SOURCE", "DATA_SOURCE",
                    sourceId, null, "status=DISABLED", "status=ENABLED");
        } else {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "数据源已启用");
        }
        runtimeStateService.refreshAfterCommit(sourceId, source.getRuntimeRevision());
        return sourceView(source, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public DataSourceView disableSource(Long userId, Collection<String> roles,
                                        String sourceId, String reason) {
        requireAdmin(roles);
        requireText(reason, "停用原因不能为空");
        BizDataSource source = requireLockedSource(sourceId);
        if (!SourceStatus.ENABLED.name().equals(source.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "只有已启用数据源可以停用");
        }
        source.setStatus(SourceStatus.DISABLED.name());
        bumpRuntime(source, userId);
        audit(userId, source.getBuildingId(), "DISABLE_SOURCE", "DATA_SOURCE",
                sourceId, null, "status=ENABLED", "status=DISABLED;reason=" + safe(reason));
        runtimeStateService.refreshAfterCommit(sourceId, source.getRuntimeRevision());
        return sourceView(source, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public DataSourceView refreshRuntime(Long userId, Collection<String> roles, String sourceId) {
        requireAdmin(roles);
        BizDataSource source = requireSource(sourceId);
        boolean success = runtimeStateService.refreshAll();
        audit(userId, source.getBuildingId(),
                success ? "MANUAL_RUNTIME_REFRESH" : "MANUAL_RUNTIME_REFRESH_FAILED",
                "DATA_SOURCE", source.getSourceId(), null, null,
                "runtimeRevision=" + source.getRuntimeRevision());
        return sourceView(requireSource(sourceId), roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSource(Long userId, Collection<String> roles, String sourceId) {
        requireAdmin(roles);
        BizDataSource source = requireLockedSource(sourceId);
        if (!SourceStatus.DRAFT.name().equals(source.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "启用过的数据源不能物理删除");
        }
        requireNoPending(ReviewTargetType.SOURCE_ACTIVATION.name(), sourceId);
        List<BizPointAlias> aliases = aliases(sourceId);
        for (BizPointAlias alias : aliases) {
            if (!Integer.valueOf(2).equals(alias.getStatus())) {
                throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT,
                        "草稿数据源异常关联正式别名，拒绝删除");
            }
            deleteNeverPublishedPolicy(alias.getAliasId());
            aliasMapper.deleteById(alias.getAliasId());
        }
        sourceMapper.deleteById(sourceId);
        audit(userId, source.getBuildingId(), "DELETE_DRAFT_SOURCE", "DATA_SOURCE",
                sourceId, null, sourceSummary(source), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public AliasView enableAlias(Long userId, Collection<String> roles, String sourceId,
                                 String aliasId, String reason) {
        requireAdmin(roles);
        BizDataSource source = requireLockedSource(sourceId);
        BizPointAlias alias = requireLockedAlias(aliasId);
        requireAliasSource(alias, source);
        if (Integer.valueOf(2).equals(alias.getStatus())) {
            publishInitialPolicy(source, alias, userId, now());
        } else if (Integer.valueOf(0).equals(alias.getStatus())) {
            requireActivePolicy(aliasId);
            alias.setStatus(1);
            alias.setUpdateBy(userId);
            alias.setUpdateTime(now());
            aliasMapper.updateById(alias);
        } else {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "别名已启用");
        }
        bumpRuntime(source, userId);
        audit(userId, source.getBuildingId(), "ENABLE_ALIAS", "POINT_ALIAS",
                aliasId, null, null, "status=ENABLED;reason=" + safe(reason));
        if (SourceStatus.ENABLED.name().equals(source.getStatus())) {
            runtimeStateService.refreshAfterCommit(sourceId, source.getRuntimeRevision());
        }
        return aliasView(alias, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public AliasView disableAlias(Long userId, Collection<String> roles, String sourceId,
                                  String aliasId, String reason) {
        requireAdmin(roles);
        requireText(reason, "停用原因不能为空");
        BizDataSource source = requireLockedSource(sourceId);
        BizPointAlias alias = requireLockedAlias(aliasId);
        requireAliasSource(alias, source);
        if (!Integer.valueOf(1).equals(alias.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "只有已启用别名可以停用");
        }
        alias.setStatus(0);
        alias.setUpdateBy(userId);
        alias.setUpdateTime(now());
        aliasMapper.updateById(alias);
        bumpRuntime(source, userId);
        audit(userId, source.getBuildingId(), "DISABLE_ALIAS", "POINT_ALIAS",
                aliasId, null, "status=ENABLED", "status=DISABLED;reason=" + safe(reason));
        if (SourceStatus.ENABLED.name().equals(source.getStatus())) {
            runtimeStateService.refreshAfterCommit(sourceId, source.getRuntimeRevision());
        }
        return aliasView(alias, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAlias(Long userId, Collection<String> roles, String sourceId, String aliasId) {
        requireAdmin(roles);
        BizDataSource source = requireLockedSource(sourceId);
        BizPointAlias alias = requireLockedAlias(aliasId);
        requireAliasSource(alias, source);
        if (!Integer.valueOf(2).equals(alias.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "正式别名不能物理删除");
        }
        requireNoPending(ReviewTargetType.ALIAS_ACTIVATION.name(), aliasId);
        deleteNeverPublishedPolicy(aliasId);
        aliasMapper.deleteById(aliasId);
        bumpConfig(source, userId);
        audit(userId, source.getBuildingId(), "DELETE_DRAFT_ALIAS", "POINT_ALIAS",
                aliasId, null, "status=DRAFT", null);
    }

    public PageResponse<PolicyView> listPolicies(Long userId, Collection<String> roles,
                                                 String buildingId, int page, int size) {
        requireReader(roles);
        validatePage(page, size);
        if (buildingId != null) buildingScopeService.checkAccess(userId, roles, buildingId);
        Set<String> scope = buildingScopeService.getAccessibleBuildingIds(userId, roles);
        List<BizCollectionPolicy> visible = policyMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(policy -> buildingId == null || buildingId.equals(policy.getBuildingId()))
                .filter(policy -> scope == null || scope.contains(policy.getBuildingId()))
                .filter(policy -> policyVisible(policy, userId, roles))
                .toList();
        int from = Math.min((page - 1) * size, visible.size());
        int to = Math.min(from + size, visible.size());
        return new PageResponse<>(page, size, visible.size(), visible.subList(from, to).stream()
                .map(policy -> policyView(policy, roles)).toList());
    }

    public PolicyView policyDetail(Long userId, Collection<String> roles, String policyId) {
        BizCollectionPolicy policy = requirePolicy(policyId);
        checkVisiblePolicy(userId, roles, policy);
        return policyView(policy, roles);
    }

    public List<PolicyVersionView> listVersions(Long userId, Collection<String> roles, String policyId) {
        BizCollectionPolicy policy = requirePolicy(policyId);
        checkVisiblePolicy(userId, roles, policy);
        return versions(policyId).stream()
                .filter(version -> versionVisible(version, policy, userId, roles))
                .map(version -> versionView(version, roles)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public PolicyVersionView createVersion(Long userId, Collection<String> roles, String policyId,
                                           PolicyVersionCreateRequest request) {
        requireMaintainer(roles);
        BizCollectionPolicy policy = requireLockedPolicy(policyId);
        BizDataSource source = requireLockedSource(policy.getSourceId());
        buildingScopeService.checkAccess(userId, roles, policy.getBuildingId());
        if (policy.getDraftVersionId() != null) {
            throw CollectionErrors.error(409, CollectionErrors.DRAFT_CONFLICT,
                    "该策略已有待处理草稿，请联系平台管理员");
        }
        validatePolicy(request.policy());
        BizCollectionPolicyVersion active = requireVersion(policy.getActiveVersionId());
        BizPointAlias alias = requireAlias(policy.getAliasId());
        BizDataPoint point = requirePoint(alias.getPointId());
        BizCollectionPolicyVersion draft = draftVersion(policy, source, alias, point,
                request.policy(), request.changeReason(), ChangeType.UPDATE,
                active.getVersionId(), userId, versionMapper.selectMaxVersionNo(policyId) + 1);
        versionMapper.insert(draft);
        policy.setDraftVersionId(draft.getVersionId());
        policy.setUpdateTime(now());
        policyMapper.updateById(policy);
        bumpConfig(source, userId);
        audit(userId, policy.getBuildingId(), "CREATE_POLICY_VERSION", "COLLECTION_POLICY",
                policyId, draft.getVersionId(), null, "status=DRAFT;version=" + draft.getVersionNo());
        return versionView(draft, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public PolicyVersionView updateVersion(Long userId, Collection<String> roles, String policyId,
                                           String versionId, PolicyVersionUpdateRequest request) {
        requireMaintainer(roles);
        BizCollectionPolicy policy = requireLockedPolicy(policyId);
        BizDataSource source = requireLockedSource(policy.getSourceId());
        buildingScopeService.checkAccess(userId, roles, policy.getBuildingId());
        BizCollectionPolicyVersion version = requireVersion(versionId);
        requirePolicyVersion(policy, version);
        requireDraftOwnerOrAdmin(version.getCreatedBy(), userId, roles);
        requireNoPending(ReviewTargetType.POLICY_VERSION.name(), versionId);
        validatePolicy(request.policy());
        int updated = versionMapper.updateDraft(versionId, request.policy().enabled() ? 1 : 0,
                request.policy().expectedIntervalSeconds(), request.policy().allowedDelaySeconds(),
                request.policy().rawRetentionMode(), request.policy().rawRetentionDays(),
                request.policy().minuteRetentionMode(), request.policy().minuteRetentionDays(),
                requireText(request.changeReason(), "变更原因不能为空"), request.expectedRevision());
        if (updated == 0) throw versionConflict();
        bumpConfig(source, userId);
        BizCollectionPolicyVersion refreshed = requireVersion(versionId);
        audit(userId, policy.getBuildingId(), "UPDATE_POLICY_VERSION", "COLLECTION_POLICY",
                policyId, versionId, "revision=" + request.expectedRevision(),
                "revision=" + refreshed.getRevision());
        return versionView(refreshed, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReviewView submitVersion(Long userId, Collection<String> roles, String policyId,
                                    String versionId, String comment) {
        requireEnergyManager(roles);
        BizCollectionPolicy policy = requireLockedPolicy(policyId);
        BizDataSource source = requireLockedSource(policy.getSourceId());
        BizCollectionPolicyVersion version = requireVersion(versionId);
        requirePolicyVersion(policy, version);
        requireDraftOwner(version.getCreatedBy(), userId);
        buildingScopeService.checkAccess(userId, roles, policy.getBuildingId());
        return submitReview(userId, source, ReviewTargetType.POLICY_VERSION,
                versionId, source.getConfigRevision(), comment);
    }

    @Transactional(rollbackFor = Exception.class)
    public PolicyVersionView publishVersion(Long userId, Collection<String> roles, String policyId,
                                            String versionId, String comment) {
        requireAdmin(roles);
        BizCollectionPolicy policy = requireLockedPolicy(policyId);
        BizDataSource source = requireLockedSource(policy.getSourceId());
        BizCollectionPolicyVersion version = requireLockedVersion(versionId);
        requirePolicyVersion(policy, version);
        publishPolicyVersion(source, policy, version, userId, now());
        audit(userId, policy.getBuildingId(), "DIRECT_ADMIN_PUBLISH", "COLLECTION_POLICY",
                policyId, versionId, null, "status=ACTIVE;comment=" + safe(comment));
        runtimeStateService.refreshAfterCommit(source.getSourceId(), source.getRuntimeRevision());
        return versionView(version, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public PolicyVersionView copyVersion(Long userId, Collection<String> roles, String policyId,
                                         CopyVersionRequest request) {
        requireMaintainer(roles);
        BizCollectionPolicy policy = requireLockedPolicy(policyId);
        BizDataSource source = requireLockedSource(policy.getSourceId());
        buildingScopeService.checkAccess(userId, roles, policy.getBuildingId());
        if (policy.getDraftVersionId() != null) {
            throw CollectionErrors.error(409, CollectionErrors.DRAFT_CONFLICT,
                    "该策略已有待处理草稿，请联系平台管理员");
        }
        BizCollectionPolicyVersion historical = requireVersion(request.sourceVersionId());
        requirePolicyVersion(policy, historical);
        if (!isAdmin(roles) && !Objects.equals(historical.getCreatedBy(), userId)) {
            throw CollectionErrors.error(403, CollectionErrors.FORBIDDEN, "无权复制该历史版本");
        }
        BizCollectionPolicyVersion draft = copyAsRollbackDraft(policy, historical, userId,
                requireText(request.reason(), "回滚原因不能为空"));
        versionMapper.insert(draft);
        policy.setDraftVersionId(draft.getVersionId());
        policy.setUpdateTime(now());
        policyMapper.updateById(policy);
        bumpConfig(source, userId);
        audit(userId, policy.getBuildingId(), "CREATE_ROLLBACK_DRAFT", "COLLECTION_POLICY",
                policyId, draft.getVersionId(), null,
                "copiedFrom=" + historical.getVersionId() + ";version=" + draft.getVersionNo());
        return versionView(draft, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public PolicyVersionView disablePolicy(Long userId, Collection<String> roles,
                                           String policyId, String reason) {
        requireAdmin(roles);
        BizCollectionPolicy policy = requireLockedPolicy(policyId);
        BizDataSource source = requireLockedSource(policy.getSourceId());
        if (policy.getDraftVersionId() != null) {
            throw CollectionErrors.error(409, CollectionErrors.DRAFT_CONFLICT, "策略已有草稿版本");
        }
        BizCollectionPolicyVersion active = requireVersion(policy.getActiveVersionId());
        BizCollectionPolicyVersion draft = copyAsRollbackDraft(policy, active, userId,
                requireText(reason, "停用原因不能为空"));
        draft.setChangeType(ChangeType.DISABLE.name());
        draft.setEnabledFlag(0);
        draft.setCopiedFromVersionId(active.getVersionId());
        versionMapper.insert(draft);
        policy.setDraftVersionId(draft.getVersionId());
        policyMapper.updateById(policy);
        publishPolicyVersion(source, policy, draft, userId, now());
        audit(userId, policy.getBuildingId(), "DISABLE_POLICY", "COLLECTION_POLICY",
                policyId, draft.getVersionId(), null, "enabled=false");
        runtimeStateService.refreshAfterCommit(source.getSourceId(), source.getRuntimeRevision());
        return versionView(draft, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(Long userId, Collection<String> roles, String policyId, String versionId) {
        requireMaintainer(roles);
        BizCollectionPolicy policy = requireLockedPolicy(policyId);
        BizDataSource source = requireLockedSource(policy.getSourceId());
        BizCollectionPolicyVersion version = requireLockedVersion(versionId);
        requirePolicyVersion(policy, version);
        requireDraftOwnerOrAdmin(version.getCreatedBy(), userId, roles);
        requireNoPending(ReviewTargetType.POLICY_VERSION.name(), versionId);
        if (!PolicyVersionStatus.DRAFT.name().equals(version.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "已发布版本不能删除");
        }
        versionMapper.deleteById(versionId);
        policy.setDraftVersionId(null);
        policy.setUpdateTime(now());
        policyMapper.updatePointers(policy.getPolicyId(), policy.getActiveVersionId(),
                null, policy.getUpdateTime());
        bumpConfig(source, userId);
        audit(userId, policy.getBuildingId(), "DELETE_POLICY_DRAFT", "COLLECTION_POLICY",
                policyId, versionId, "status=DRAFT", null);
    }

    public PageResponse<ReviewView> listReviews(Long userId, Collection<String> roles,
                                                int page, int size) {
        requireReviewerOrSubmitter(roles);
        validatePage(page, size);
        List<BizCollectionReviewRequest> visible = reviewMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(request -> isAdmin(roles) || Objects.equals(request.getSubmittedBy(), userId))
                .sorted(Comparator.comparing(BizCollectionReviewRequest::getSubmittedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((page - 1) * size, visible.size());
        int to = Math.min(from + size, visible.size());
        return new PageResponse<>(page, size, visible.size(), visible.subList(from, to).stream()
                .map(request -> reviewView(request, roles, userId)).toList());
    }

    public ReviewView reviewDetail(Long userId, Collection<String> roles, String requestId) {
        requireReviewerOrSubmitter(roles);
        BizCollectionReviewRequest request = requireReview(requestId);
        if (!isAdmin(roles) && !Objects.equals(request.getSubmittedBy(), userId)) notVisible();
        return reviewView(request, roles, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReviewView approveReview(Long reviewerId, Collection<String> roles,
                                    String requestId, String comment) {
        requireAdmin(roles);
        BizCollectionReviewRequest request = requireLockedReview(requestId);
        requirePending(request);
        BizDataSource source = lockSourceForReview(request);
        if (!Objects.equals(source.getConfigRevision(), request.getTargetConfigRevision())) {
            throw versionConflict();
        }
        LocalDateTime reviewedAt = now();
        switch (ReviewTargetType.valueOf(request.getTargetType())) {
            case SOURCE_ACTIVATION -> publishSourcePackage(source, reviewerId, comment, request);
            case ALIAS_ACTIVATION -> {
                BizPointAlias alias = requireLockedAlias(request.getTargetId());
                requireAliasSource(alias, source);
                publishInitialPolicy(source, alias, reviewerId, reviewedAt);
                bumpRuntime(source, reviewerId);
            }
            case POLICY_VERSION -> {
                BizCollectionPolicyVersion version = requireLockedVersion(request.getTargetId());
                BizCollectionPolicy policy = requireLockedPolicy(version.getPolicyId());
                publishPolicyVersion(source, policy, version, reviewerId, reviewedAt);
            }
        }
        request.setStatus(ReviewStatus.APPROVED.name());
        request.setReviewerId(reviewerId);
        request.setReviewComment(trimToNull(comment));
        request.setReviewedAt(reviewedAt);
        request.setUpdateTime(reviewedAt);
        reviewMapper.updateById(request);
        audit(reviewerId, request.getBuildingId(), "APPROVE_REVIEW", "REVIEW_REQUEST",
                requestId, null, "status=PENDING", "status=APPROVED");
        runtimeStateService.refreshAfterCommit(source.getSourceId(), source.getRuntimeRevision());
        return reviewView(request, roles, reviewerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReviewView rejectReview(Long reviewerId, Collection<String> roles,
                                   String requestId, String reason) {
        requireAdmin(roles);
        BizCollectionReviewRequest request = requireLockedReview(requestId);
        requirePending(request);
        request.setStatus(ReviewStatus.REJECTED.name());
        request.setReviewerId(reviewerId);
        request.setReviewComment(requireText(reason, "拒绝原因不能为空"));
        request.setReviewedAt(now());
        request.setUpdateTime(request.getReviewedAt());
        reviewMapper.updateById(request);
        audit(reviewerId, request.getBuildingId(), "REJECT_REVIEW", "REVIEW_REQUEST",
                requestId, null, "status=PENDING", "status=REJECTED");
        return reviewView(request, roles, reviewerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReviewView withdrawReview(Long userId, Collection<String> roles, String requestId) {
        requireEnergyManager(roles);
        BizCollectionReviewRequest request = requireLockedReview(requestId);
        requirePending(request);
        if (!Objects.equals(request.getSubmittedBy(), userId)) notVisible();
        request.setStatus(ReviewStatus.WITHDRAWN.name());
        request.setWithdrawnAt(now());
        request.setUpdateTime(request.getWithdrawnAt());
        reviewMapper.updateById(request);
        audit(userId, request.getBuildingId(), "WITHDRAW_REVIEW", "REVIEW_REQUEST",
                requestId, null, "status=PENDING", "status=WITHDRAWN");
        return reviewView(request, roles, userId);
    }

    public PageResponse<AuditView> listAudits(Long userId, Collection<String> roles, int page, int size) {
        requireReviewerOrSubmitter(roles);
        validatePage(page, size);
        List<BizCollectionConfigAuditLog> visible = auditMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(audit -> isAdmin(roles) || Objects.equals(audit.getOperatorId(), userId))
                .sorted(Comparator.comparing(BizCollectionConfigAuditLog::getOperationTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((page - 1) * size, visible.size());
        int to = Math.min(from + size, visible.size());
        return new PageResponse<>(page, size, visible.size(), visible.subList(from, to).stream()
                .map(this::auditView).toList());
    }

    private ReviewView submitReview(Long userId, BizDataSource source, ReviewTargetType type,
                                    String targetId, int revision, String comment) {
        requireText(comment, "提交说明不能为空");
        requireNoPending(type.name(), targetId);
        BizCollectionReviewRequest request = new BizCollectionReviewRequest();
        request.setRequestId(id());
        request.setBuildingId(source.getBuildingId());
        request.setTargetType(type.name());
        request.setTargetId(targetId);
        request.setTargetConfigRevision(revision);
        request.setStatus(ReviewStatus.PENDING.name());
        request.setSubmittedBy(userId);
        request.setSubmittedAt(now());
        request.setReviewComment(trimToNull(comment));
        request.setCreateTime(request.getSubmittedAt());
        request.setUpdateTime(request.getSubmittedAt());
        reviewMapper.insert(request);
        audit(userId, source.getBuildingId(), "SUBMIT_REVIEW", "REVIEW_REQUEST",
                request.getRequestId(), null, null,
                "targetType=" + type.name() + ";targetId=" + targetId + ";revision=" + revision);
        return reviewView(request, Set.of(FormalRole.ENERGY_MANAGER.name()), userId);
    }

    private void publishSourcePackage(BizDataSource source, Long operatorId,
                                      String comment, BizCollectionReviewRequest ignored) {
        if (!SourceStatus.DRAFT.name().equals(source.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "只有草稿数据源可以首次启用");
        }
        List<BizPointAlias> aliases = aliases(source.getSourceId());
        if (aliases.isEmpty()) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "数据源至少需要一个完整别名策略");
        }
        LocalDateTime publishedAt = now();
        for (BizPointAlias alias : aliases) publishInitialPolicy(source, alias, operatorId, publishedAt);
        source.setStatus(SourceStatus.ENABLED.name());
        bumpRuntime(source, operatorId);
        audit(operatorId, source.getBuildingId(), "ENABLE_SOURCE", "DATA_SOURCE",
                source.getSourceId(), null, "status=DRAFT",
                "status=ENABLED;comment=" + safe(comment));
    }

    private void publishInitialPolicy(BizDataSource source, BizPointAlias alias,
                                      Long operatorId, LocalDateTime publishedAt) {
        if (!Integer.valueOf(2).equals(alias.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "只有草稿别名可以首次启用");
        }
        BizCollectionPolicy policy = requireLockedPolicyByAlias(alias.getAliasId());
        BizCollectionPolicyVersion version = requireLockedVersion(policy.getDraftVersionId());
        validatePublication(source, alias, version);
        activateVersion(policy, version, null, operatorId, publishedAt);
        alias.setStatus(1);
        alias.setRevision(alias.getRevision() + 1);
        alias.setUpdateBy(operatorId);
        alias.setUpdateTime(publishedAt);
        aliasMapper.updateById(alias);
    }

    private void publishPolicyVersion(BizDataSource source, BizCollectionPolicy policy,
                                      BizCollectionPolicyVersion version, Long operatorId,
                                      LocalDateTime publishedAt) {
        BizPointAlias alias = requireAlias(policy.getAliasId());
        validatePublication(source, alias, version);
        BizCollectionPolicyVersion active = policy.getActiveVersionId() == null
                ? null : requireLockedVersion(policy.getActiveVersionId());
        activateVersion(policy, version, active, operatorId, publishedAt);
        bumpRuntime(source, operatorId);
    }

    private void activateVersion(BizCollectionPolicy policy, BizCollectionPolicyVersion version,
                                 BizCollectionPolicyVersion active, Long operatorId,
                                 LocalDateTime publishedAt) {
        if (!PolicyVersionStatus.DRAFT.name().equals(version.getStatus())
                || !Objects.equals(policy.getDraftVersionId(), version.getVersionId())) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "目标不是当前草稿版本");
        }
        if (active != null) {
            active.setStatus(PolicyVersionStatus.RETIRED.name());
            active.setEffectiveTo(publishedAt);
            active.setRetiredAt(publishedAt);
            active.setUpdateTime(publishedAt);
            versionMapper.updateById(active);
        }
        BizPointAlias alias = requireAlias(policy.getAliasId());
        BizDataSource source = requireSource(policy.getSourceId());
        BizDataPoint point = requirePoint(alias.getPointId());
        version.setSourceCodeSnapshot(source.getSourceCode());
        version.setSourcePointCodeSnapshot(alias.getSourcePointCode());
        version.setPointIdSnapshot(point.getPointId());
        version.setPointCodeSnapshot(point.getPointCode());
        version.setDataTypeSnapshot(point.getDataType());
        version.setUnitSnapshot(point.getUnit());
        version.setStatus(PolicyVersionStatus.ACTIVE.name());
        version.setPublishedBy(operatorId);
        version.setPublishedAt(publishedAt);
        version.setEffectiveFrom(publishedAt);
        version.setUpdateTime(publishedAt);
        versionMapper.updateById(version);
        policy.setActiveVersionId(version.getVersionId());
        policy.setDraftVersionId(null);
        policy.setUpdateTime(publishedAt);
        policyMapper.updatePointers(policy.getPolicyId(), version.getVersionId(), null, publishedAt);
    }

    private void validatePublication(BizDataSource source, BizPointAlias alias,
                                     BizCollectionPolicyVersion version) {
        BizDataPoint point = requirePoint(alias.getPointId());
        if (!source.getSourceId().equals(alias.getSourceId())
                || !source.getBuildingId().equals(alias.getBuildingId())
                || !source.getBuildingId().equals(point.getBuildingId())) {
            throw CollectionErrors.error(409, CollectionErrors.BUILDING_MISMATCH,
                    "数据源、别名和测点建筑归属不一致");
        }
        validatePolicyValues(version.getExpectedIntervalSeconds(), version.getAllowedDelaySeconds(),
                version.getRawRetentionMode(), version.getRawRetentionDays(),
                version.getMinuteRetentionMode(), version.getMinuteRetentionDays());
    }

    private void validateFormalConfiguration(BizDataSource source) {
        for (BizPointAlias alias : aliases(source.getSourceId())) {
            if (Integer.valueOf(1).equals(alias.getStatus())) {
                BizCollectionPolicy policy = requireActivePolicy(alias.getAliasId());
                validatePublication(source, alias, requireVersion(policy.getActiveVersionId()));
            }
        }
    }

    private BizCollectionPolicyVersion draftVersion(BizCollectionPolicy policy, BizDataSource source,
                                                     BizPointAlias alias, BizDataPoint point,
                                                     InitialPolicyRequest request, String reason,
                                                     ChangeType changeType, String copiedFrom,
                                                     Long userId, int versionNo) {
        BizCollectionPolicyVersion version = new BizCollectionPolicyVersion();
        version.setVersionId(id());
        version.setPolicyId(policy.getPolicyId());
        version.setVersionNo(versionNo);
        version.setStatus(PolicyVersionStatus.DRAFT.name());
        applyPolicy(version, request);
        version.setTimeSemantics("DEVICE_EVENT_TIME");
        version.setSourceCodeSnapshot(source.getSourceCode());
        version.setSourcePointCodeSnapshot(alias.getSourcePointCode());
        version.setPointIdSnapshot(point.getPointId());
        version.setPointCodeSnapshot(point.getPointCode());
        version.setDataTypeSnapshot(point.getDataType());
        version.setUnitSnapshot(point.getUnit());
        version.setChangeType(changeType.name());
        version.setChangeSource(ChangeSource.MANUAL.name());
        version.setChangeReason(requireText(reason, "变更原因不能为空"));
        version.setCopiedFromVersionId(copiedFrom);
        version.setRevision(0);
        version.setCreatedBy(userId);
        version.setCreateTime(now());
        version.setUpdateTime(version.getCreateTime());
        return version;
    }

    private BizCollectionPolicyVersion copyAsRollbackDraft(BizCollectionPolicy policy,
                                                            BizCollectionPolicyVersion source,
                                                            Long userId, String reason) {
        BizCollectionPolicyVersion version = new BizCollectionPolicyVersion();
        version.setVersionId(id());
        version.setPolicyId(policy.getPolicyId());
        version.setVersionNo(versionMapper.selectMaxVersionNo(policy.getPolicyId()) + 1);
        version.setStatus(PolicyVersionStatus.DRAFT.name());
        version.setEnabledFlag(source.getEnabledFlag());
        version.setExpectedIntervalSeconds(source.getExpectedIntervalSeconds());
        version.setAllowedDelaySeconds(source.getAllowedDelaySeconds());
        version.setTimeSemantics(source.getTimeSemantics());
        version.setRawRetentionMode(source.getRawRetentionMode());
        version.setRawRetentionDays(source.getRawRetentionDays());
        version.setMinuteRetentionMode(source.getMinuteRetentionMode());
        version.setMinuteRetentionDays(source.getMinuteRetentionDays());
        version.setSourceCodeSnapshot(source.getSourceCodeSnapshot());
        version.setSourcePointCodeSnapshot(source.getSourcePointCodeSnapshot());
        version.setPointIdSnapshot(source.getPointIdSnapshot());
        version.setPointCodeSnapshot(source.getPointCodeSnapshot());
        version.setDataTypeSnapshot(source.getDataTypeSnapshot());
        version.setUnitSnapshot(source.getUnitSnapshot());
        version.setChangeType(ChangeType.ROLLBACK.name());
        version.setChangeSource(ChangeSource.MANUAL.name());
        version.setChangeReason(reason);
        version.setCopiedFromVersionId(source.getVersionId());
        version.setRevision(0);
        version.setCreatedBy(userId);
        version.setCreateTime(now());
        version.setUpdateTime(version.getCreateTime());
        return version;
    }

    private void applyPolicy(BizCollectionPolicyVersion target, InitialPolicyRequest request) {
        target.setEnabledFlag(request.enabled() ? 1 : 0);
        target.setExpectedIntervalSeconds(request.expectedIntervalSeconds());
        target.setAllowedDelaySeconds(request.allowedDelaySeconds());
        target.setRawRetentionMode(request.rawRetentionMode());
        target.setRawRetentionDays(request.rawRetentionDays());
        target.setMinuteRetentionMode(request.minuteRetentionMode());
        target.setMinuteRetentionDays(request.minuteRetentionDays());
    }

    private void validatePolicy(InitialPolicyRequest request) {
        validatePolicyValues(request.expectedIntervalSeconds(), request.allowedDelaySeconds(),
                request.rawRetentionMode(), request.rawRetentionDays(),
                request.minuteRetentionMode(), request.minuteRetentionDays());
    }

    private void validatePolicyValues(Integer interval, Integer delay, String rawMode,
                                      Integer rawDays, String minuteMode, Integer minuteDays) {
        if (interval == null || interval <= 0 || delay == null || delay < 0) {
            throw CollectionErrors.error(400, CollectionErrors.VALIDATION_FAILED,
                    "采样周期必须大于0且允许迟到不能小于0");
        }
        validateRetention(rawMode, rawDays, "原始事件");
        validateRetention(minuteMode, minuteDays, "分钟数据");
    }

    private void validateRetention(String modeValue, Integer days, String name) {
        RetentionMode mode;
        try { mode = RetentionMode.valueOf(modeValue); }
        catch (RuntimeException exception) {
            throw CollectionErrors.error(400, CollectionErrors.VALIDATION_FAILED, name + "保留模式无效");
        }
        if ((mode == RetentionMode.FIXED_DAYS && (days == null || days <= 0))
                || (mode == RetentionMode.LONG_TERM && days != null)) {
            throw CollectionErrors.error(400, CollectionErrors.VALIDATION_FAILED,
                    name + "保留模式与天数不匹配");
        }
    }

    private void bumpConfig(BizDataSource source, Long userId) {
        source.setConfigRevision(source.getConfigRevision() + 1);
        source.setUpdateBy(userId);
        source.setUpdateTime(now());
        sourceMapper.updateById(source);
    }

    private void bumpRuntime(BizDataSource source, Long userId) {
        source.setConfigRevision(source.getConfigRevision() + 1);
        source.setRuntimeRevision(source.getRuntimeRevision() + 1);
        source.setUpdateBy(userId);
        source.setUpdateTime(now());
        sourceMapper.updateById(source);
    }

    private void deleteNeverPublishedPolicy(String aliasId) {
        BizCollectionPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<BizCollectionPolicy>()
                .eq(BizCollectionPolicy::getAliasId, aliasId));
        if (policy == null) return;
        if (policy.getActiveVersionId() != null || versions(policy.getPolicyId()).stream()
                .anyMatch(v -> !PolicyVersionStatus.DRAFT.name().equals(v.getStatus()))) {
            throw CollectionErrors.error(409, CollectionErrors.REFERENCE_CONFLICT,
                    "策略已经发布，不能随草稿物理删除");
        }
        versions(policy.getPolicyId()).forEach(version -> versionMapper.deleteById(version.getVersionId()));
        policyMapper.deleteById(policy.getPolicyId());
    }

    private void audit(Long operatorId, String buildingId, String action, String objectType,
                       String objectId, String versionId, String before, String after) {
        BizCollectionConfigAuditLog audit = new BizCollectionConfigAuditLog();
        audit.setAuditId(id());
        audit.setBuildingId(buildingId);
        audit.setActorType("USER");
        audit.setOperatorId(operatorId);
        audit.setActionType(action);
        audit.setObjectType(objectType);
        audit.setObjectId(objectId);
        audit.setVersionId(versionId);
        audit.setBeforeSummary(trimSummary(before));
        audit.setAfterSummary(trimSummary(after));
        audit.setResult("SUCCESS");
        audit.setOperationTime(now());
        auditMapper.insert(audit);
    }

    private BizDataSource lockSourceForReview(BizCollectionReviewRequest request) {
        return switch (ReviewTargetType.valueOf(request.getTargetType())) {
            case SOURCE_ACTIVATION -> requireLockedSource(request.getTargetId());
            case ALIAS_ACTIVATION -> requireLockedSource(requireAlias(request.getTargetId()).getSourceId());
            case POLICY_VERSION -> requireLockedSource(requirePolicy(
                    requireVersion(request.getTargetId()).getPolicyId()).getSourceId());
        };
    }

    private DataSourceView sourceView(BizDataSource source, Collection<String> roles) {
        List<BizPointAlias> aliases = aliases(source.getSourceId());
        int draft = (int) aliases.stream().filter(a -> Integer.valueOf(2).equals(a.getStatus())).count();
        int enabled = (int) aliases.stream().filter(a -> Integer.valueOf(1).equals(a.getStatus())).count();
        int disabled = (int) aliases.stream().filter(a -> Integer.valueOf(0).equals(a.getStatus())).count();
        boolean complete = !aliases.isEmpty() && aliases.stream().allMatch(a -> {
            BizCollectionPolicy policy = policyByAlias(a.getAliasId());
            return policy != null && (policy.getDraftVersionId() != null || policy.getActiveVersionId() != null);
        });
        boolean effective = SourceStatus.ENABLED.name().equals(source.getStatus()) && enabled > 0;
        var runtime = runtimeStateService.state(source.getSourceId(), source.getStatus(), source.getRuntimeRevision());
        return new DataSourceView(source.getSourceId(), source.getSourceCode(), source.getSourceName(),
                source.getBuildingId(), source.getSourceCategory(), source.getTransportType(),
                source.getStatus(), source.getDescription(), draft, enabled, disabled, complete, effective,
                runtime.applyStatus().name(), source.getConfigRevision(), source.getRuntimeRevision(),
                runtime.appliedRuntimeRevision(), epoch(runtime.appliedAt()),
                ineffectiveReason(source, aliases), sourceActions(source, roles),
                epoch(source.getCreateTime()), epoch(source.getUpdateTime()));
    }

    private AliasView aliasView(BizPointAlias alias, Collection<String> roles) {
        BizCollectionPolicy policy = policyByAlias(alias.getAliasId());
        return new AliasView(alias.getAliasId(), alias.getSourceId(), alias.getBuildingId(),
                alias.getSourceSystem(), alias.getSourcePointCode(), alias.getPointId(),
                aliasStatus(alias.getStatus()).name(), alias.getRevision(),
                policy == null ? null : policy.getPolicyId(),
                policy == null ? null : policy.getActiveVersionId(),
                policy == null ? null : policy.getDraftVersionId(), aliasActions(alias, roles));
    }

    private PolicyView policyView(BizCollectionPolicy policy, Collection<String> roles) {
        return new PolicyView(policy.getPolicyId(), policy.getSourceId(), policy.getAliasId(),
                policy.getBuildingId(), policy.getActiveVersionId(), policy.getDraftVersionId(),
                policyActions(policy, roles));
    }

    private PolicyVersionView versionView(BizCollectionPolicyVersion version, Collection<String> roles) {
        return new PolicyVersionView(version.getVersionId(), version.getPolicyId(), version.getVersionNo(),
                version.getStatus(), Integer.valueOf(1).equals(version.getEnabledFlag()),
                version.getExpectedIntervalSeconds(), version.getAllowedDelaySeconds(),
                version.getTimeSemantics(), version.getRawRetentionMode(), version.getRawRetentionDays(),
                version.getMinuteRetentionMode(), version.getMinuteRetentionDays(),
                version.getSourceCodeSnapshot(), version.getSourcePointCodeSnapshot(),
                version.getPointIdSnapshot(), version.getPointCodeSnapshot(),
                version.getDataTypeSnapshot(), version.getUnitSnapshot(), version.getChangeType(),
                version.getChangeSource(), version.getChangeReason(), version.getCopiedFromVersionId(),
                version.getRevision(), epoch(version.getPublishedAt()), epoch(version.getEffectiveFrom()),
                epoch(version.getEffectiveTo()), epoch(version.getRetiredAt()), versionActions(version, roles));
    }

    private ReviewView reviewView(BizCollectionReviewRequest request, Collection<String> roles, Long userId) {
        List<String> actions = new ArrayList<>();
        if (ReviewStatus.PENDING.name().equals(request.getStatus())) {
            if (isAdmin(roles)) { actions.add("APPROVE"); actions.add("REJECT"); }
            if (Objects.equals(request.getSubmittedBy(), userId) && hasRole(roles, FormalRole.ENERGY_MANAGER)) {
                actions.add("WITHDRAW");
            }
        }
        return new ReviewView(request.getRequestId(), request.getBuildingId(), request.getTargetType(),
                request.getTargetId(), request.getTargetConfigRevision(), request.getStatus(),
                request.getSubmittedBy(), epoch(request.getSubmittedAt()), request.getReviewerId(),
                request.getReviewComment(), epoch(request.getReviewedAt()), epoch(request.getWithdrawnAt()),
                List.copyOf(actions));
    }

    private AuditView auditView(BizCollectionConfigAuditLog audit) {
        return new AuditView(audit.getAuditId(), audit.getBuildingId(), audit.getActionType(),
                audit.getObjectType(), audit.getObjectId(), audit.getVersionId(),
                audit.getBeforeSummary(), audit.getAfterSummary(), epoch(audit.getOperationTime()));
    }

    private String ineffectiveReason(BizDataSource source, List<BizPointAlias> aliases) {
        if (SourceStatus.DRAFT.name().equals(source.getStatus())) return "SOURCE_DRAFT";
        if (SourceStatus.DISABLED.name().equals(source.getStatus())) return "SOURCE_DISABLED";
        if (aliases.stream().noneMatch(a -> Integer.valueOf(1).equals(a.getStatus()))) return "ALIAS_DISABLED";
        for (BizPointAlias alias : aliases) {
            if (!Integer.valueOf(1).equals(alias.getStatus())) continue;
            BizCollectionPolicy policy = policyByAlias(alias.getAliasId());
            if (policy == null || policy.getActiveVersionId() == null) return "POLICY_MISSING";
            BizCollectionPolicyVersion active = requireVersion(policy.getActiveVersionId());
            if (!Integer.valueOf(1).equals(active.getEnabledFlag())) return "POLICY_DISABLED";
        }
        return null;
    }

    private List<String> sourceActions(BizDataSource source, Collection<String> roles) {
        List<String> actions = new ArrayList<>();
        if (SourceStatus.DRAFT.name().equals(source.getStatus()) && hasRole(roles, FormalRole.ENERGY_MANAGER)) {
            actions.add("UPDATE"); actions.add("SUBMIT");
        }
        if (isAdmin(roles)) {
            if (!SourceStatus.ENABLED.name().equals(source.getStatus())) actions.add("ENABLE");
            if (SourceStatus.ENABLED.name().equals(source.getStatus())) actions.add("DISABLE");
            if (SourceStatus.DRAFT.name().equals(source.getStatus())) actions.add("DELETE");
            actions.add("RUNTIME_REFRESH");
        }
        return List.copyOf(actions);
    }

    private List<String> aliasActions(BizPointAlias alias, Collection<String> roles) {
        if (isAdmin(roles)) return switch (aliasStatus(alias.getStatus())) {
            case DRAFT -> List.of("ENABLE", "DELETE");
            case ENABLED -> List.of("DISABLE");
            case DISABLED -> List.of("ENABLE");
        };
        if (hasRole(roles, FormalRole.ENERGY_MANAGER) && Integer.valueOf(2).equals(alias.getStatus())) {
            return List.of("SUBMIT");
        }
        return List.of();
    }

    private List<String> policyActions(BizCollectionPolicy policy, Collection<String> roles) {
        List<String> actions = new ArrayList<>();
        if (policy.getDraftVersionId() == null && hasAnyMaintainer(roles)) actions.add("CREATE_VERSION");
        if (isAdmin(roles) && policy.getActiveVersionId() != null) actions.add("DISABLE");
        return List.copyOf(actions);
    }

    private List<String> versionActions(BizCollectionPolicyVersion version, Collection<String> roles) {
        if (!PolicyVersionStatus.DRAFT.name().equals(version.getStatus())) {
            return hasAnyMaintainer(roles) ? List.of("COPY") : List.of();
        }
        if (isAdmin(roles)) return List.of("UPDATE", "PUBLISH", "DELETE");
        if (hasRole(roles, FormalRole.ENERGY_MANAGER)) return List.of("UPDATE", "SUBMIT", "DELETE");
        return List.of();
    }

    private boolean sourceVisible(BizDataSource source, Long userId, Collection<String> roles) {
        if (isAdmin(roles)) return true;
        if (hasRole(roles, FormalRole.BUILDING_OWNER)) return SourceStatus.ENABLED.name().equals(source.getStatus());
        return !SourceStatus.DRAFT.name().equals(source.getStatus()) || Objects.equals(source.getCreateBy(), userId);
    }

    private boolean aliasVisible(BizPointAlias alias, Long userId, Collection<String> roles) {
        if (isAdmin(roles)) return true;
        if (hasRole(roles, FormalRole.BUILDING_OWNER)) return Integer.valueOf(1).equals(alias.getStatus());
        return !Integer.valueOf(2).equals(alias.getStatus()) || Objects.equals(alias.getCreateBy(), userId);
    }

    private boolean policyVisible(BizCollectionPolicy policy, Long userId, Collection<String> roles) {
        if (isAdmin(roles)) return true;
        if (hasRole(roles, FormalRole.BUILDING_OWNER)) return policy.getActiveVersionId() != null;
        return policy.getActiveVersionId() != null || Objects.equals(policy.getCreateBy(), userId);
    }

    private boolean versionVisible(BizCollectionPolicyVersion version, BizCollectionPolicy policy,
                                   Long userId, Collection<String> roles) {
        if (isAdmin(roles)) return true;
        if (hasRole(roles, FormalRole.BUILDING_OWNER)) {
            return Objects.equals(policy.getActiveVersionId(), version.getVersionId());
        }
        return PolicyVersionStatus.ACTIVE.name().equals(version.getStatus())
                || Objects.equals(version.getCreatedBy(), userId);
    }

    private void checkVisibleSource(Long userId, Collection<String> roles, BizDataSource source) {
        buildingScopeService.checkAccess(userId, roles, source.getBuildingId());
        if (!sourceVisible(source, userId, roles)) notVisible();
    }

    private void checkVisiblePolicy(Long userId, Collection<String> roles, BizCollectionPolicy policy) {
        requireReader(roles);
        buildingScopeService.checkAccess(userId, roles, policy.getBuildingId());
        if (!policyVisible(policy, userId, roles)) notVisible();
    }

    private BizDataSource requireSource(String id) {
        BizDataSource value = sourceMapper.selectById(id);
        if (value == null) notVisible();
        return value;
    }

    private BizDataSource requireLockedSource(String id) {
        BizDataSource value = sourceMapper.selectByIdForUpdate(id);
        if (value == null) notVisible();
        return value;
    }

    private BizPointAlias requireAlias(String id) {
        BizPointAlias value = aliasMapper.selectById(id);
        if (value == null) notVisible();
        return value;
    }

    private BizPointAlias requireLockedAlias(String id) {
        BizPointAlias value = aliasMapper.selectByIdForUpdate(id);
        if (value == null) notVisible();
        return value;
    }

    private BizCollectionPolicy requirePolicy(String id) {
        BizCollectionPolicy value = policyMapper.selectById(id);
        if (value == null) notVisible();
        return value;
    }

    private BizCollectionPolicy requireLockedPolicy(String id) {
        BizCollectionPolicy value = policyMapper.selectByIdForUpdate(id);
        if (value == null) notVisible();
        return value;
    }

    private BizCollectionPolicy requireLockedPolicyByAlias(String aliasId) {
        BizCollectionPolicy value = policyMapper.selectByAliasIdForUpdate(aliasId);
        if (value == null) throw CollectionErrors.error(409, CollectionErrors.REFERENCE_CONFLICT,
                "别名缺少采集策略");
        return value;
    }

    private BizCollectionPolicy requireActivePolicy(String aliasId) {
        BizCollectionPolicy policy = policyByAlias(aliasId);
        if (policy == null || policy.getActiveVersionId() == null) {
            throw CollectionErrors.error(409, CollectionErrors.REFERENCE_CONFLICT, "别名缺少当前有效策略");
        }
        return policy;
    }

    private BizCollectionPolicy policyByAlias(String aliasId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<BizCollectionPolicy>()
                .eq(BizCollectionPolicy::getAliasId, aliasId));
    }

    private BizCollectionPolicyVersion requireVersion(String id) {
        if (id == null) throw CollectionErrors.error(409, CollectionErrors.REFERENCE_CONFLICT, "策略版本不存在");
        BizCollectionPolicyVersion value = versionMapper.selectById(id);
        if (value == null) notVisible();
        return value;
    }

    private BizCollectionPolicyVersion requireLockedVersion(String id) {
        if (id == null) throw CollectionErrors.error(409, CollectionErrors.REFERENCE_CONFLICT, "策略版本不存在");
        BizCollectionPolicyVersion value = versionMapper.selectByIdForUpdate(id);
        if (value == null) notVisible();
        return value;
    }

    private BizCollectionReviewRequest requireReview(String id) {
        BizCollectionReviewRequest value = reviewMapper.selectById(id);
        if (value == null) notVisible();
        return value;
    }

    private BizCollectionReviewRequest requireLockedReview(String id) {
        BizCollectionReviewRequest value = reviewMapper.selectByIdForUpdate(id);
        if (value == null) notVisible();
        return value;
    }

    private BizDataPoint requirePoint(String id) {
        BizDataPoint point = pointMapper.selectById(id);
        if (point == null || Integer.valueOf(1).equals(point.getDelFlag())) {
            throw CollectionErrors.error(404, CollectionErrors.NOT_FOUND, "标准测点不存在");
        }
        return point;
    }

    private List<BizPointAlias> aliases(String sourceId) {
        return aliasMapper.selectList(new LambdaQueryWrapper<BizPointAlias>()
                .eq(BizPointAlias::getSourceId, sourceId).orderByAsc(BizPointAlias::getAliasId));
    }

    private List<BizCollectionPolicyVersion> versions(String policyId) {
        return versionMapper.selectList(new LambdaQueryWrapper<BizCollectionPolicyVersion>()
                .eq(BizCollectionPolicyVersion::getPolicyId, policyId)
                .orderByDesc(BizCollectionPolicyVersion::getVersionNo));
    }

    private void requireInitialDraft(String aliasId) {
        BizCollectionPolicy policy = requireActiveOrDraftPolicy(aliasId);
        if (policy.getActiveVersionId() != null || policy.getDraftVersionId() == null) {
            throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "别名初始策略状态不完整");
        }
        requireVersion(policy.getDraftVersionId());
    }

    private BizCollectionPolicy requireActiveOrDraftPolicy(String aliasId) {
        BizCollectionPolicy policy = policyByAlias(aliasId);
        if (policy == null) throw CollectionErrors.error(409, CollectionErrors.REFERENCE_CONFLICT, "别名缺少采集策略");
        return policy;
    }

    private void requireNoPending(String targetType, String targetId) {
        if (reviewMapper.selectPendingForUpdate(targetType, targetId) != null) {
            throw CollectionErrors.error(409, CollectionErrors.REVIEW_CONFLICT, "目标已有待处理审核申请");
        }
    }

    private void requirePending(BizCollectionReviewRequest request) {
        if (!ReviewStatus.PENDING.name().equals(request.getStatus())) {
            throw CollectionErrors.error(409, CollectionErrors.REVIEW_CONFLICT, "审核申请已处理");
        }
    }

    private void requireAliasSource(BizPointAlias alias, BizDataSource source) {
        if (!source.getSourceId().equals(alias.getSourceId())) notVisible();
    }

    private void requirePolicyVersion(BizCollectionPolicy policy, BizCollectionPolicyVersion version) {
        if (!policy.getPolicyId().equals(version.getPolicyId())) notVisible();
    }

    private void requireSourceEditableBy(BizDataSource source, Long userId, Collection<String> roles) {
        if (SourceStatus.DRAFT.name().equals(source.getStatus())) {
            requireDraftOwnerOrAdmin(source.getCreateBy(), userId, roles);
        } else if (!isAdmin(roles) && !hasRole(roles, FormalRole.ENERGY_MANAGER)) {
            forbidden("当前角色不能维护该数据源");
        }
    }

    private static void requireDraftOwnerOrAdmin(Long owner, Long userId, Collection<String> roles) {
        if (!isAdmin(roles)) requireDraftOwner(owner, userId);
    }

    private static void requireDraftOwner(Long owner, Long userId) {
        if (!Objects.equals(owner, userId)) {
            throw CollectionErrors.error(409, CollectionErrors.DRAFT_CONFLICT,
                    "该策略已有待处理草稿，请联系平台管理员");
        }
    }

    private static void requireReader(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.BUILDING_OWNER)
                && !hasRole(roles, FormalRole.ENERGY_MANAGER) && !isAdmin(roles)) forbidden("无权读取采集治理配置");
    }

    private static void requireMaintainer(Collection<String> roles) {
        if (!hasAnyMaintainer(roles)) forbidden("只有能源管理员或平台管理员可以维护采集配置");
    }

    private static void requireEnergyManager(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.ENERGY_MANAGER)) forbidden("只有能源管理员可以提交或撤回审核");
    }

    private static void requireReviewerOrSubmitter(Collection<String> roles) {
        if (!hasRole(roles, FormalRole.ENERGY_MANAGER) && !isAdmin(roles)) forbidden("无权访问审核或审计");
    }

    private static void requireAdmin(Collection<String> roles) {
        if (!isAdmin(roles)) forbidden("只有平台管理员可以执行该操作");
    }

    private static boolean hasAnyMaintainer(Collection<String> roles) {
        return hasRole(roles, FormalRole.ENERGY_MANAGER) || isAdmin(roles);
    }

    private static boolean isAdmin(Collection<String> roles) {
        return hasRole(roles, FormalRole.PLATFORM_ADMIN);
    }

    private static boolean hasRole(Collection<String> roles, FormalRole role) {
        return roles != null && roles.stream().anyMatch(value -> role.name().equalsIgnoreCase(value));
    }

    private static void forbidden(String message) {
        throw CollectionErrors.error(403, CollectionErrors.FORBIDDEN, message);
    }

    private static void notVisible() {
        throw CollectionErrors.error(404, CollectionErrors.NOT_FOUND, "配置不存在");
    }

    private static RuntimeException versionConflict() {
        return CollectionErrors.error(409, CollectionErrors.VERSION_CONFLICT, "配置已被其他操作修改，请重新提交");
    }

    private static String requireSourceCode(String value) {
        String code = requireText(value, "数据源编码不能为空");
        if (!SOURCE_CODE.matcher(code).matches()) {
            throw CollectionErrors.error(400, CollectionErrors.VALIDATION_FAILED,
                    "数据源编码必须使用大写字母、数字、下划线或短横线");
        }
        return code;
    }

    private static String requireTransport(String value) {
        String transport = requireText(value, "传输类型不能为空").toUpperCase(Locale.ROOT);
        if (!Set.of("MQTT", "HTTP").contains(transport)) {
            throw CollectionErrors.error(400, CollectionErrors.VALIDATION_FAILED, "传输类型只支持MQTT或HTTP");
        }
        return transport;
    }

    private static String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) throw CollectionErrors.error(400, CollectionErrors.VALIDATION_FAILED, message);
        return normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safe(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? "" : normalized.replace(';', ',');
    }

    private static String trimSummary(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static String sourceSummary(BizDataSource source) {
        return "sourceCode=" + source.getSourceCode() + ";sourceName=" + safe(source.getSourceName())
                + ";status=" + source.getStatus() + ";configRevision=" + source.getConfigRevision()
                + ";runtimeRevision=" + source.getRuntimeRevision();
    }

    private static AliasStatus aliasStatus(Integer status) {
        return switch (status == null ? -1 : status) {
            case 2 -> AliasStatus.DRAFT;
            case 1 -> AliasStatus.ENABLED;
            case 0 -> AliasStatus.DISABLED;
            default -> throw CollectionErrors.error(409, CollectionErrors.STATE_CONFLICT, "别名状态无效");
        };
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 200) {
            throw CollectionErrors.error(400, CollectionErrors.VALIDATION_FAILED, "分页参数无效");
        }
    }

    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static LocalDateTime now() { return LocalDateTime.now(MYSQL_ZONE); }
    private static Long epoch(LocalDateTime value) {
        return value == null ? null : value.atZone(MYSQL_ZONE).toInstant().toEpochMilli();
    }
}
