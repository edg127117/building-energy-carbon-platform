package com.platform.iot.temperature;

import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.model.*;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.onboarding.*;
import com.platform.iot.onboarding.api.*;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static com.platform.iot.temperature.TemperatureContracts.*;
import static org.assertj.core.api.Assertions.*;

/** H2 隔离验证真实审批、设备事务及缓存配置；不连接厂家或 TDengine，不替代现场曲线验收。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TemperatureBindingIntegrationTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    @Autowired TemperaturePlanService plans;
    @Autowired TemperatureBindingService service;
    @Autowired DeviceOnboardingService onboarding;
    @Autowired ScopedDeviceOnboardingService scoped;
    @Autowired DeviceProductService products;
    @Autowired DaikinDirectoryService directory;
    @Autowired SensitiveChangeService changes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MySqlDataPointConfigProvider cache;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    private String suffix, source, http, template, asset, pending;

    @BeforeEach void setup() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        source = "TPD" + suffix; http = "TPH" + suffix;
        jdbc.update("INSERT INTO biz_equipment_type VALUES(?,?,?,?,?,1)", "TP" + suffix, "测试内机", "TPT", "INDOOR_UNIT", "TEST");
        jdbc.update("INSERT INTO biz_point_naming_rule(rule_id,standard_version,family_code,component_code,code_template,standard_source,status) VALUES(?,?,?,?,?,?,1)",
                "TPR" + suffix, "TEST", "TPT", "MAIN", "TPT[n]", "TEST");
        jdbc.update("INSERT INTO biz_data_source(source_id,source_code,source_name,building_id,source_category,transport_type,status,config_revision,runtime_revision) VALUES(?,?,?,'BLD001','DEVICE_ACCESS','HTTP','ENABLED',1,1)",
                http, http, "温度隔离来源");
        for (var duty : List.of(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER, BackendDuty.BACKOFFICE_CHANGE_REVIEWER)) {
            jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by,created_at) VALUES(?,1,?,'ACTIVE',?,1,?)",
                    TemperaturePlanService.id(), duty.name(), LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
        }
        directory.registerSource(source, 1L, ADMIN);
        directory.mapProject(source, "site", "BLD001", 1L, ADMIN);
        template = createProduct("TPL", List.of(point("roomTemp"), point("temperature")));
        asset = createProduct("AST", List.of());
        pending = discover("1");
        var rule = new Rule(null, "DAIKIN_INDOOR_V2", "BLD001", source, "", template, http, 0, true);
        approve(service.ruleRequest(1L, ADMIN, new RuleRequest(rule, "rule" + suffix)).requestId());
    }

    @Test void automaticAndManualPlansCreateEquivalentPointsAndKeepLegacyZeroPointBinding() {
        var input = new Input(pending, "AUTO", null, null, Map.of(), binding(null, null));
        var automatic = plans.preview(input);
        var manual = plans.preview(new Input(pending, "MANUAL", template, http, Map.of(), binding(null, null)));
        assertThat(automatic.points()).isEqualTo(manual.points());
        assertThat(countPoints()).isZero();
        var result = onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        assertThat(result.pointIds()).isEmpty();
        assertThat(countPoints()).isZero();
    }

    @Test void newDeviceUsesExistingApprovalAndCreatesTwoTemperaturePoints() {
        var preview = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), binding(null, null)));
        var application = scoped.apply(1L, ADMIN, pending, binding("AUTO", preview.digest()), "new" + suffix);
        assertThat(jdbc.queryForObject("SELECT impact_summary FROM sys_sensitive_change_request WHERE request_id=?", String.class,
                application.requestId())).contains("pointCount=2");
        assertThat(countPoints()).isZero();
        approve(application.requestId());
        assertThat(countPoints()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT data_type FROM biz_data_point WHERE family_code='TPT'", String.class)).containsOnly("AI");
        assertThat(jdbc.queryForObject("SELECT status FROM biz_device_identity WHERE identity_id=(SELECT bound_identity_id FROM biz_pending_device WHERE pending_id=?)", Integer.class, pending)).isZero();
    }

    @Test void retrofitPreservesAssetProductAndIdentityAndRepeatedPreviewReusesBothPoints() {
        var bound = onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        var preview = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        var request = new BatchRequest("job" + suffix, List.of(item(pending, preview)));
        assertThat(service.latestJob(1L, ADMIN, pending)).isNull();
        var job = service.createJob(1L, ADMIN, request);
        assertThat(service.latestJob(1L, ADMIN, pending).jobId()).isEqualTo(job.jobId());
        assertThat(service.createJob(1L, ADMIN, request).jobId()).isEqualTo(job.jobId());
        approve(job.items().getFirst().requestId());
        cache.refreshAll();
        assertThat(service.job(1L, ADMIN, job.jobId()).items().getFirst().configurationStatus()).isEqualTo("CONFIGURED");
        assertThat(service.job(1L, ADMIN, job.jobId()).items().getFirst().samplingStatus()).isEqualTo("WAITING_ACTIVATION");
        assertThat(jdbc.queryForObject("SELECT product_id FROM biz_equipment WHERE equip_id=?", String.class, bound.equipmentId())).isEqualTo(asset);
        var repeated = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        assertThat(repeated.status()).isEqualTo("COMPLETE");
        var again = service.createJob(1L, ADMIN, new BatchRequest("again" + suffix, List.of(item(pending, repeated))));
        approve(again.items().getFirst().requestId());
        assertThat(countPoints()).isEqualTo(2);
    }

    @Test void approvalRejectsChangedRuleAndPreviewHasNoSideEffects() {
        onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        var preview = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        var job = service.createJob(1L, ADMIN, new BatchRequest("stale" + suffix, List.of(item(pending, preview))));
        jdbc.update("UPDATE biz_temperature_rule SET revision=revision+1 WHERE source_scope=?", source);
        assertThatThrownBy(() -> approve(job.items().getFirst().requestId())).isInstanceOf(BusinessException.class);
        assertThat(countPoints()).isZero();
    }

    @Test void conflictsAndCrossBuildingSourcesAreRejectedWithoutCreatingPoints() {
        var bound = onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        assertThatThrownBy(() -> plans.preview(new Input(pending, "MANUAL", template, "SOURCE_MQTT_FREEZE_V1", Map.of(), null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> plans.preview(new Input(pending, "MANUAL", template, http, Map.of("roomTemp", "POINT001"), null)))
                .isInstanceOf(BusinessException.class);
        var preview = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        jdbc.update("UPDATE biz_equipment SET space_id='SPACE002' WHERE equip_id=?", bound.equipmentId());
        sqlSession.clearCache(); // 模拟下一次 HTTP 请求的新会话，避免测试外层事务保留 MyBatis 一级缓存。
        assertThatThrownBy(() -> plans.validate(new Input(pending, "AUTO", null, null, Map.of(), null), preview.digest(), false))
                .isInstanceOf(BusinessException.class);
        assertThat(countPoints()).isZero();
    }

    @Test void missingRuleRequiresManualSelectionAndExpiredPreviewCannotBeSubmitted() {
        jdbc.update("DELETE FROM biz_temperature_rule WHERE source_scope=?", source);
        assertThatThrownBy(() -> plans.preview(new Input(pending, "AUTO", null, null, Map.of(), binding(null, null))))
                .isInstanceOf(BusinessException.class);
        var preview = plans.preview(new Input(pending, "MANUAL", template, http, Map.of(), binding(null, null)));
        assertThat(preview.status()).isEqualTo("READY");
        assertThatThrownBy(() -> plans.validate(new Input(pending, "MANUAL", template, http, Map.of(), binding(null, null)),
                "1000000000000" + preview.digest().substring(13), true)).isInstanceOf(BusinessException.class);
    }

    @Test void explicitStateOnlyDoesNotCreatePointsEvenWhenSelectedProductHasRequiredTemplates() {
        var request = new DeviceOnboardingContracts.TypedBindRequest(template, "BLD001", "SPACE001", "GROUP001", null,
                new DeviceOnboardingContracts.NewEquipmentRequest("测试内机", "大金"), List.of(), null,
                "STATE_ONLY", null, null, Map.of());
        var bound = onboarding.bindTyped(pending, request, 1L, ADMIN);
        assertThat(bound.pointIds()).isEmpty();
    }

    @Test void optionsAndJobsCannotBeReadWithoutBuildingPermission() {
        assertThatThrownBy(() -> service.options(9999L, Set.of("OPERATIONS"), pending)).isInstanceOf(BusinessException.class);
        onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        var plan = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        var job = service.createJob(1L, ADMIN, new BatchRequest("private" + suffix, List.of(item(pending, plan))));
        assertThatThrownBy(() -> service.job(9999L, Set.of("OPERATIONS"), job.jobId())).isInstanceOf(BusinessException.class);
    }

    @Test void existingSinglePointIsReusedAndOnlyMissingPointIsCreated() {
        onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        String single = createProduct("ONE", List.of(point("roomTemp")));
        var first = plans.preview(new Input(pending, "MANUAL", single, http, Map.of(), null));
        var job = service.createJob(1L, ADMIN, new BatchRequest("one" + suffix,
                List.of(new Item(pending, "MANUAL", single, http, Map.of(), first.digest()))));
        approve(job.items().getFirst().requestId());
        String original = jdbc.queryForObject("SELECT point_id FROM biz_temperature_binding WHERE metric_code='roomTemp' AND identity_id=(SELECT bound_identity_id FROM biz_pending_device WHERE pending_id=?)", String.class, pending);
        var complement = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        assertThat(complement.points()).extracting(PointPlan::action).containsExactly("REUSE", "CREATE");
        var second = service.createJob(1L, ADMIN, new BatchRequest("two" + suffix, List.of(item(pending, complement))));
        approve(second.items().getFirst().requestId());
        assertThat(countPoints()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT point_id FROM biz_temperature_binding WHERE metric_code='roomTemp' AND identity_id=(SELECT bound_identity_id FROM biz_pending_device WHERE pending_id=?)", String.class, pending)).isEqualTo(original);
    }

    @Test void sourceDisableAllowsRuleDisableAndPreventsFutureAutomaticBinding() {
        var rule = plans.rules("BLD001", "DAIKIN_INDOOR_V2").stream().filter(r -> r.sourceScope().equals(source)).findFirst().orElseThrow();
        jdbc.update("UPDATE biz_data_source SET status='DISABLED' WHERE source_id=?", http);
        sqlSession.clearCache();
        var disabled = new Rule(rule.ruleId(), rule.adapterId(), rule.buildingId(), rule.sourceScope(), rule.model(),
                rule.templateProductId(), rule.numericSourceId(), rule.revision(), false);
        approve(service.ruleRequest(1L, ADMIN, new RuleRequest(disabled, "disable" + suffix)).requestId());
        assertThatThrownBy(() -> plans.preview(new Input(pending, "AUTO", null, null, Map.of(), binding(null, null)))).isInstanceOf(BusinessException.class);
    }

    @Test void runtimeCacheFailureIsReportedSeparatelyFromCommittedBinding() {
        onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        var preview = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        var job = service.createJob(1L, ADMIN, new BatchRequest("cache" + suffix, List.of(item(pending, preview))));
        approve(job.items().getFirst().requestId());
        // 外层测试事务尚未提交，afterCommit 未执行；查询必须如实显示缓存未加载，而不是重新建点。
        assertThat(service.job(1L, ADMIN, job.jobId()).items().getFirst().configurationStatus()).isEqualTo("CACHE_PENDING");
        assertThat(service.retry(1L, ADMIN, job.jobId(), List.of(pending)).items().getFirst().configurationStatus()).isEqualTo("CONFIGURED");
        assertThat(countPoints()).isEqualTo(2);
    }

    @Test void twoPendingPlansForSameIdentityCannotCreateDuplicatePointSets() {
        onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        var preview = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        var first = service.createJob(1L, ADMIN, new BatchRequest("first" + suffix, List.of(item(pending, preview))));
        var second = service.createJob(1L, ADMIN, new BatchRequest("second" + suffix, List.of(item(pending, preview))));
        approve(first.items().getFirst().requestId());
        assertThatThrownBy(() -> approve(second.items().getFirst().requestId())).isInstanceOf(BusinessException.class);
        assertThat(countPoints()).isEqualTo(2);
        assertThat(service.job(1L, ADMIN, second.jobId()).items().getFirst().configurationStatus()).isEqualTo("PLAN_EXPIRED");
    }

    @Test void oneExpiredDeviceDoesNotInvalidateOtherApprovedDeviceInBatch() {
        String other = discover("2");
        onboarding.bindTyped(pending, binding(null, null), 1L, ADMIN);
        var otherBound = onboarding.bindTyped(other, binding(null, null), 1L, ADMIN);
        var first = plans.preview(new Input(pending, "AUTO", null, null, Map.of(), null));
        var second = plans.preview(new Input(other, "AUTO", null, null, Map.of(), null));
        var job = service.createJob(1L, ADMIN, new BatchRequest("partial" + suffix, List.of(item(pending, first), item(other, second))));
        jdbc.update("UPDATE biz_equipment SET space_id='SPACE002' WHERE equip_id=?", otherBound.equipmentId());
        sqlSession.clearCache();
        approve(job.items().stream().filter(i -> i.pendingId().equals(pending)).findFirst().orElseThrow().requestId());
        assertThatThrownBy(() -> approve(job.items().stream().filter(i -> i.pendingId().equals(other)).findFirst().orElseThrow().requestId()))
                .isInstanceOf(BusinessException.class);
        assertThat(countPoints()).isEqualTo(2);
    }

    private int countPoints() { return jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point WHERE family_code='TPT'", Integer.class); }
    private void approve(String request) { changes.approve(1L, request, "隔离测试审批"); changes.execute(1L, request); }
    private Item item(String id, PlanView preview) { return new Item(id, "AUTO", null, null, Map.of(), preview.digest()); }
    private DeviceOnboardingContracts.TypedBindRequest binding(String mode, String digest) {
        return new DeviceOnboardingContracts.TypedBindRequest(asset, "BLD001", "SPACE001", "GROUP001", null,
                new DeviceOnboardingContracts.NewEquipmentRequest("测试内机", "大金"), List.of(), null, mode, null, digest, Map.of());
    }
    private DeviceProductContracts.PointTemplateRequest point(String field) {
        return new DeviceProductContracts.PointTemplateRequest(field, field, field, "°C", null, null, false, true, 1, true);
    }
    private String createProduct(String code, List<DeviceProductContracts.PointTemplateRequest> points) {
        var product = products.createTypedState(new DeviceProductContracts.TypedStateRequest("TP" + code + suffix, "温度模板", "大金", null,
                "TP" + suffix, "DAIKIN_INDOOR_V2", points), 1L, ADMIN);
        return products.enable(product.productId(), 1L, ADMIN).productId();
    }
    private String discover(String unit) {
        Instant now = Instant.now();
        var units = new ArrayList<>(jdbc.queryForList("SELECT unit_id FROM biz_daikin_directory WHERE source_id=?", String.class, source));
        if (!units.contains(unit)) units.add(unit);
        directory.acceptCompleteCatalog(source, DaikinDeviceKey.Kind.INDOOR, now,
                units.stream().map(id -> new DaikinDeviceObservation(new DaikinDeviceKey(source, "site", "controller", DaikinDeviceKey.Kind.INDOOR, id),
                        "external-" + id, "测试项目", "测试内机", now, null, Map.of())).toList());
        return jdbc.queryForObject("SELECT pending_id FROM biz_daikin_directory WHERE source_id=? AND unit_id=?", String.class, source, unit);
    }
}
