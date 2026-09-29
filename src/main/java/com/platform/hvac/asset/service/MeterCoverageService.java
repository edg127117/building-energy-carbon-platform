package com.platform.hvac.asset.service;

import com.platform.framework.web.PageResponse;
import com.platform.hvac.asset.api.MeterCoverageContracts.*;
import com.platform.hvac.asset.service.MeterCoverageRepository.Equipment;
import com.platform.hvac.asset.service.MeterCoverageRepository.Revision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 表计覆盖档案与整楼能源治理独立：关联仅描述共同计量范围，不产生单机分摊或表间汇总。
 * 平台管理员沿用资产管理的全建筑权限；对象之间仍强制同建筑，时间由服务端记录且不回填历史。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeterCoverageService {
    private final MeterCoverageRepository repository;

    public View current(String meterId, Collection<String> roles) {
        requireAdmin(roles);
        requireMeter(meterId, false);
        return repository.revisions(meterId, 0, 1).stream().findFirst().map(this::view)
                .orElse(new View(meterId, 0, null, null, null, null, null, List.of(), "GROUP_ONLY", "SEPARATE_ONLY"));
    }

    public List<View> batch(String buildingId, List<String> ids, Collection<String> roles) {
        requireAdmin(roles);
        if (ids == null || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw invalid("每次最多查询 100 块表计");
        }
        List<View> result = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            Equipment meter = requireMeter(id, false);
            if (buildingId != null && !buildingId.equals(meter.buildingId())) throw invalid("表计不属于指定建筑");
            result.add(current(id, roles));
        }
        return result;
    }

    public PageResponse<View> history(String meterId, int page, int size, Collection<String> roles) {
        requireAdmin(roles);
        requireMeter(meterId, false);
        checkPage(page, size);
        return new PageResponse<>(page, size, repository.revisionCount(meterId),
                repository.revisions(meterId, (page - 1) * size, size).stream().map(this::view).toList());
    }

    public PageResponse<Target> candidates(String meterId, String keyword, int page, int size, Collection<String> roles) {
        requireAdmin(roles);
        Equipment meter = requireMeter(meterId, false);
        checkPage(page, size);
        if (keyword != null && keyword.length() > 100) throw invalid("检索文本不能超过 100 字符");
        String pattern = "%" + Objects.toString(keyword, "").trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return new PageResponse<>(page, size, repository.candidateCount(meter.buildingId(), pattern),
                repository.candidates(meter.buildingId(), pattern, (page - 1) * size, size).stream()
                        .map(e -> new Target(e.id(), e.code(), e.name(), e.spaceId(), e.spaceName(), true)).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public View save(String meterId, SaveRequest request, long actor, Collection<String> roles) {
        requireAdmin(roles);
        validate(request);
        Equipment meter = requireMeter(meterId, true);
        Revision previous = repository.revisions(meterId, 0, 1).stream().findFirst().orElse(null);
        long revision = previous == null ? 0 : previous.revision();
        if (request.expectedRevision() != revision) {
            throw AssetErrors.error(409, "METER_COVERAGE_CONFLICT", "表计档案已被更新，请刷新后重试");
        }
        String spaceId = trim(request.installationSpaceId());
        String spaceName = spaceId == null ? null : repository.installationSpace(meter.buildingId(), spaceId)
                .orElseThrow(() -> invalid("安装空间不存在或不属于表计建筑"));
        List<Equipment> targets = new ArrayList<>();
        for (String id : request.targetEquipmentIds().stream().sorted().toList()) {
            if (meterId.equals(id)) throw invalid("电表不能计量自身");
            // 先排除其他表计，避免两个非法的相互覆盖请求交叉锁住对方表计。
            Equipment candidate = repository.equipment(id, false).orElseThrow(() -> invalid("被测设备不存在或已删除"));
            if (!candidate.business()) throw invalid("被测对象必须是已明确分类的用能设备");
            Equipment target = repository.equipment(id, true).orElseThrow(() -> invalid("被测设备不存在或已删除"));
            if (!meter.buildingId().equals(target.buildingId())) throw invalid("被测设备必须与电表属于同一建筑");
            if (!target.business()) throw invalid("被测对象必须是已明确分类的用能设备");
            targets.add(target);
        }
        // 即刻生效，禁止把今天确认的覆盖范围默认为历史事实；时钟回退时拒绝以免区间倒序。
        long now = Instant.now().toEpochMilli();
        if (previous != null && now <= previous.effectiveAt()) {
            throw AssetErrors.error(409, "METER_COVERAGE_TIME_CONFLICT", "生效时间未推进，请稍后重试");
        }
        Revision saved = new Revision(meterId, revision + 1, now, spaceId, spaceName,
                request.scopeLabel().trim(), request.reason().trim());
        repository.insert(saved, meter.buildingId(), actor, targets);
        return view(saved);
    }

    private View view(Revision r) {
        return new View(r.meterId(), r.revision(), Instant.ofEpochMilli(r.effectiveAt()),
                r.installationSpaceId(), r.installationSpaceName(), r.label(), r.reason(),
                repository.targets(r.meterId(), r.revision()), "GROUP_ONLY", "SEPARATE_ONLY");
    }

    private Equipment requireMeter(String id, boolean lock) {
        Equipment e = repository.equipment(id, lock).orElseThrow(() ->
                AssetErrors.error(404, AssetErrors.NOT_FOUND, "表计不存在"));
        if (!e.meter()) throw invalid("该设备不是已配置的电表");
        return e;
    }

    private static void requireAdmin(Collection<String> roles) {
        if (roles == null || roles.stream().noneMatch("PLATFORM_ADMIN"::equalsIgnoreCase)) {
            throw AssetErrors.error(403, "ASSET_FORBIDDEN", "只有平台管理员可以管理表计档案");
        }
    }

    private static void checkPage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid("分页参数不合法");
    }

    private static void validate(SaveRequest r) {
        if (r == null || r.expectedRevision() == null || r.expectedRevision() < 0
                || trim(r.scopeLabel()) == null || r.scopeLabel().length() > 160
                || trim(r.reason()) == null || r.reason().length() > 500
                || (r.installationSpaceId() != null && r.installationSpaceId().length() > 32)
                || r.targetEquipmentIds() == null || r.targetEquipmentIds().size() > 100
                || r.targetEquipmentIds().stream().anyMatch(id -> trim(id) == null || id.length() > 32)
                || new HashSet<>(r.targetEquipmentIds()).size() != r.targetEquipmentIds().size()) {
            throw invalid("请填写覆盖范围、变更依据和不重复的被测设备清单");
        }
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static com.platform.framework.exception.BusinessException invalid(String message) {
        return AssetErrors.error(400, AssetErrors.VALIDATION_FAILED, message);
    }
}
