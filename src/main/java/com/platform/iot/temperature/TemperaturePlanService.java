package com.platform.iot.temperature;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.*;
import com.platform.hvac.model.entity.*;
import com.platform.hvac.service.PointCodeNamingValidator;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.entity.BizDataSource;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts.TypedBindRequest;
import com.platform.iot.onboarding.mapper.*;
import com.platform.iot.onboarding.model.entity.*;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.platform.iot.temperature.TemperatureContracts.*;

/**
 * 温度配置的唯一计划与执行边界。预览不写库；审批执行在设备锁内重算冻结摘要，
 * 以单设备事务写正式测点和别名，提交之后才刷新运行缓存，不修改设备身份启停状态。
 */
@Service
@RequiredArgsConstructor
public class TemperaturePlanService {
    private static final long PREVIEW_TTL = 15 * 60_000L;
    private final List<TemperatureAdapter> adapters;
    private final BizPendingDeviceMapper pendingMapper;
    private final BizDeviceProductMapper products;
    private final BizProductPointTemplateMapper templates;
    private final BizDeviceIdentityMapper identities;
    private final BizEquipmentMapper equipment;
    private final BizDataPointMapper points;
    private final BizPointAliasMapper aliases;
    private final BizPointNamingRuleMapper namingRules;
    private final PointCodeNamingValidator naming;
    private final BizDataSourceMapper sources;
    private final MySqlDataPointConfigProvider cache;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public static BusinessException invalid(String message) {
        return new BusinessException(409, "TEMPERATURE_PLAN_CONFLICT", message);
    }

    public static Input input(String pendingId, TypedBindRequest binding) {
        return new Input(pendingId, binding.temperatureMode(), binding.temperatureTemplateProductId(),
                binding.numericSourceId(), binding.temperatureExistingPointIds(), binding);
    }

    public PlanView preview(Input input) {
        var plan = resolve(input);
        long expiry = System.currentTimeMillis() + PREVIEW_TTL;
        return new PlanView(input.pendingId(), plan.context().buildingId(), input.mode(),
                plan.product().getProductId(), plan.product().getProductName(), plan.source().getSourceId(),
                plan.points().stream().allMatch(p -> "REUSE".equals(p.action())) ? "COMPLETE" : "READY",
                "配置计划已校验，执行后等待实际采样", expiry + ":" + fingerprint(plan), expiry, plan.points());
    }

    /** 提交时限制预览时效；审批可能晚于预览过期，但其配置快照必须仍然完全一致。 */
    public Resolved validate(Input input, String digest, boolean submitting) {
        if (digest == null || !digest.matches("[0-9]{13}:[0-9a-f]{64}")) throw invalid("请先预览温度配置");
        long expiry = Long.parseLong(digest.substring(0, 13));
        if (submitting && (expiry < System.currentTimeMillis() || expiry > System.currentTimeMillis() + PREVIEW_TTL + 1000)) {
            throw invalid("温度预览已过期，请重新预览");
        }
        Resolved plan = resolve(input);
        if (!digest.substring(14).equals(fingerprint(plan))) throw invalid("温度计划或设备配置已变化，请重新预览");
        return plan;
    }

    public Options options(String pendingId) {
        BizPendingDevice pending = requirePending(pendingId);
        TemperatureAdapter adapter = adapter(pending);
        var context = adapter.context(pending);
        List<TemplateOption> compatible = products.selectList(new LambdaQueryWrapper<BizDeviceProduct>()
                .eq(BizDeviceProduct::getStatus, "ENABLED")
                .eq(BizDeviceProduct::getIdentityType, pending.getIdentityType())
                .eq(BizDeviceProduct::getExpectedProfileCode, pending.getProfileCode())
                .orderByAsc(BizDeviceProduct::getProductId)).stream().filter(p -> {
                    try { templatePoints(p, adapter); return true; } catch (BusinessException ex) { return false; }
                }).map(p -> new TemplateOption(p.getProductId(), p.getProductName())).toList();
        return new Options(compatible, sources.selectList(new LambdaQueryWrapper<BizDataSource>()
                .eq(BizDataSource::getBuildingId, context.buildingId())
                .eq(BizDataSource::getStatus, "ENABLED").eq(BizDataSource::getTransportType, adapter.transportType())
                .orderByAsc(BizDataSource::getSourceId)).stream()
                .map(s -> new SourceOption(s.getSourceId(), s.getSourceName())).toList(),
                rules(context.buildingId(), adapter.id()));
    }

    public Resolved resolve(Input input) {
        return resolve(input, null);
    }

    /** 初始化预览只使用内存候选来源；不放宽普通绑定的数据源校验。 */
    Resolved resolveInitial(Input input, BizDataSource candidate) {
        return resolve(input, candidate);
    }

    TemperatureAdapter.Context context(String pendingId) {
        var pending = requirePending(pendingId);
        return adapter(pending).context(pending);
    }

    private Resolved resolve(Input input, BizDataSource candidate) {
        if (input == null || !Set.of("AUTO", "MANUAL").contains(Objects.toString(input.mode(), ""))) {
            throw invalid("温度计划仅接受自动或人工模式；仅状态接入无需温度计划");
        }
        if (input.existingPointIds().size() > 2 || ("AUTO".equals(input.mode()) && !input.existingPointIds().isEmpty())) {
            throw invalid("已有测点映射仅适用于人工模式");
        }
        var pending = requirePending(input.pendingId());
        var adapter = adapter(pending);
        var context = adapter.context(pending);
        BizEquipment target = null;
        if ("BOUND".equals(pending.getStatus())) {
            if (input.binding() != null) throw invalid("已接入设备请使用温度补齐入口");
            var identity = identities.selectById(pending.getBoundIdentityId());
            if (identity == null || !context.buildingId().equals(identity.getBuildingId())
                    || !pending.getIdentityValue().equals(identity.getIdentityValue())
                    || !pending.getIdentityType().equals(identity.getIdentityType())
                    || !pending.getProfileCode().equals(identity.getExpectedProfileCode())) throw invalid("身份归属已变化");
            target = equipment.selectById(identity.getEquipId());
        } else if ("DISCOVERED".equals(pending.getStatus()) && input.binding() != null) {
            var binding = input.binding();
            if (!context.buildingId().equals(binding.buildingId()) || !binding.pointBindings().isEmpty()) {
                throw invalid("自动温度计划不能混用旧测点映射或跨建筑归属");
            }
            var assetProduct = products.selectById(binding.productId());
            if (assetProduct == null || !"ENABLED".equals(assetProduct.getStatus())
                    || !pending.getProfileCode().equals(assetProduct.getExpectedProfileCode())
                    || !pending.getIdentityType().equals(assetProduct.getIdentityType())) throw invalid("接入产品不兼容");
            if (text(binding.existingEquipmentId())) target = equipment.selectById(binding.existingEquipmentId());
            else if (binding.newEquipment() == null) throw invalid("缺少设备归属配置");
        } else throw invalid("设备状态不允许温度配置");
        if (("BOUND".equals(pending.getStatus()) || input.binding() != null && text(input.binding().existingEquipmentId()))
                && (target == null || !context.buildingId().equals(target.getBuildingId()))) throw invalid("设备归属不一致");
        List<Rule> available = rules(context.buildingId(), adapter.id()).stream().filter(Rule::enabled).toList();
        Rule match = "MANUAL".equals(input.mode()) && text(input.numericSourceId()) ? null : match(available, context);
        String templateId = "MANUAL".equals(input.mode()) ? input.templateProductId()
                : match == null ? null : match.templateProductId();
        if (!text(templateId)) throw invalid("未找到唯一模板，请配置默认匹配规则或人工选择兼容模板");
        if ("AUTO".equals(input.mode()) && text(input.templateProductId()) && !templateId.equals(input.templateProductId())) {
            throw invalid("自动匹配结果与指定模板不一致");
        }
        BizDeviceProduct product = products.selectById(templateId);
        if (product == null || !"ENABLED".equals(product.getStatus())
                || !pending.getProfileCode().equals(product.getExpectedProfileCode())
                || !pending.getIdentityType().equals(product.getIdentityType())) throw invalid("温度模板不兼容或已停用");
        String equipmentType = target == null ? products.selectById(input.binding().productId()).getEquipmentTypeCode() : target.getTypeCode();
        if (!Objects.equals(equipmentType, product.getEquipmentTypeCode())) throw invalid("温度模板与设备类型不兼容");
        var templatePoints = templatePoints(product, adapter);
        String sourceId = text(input.numericSourceId()) ? input.numericSourceId() : match == null ? null : match.numericSourceId();
        var source = candidate == null ? requireSource(sourceId, context.buildingId(), adapter) : candidate;
        if (!context.buildingId().equals(source.getBuildingId()) || !sourceId.equals(source.getSourceId())
                || !adapter.transportType().equals(source.getTransportType())) throw invalid("初始化来源归属不匹配");
        if ("AUTO".equals(input.mode()) && match != null && !match.numericSourceId().equals(sourceId)) {
            throw invalid("自动模式必须使用已批准的来源关系，变更来源请使用人工模式");
        }
        for (String field : input.existingPointIds().keySet()) if (!adapter.fields().containsKey(field)) throw invalid("未知温度测点映射");
        List<PointPlan> pointPlans = new ArrayList<>();
        Set<String> usedPointIds = new HashSet<>();
        for (var template : templatePoints) {
            var alias = alias(context.buildingId(), adapter.sourceSystem(), adapter.alias(pending, template.getMetricCode()));
            String requested = input.existingPointIds().get(template.getMetricCode());
            BizDataPoint point = null;
            if (alias != null) {
                if (!Integer.valueOf(1).equals(alias.getStatus()) || !sourceId.equals(alias.getSourceId())) {
                    throw invalid("已有温度别名来源不一致或已停用");
                }
                if (text(requested) && !requested.equals(alias.getPointId())) throw invalid("不能覆盖已有温度绑定");
                point = points.selectById(alias.getPointId());
                if (point == null) throw invalid("已有温度别名引用的测点不存在");
            } else if (text(requested)) {
                point = points.selectById(requested);
                if (point == null) throw invalid("选择的测点不存在");
                if (aliases.selectCount(new LambdaQueryWrapper<BizPointAlias>().eq(BizPointAlias::getPointId, requested)) > 0) {
                    throw invalid("选择的测点已有其他来源绑定");
                }
            }
            if (point != null) {
                validatePoint(point, target, template);
                if (!usedPointIds.add(point.getPointId())) throw invalid("不同温度语义不能共用同一测点");
                pointPlans.add(new PointPlan(template.getMetricCode(), adapter.fields().get(template.getMetricCode()),
                        template.getUnit(), "REUSE", point.getPointId(), point.getPointCode(), point.getPointName()));
            } else {
                String code = target == null ? null : target.getEquipCode() + "_" + template.getSuffixCode();
                if (code != null) {
                    namingRule(code);
                    if (points.selectCount(new LambdaQueryWrapper<BizDataPoint>().eq(BizDataPoint::getBuildingId, context.buildingId())
                            .eq(BizDataPoint::getPointCode, code)) > 0) throw invalid("测点编码已存在，请人工选择复用");
                }
                pointPlans.add(new PointPlan(template.getMetricCode(), adapter.fields().get(template.getMetricCode()),
                        template.getUnit(), "CREATE", null, code, template.getPointNameTemplate()));
            }
        }
        return new Resolved(input, pending, adapter, context, product, templatePoints, source, target, match, List.copyOf(pointPlans));
    }

    /** 首个命中层级必须唯一，人工选模板时不以模板发布顺序作为默认优先级。 */
    static Rule match(List<Rule> rules, TemperatureAdapter.Context context) {
        for (int tier = 0; tier < 4; tier++) {
            final int level = tier;
            var matches = rules.stream().filter(r -> {
                boolean scoped = !r.sourceScope().isEmpty();
                boolean modeled = !r.model().isEmpty();
                int priority = scoped ? modeled ? 0 : 1 : modeled ? 2 : 3;
                return priority == level && (!scoped || r.sourceScope().equals(context.sourceScope()))
                        && (!modeled || r.model().equals(context.model()));
            }).toList();
            if (matches.size() > 1) throw invalid("同一层级存在多个温度模板规则");
            if (!matches.isEmpty()) return matches.getFirst();
        }
        return null;
    }

    private List<BizProductPointTemplate> templatePoints(BizDeviceProduct product, TemperatureAdapter adapter) {
        var values = templates.selectList(new LambdaQueryWrapper<BizProductPointTemplate>()
                .eq(BizProductPointTemplate::getProductId, product.getProductId())
                .eq(BizProductPointTemplate::getStatus, 1).orderByAsc(BizProductPointTemplate::getMetricCode));
        if (values.isEmpty() || values.stream().anyMatch(p -> !adapter.fields().containsKey(p.getMetricCode())
                || !adapter.unit().equals(p.getUnit()) || !Integer.valueOf(0).equals(p.getForCalc()))
                || values.stream().map(BizProductPointTemplate::getMetricCode).distinct().count() != values.size()) {
            throw invalid("模板没有兼容温度测点或单位、用途不正确");
        }
        return values;
    }

    private void validatePoint(BizDataPoint point, BizEquipment target, BizProductPointTemplate template) {
        if (target == null || !target.getEquipId().equals(point.getEquipId())
                || !target.getBuildingId().equals(point.getBuildingId())
                || !Objects.equals(target.getSystemGroupId(), point.getSystemGroupId())
                || !"AI".equals(point.getDataType()) || !"ONLINE".equals(point.getStatus())
                || !Integer.valueOf(0).equals(point.getIsForCalc()) || !template.getUnit().equals(point.getUnit())
                || !template.getSuffixCode().equals(point.getSuffixCode())) throw invalid("已有测点归属、单位或语义不兼容");
    }

    private BizPointNamingRule namingRule(String code) {
        var matches = namingRules.selectList(new LambdaQueryWrapper<BizPointNamingRule>().eq(BizPointNamingRule::getStatus, 1))
                .stream().filter(r -> naming.matches(r, code)).toList();
        if (matches.size() != 1) throw invalid("设备编码没有唯一可用的测点命名规则");
        return matches.getFirst();
    }

    String fingerprint(Resolved plan) {
        // 输入中的摘要本身不能参与摘要；设备运行状态和采样值也不是配置版本。
        var binding = plan.input().binding();
        Object asset = binding == null ? "EXISTING" : Arrays.asList(binding.productId(), binding.buildingId(),
                binding.spaceId(), binding.systemGroupId(), binding.existingEquipmentId(), binding.newEquipment());
        var target = plan.equipment();
        Object ownership = target == null ? "NEW" : Arrays.asList(target.getEquipId(), target.getEquipCode(),
                target.getBuildingId(), target.getSpaceId(), target.getSystemGroupId(), target.getProductId(), target.getTypeCode());
        List<Object> existing = new ArrayList<>();
        for (var p : plan.points()) {
            existing.add(Arrays.asList(p, p.pointId() == null ? null : points.selectById(p.pointId()),
                    alias(plan.context().buildingId(), plan.adapter().sourceSystem(), plan.adapter().alias(plan.pending(), p.metricCode()))));
        }
        return hash(write(Arrays.asList(plan.pending().getPendingId(), plan.pending().getStatus(), plan.pending().getBoundIdentityId(),
                plan.pending().getIdentityType(), plan.pending().getIdentityValue(), plan.pending().getProfileCode(),
                plan.context(), plan.input().mode(), plan.product(), plan.templates(), plan.source(), plan.rule(), asset, ownership, existing,
                namingRules.selectList(new LambdaQueryWrapper<BizPointNamingRule>().eq(BizPointNamingRule::getStatus, 1)
                        .orderByAsc(BizPointNamingRule::getRuleId)))));
    }

    /** 调用方必须持有当前设备事务锁；已有正式产品归属不因模板来源不同而改写。 */
    public List<String> install(Resolved plan, BizEquipment target, String identityId, String requestId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TEMPERATURE_TRANSACTION_REQUIRED");
        if (!plan.context().buildingId().equals(target.getBuildingId())) throw invalid("设备建筑发生变化");
        lockAndRecheck(plan, target);
        List<String> ids = new ArrayList<>();
        for (var template : plan.templates()) {
            var item = plan.points().stream().filter(p -> p.metricCode().equals(template.getMetricCode())).findFirst().orElseThrow();
            BizDataPoint point;
            if ("REUSE".equals(item.action())) {
                point = points.selectOne(new LambdaQueryWrapper<BizDataPoint>().eq(BizDataPoint::getPointId, item.pointId()).last("FOR UPDATE"));
                validatePoint(point, target, template);
            } else {
                String code = target.getEquipCode() + "_" + template.getSuffixCode();
                var namingRule = namingRule(code);
                point = new BizDataPoint();
                point.setPointCode(code); point.setPointName(template.getPointNameTemplate());
                point.setBuildingId(target.getBuildingId()); point.setEquipId(target.getEquipId());
                point.setSystemGroupId(target.getSystemGroupId()); point.setNamingRuleId(namingRule.getRuleId());
                point.setFamilyCode(namingRule.getFamilyCode()); point.setComponentCode(namingRule.getComponentCode());
                point.setSuffixCode(template.getSuffixCode()); point.setDataType("AI"); point.setUnit(template.getUnit());
                point.setIsForCalc(0); point.setValueMin(template.getMinValue()); point.setValueMax(template.getMaxValue());
                point.setStatus("ONLINE"); point.setDelFlag(0); point.setCreateTime(new Date()); point.setUpdateTime(point.getCreateTime());
                points.insert(point);
            }
            String code = plan.adapter().alias(plan.pending(), template.getMetricCode());
            var alias = aliases.selectOne(new LambdaQueryWrapper<BizPointAlias>()
                    .eq(BizPointAlias::getBuildingId, target.getBuildingId())
                    .eq(BizPointAlias::getSourceSystem, plan.adapter().sourceSystem())
                    .eq(BizPointAlias::getSourcePointCode, code).last("FOR UPDATE"));
            if (alias == null) {
                alias = new BizPointAlias();
                alias.setBuildingId(target.getBuildingId()); alias.setSourceSystem(plan.adapter().sourceSystem());
                alias.setSourcePointCode(code); alias.setSourceId(plan.source().getSourceId());
                alias.setPointId(point.getPointId()); alias.setStatus(1); aliases.insert(alias);
            } else if (!point.getPointId().equals(alias.getPointId()) || !Integer.valueOf(1).equals(alias.getStatus())
                    || !plan.source().getSourceId().equals(alias.getSourceId())) throw invalid("已有温度别名被占用、停用或来源已变化");
            var recorded = jdbc.queryForList("SELECT point_id FROM biz_temperature_binding WHERE identity_id=? AND metric_code=?",
                    String.class, identityId, template.getMetricCode());
            if (recorded.isEmpty()) {
                jdbc.update("""
                        INSERT INTO biz_temperature_binding(identity_id,metric_code,building_id,equipment_id,point_id,alias_id,
                          template_product_id,snapshot_json,request_id,effective_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)
                        """, identityId, template.getMetricCode(), target.getBuildingId(), target.getEquipId(), point.getPointId(),
                        alias.getAliasId(), plan.product().getProductId(), write(Arrays.asList(template, plan.rule(), plan.context())),
                        requestId, System.currentTimeMillis());
            } else if (!recorded.getFirst().equals(point.getPointId())) throw invalid("已登记温度关系与测点不一致");
            ids.add(point.getPointId());
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.refreshAll(); }
        });
        return List.copyOf(ids);
    }

    /** MySQL 可重复读下普通重读可能仍是旧快照；落地前用当前锁定读再次比对审批所依赖的配置。 */
    private void lockAndRecheck(Resolved plan, BizEquipment target) {
        var product = products.selectOne(new LambdaQueryWrapper<BizDeviceProduct>()
                .eq(BizDeviceProduct::getProductId, plan.product().getProductId()).last("FOR UPDATE"));
        var currentTemplates = templates.selectList(new LambdaQueryWrapper<BizProductPointTemplate>()
                .eq(BizProductPointTemplate::getProductId, plan.product().getProductId()).eq(BizProductPointTemplate::getStatus, 1)
                .orderByAsc(BizProductPointTemplate::getMetricCode).last("FOR UPDATE"));
        var source = sources.selectOne(new LambdaQueryWrapper<BizDataSource>()
                .eq(BizDataSource::getSourceId, plan.source().getSourceId()).last("FOR UPDATE"));
        if (!Objects.equals(product, plan.product()) || !currentTemplates.equals(plan.templates()) || !Objects.equals(source, plan.source())) {
            throw invalid("模板或来源在审批执行期间发生变化");
        }
        if (plan.rule() != null) {
            var currentRules = readRules(plan.context().buildingId(), plan.adapter().id(), true).stream().filter(Rule::enabled).toList();
            if (!Objects.equals(match(currentRules, plan.context()), plan.rule())) throw invalid("自动匹配规则已变化");
        }
        if (plan.equipment() != null && (!Objects.equals(plan.equipment().getEquipId(), target.getEquipId())
                || !Objects.equals(plan.equipment().getSpaceId(), target.getSpaceId())
                || !Objects.equals(plan.equipment().getSystemGroupId(), target.getSystemGroupId())
                || !Objects.equals(plan.equipment().getProductId(), target.getProductId())
                || !Objects.equals(plan.equipment().getEquipCode(), target.getEquipCode()))) throw invalid("设备归属已变化");
    }

    public String configurationStatus(String pendingId) {
        var pending = requirePending(pendingId);
        var bindings = jdbc.queryForList("SELECT point_id,metric_code,equipment_id FROM biz_temperature_binding WHERE identity_id=?", pending.getBoundIdentityId());
        if (bindings.isEmpty()) return "NOT_CONFIGURED";
        var adapter = adapter(pending);
        for (var b : bindings) {
            var point = cache.find(new PointAliasKey(adapter.context(pending).buildingId(), adapter.sourceSystem(),
                    adapter.alias(pending, b.get("metric_code").toString())));
            if (point.isEmpty() || !point.get().pointId().equals(b.get("point_id"))
                    || !Objects.equals(point.get().equipId(), b.get("equipment_id"))
                    || !"AI".equals(point.get().dataType()) || !"ONLINE".equals(point.get().status())
                    || !adapter.unit().equals(point.get().unit()) || point.get().isForCalc() != 0) return "CACHE_PENDING";
        }
        return "CONFIGURED";
    }

    public void refreshCache() { cache.refreshAll(); }

    public List<Rule> rules(String buildingId, String adapterId) {
        return readRules(buildingId, adapterId, false);
    }

    private List<Rule> readRules(String buildingId, String adapterId, boolean lock) {
        return jdbc.query("SELECT * FROM biz_temperature_rule WHERE building_id=? AND adapter_id=? ORDER BY rule_id" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Rule(rs.getString("rule_id"), rs.getString("adapter_id"), rs.getString("building_id"),
                        rs.getString("source_scope"), rs.getString("model"), rs.getString("template_product_id"),
                        rs.getString("numeric_source_id"), rs.getInt("revision"), rs.getInt("enabled") == 1), buildingId, adapterId);
    }

    public Rule normalizeRule(Rule rule) {
        if (rule == null || !text(rule.buildingId()) || !text(rule.adapterId()) || rule.revision() < 0) throw invalid("规则参数无效");
        var adapter = adapters.stream().filter(a -> a.id().equals(rule.adapterId())).findFirst().orElseThrow(() -> invalid("厂家适配器尚未实现"));
        if (rule.enabled()) {
            var product = products.selectById(rule.templateProductId());
            if (product == null || !"ENABLED".equals(product.getStatus())) throw invalid("模板未启用");
            var probe = new BizPendingDevice(); probe.setIdentityType(product.getIdentityType()); probe.setProfileCode(product.getExpectedProfileCode());
            if (!adapter.supports(probe)) throw invalid("模板不属于指定厂家协议");
            templatePoints(product, adapter);
            requireSource(rule.numericSourceId(), rule.buildingId(), adapter);
        } else if (rule.revision() == 0 || !text(rule.ruleId())) throw invalid("只能停用已有规则");
        String scope = Objects.toString(rule.sourceScope(), "");
        String model = Objects.toString(rule.model(), "");
        if (scope.length() > 200 || model.length() > 100 || !scope.equals(scope.strip()) || !model.equals(model.strip())) throw invalid("匹配范围无效");
        String stableId = hash(write(List.of(rule.adapterId(), rule.buildingId(), scope, model))).substring(0, 32);
        return new Rule(text(rule.ruleId()) ? rule.ruleId() : stableId, rule.adapterId(), rule.buildingId(), scope,
                model, rule.templateProductId(), rule.numericSourceId(), rule.revision(), rule.enabled());
    }

    @Transactional
    public void saveRule(Rule value, String requestId) {
        Rule rule = normalizeRule(value);
        if (rule.revision() == 0) {
            jdbc.update("""
                    INSERT INTO biz_temperature_rule(rule_id,adapter_id,building_id,source_scope,model,template_product_id,
                      numeric_source_id,revision,enabled,request_id) VALUES(?,?,?,?,?,?,?,1,?,?)
                    """, rule.ruleId(), rule.adapterId(), rule.buildingId(), rule.sourceScope(), rule.model(),
                    rule.templateProductId(), rule.numericSourceId(), rule.enabled() ? 1 : 0, requestId);
        } else if (jdbc.update("""
                    UPDATE biz_temperature_rule SET template_product_id=?,numeric_source_id=?,enabled=?,revision=revision+1,request_id=?
                    WHERE rule_id=? AND revision=? AND building_id=? AND adapter_id=? AND source_scope=? AND model=?
                    """, rule.templateProductId(), rule.numericSourceId(), rule.enabled() ? 1 : 0, requestId,
                    rule.ruleId(), rule.revision(), rule.buildingId(), rule.adapterId(), rule.sourceScope(), rule.model()) != 1) {
            throw invalid("规则版本已变化，请重新申请");
        }
    }

    private BizDataSource requireSource(String id, String building, TemperatureAdapter adapter) {
        var source = text(id) ? sources.selectById(id) : null;
        if (source == null || !building.equals(source.getBuildingId()) || !"ENABLED".equals(source.getStatus())
                || !adapter.transportType().equals(source.getTransportType())) throw invalid("缺少同建筑已启用且兼容的温度数据源");
        return source;
    }
    private BizPointAlias alias(String building, String system, String code) {
        return aliases.selectOne(new LambdaQueryWrapper<BizPointAlias>().eq(BizPointAlias::getBuildingId, building)
                .eq(BizPointAlias::getSourceSystem, system).eq(BizPointAlias::getSourcePointCode, code));
    }
    public BizPendingDevice requirePending(String id) {
        var pending = text(id) ? pendingMapper.selectById(id) : null;
        if (pending == null) throw invalid("设备不存在");
        return pending;
    }
    private TemperatureAdapter adapter(BizPendingDevice pending) {
        var candidates = adapters.stream().filter(a -> a.supports(pending)).toList();
        if (candidates.size() != 1) throw invalid("该厂家协议或设备类型尚未适配温度自动绑定");
        return candidates.getFirst();
    }
    public String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("TEMPERATURE_JSON_INVALID", ex); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    public record Resolved(Input input, BizPendingDevice pending, TemperatureAdapter adapter, TemperatureAdapter.Context context,
                           BizDeviceProduct product, List<BizProductPointTemplate> templates, BizDataSource source,
                           BizEquipment equipment, Rule rule, List<PointPlan> points) { }
}
