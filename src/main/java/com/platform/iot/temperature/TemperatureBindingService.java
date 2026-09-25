package com.platform.iot.temperature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.sensitive.*;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.onboarding.ScopedDeviceOnboardingService;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.platform.iot.temperature.TemperatureContracts.*;
import static com.platform.iot.temperature.TemperaturePlanService.*;

/** 范围受控的温度配置申请与持久批量进度；审批状态始终从公共审批表读取。 */
@Service
@RequiredArgsConstructor
public class TemperatureBindingService {
    private final TemperaturePlanService plans;
    private final ScopedDeviceOnboardingService onboarding;
    private final BuildingScopeService scope;
    private final BackendDutyService duties;
    private final SensitiveChangeService changes;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final TemperatureHistoryVerifier history;
    private final TemperatureInitializationService initialization;

    public InitializationPlan previewInitialization(Long user, Set<String> roles, InitializationInput input) {
        requireInitializationAccess(user, roles, input);
        return initialization.preview(input);
    }

    private void requireInitializationAccess(Long user, Set<String> roles, InitializationInput input) {
        if (!roles.contains("PLATFORM_ADMIN")) throw new BusinessException(403, "TEMPERATURE_FORBIDDEN", "首次初始化需平台管理员操作");
        if (input == null || input.pendingIds() == null || input.pendingIds().isEmpty() || input.pendingIds().size() > 50) {
            throw invalid("请选择1至50台内机");
        }
        input.pendingIds().forEach(id -> onboarding.detail(user, roles, id));
    }

    /** 同一批引用同一审批单，幂等重试返回原任务，不产生重复审批或部分设备生效。 */
    public JobView initialize(Long user, Set<String> roles, InitializationRequest request) {
        requireInitializationAccess(user, roles, request.input());
        duties.requireDuty(user, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        String requestHash = hash(plans.write(request));
        String jobId;
        try {
            jobId = transaction.execute(status -> {
                var existing = jdbc.queryForList("SELECT job_id,request_hash FROM biz_temperature_job WHERE submitted_by=? AND idempotency_key=?",
                        user, request.idempotencyKey());
                if (!existing.isEmpty()) {
                    if (!requestHash.equals(existing.getFirst().get("request_hash"))) throw invalid("幂等键已用于其他批次");
                    return existing.getFirst().get("job_id").toString();
                }
                var bundle = initialization.validate(request, true);
                String job = id();
                jdbc.update("INSERT INTO biz_temperature_job(job_id,submitted_by,idempotency_key,request_hash,created_at_ms) VALUES(?,?,?,?,?)",
                        job, user, request.idempotencyKey(), requestHash, System.currentTimeMillis());
                var draft = changes.createDraft(user, "INITIALIZE_HVAC_TEMPERATURE", json.valueToTree(request), "temperature-init:" + job);
                var submitted = changes.submit(user, draft.requestId());
                for (String pendingId : request.pendingIds()) {
                    jdbc.update("INSERT INTO biz_temperature_job_item(job_id,pending_id,building_id,command_json,submission_status,request_id) VALUES(?,?,?,?,?,?)",
                            job, pendingId, bundle.context().buildingId(), plans.write(request), "SUBMITTED", submitted.requestId());
                }
                return job;
            });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            var existing = jdbc.queryForMap("SELECT job_id,request_hash FROM biz_temperature_job WHERE submitted_by=? AND idempotency_key=?",
                    user, request.idempotencyKey());
            if (!requestHash.equals(existing.get("request_hash"))) throw invalid("幂等键已用于其他批次");
            jobId = existing.get("job_id").toString();
        }
        return job(user, roles, jobId);
    }

    public Options options(Long user, Set<String> roles, String pending) {
        onboarding.detail(user, roles, pending);
        return plans.options(pending);
    }

    public List<PlanView> preview(Long user, Set<String> roles, List<Input> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 50) throw invalid("每批应为1至50台设备");
        return inputs.stream().map(input -> {
            // 权限失败不包装成包含设备详情的可见预览。
            onboarding.detail(user, roles, input.pendingId());
            try {
                if (input.binding() != null) onboarding.validateTemperatureBinding(user, roles, input.pendingId(), input.binding());
                return plans.preview(input);
            } catch (BusinessException ex) {
                return new PlanView(input.pendingId(), null, input.mode(), null, null, null, "BLOCKED",
                        ex.getMessage(), null, 0, List.of());
            }
        }).toList();
    }

    public Application ruleRequest(Long user, Set<String> roles, RuleRequest request) {
        if (!roles.contains("PLATFORM_ADMIN")) throw new BusinessException(403, "TEMPERATURE_FORBIDDEN", "仅平台管理员可维护匹配规则");
        scope.checkAccess(user, roles, request.rule().buildingId());
        Rule rule = plans.normalizeRule(request.rule());
        return transaction.execute(status -> {
            var draft = changes.createDraft(user, "CONFIGURE_TEMPERATURE_RULE", json.valueToTree(rule), request.idempotencyKey());
            var submitted = draft.status() == SensitiveChangeStatus.DRAFT ? changes.submit(user, draft.requestId()) : draft;
            return new Application(submitted.requestId(), submitted.status().name());
        });
    }

    public JobView createJob(Long user, Set<String> roles, BatchRequest request) {
        duties.requireDuty(user, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        if (request.items().stream().map(Item::pendingId).distinct().count() != request.items().size()) throw invalid("批次包含重复设备");
        for (Item item : request.items()) {
            onboarding.detail(user, roles, item.pendingId());
            if (!"BOUND".equals(plans.requirePending(item.pendingId()).getStatus())) throw invalid("补齐仅适用于已接入设备");
        }
        String requestHash = hash(plans.write(request.items()));
        String job;
        try {
            job = transaction.execute(status -> {
            var existing = jdbc.queryForList("SELECT job_id,request_hash FROM biz_temperature_job WHERE submitted_by=? AND idempotency_key=?",
                    user, request.idempotencyKey());
            if (!existing.isEmpty()) {
                if (!requestHash.equals(existing.getFirst().get("request_hash"))) throw invalid("幂等键已用于其他批次");
                return existing.getFirst().get("job_id").toString();
            }
            String jobId = id();
            jdbc.update("INSERT INTO biz_temperature_job(job_id,submitted_by,idempotency_key,request_hash,created_at_ms) VALUES(?,?,?,?,?)",
                    jobId, user, request.idempotencyKey(), requestHash, System.currentTimeMillis());
            for (Item item : request.items()) {
                var plan = plans.validate(item.input(), item.digest(), true);
                jdbc.update("INSERT INTO biz_temperature_job_item(job_id,pending_id,building_id,command_json,submission_status) VALUES(?,?,?,?,?)",
                        jobId, item.pendingId(), plan.context().buildingId(), plans.write(item), "PENDING");
            }
            return jobId;
            });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            var existing = jdbc.queryForMap("SELECT job_id,request_hash FROM biz_temperature_job WHERE submitted_by=? AND idempotency_key=?",
                    user, request.idempotencyKey());
            if (!requestHash.equals(existing.get("request_hash"))) throw invalid("幂等键已用于其他批次");
            job = existing.get("job_id").toString();
        }
        for (Item item : request.items()) submitItem(user, job, item.pendingId());
        return job(user, roles, job);
    }

    private void submitItem(Long user, String job, String pending) {
        try {
            transaction.executeWithoutResult(status -> {
                var row = jdbc.queryForMap("SELECT * FROM biz_temperature_job_item WHERE job_id=? AND pending_id=? FOR UPDATE", job, pending);
                if (row.get("request_id") != null) return;
                Item item = readItem(row.get("command_json").toString());
                plans.validate(item.input(), item.digest(), false);
                var draft = changes.createDraft(user, "BIND_HVAC_TEMPERATURE", json.valueToTree(item), "temperature:" + job + ":" + pending);
                var submitted = draft.status() == SensitiveChangeStatus.DRAFT ? changes.submit(user, draft.requestId()) : draft;
                jdbc.update("UPDATE biz_temperature_job_item SET request_id=?,submission_status='SUBMITTED',error_message=NULL WHERE job_id=? AND pending_id=?",
                        submitted.requestId(), job, pending);
            });
        } catch (BusinessException | org.springframework.dao.DataAccessException ex) {
            jdbc.update("UPDATE biz_temperature_job_item SET submission_status='FAILED',error_message=? WHERE job_id=? AND pending_id=? AND request_id IS NULL",
                    "配置发生冲突，请重新预览；临时失败可重试", job, pending);
        }
    }

    public JobView job(Long user, Set<String> roles, String jobId) {
        var owner = jdbc.queryForList("SELECT submitted_by FROM biz_temperature_job WHERE job_id=?", Long.class, jobId);
        if (owner.isEmpty() || !owner.getFirst().equals(user) && !roles.contains("PLATFORM_ADMIN")) {
            throw new BusinessException(404, "TEMPERATURE_JOB_NOT_FOUND", "任务不存在或不可见");
        }
        var rows = jdbc.queryForList("""
                SELECT i.*,r.status AS approval_status,r.operation_code FROM biz_temperature_job_item i
                LEFT JOIN sys_sensitive_change_request r ON r.request_id=i.request_id WHERE i.job_id=? ORDER BY i.pending_id
                """, jobId);
        List<JobItem> result = new ArrayList<>();
        for (var row : rows) {
            String building = row.get("building_id").toString();
            if (!scope.canAccess(user, roles, building)) continue;
            String pending = row.get("pending_id").toString();
            try { onboarding.detail(user, roles, pending); } catch (BusinessException ex) { continue; }
            String state = Objects.toString(row.get("approval_status"), row.get("submission_status").toString());
            String sampling = "NOT_CONFIGURED";
            String message = Objects.toString(row.get("error_message"), "");
            if ("EXECUTED".equals(state)) {
                state = plans.configurationStatus(pending);
                sampling = "CONFIGURED".equals(state) ? sampling(pending) : "WAITING_CONFIGURATION";
            } else if (Set.of("PENDING_REVIEW", "APPROVED", "EXECUTION_FAILED").contains(state)) {
                try {
                    if ("INITIALIZE_HVAC_TEMPERATURE".equals(row.get("operation_code"))) {
                        var frozen = TemperatureOperationHandlers.read(json,
                                TemperatureOperationHandlers.read(json, row.get("command_json").toString()), InitializationRequest.class);
                        initialization.validate(frozen, false);
                    } else {
                        Item frozen = readItem(row.get("command_json").toString());
                        plans.validate(frozen.input(), frozen.digest(), false);
                    }
                } catch (BusinessException ex) {
                    state = "PLAN_EXPIRED";
                    message = "配置已变化，请重新预览并提交申请";
                }
            }
            result.add(new JobItem(pending, (String) row.get("request_id"), state,
                    message, sampling));
        }
        return new JobView(jobId, List.copyOf(result));
    }

    /** 页面重开后恢复本人该设备最近的任务，结果仍逐项执行当前权限检查。 */
    public JobView latestJob(Long user, Set<String> roles, String pendingId) {
        onboarding.detail(user, roles, pendingId);
        var jobs = jdbc.queryForList("""
                SELECT j.job_id FROM biz_temperature_job j JOIN biz_temperature_job_item i ON i.job_id=j.job_id
                WHERE j.submitted_by=? AND i.pending_id=? ORDER BY j.created_at_ms DESC,j.job_id DESC LIMIT 1
                """, String.class, user, pendingId);
        return jobs.isEmpty() ? null : job(user, roles, jobs.getFirst());
    }

    public JobView retry(Long user, Set<String> roles, String job, List<String> pendingIds) {
        var view = job(user, roles, job);
        duties.requireDuty(user, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        for (String pending : pendingIds) {
            var item = view.items().stream().filter(i -> i.pendingId().equals(pending)).findFirst().orElseThrow(() -> invalid("任务项不可见"));
            onboarding.detail(user, roles, pending);
            if ("CACHE_PENDING".equals(item.configurationStatus())) plans.refreshCache();
            else if (item.requestId() == null && Set.of("FAILED", "PENDING").contains(item.configurationStatus())) {
                Long owner = jdbc.queryForObject("SELECT submitted_by FROM biz_temperature_job WHERE job_id=?", Long.class, job);
                if (!Objects.equals(owner, user)) throw invalid("只能由原提交人重试申请");
                submitItem(user, job, pending);
            } else throw invalid("该项不可直接重试；执行失败须重新预览并提交审批");
        }
        return job(user, roles, job);
    }

    private String sampling(String pendingId) {
        var pending = plans.requirePending(pendingId);
        Integer active = jdbc.queryForObject("SELECT status FROM biz_device_identity WHERE identity_id=?", Integer.class, pending.getBoundIdentityId());
        if (!Integer.valueOf(1).equals(active)) return "WAITING_ACTIVATION";
        // 只将绑定生效后的厂家状态检查点作为采样证据，不将旧温度或原始报文冒充新点历史。
        var statuses = jdbc.queryForList("""
                SELECT s.field_status,s.last_valid_at_ms,s.last_attempt_at_ms,b.effective_at_ms,b.point_id,b.building_id,b.equipment_id
                FROM biz_temperature_binding b LEFT JOIN biz_daikin_current_state s
                ON s.identity_id=b.identity_id AND s.field_name=b.metric_code AND s.building_id=b.building_id
                AND s.equipment_id=b.equipment_id WHERE b.identity_id=?
                """, pending.getBoundIdentityId());
        if (statuses.isEmpty()) return "WAITING_SAMPLE";
        boolean complete = true;
        for (var s : statuses) {
            if (s.get("last_attempt_at_ms") == null || ((Number) s.get("last_attempt_at_ms")).longValue() < ((Number) s.get("effective_at_ms")).longValue()) {
                complete = false; continue;
            }
            String state = Objects.toString(s.get("field_status"), "MISSING");
            if (!"PRESENT".equals(state)) return state;
            if (s.get("last_valid_at_ms") == null || ((Number) s.get("last_valid_at_ms")).longValue() < ((Number) s.get("effective_at_ms")).longValue()) {
                complete = false;
                continue;
            }
            String verified = history.verify(s.get("building_id").toString(), s.get("equipment_id").toString(),
                    s.get("point_id").toString(), "DAIKIN_V2", ((Number) s.get("last_valid_at_ms")).longValue());
            if (!"VALID_SAMPLE".equals(verified)) return verified;
        }
        return complete ? "VALID_SAMPLE" : "WAITING_SAMPLE";
    }

    private Item readItem(String value) {
        try { return json.readValue(value, Item.class); } catch (Exception ex) { throw new IllegalStateException("TEMPERATURE_JOB_CORRUPTED", ex); }
    }
}
