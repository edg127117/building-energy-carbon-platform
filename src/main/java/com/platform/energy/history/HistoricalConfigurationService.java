package com.platform.energy.history;

import com.platform.carbon.CarbonHistoricalConfigurationReader;
import com.platform.carbon.CarbonProjectService;
import com.platform.energy.summary.EnergyBoundarySummaryService;
import com.platform.energy.classification.ClassificationContracts.VersionView;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.EnergyPeriodBoundary;
import com.platform.energy.period.EnergyPeriodGovernanceService;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.platform.energy.history.HistoricalConfigurationContracts.*;

/** 按请求的建筑与整月业务区间检查现有配置，不补读数、不审核、不重算。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoricalConfigurationService {
    private final EnergyPeriodAuthorization authorization;
    private final ClassificationService classifications;
    private final EnergyPeriodGovernanceService policies;
    private final EnergyCatalogService catalog;
    private final EnergyConversionService conversion;
    private final CarbonHistoricalConfigurationReader carbon;
    private final CarbonProjectService projects;
    private final EnergyBoundarySummaryService summaries;

    public Coverage coverage(long user, Collection<String> roles, String building, Request request) {
        authorization.requireReader(roles);
        authorization.checkBuilding(user, roles, building);
        var format = classifications.preview(user, roles, building, request.data());
        List<Check> checks = new ArrayList<>();
        for (var issue : format.issues()) checks.add(new Check(0, null, "READING_FORMAT",
                issue.severity().equals("ERROR") ? "BLOCKED" : "PENDING_EXPERT", null, issue.code(), issue.message()));
        if ("INVALID".equals(format.status())) return new Coverage(building, format.status(), "NOT_CHECKED", "NOT_RUN", checks);
        ZoneId zone;
        try { zone = ZoneId.of(request.timezoneId()); }
        catch (DateTimeException exception) { throw new BusinessException(400, "历史检查时区无效"); }
        // 同一请求可含多测点；期间级规则每个能源/单位/月份只读取一次。
        Map<String, Check> shared = new HashMap<>();
        Map<String, VersionView> configurations = new HashMap<>();
        int index = 0;
        for (var reading : request.data().readings()) {
            int row = ++index;
            String point = reading.pointId();
            var from = LocalDateTime.ofInstant(reading.startInclusive(), zone);
            var to = LocalDateTime.ofInstant(reading.endExclusive(), zone);
            var month = EnergyPeriodBoundary.resolve("MONTH", from.toLocalDate().withDayOfMonth(1), zone.getId());
            if (!month.startInclusive().equals(reading.startInclusive()) || !month.endExclusive().equals(reading.endExclusive())) {
                checks.add(new Check(row, point, "MONTH_BOUNDARY", "BLOCKED", null, "FULL_MONTH_REQUIRED", "须提供自然整月，不能按日摊分历史用量"));
                continue;
            }
            checks.add(check(row, point, "POINT_BINDING", () -> {
                var binding = catalog.effectiveBindingForPeriod(user, roles, building, point, from, to);
                if (!reading.energyItemCode().equals(binding.energyItemCode()) || !reading.unit().equals(binding.rawUnit())) {
                    throw new BusinessException(409, "读数能源品种或单位与期间绑定不一致");
                }
                return binding.bindingVersionId();
            }));
            String key = reading.startInclusive() + "/" + reading.energyItemCode() + "/" + reading.unit();
            addShared(checks, shared, key, row, point, "PERIOD_POLICY", () -> {
                var policy = policies.policyForPeriod(building, from, to);
                if (!"FORMAL".equals(policy.sourceType()) || !zone.getId().equals(policy.timezoneId())
                        || policy.effectiveTo() != null && policy.effectiveTo().isBefore(to)) {
                    throw new BusinessException(409, "FORMAL周期口径及其时区未覆盖整个月份");
                }
                if (Instant.now().isBefore(reading.endExclusive().plusSeconds(policy.closingDelayHours() * 3600L))) {
                    throw new BusinessException(409, "尚未结束迟到窗口");
                }
                return policy.versionId();
            });
            addShared(checks, shared, key, row, point, "CLASSIFICATION_AND_METERING_SCOPE", () ->
                    classifications.configurationForPeriod(user, roles, building, request.classificationVersionId(),
                            reading.startInclusive(), reading.endExclusive(), reading.energyItemCode(), reading.unit()).versionId());
            var classificationCheck = shared.get(key + "/CLASSIFICATION_AND_METERING_SCOPE");
            if ("COVERED".equals(classificationCheck.status())) {
                var configuration = configurations.computeIfAbsent(classificationCheck.versionId(),
                        id -> classifications.version(user, roles, building, id));
                for (var boundary : configuration.configuration().boundaryRules()) {
                    if (reading.energyItemCode().equals(boundary.energyItemCode()) && reading.unit().equals(boundary.unit())) {
                        addShared(checks, shared, key, row, point, "BOUNDARY_SUMMARY:" + boundary.boundaryId(), () ->
                                summaries.policyForPeriod(user, roles, building, boundary.boundaryId(),
                                        reading.energyItemCode(), from, to).versionId());
                    }
                }
            }
            addShared(checks, shared, key, row, point, "CARBON_PROJECT", () -> {
                if (!"COVERED".equals(classificationCheck.status())) throw new BusinessException(409, "分类配置尚未覆盖，无法核对碳项目");
                return projects.configurationForPeriod(user, roles, building, reading.startInclusive(),
                        reading.endExclusive(), classificationCheck.versionId(), reading.energyItemCode()).versionId();
            });
            addShared(checks, shared, key, row, point, "TCE_RULE", () -> conversion.evaluateFormalRules(user, roles,
                    new SimulationRequest(building, reading.energyItemCode(), BigDecimal.ZERO, reading.unit(),
                            request.method(), request.perspective(), request.consumptionScope(), request.regionCode(), from), to).parameterVersionId());
            addShared(checks, shared, key, row, point, "CARBON_ACTIVE_FACTOR", () -> carbon.factor(user, roles, building,
                    reading.energyItemCode(), reading.unit(), reading.startInclusive(), reading.endExclusive(), zone.getId()));
            addShared(checks, shared, key, row, point, "BUILDING_AREA", () -> carbon.area(user, roles, building,
                    reading.startInclusive(), reading.endExclusive(), zone.getId()));
        }
        boolean covered = checks.stream().allMatch(c -> "COVERED".equals(c.status()));
        return new Coverage(building, format.status(), covered ? "COVERED" : "BLOCKED", "NOT_RUN", List.copyOf(checks));
    }

    private static void addShared(List<Check> checks, Map<String, Check> shared, String key,
            int row, String point, String domain, Supplier<String> operation) {
        var value = shared.computeIfAbsent(key + "/" + domain, ignored -> check(row, point, domain, operation));
        checks.add(new Check(row, point, domain, value.status(), value.versionId(), value.code(), value.message()));
    }

    private static Check check(int row, String point, String domain, Supplier<String> operation) {
        try { return new Check(row, point, domain, "COVERED", operation.get(), null, null); }
        catch (BusinessException exception) {
            // 权限和运行故障不能伪装成缺配置，交给正常HTTP错误处理。
            if (exception.getCode() == 401 || exception.getCode() == 403 || exception.getCode() >= 500) throw exception;
            return new Check(row, point, domain, "BLOCKED", null, exception.getErrorCode(), exception.getMessage());
        }
    }
}
