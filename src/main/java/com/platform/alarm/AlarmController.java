package com.platform.alarm;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.alarm.AlarmModels.*;

@RestController
@RequestMapping("/v1/alarms/buildings/{buildingId}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
/** 三个界面共用建筑隔离契约，屏蔽及确认仅写报警域。 */
public class AlarmController {
    private final AlarmService service;
    @GetMapping("/summary")
    public Result<Summary> summary(Authentication auth, @PathVariable String buildingId) {
        return Result.success(service.summary(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId));
    }
    @GetMapping("/rules")
    public Result<List<Rule>> rules(Authentication auth, @PathVariable String buildingId) {
        return Result.success(service.rules(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId));
    }
    @GetMapping("/rules/options")
    public Result<RuleOptions> ruleOptions(Authentication auth,@PathVariable String buildingId) {
        return Result.success(service.ruleOptions(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId));
    }
    @PostMapping("/rules")
    public Result<String> createRule(Authentication auth,@PathVariable String buildingId,@RequestBody RuleRequest request) {
        return Result.success(service.createRule(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,request));
    }
    @GetMapping("/rule-templates")
    public Result<List<AlarmTemplateCatalog.Descriptor>> templates(Authentication auth,@PathVariable String buildingId) {
        return Result.success(service.templates(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId));
    }
    @PutMapping("/rules/{id}")
    public Result<Void> updateRule(Authentication auth, @PathVariable String buildingId, @PathVariable String id, @RequestBody RuleUpdateBody body) {
        if(body.name()!=null) {
            RuleRequest request=new RuleRequest(body.type(),body.name(),body.severity(),body.qualityLevel(),body.spaceId(),body.deviceCategory(),
                    Boolean.TRUE.equals(body.enabled()),body.parameter()==null?0:body.parameter(),body.revision(),body.recoveryCycles(),body.triggerMinutes(),
                    body.recoveryMinutes(),body.conditionLogic(),body.classification(),body.conditions(),body.advanced());
            service.updateRule(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,id,request);
        } else {
            SaveRule request=new SaveRule(Boolean.TRUE.equals(body.enabled()),body.parameter()==null?0:body.parameter(),body.revision()==null?0:body.revision());
            service.saveRule(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,id,request);
        }
        return Result.success();
    }
    public record RuleUpdateBody(String type,String name,String severity,String qualityLevel,String spaceId,String deviceCategory,
            Boolean enabled,Integer parameter,Long revision,Integer recoveryCycles,Integer triggerMinutes,Integer recoveryMinutes,
            String conditionLogic,String classification,List<Condition> conditions,Advanced advanced) { }
    @GetMapping("/rules/{type}/history")
    public Result<List<java.util.Map<String, Object>>> ruleHistory(Authentication auth, @PathVariable String buildingId, @PathVariable String type) {
        return Result.success(service.ruleHistory(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, type));
    }
    @GetMapping("/state-dictionaries")
    public Result<List<StateDictionary>> dictionaries(Authentication auth,@PathVariable String buildingId) {
        return Result.success(service.dictionaries(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId));
    }
    @PostMapping("/state-dictionaries")
    public Result<String> createDictionary(Authentication auth,@PathVariable String buildingId,@RequestBody SaveDictionary body) {
        return Result.success(service.createDictionary(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,body));
    }
    @PutMapping("/state-dictionaries/{id}")
    public Result<Void> updateDictionary(Authentication auth,@PathVariable String buildingId,@PathVariable String id,@RequestBody SaveDictionary body) {
        service.updateDictionary(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,id,body); return Result.success();
    }
    @PostMapping("/state-dictionaries/{id}/apply")
    public Result<Integer> applyDictionary(Authentication auth,@PathVariable String buildingId,@PathVariable String id,@RequestBody DictionaryBinding body) {
        if(body.dictionaryRevision()==null) throw new com.platform.framework.exception.BusinessException("字典版本必填");
        return Result.success(service.applyDictionary(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,id,body.dictionaryRevision()));
    }
    @PostMapping("/points/{pointId}/state-dictionaries")
    public Result<String> createBoundDictionary(Authentication auth,@PathVariable String buildingId,@PathVariable String pointId,@RequestBody SaveDictionary body) {
        return Result.success(service.createBoundDictionary(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,pointId,body));
    }
    @PutMapping("/points/{pointId}/state-dictionary")
    public Result<Void> bindDictionary(Authentication auth,@PathVariable String buildingId,@PathVariable String pointId,@RequestBody DictionaryBinding body) {
        service.bindDictionary(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,pointId,body); return Result.success();
    }
    @PostMapping("/events/{id}/close")
    public Result<Void> close(Authentication auth,@PathVariable String buildingId,@PathVariable String id,@RequestBody Note body) {
        service.closeEvent(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,id,body.note()); return Result.success();
    }
    @GetMapping("/events/{id}/trend")
    public Result<List<TrendSeries>> trend(Authentication auth,@PathVariable String buildingId,@PathVariable String id,@RequestParam long from,@RequestParam long to) {
        return Result.success(service.trend(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,id,from,to));
    }
    @GetMapping("/events")
    public Result<PageResponse<Event>> events(Authentication auth, @PathVariable String buildingId,
            @RequestParam(defaultValue="false") boolean history, @RequestParam(defaultValue="DEVICE") String type,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String alarmType, @RequestParam(required=false) String state,
            @RequestParam(required=false) String keyword, @RequestParam(required=false) Long fromAt,
            @RequestParam(required=false) Long toAt) {
        return Result.success(service.events(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, history, type, page, size,
                alarmType, state, keyword, fromAt, toAt));
    }
    @GetMapping("/events/{id}")
    public Result<Event> detail(Authentication auth, @PathVariable String buildingId, @PathVariable String id) {
        return Result.success(service.detail(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, id));
    }
    @PostMapping("/events/{id}/ack")
    public Result<Void> ack(Authentication auth, @PathVariable String buildingId, @PathVariable String id) {
        service.action(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, id, "ACK", null, 0);
        return Result.success();
    }
    @PostMapping("/events/{id}/note")
    public Result<Void> note(Authentication auth, @PathVariable String buildingId, @PathVariable String id, @RequestBody Note request) {
        service.action(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, id, "NOTE", request.note(), 0);
        return Result.success();
    }
    @PostMapping("/events/{id}/silence")
    public Result<Void> silence(Authentication auth, @PathVariable String buildingId, @PathVariable String id, @RequestBody Silence request) {
        service.action(SecurityUser.userId(auth), SecurityUser.roles(auth), buildingId, id, "SILENCE", request.note(), request.minutes());
        return Result.success();
    }
}
