package com.platform.iot.temperature;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.iot.collection.CollectionPolicyService;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.entity.BizDataSource;
import com.platform.iot.daikin.monitoring.DaikinMonitoringProperties;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static com.platform.iot.temperature.TemperatureContracts.*;
import static com.platform.iot.temperature.TemperaturePlanService.*;

/**
 * 无数值来源时的一次性配置包：预览纯读取，执行复用公共审批事务，
 * 来源、首批测点、采集策略和来源专用规则整体提交；不修改原设备产品或启停状态。
 */
@Service
@RequiredArgsConstructor
public class TemperatureInitializationService {
    private final TemperaturePlanService plans;
    private final BizDataSourceMapper sources;
    private final BizPendingDeviceMapper pending;
    private final BizEquipmentMapper equipment;
    private final DaikinDirectoryService directory;
    private final DaikinMonitoringProperties monitoring;
    private final CollectionPolicyService collection;

    public InitializationPlan preview(InitializationInput input) {
        Bundle bundle = resolve(input);
        long expiry = System.currentTimeMillis() + 900_000L;
        String digest = expiry + ":" + fingerprint(bundle);
        var views = bundle.items().stream().map(p -> new PlanView(p.pending().getPendingId(),
                p.context().buildingId(), "MANUAL", p.product().getProductId(), p.product().getProductName(),
                bundle.source().getSourceId(), "READY", "同时创建温度来源、测点及匹配规则；温度历史沿用90天保留口径",
                digest, expiry, p.points())).toList();
        return new InitializationPlan(bundle.context().buildingId(), bundle.context().sourceScope(),
                bundle.source().getSourceId(), bundle.source().getSourceName(), input.templateProductId(), digest, expiry, views);
    }

    Bundle validate(InitializationRequest request, boolean submitting) {
        if (request == null || request.digest() == null || !request.digest().matches("[0-9]{13}:[0-9a-f]{64}")) {
            throw invalid("请先预览首次温度配置");
        }
        long expiry = Long.parseLong(request.digest().substring(0, 13));
        if (submitting && (expiry < System.currentTimeMillis() || expiry > System.currentTimeMillis() + 901_000L)) {
            throw invalid("初始化预览已过期，请重新预览");
        }
        var bundle = resolve(request.input());
        if (!fingerprint(bundle).equals(request.digest().substring(14))) throw invalid("初始化配置已变化，请重新预览");
        return bundle;
    }

    private Bundle resolve(InitializationInput input) {
        if (input == null || input.pendingIds() == null || input.pendingIds().isEmpty() || input.pendingIds().size() > 50
                || input.pendingIds().stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(input.pendingIds()).size() != input.pendingIds().size()) throw invalid("请选择1至50台不同内机");
        List<String> ids = input.pendingIds().stream().sorted().toList();
        var context = plans.context(ids.getFirst());
        if (sources.selectCount(new LambdaQueryWrapper<BizDataSource>().eq(BizDataSource::getBuildingId, context.buildingId())
                .eq(BizDataSource::getTransportType, "HTTP").eq(BizDataSource::getStatus, "ENABLED")) > 0) {
            throw invalid("本建筑已有HTTP温度来源，请使用已有来源配置匹配规则");
        }
        var source = candidate(context);
        if (sources.selectById(source.getSourceId()) != null || sources.selectCount(new LambdaQueryWrapper<BizDataSource>()
                .eq(BizDataSource::getSourceCode, source.getSourceCode())) > 0) throw invalid("初始化来源已存在，请检查其状态后重新预览");
        if (!plans.rules(context.buildingId(), "DAIKIN_INDOOR_V2").stream()
                .filter(r -> r.sourceScope().equals(context.sourceScope()) && r.model().isEmpty()).toList().isEmpty()) {
            throw invalid("已有来源匹配规则，请核对原规则和来源，不能覆盖初始化");
        }
        List<TemperaturePlanService.Resolved> items = new ArrayList<>();
        for (String id : ids) {
            var device = plans.requirePending(id);
            if (!"BOUND".equals(device.getStatus()) || !"DAIKIN_INDOOR_V2".equals(device.getProfileCode())
                    || !"DAIKIN_UNIT".equals(device.getIdentityType())) throw invalid("首次初始化仅支持已绑定的大金内机");
            var current = plans.context(id);
            if (!context.buildingId().equals(current.buildingId()) || !context.sourceScope().equals(current.sourceScope())) {
                throw invalid("请按同一建筑、同一厂家来源分别初始化");
            }
            var plan = plans.resolveInitial(new Input(id, "MANUAL", input.templateProductId(), source.getSourceId(), Map.of(), null), source);
            if (plan.points().size() != 2 || plan.points().stream().anyMatch(p -> !"CREATE".equals(p.action()))) {
                throw invalid("首次初始化需要完整双温度模板且设备尚无温度绑定");
            }
            items.add(plan);
        }
        return new Bundle(context, source, List.copyOf(items));
    }

    private BizDataSource candidate(TemperatureAdapter.Context context) {
        String key = hash(plans.write(List.of(context.buildingId(), context.sourceScope()))).substring(0, 24);
        var source = new BizDataSource();
        source.setSourceId("TEMP" + key); source.setSourceCode("DAIKIN_TEMP_" + key.toUpperCase(Locale.ROOT));
        source.setSourceName("大金空调温度数值来源"); source.setBuildingId(context.buildingId());
        source.setSourceCategory("DEVICE_ACCESS"); source.setTransportType("HTTP"); source.setStatus("ENABLED");
        source.setConfigRevision(1); source.setRuntimeRevision(1L);
        source.setDescription("由已审批的温度首次初始化配置包建立");
        return source;
    }

    private String fingerprint(Bundle bundle) {
        return hash(plans.write(List.of(bundle.source(), monitoring.getIntervalSeconds(),
                bundle.items().stream().map(plans::fingerprint).toList())));
    }

    /** 必须由已批准的敏感变更执行；唯一来源键与事务锁共同防止并发初始化和部分落地。 */
    public void execute(InitializationRequest request, String requestId, Long operator) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TEMPERATURE_TRANSACTION_REQUIRED");
        var initial = validate(request, false);
        for (var item : initial.items()) {
            directory.requireBinding(item.pending().getPendingId(), item.context().buildingId(), item.pending().getProfileCode());
            pending.selectByIdForUpdate(item.pending().getPendingId());
            equipment.selectOne(new LambdaQueryWrapper<BizEquipment>().eq(BizEquipment::getEquipId, item.equipment().getEquipId()).last("FOR UPDATE"));
        }
        var bundle = validate(request, false);
        var frozen = bundle.items().stream().map(plans::fingerprint).toList();
        // 先占用确定性来源键；本事务提交前其他采集线程看不到候选来源。
        var source = bundle.source();
        source.setCreateBy(operator); source.setUpdateBy(operator);
        sources.insert(source);
        for (int index = 0; index < bundle.items().size(); index++) {
            var item = bundle.items().get(index);
            var actual = plans.resolve(item.input());
            var comparable = new TemperaturePlanService.Resolved(actual.input(), actual.pending(), actual.adapter(), actual.context(),
                    actual.product(), actual.templates(), candidate(bundle.context()), actual.equipment(), actual.rule(), actual.points());
            if (!frozen.get(index).equals(plans.fingerprint(comparable))) throw invalid("初始化设备配置已变化，请重新预览");
            var target = equipment.selectOne(new LambdaQueryWrapper<BizEquipment>()
                    .eq(BizEquipment::getEquipId, actual.equipment().getEquipId()).last("FOR UPDATE"));
            plans.install(actual, target, actual.pending().getBoundIdentityId(), requestId);
        }
        collection.completeApprovedTemperatureSource(source.getSourceId(), operator, requestId, monitoring.getIntervalSeconds());
        plans.saveRule(new Rule(null, "DAIKIN_INDOOR_V2", bundle.context().buildingId(), bundle.context().sourceScope(), "",
                request.templateProductId(), source.getSourceId(), 0, true), requestId);
    }

    record Bundle(TemperatureAdapter.Context context, BizDataSource source, List<TemperaturePlanService.Resolved> items) { }
}
