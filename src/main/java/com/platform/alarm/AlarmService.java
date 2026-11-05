package com.platform.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.platform.alarm.AlarmModels.*;

@Service
@Slf4j
@Transactional
/**
 * 报警 MySQL 事务边界。按建筑锁定规则、判定与人工操作，多实例不会重复开事件。
 * 数据源失败只更新可用性，不将既有事件误恢复；确认、备注、屏蔽不产生任何设备指令。
 */
public class AlarmService {
    private static final String LIVE = "/operations/alarms/liveAlarms";
    private static final String HISTORY = "/operations/alarms/historyAlarms";
    private static final String CONFIG = "/configuration/rules/alarmRules";
    private static final long FRESH_MS = 120_000;
    private final JdbcTemplate jdbc;
    private final AlarmAccess access;
    private final AlarmDeviceSource devices;
    private final List<MonthlyQuotaSource> quotas;
    private final ObjectMapper json;
    private final Clock clock;
    @Autowired(required=false)
    private AlarmDictionaryService dictionaries;
    @Autowired(required=false)
    private AlarmEmissionEvaluator emissionEvaluator;
    @Autowired(required=false)
    private AlarmTemplateCatalog templateCatalog;
    @Autowired(required=false)
    private com.platform.iot.temporal.HvacRawEventRepository rawEvents;

    @Autowired
    public AlarmService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AlarmAccess access,
            AlarmDeviceSource devices, List<MonthlyQuotaSource> quotas, ObjectMapper json) {
        this(jdbc, access, devices, quotas, json, Clock.systemUTC());
    }
    AlarmService(JdbcTemplate jdbc, AlarmAccess access, AlarmDeviceSource devices,
            List<MonthlyQuotaSource> quotas, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.access = access; this.devices = devices;
        this.quotas = quotas; this.json = json; this.clock = clock;
    }

    public List<Rule> rules(Long user, Set<String> roles, String building) {
        access.require(user, roles, building, CONFIG, false);
        initialize(building);
        return loadRules(building);
    }

    public List<AlarmTemplateCatalog.Descriptor> templates(Long user,Set<String> roles,String building) {
        access.require(user,roles,building,CONFIG,false);
        return templateCatalog().list();
    }

    public RuleOptions ruleOptions(Long user, Set<String> roles, String building) {
        access.require(user, roles, building, CONFIG, false);
        initialize(building);
        List<Option> spaces = jdbc.query("SELECT space_id,space_name FROM biz_space WHERE building_id=? AND del_flag=0 ORDER BY space_name,space_id",
                (rs, n) -> new Option(rs.getString(1), rs.getString(2)), building);
        List<Option> categories = jdbc.query("""
                SELECT e.equip_category,COALESCE(GROUP_CONCAT(DISTINCT t.type_name ORDER BY t.type_name SEPARATOR '、'),e.equip_category)
                FROM biz_equipment e LEFT JOIN biz_equipment_type t ON t.type_code=e.type_code
                WHERE e.building_id=? AND e.del_flag=0 AND e.equip_category IS NOT NULL AND e.equip_category<>''
                GROUP BY e.equip_category ORDER BY 2,e.equip_category
                """,(rs,n)->new Option(rs.getString(1),rs.getString(2)),building);
        List<PointOption> points=jdbc.query("""
                SELECT p.point_id,CONCAT(e.equip_name,' · ',p.point_name),p.unit,p.data_type,e.equip_id,e.space_id,e.equip_category
                FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0
                WHERE p.building_id=? AND p.status='ONLINE' AND p.del_flag=0 ORDER BY e.equip_name,p.point_name,p.point_id
                """,(rs,n)->new PointOption(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),building);
        List<Option> equipment=jdbc.query("SELECT equip_id,equip_name FROM biz_equipment WHERE building_id=? AND del_flag=0 ORDER BY equip_name,equip_id",
                (rs,n)->new Option(rs.getString(1),rs.getString(2)),building);
        List<PointOption> resolved=new ArrayList<>();
        for(PointOption point:points) {
            DictionaryBinding binding=dictionaryService().binding(building,point.value());
            List<StateOption> states=binding==null?List.of():dictionaryService().getVersion(building,binding.dictionaryId(),binding.dictionaryRevision()).states();
            resolved.add(new PointOption(point.value(),point.label(),point.unit(),point.dataType(),point.equipmentId(),point.spaceId(),point.deviceCategory(),
                    binding==null?null:binding.dictionaryId(),binding==null?null:binding.dictionaryRevision(),states));
        }
        return new RuleOptions(spaces, categories,resolved,equipment);
    }

    public String createRule(Long user, Set<String> roles, String building, RuleRequest request) {
        access.require(user, roles, building, CONFIG, true);
        request=withDefaults(request,2);
        if(request==null) throw new BusinessException("规则内容不能为空");
        request=resolveDictionaries(building,request);
        validateRule(request, building);
        if (request.revision() != null && request.revision() != 0) throw new BusinessException("新规则版本必须从1开始");
        initialize(building); lock(building);
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        jdbc.update("INSERT INTO biz_alarm_rule(building_id,rule_id,rule_type,rule_name,severity,quality_level,space_id,device_category,enabled,parameter_value,revision,updated_at,updated_by,recovery_cycles,trigger_minutes,recovery_minutes,condition_logic,classification,conditions_json,advanced_json) VALUES (?,?,?,?,?,?,?,?,?, ?,1,?,?,?,?,?,?,?,?,?)",
                building,id,request.type(),request.name().trim(),request.severity(),request.qualityLevel(),request.spaceId(),request.deviceCategory(),request.enabled()?1:0,request.parameter(),now,user,
                request.recoveryCycles(),request.triggerMinutes(),request.recoveryMinutes(),request.conditionLogic(),request.classification(),conditionJson(request.conditions()),advancedJson(request.advanced()));
        insertRuleVersion(building,id,request,1,now,user);
        jdbc.update("UPDATE biz_alarm_scope SET evaluated_at=NULL WHERE building_id=?",building);
        if (!request.enabled()) recoverRule(building,id,now,"规则停用");
        return id;
    }

    public void updateRule(Long user, Set<String> roles, String building, String id, RuleRequest request) {
        access.require(user, roles, building, CONFIG, true);
        if(request==null) throw new BusinessException("规则内容不能为空");
        initialize(building); lock(building);
        Rule previous = findRule(building,id);
        request=withPreviousDefaults(request,previous);
        request=resolveDictionaries(building,request);
        validateRule(request,building);
        if(!previous.type().equals(request.type())) throw new BusinessException("规则模板类型不可更改");
        if (request.revision() == null || previous.revision() != request.revision()) throw new BusinessException(409,"规则已更新，请刷新后重试");
        long now=clock.millis(); long revision=previous.revision()+1;
        jdbc.update("UPDATE biz_alarm_rule SET rule_type=?,rule_name=?,severity=?,quality_level=?,space_id=?,device_category=?,enabled=?,parameter_value=?,revision=?,updated_at=?,updated_by=?,recovery_cycles=?,trigger_minutes=?,recovery_minutes=?,condition_logic=?,classification=?,conditions_json=?,advanced_json=? WHERE building_id=? AND rule_id=?",
                request.type(),request.name().trim(),request.severity(),request.qualityLevel(),request.spaceId(),request.deviceCategory(),request.enabled()?1:0,request.parameter(),revision,now,user,
                request.recoveryCycles(),request.triggerMinutes(),request.recoveryMinutes(),request.conditionLogic(),request.classification(),conditionJson(request.conditions()),advancedJson(request.advanced()),building,id);
        // 修订冻结所用字典、条件和数据策略；已发生事件继续引用旧版本快照。
        insertRuleVersion(building,id,request,revision,now,user);
        jdbc.update("DELETE FROM biz_alarm_rule_state WHERE building_id=? AND rule_id=?",building,id);
        jdbc.update("UPDATE biz_alarm_scope SET evaluated_at=NULL WHERE building_id=?",building);
        if (!request.enabled()) recoverRule(building,id,now,"规则停用");
    }

    private void validateRule(RuleRequest request,String building) {
        if(request==null) throw new BusinessException("规则内容不能为空");
        requireRuleType(request.type());
        if(request.qualityLevel()==null||!Set.of("Q0","Q1","Q2").contains(request.qualityLevel())) throw new BusinessException("质量要求无效");
        if(blank(request.name())||request.name().trim().length()>100) throw new BusinessException("规则名称须为1至100字");
        if(request.parameter()<1||request.parameter()>100||request.type().equals("FAULT")&&request.parameter()!=1) throw new BusinessException("报警参数无效");
        if(request.recoveryCycles()!=null&&(request.recoveryCycles()<1||request.recoveryCycles()>100)) throw new BusinessException("恢复周期须为1至100");
        if(request.triggerMinutes()!=null&&(request.triggerMinutes()<0||request.triggerMinutes()>10080)||request.recoveryMinutes()!=null&&(request.recoveryMinutes()<0||request.recoveryMinutes()>10080)) throw new BusinessException("持续时间须为0至10080分钟");
        if(Set.of("CONDITION","JUMP","STUCK").contains(request.type())&&!Set.of("VALUE","FAULT").contains(request.classification())) throw new BusinessException("条件规则分类无效");
        for(Condition condition:request.conditions()) if(condition!=null) validateStateChoices(building,condition);
        if(request.advanced()!=null) {
            for(Condition condition:nodeConditions(request.advanced().trigger())) validateStateChoices(building,condition);
            for(Condition condition:nodeConditions(request.advanced().recovery())) validateStateChoices(building,condition);
        }
        if(request.type().equals("EMISSION")) templateCatalog().validate(request);
        else if(request.advanced()!=null||Set.of("JUMP","STUCK").contains(request.type())) {
            new AlarmDefinitionValidator(jdbc).validate(building,request);
            if(request.type().equals("QUALITY")) validateConditions(request,building);
        }
        else if(request.type().equals("QUALITY")||request.type().equals("CONDITION")) validateConditions(request,building);
        else if(request.conditions()!=null&&!request.conditions().isEmpty()) throw new BusinessException("此规则类型不支持测点条件");
        else if(request.classification()!=null&&!Objects.equals(request.classification(),request.type())) throw new BusinessException("规则分类必须与模板类型一致");
        if(Set.of("QUOTA","EMISSION").contains(request.type())) {
            if(request.severity()!=null&&!request.severity().isBlank()) throw new BusinessException("配额规则使用自动严重度");
        } else if(request.severity()==null||!Set.of("URGENT","IMPORTANT","GENERAL","INFO").contains(request.severity())) throw new BusinessException("严重度无效");
        if(request.spaceId()!=null&&!request.spaceId().isBlank()) {
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM biz_space WHERE building_id=? AND space_id=? AND del_flag=0",Long.class,building,request.spaceId());
            if(count==null||count==0) throw new BusinessException("适用空间不存在或不属于当前建筑");
        }
        if(request.deviceCategory()!=null&&!request.deviceCategory().isBlank()) {
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment WHERE building_id=? AND equip_category=? AND del_flag=0",Long.class,building,request.deviceCategory());
            if(count==null||count==0) throw new BusinessException("设备类别不存在或不属于当前建筑");
        }
        if(Set.of("QUOTA","EMISSION").contains(request.type())&&(request.spaceId()!=null||request.deviceCategory()!=null)) throw new BusinessException("配额规则不支持空间或设备类别范围");
    }

    private RuleRequest withDefaults(RuleRequest value,int recoveryDefault) {
        if(value==null) return null;
        String quality=value.qualityLevel()==null?switch(Objects.toString(value.type(),"")){case "OFFLINE"->"Q2";case "FAULT"->"Q1";case "QUOTA","QUALITY","CONDITION","JUMP","STUCK","EMISSION"->"Q0";default->null;}:value.qualityLevel();
        String classification="QUALITY".equals(value.type())?"QUALITY":value.classification()==null?switch(Objects.toString(value.type(),"")){case "OFFLINE"->"OFFLINE";case "FAULT"->"FAULT";case "CONDITION","JUMP","STUCK"->"VALUE";default->null;}:value.classification();
        return new RuleRequest(value.type(),value.name(),value.severity(),quality,value.spaceId(),value.deviceCategory(),value.enabled(),value.parameter(),value.revision(),
                value.recoveryCycles()==null?recoveryDefault:value.recoveryCycles(),value.triggerMinutes()==null?0:value.triggerMinutes(),value.recoveryMinutes()==null?0:value.recoveryMinutes(),
                value.conditionLogic()==null?"ALL":value.conditionLogic(),classification,normalizeConditions(value.conditions()),value.advanced());
    }
    private static List<Condition> normalizeConditions(List<Condition> conditions) {
        if(conditions==null) return List.of();
        return conditions.stream().map(condition->{
            if(condition==null||condition.states().isEmpty()) return condition;
            List<StateOption> states=condition.states().stream().map(state->state==null||state.label()==null?state:
                    new StateOption(state.value(),state.label().trim())).toList();
            return new Condition(condition.pointId(),condition.operator(),condition.value(),condition.upperValue(),states,condition.dictionaryId(),condition.dictionaryRevision());
        }).toList();
    }
    private RuleRequest withPreviousDefaults(RuleRequest value,Rule previous) {
        RuleRequest defaults=withDefaults(value,previous.recoveryCycles()==null?1:previous.recoveryCycles());
        return new RuleRequest(defaults.type(),defaults.name(),defaults.severity(),defaults.qualityLevel(),defaults.spaceId(),defaults.deviceCategory(),defaults.enabled(),defaults.parameter(),defaults.revision(),
                value.recoveryCycles()==null?previous.recoveryCycles():defaults.recoveryCycles(),value.triggerMinutes()==null?previous.triggerMinutes():defaults.triggerMinutes(),
                value.recoveryMinutes()==null?previous.recoveryMinutes():defaults.recoveryMinutes(),value.conditionLogic()==null?previous.conditionLogic():defaults.conditionLogic(),
                value.classification()==null?previous.classification():defaults.classification(),value.conditions()==null?previous.conditions():defaults.conditions(),value.advanced()==null?previous.advanced():value.advanced());
    }
    private void insertRuleVersion(String building,String id,RuleRequest r,long revision,long at,Long actor) {
        jdbc.update("INSERT INTO biz_alarm_rule_version(building_id,rule_id,rule_type,rule_name,severity,quality_level,space_id,device_category,revision,enabled,parameter_value,changed_at,changed_by,recovery_cycles,trigger_minutes,recovery_minutes,condition_logic,classification,conditions_json,advanced_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                building,id,r.type(),r.name(),r.severity(),r.qualityLevel(),r.spaceId(),r.deviceCategory(),revision,r.enabled()?1:0,r.parameter(),at,actor,
                r.recoveryCycles(),r.triggerMinutes(),r.recoveryMinutes(),r.conditionLogic(),r.classification(),conditionJson(r.conditions()),advancedJson(r.advanced()));
    }
    private AlarmDictionaryService dictionaryService() {
        return dictionaries==null?new AlarmDictionaryService(jdbc,access,json,clock):dictionaries;
    }
    private AlarmEmissionEvaluator emissionEvaluator() {
        return emissionEvaluator==null?new AlarmEmissionEvaluator(List.of(),json):emissionEvaluator;
    }
    private AlarmTemplateCatalog templateCatalog() {
        return templateCatalog==null?new AlarmTemplateCatalog(List.of()):templateCatalog;
    }
    private String advancedJson(Advanced value) {
        try { return value==null?null:json.writeValueAsString(value); }
        catch(JsonProcessingException exception) { throw new IllegalStateException("无法保存扩展规则",exception); }
    }
    private static Advanced readAdvanced(String value) {
        if(value==null||value.isBlank()) return null;
        try { return new ObjectMapper().readValue(value,Advanced.class); }
        catch(JsonProcessingException exception) { throw new IllegalStateException("扩展规则定义无法解析",exception); }
    }
    private ConditionNode resolveNode(String building,ConditionNode node) { return resolveNode(building,node,1); }
    private ConditionNode resolveNode(String building,ConditionNode node,int depth) {
        if(depth>3) throw new BusinessException("条件分组最多三级");
        if(node==null) return null;
        if(node.condition()!=null) return new ConditionNode(node.logic(),node.children(),dictionaryService().resolve(building,normalizeConditions(List.of(node.condition())).getFirst()));
        if(node.children()==null) throw new BusinessException("条件分组缺少子项");
        return new ConditionNode(node.logic(),node.children().stream().map(child->resolveNode(building,child,depth+1)).toList(),null);
    }
    private RuleRequest resolveDictionaries(String building,RuleRequest request) {
        List<Condition> conditions=request.conditions().stream().map(condition->condition==null?null:dictionaryService().resolve(building,condition)).toList();
        Advanced advanced=request.advanced();
        if(advanced!=null) advanced=new Advanced(advanced.primaryEquipmentId(),resolveNode(building,advanced.trigger()),resolveNode(building,advanced.recovery()),
                advanced.timing(),advanced.window(),advanced.emission());
        return new RuleRequest(request.type(),request.name(),request.severity(),request.qualityLevel(),request.spaceId(),request.deviceCategory(),
                request.enabled(),request.parameter(),request.revision(),request.recoveryCycles(),request.triggerMinutes(),request.recoveryMinutes(),
                request.conditionLogic(),request.classification(),conditions,advanced);
    }

    public List<StateDictionary> dictionaries(Long user,Set<String> roles,String building) { return dictionaryService().list(user,roles,building); }
    public String createDictionary(Long user,Set<String> roles,String building,SaveDictionary body) { return dictionaryService().create(user,roles,building,body); }
    public String createBoundDictionary(Long user,Set<String> roles,String building,String point,SaveDictionary body) {
        String id=dictionaryService().create(user,roles,building,body);
        dictionaryService().bind(user,roles,building,point,new DictionaryBinding(id,1L));
        return id;
    }
    public void updateDictionary(Long user,Set<String> roles,String building,String id,SaveDictionary body) { dictionaryService().update(user,roles,building,id,body); }
    public void bindDictionary(Long user,Set<String> roles,String building,String point,DictionaryBinding binding) { dictionaryService().bind(user,roles,building,point,binding); }

    /** 明确升级字典引用时逐条产生规则修订，历史事件与旧版本保持原映射。 */
    public int applyDictionary(Long user,Set<String> roles,String building,String id,long revision) {
        access.require(user,roles,building,CONFIG,true); initialize(building); lock(building);
        dictionaryService().getVersion(building,id,revision);
        int changed=0;
        for(Rule rule:loadRules(building)) {
            List<Condition> upgraded=rule.conditions().stream().map(condition->upgradeCondition(condition,id,revision)).toList();
            Advanced advanced=rule.advanced();
            if(advanced!=null) advanced=new Advanced(advanced.primaryEquipmentId(),upgradeNode(advanced.trigger(),id,revision),upgradeNode(advanced.recovery(),id,revision),
                    advanced.timing(),advanced.window(),advanced.emission());
            if(upgraded.equals(rule.conditions())&&Objects.equals(advanced,rule.advanced())) continue;
            updateRule(user,roles,building,rule.id(),new RuleRequest(rule.type(),rule.name(),rule.severity(),rule.qualityLevel(),rule.spaceId(),rule.deviceCategory(),
                    rule.enabled(),rule.parameter(),rule.revision(),rule.recoveryCycles(),rule.triggerMinutes(),rule.recoveryMinutes(),rule.conditionLogic(),
                    rule.classification(),upgraded,advanced));
            changed++;
        }
        var bindings=jdbc.queryForList("SELECT point_id FROM biz_alarm_point_dictionary WHERE building_id=? AND dictionary_id=?",String.class,building,id);
        for(String point:bindings) dictionaryService().bind(user,roles,building,point,new DictionaryBinding(id,revision));
        return changed;
    }
    private static Condition upgradeCondition(Condition condition,String id,long revision) {
        return id.equals(condition.dictionaryId())?new Condition(condition.pointId(),condition.operator(),condition.value(),condition.upperValue(),List.of(),id,revision):condition;
    }
    private static ConditionNode upgradeNode(ConditionNode node,String id,long revision) {
        if(node==null) return null;
        if(node.condition()!=null) return new ConditionNode(null,List.of(),upgradeCondition(node.condition(),id,revision));
        return new ConditionNode(node.logic(),node.children().stream().map(child->upgradeNode(child,id,revision)).toList(),null);
    }

    public void closeEvent(Long user,Set<String> roles,String building,String id,String note) {
        access.require(user,roles,building,HISTORY,false); lock(building);
        Event event=find(building,id);
        if(event.status().equals("CLOSED")) return;
        if(!event.status().equals("RECOVERED")) throw new BusinessException(409,"只可关闭已经恢复的事件");
        if(note!=null&&note.length()>500) throw new BusinessException("处理说明不能超过500字");
        jdbc.update("UPDATE biz_alarm_event SET status='CLOSED',closed_at=?,closed_by=? WHERE id=?",clock.millis(),user,id);
        log(id,clock.millis(),user,"CLOSED",note==null?"人工关单":note);
    }

    /** 历史趋势只查询事件引用的授权测点，保留真实状态与质量，超限截断明确标记。 */
    public List<TrendSeries> trend(Long user,Set<String> roles,String building,String id,long from,long to) {
        Event event=detail(user,roles,building,id);
        if(from<0||to<=from||to-from>86_400_000L||to>clock.millis()) throw new BusinessException("趋势范围须为过去24小时以内");
        Set<String> ids=new LinkedHashSet<>();
        for(String encoded:List.of(event.triggerEvidence(),event.evidence())) {
            try { for(var observation:json.readTree(encoded).path("observations")) if(observation.hasNonNull("pointId")) ids.add(observation.get("pointId").asText()); }
            catch(JsonProcessingException exception) { throw new IllegalStateException("报警证据无法解析",exception); }
        }
        if(ids.isEmpty()) ids.addAll(jdbc.queryForList("SELECT point_id FROM biz_data_point WHERE building_id=? AND equip_id=? ORDER BY point_id LIMIT 20",
                String.class,building,event.objectId()));
        if(ids.size()>20) throw new BusinessException("趋势测点超过20项");
        List<TrendSeries> result=new ArrayList<>();
        for(String point:ids) {
            var metadata=jdbc.queryForList("SELECT point_name,unit,equip_id,data_type FROM biz_data_point WHERE building_id=? AND point_id=?",building,point);
            if(metadata.isEmpty()) continue;
            var row=metadata.getFirst(); String name=Objects.toString(row.get("point_name"),point),unit=Objects.toString(row.get("unit"),"");
            if(rawEvents==null) { result.add(new TrendSeries(point,name,unit,List.of(),false,"原始趋势来源不可用")); continue; }
            try {
                var readings=rawEvents.findPointHistory(building,Objects.toString(row.get("equip_id"),""),point,from,to,null,2001);
                List<TrendSample> samples=readings.stream().filter(reading->point.equals(reading.pointId())&&building.equals(reading.buildingId())
                        && reading.eventTime()>=from&&reading.eventTime()<to&&Double.isFinite(reading.value())).limit(2000)
                        .map(reading->new TrendSample(reading.eventTime(),reading.value(),reading.dataQuality())).toList();
                result.add(new TrendSeries(point,name,unit,samples,readings.size()>2000,null));
            } catch(RuntimeException failure) { result.add(new TrendSeries(point,name,unit,List.of(),false,"原始趋势暂不可用")); }
        }
        return result;
    }

    private static List<Condition> nodeConditions(ConditionNode node) {
        if(node==null) return List.of();
        return node.condition()!=null?List.of(node.condition()):(node.children()==null?List.<ConditionNode>of():node.children()).stream().flatMap(child->nodeConditions(child).stream()).toList();
    }
    private void validateStateChoices(String building,Condition condition) {
        if(condition.states().isEmpty()) return;
        if(condition.states().size()>32||condition.value()==null||condition.operator()==null
                ||!Set.of("EQ","NE").contains(condition.operator())||condition.upperValue()!=null) throw new BusinessException("状态选项或比较方式无效");
        List<String> kinds=jdbc.queryForList("SELECT data_type FROM biz_data_point WHERE building_id=? AND point_id=? AND status='ONLINE' AND del_flag=0",String.class,building,condition.pointId());
        if(kinds.size()!=1||!"DIGITAL".equals(kinds.getFirst())) throw new BusinessException("状态选项仅支持当前建筑内DIGITAL测点");
        Set<java.math.BigDecimal> values=new java.util.TreeSet<>(); Set<String> labels=new HashSet<>(); boolean selected=false;
        for(StateOption state:condition.states()) {
            if(state==null||state.value()==null||blank(state.label())||state.label().length()>40||!values.add(state.value())||!labels.add(state.label())) throw new BusinessException("状态编码及名称须完整且唯一");
            selected|=state.value().compareTo(condition.value())==0;
        }
        if(!selected) throw new BusinessException("比较值必须对应一个状态编码");
    }
    private void validateConditions(RuleRequest request,String building) {
        if(request.conditions()==null||request.conditions().isEmpty()||request.conditions().size()>20) throw new BusinessException("至少选择1个、最多选择20个测点");
        if(!Set.of("ALL","ANY").contains(request.conditionLogic())) throw new BusinessException("条件组合方式无效");
        if(request.type().equals("QUALITY")&&!"QUALITY".equals(request.classification())) throw new BusinessException("质量规则分类固定为QUALITY");
        if(request.type().equals("CONDITION")&&!Set.of("VALUE","FAULT").contains(request.classification())) throw new BusinessException("条件规则分类无效");
        Set<String> ids=new HashSet<>(); String equipment=null;
        for(Condition condition:request.conditions()) {
            if(condition==null||blank(condition.pointId())||!ids.add(condition.pointId())) throw new BusinessException("规则测点不能为空且不可重复");
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0 WHERE p.point_id=? AND p.building_id=? AND p.status='ONLINE' AND p.del_flag=0",Long.class,condition.pointId(),building);
            if(count==null||count==0) throw new BusinessException("规则测点不存在、未上线或不属于当前建筑");
            List<StateOption> states=condition.states()==null?List.of():condition.states();
            if(!states.isEmpty()) {
                if(states.size()>32) throw new BusinessException("状态选项须为1至32项");
                String dataType=jdbc.queryForObject("SELECT p.data_type FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0 WHERE p.point_id=? AND p.building_id=? AND p.status='ONLINE' AND p.del_flag=0",String.class,condition.pointId(),building);
                if(!"DIGITAL".equals(dataType)) throw new BusinessException("状态选项仅支持DIGITAL测点");
                if(condition.operator()==null||!Set.of("EQ","NE").contains(condition.operator())||condition.upperValue()!=null||condition.value()==null)
                    throw new BusinessException("状态选项仅支持EQ或NE单值比较");
                Set<BigDecimal> values=new TreeSet<>(BigDecimal::compareTo); Set<String> labels=new HashSet<>(); boolean selected=false;
                for(StateOption state:states) {
                    if(state==null||state.value()==null||blank(state.label())||state.label().length()>40)
                        throw new BusinessException("状态编码不能为空，中文名称须为1至40字");
                    if(!values.add(state.value())) throw new BusinessException("状态编码不可重复");
                    if(!labels.add(state.label())) throw new BusinessException("状态中文名称不可重复");
                    selected|=state.value().compareTo(condition.value())==0;
                }
                if(!selected) throw new BusinessException("比较值必须对应一个状态编码");
            }
            String owner=jdbc.queryForObject("SELECT e.equip_id FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id WHERE p.point_id=? AND p.building_id=?",String.class,condition.pointId(),building);
            if(equipment!=null&&!equipment.equals(owner)) throw new BusinessException("条件测点必须绑定同一设备"); equipment=owner;
            if(request.type().equals("CONDITION")) {
                if(condition.operator()==null||!Set.of("GT","GE","LT","LE","EQ","NE","BETWEEN","OUTSIDE").contains(condition.operator())||condition.value()==null)
                    throw new BusinessException("测点比较条件无效");
                boolean range=Set.of("BETWEEN","OUTSIDE").contains(condition.operator());
                if(range!=(condition.upperValue()!=null)||range&&condition.value().compareTo(condition.upperValue())>0) throw new BusinessException("区间比较上下界无效");
            }
        }
    }
    private String conditionJson(List<Condition> conditions) {
        try { return json.writeValueAsString(conditions==null?List.of():conditions); }
        catch(JsonProcessingException exception) { throw new IllegalStateException("无法保存报警测点条件",exception); }
    }

    public void saveRule(Long user, Set<String> roles, String building, String type, SaveRule request) {
        access.require(user, roles, building, CONFIG, true);
        requireLegacyRuleType(type);
        if (request == null || request.parameter() < 1 || request.parameter() > 100
                || type.equals("FAULT") && request.parameter() != 1) throw new BusinessException("报警参数无效");
        initialize(building);
        Rule previous=loadRules(building).stream().filter(r->r.id().equals(defaultRuleId(type))).findFirst().orElseThrow();
        updateRule(user,roles,building,previous.id(),new RuleRequest(type,previous.name(),previous.severity(),previous.qualityLevel(),previous.spaceId(),previous.deviceCategory(),request.enabled(),request.parameter(),request.revision()));
    }

    public List<Map<String, Object>> ruleHistory(Long user, Set<String> roles, String building, String id) {
        access.require(user, roles, building, CONFIG, false); initialize(building);
        if(Set.of("OFFLINE","FAULT","QUOTA").contains(id)) id=defaultRuleId(id);
        findRule(building,id);
        return jdbc.query("SELECT revision,rule_type,rule_name,severity,quality_level,space_id,device_category,enabled,parameter_value,changed_at,changed_by,recovery_cycles,trigger_minutes,recovery_minutes,condition_logic,classification,conditions_json,advanced_json FROM biz_alarm_rule_version WHERE building_id=? AND rule_id=? ORDER BY revision DESC LIMIT 100",
                (rs,n)->{
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("revision",rs.getLong("revision")); row.put("type",rs.getString("rule_type"));
                    row.put("name",rs.getString("rule_name")); row.put("severity",rs.getString("severity"));
                    row.put("qualityLevel",rs.getString("quality_level")); row.put("spaceId",rs.getString("space_id"));
                    row.put("deviceCategory",rs.getString("device_category")); row.put("enabled",rs.getBoolean("enabled"));
                    row.put("parameter_value",rs.getInt("parameter_value")); row.put("changed_at",rs.getLong("changed_at"));
                    row.put("recoveryCycles",rs.getInt("recovery_cycles")); row.put("triggerMinutes",rs.getInt("trigger_minutes"));
                    row.put("recoveryMinutes",rs.getInt("recovery_minutes")); row.put("conditionLogic",rs.getString("condition_logic"));
                    row.put("classification",rs.getString("classification")); row.put("advanced",readAdvanced(rs.getString("advanced_json")));
                    String encoded=rs.getString("conditions_json");
                    try { row.put("conditions",encoded==null||encoded.isBlank()?List.of():json.readValue(encoded,new com.fasterxml.jackson.core.type.TypeReference<List<Condition>>(){})); }
                    catch(JsonProcessingException exception){throw new IllegalStateException("报警版本条件无法解析",exception);}
                    row.put("changed_by",number(rs,"changed_by")); return row;
                },building,id);
    }

    public Summary summary(Long user, Set<String> roles, String building) {
        String menu = "/monitor/monitoring";
        try { access.requireMenu(user, roles, menu, false); }
        catch (BusinessException denied) {
            if (denied.getCode() != 403) throw denied;
            menu = LIVE;
        }
        access.require(user, roles, building, menu, false);
        lock(building);
        var scopes = jdbc.queryForList("SELECT * FROM biz_alarm_scope WHERE building_id=?", building);
        if (scopes.isEmpty()) return unavailable("报警正在初始化");
        var scope = scopes.getFirst();
        Number at = (Number) scope.get("evaluated_at");
        if (at == null || clock.millis() - at.longValue() > FRESH_MS) return unavailable("报警判定结果尚未更新");
        String deviceReason = (String) scope.get("device_reason"), emissionReason = (String) scope.get("emission_reason");
        List<Event> active = active(building).stream().filter(e -> e.silencedUntil() == null || e.silencedUntil() <= clock.millis()).toList();
        Map<String, Event> primary = active.stream().filter(e -> e.category().equals("DEVICE"))
                .collect(Collectors.toMap(Event::objectId, Function.identity(), (a, b) -> primaryRank(a.type()) <= primaryRank(b.type()) ? a : b));
        List<Group> deviceGroups = List.of("OFFLINE","QUALITY","VALUE","FAULT").stream()
                .map(type -> new Group(type, primary.values().stream().filter(e -> e.type().equals(type)).count())).toList();
        long emissionCount = active.stream().filter(e -> e.category().equals("EMISSION")).count();
        return new Summary(deviceReason == null ? (long) primary.size() : null, emissionReason == null ? emissionCount : null,
                deviceGroups, List.of("QUOTA","INTENSITY","MOM","YOY","BASELINE","COMPLETENESS","FORECAST").stream().map(type->new Group(type,active.stream().filter(event->event.category().equals("EMISSION")&&event.type().equals(type)).count())).toList(), at.longValue(),
                (String) scope.get("period"), deviceReason, emissionReason);
    }

    public PageResponse<Event> events(Long user, Set<String> roles, String building, boolean history, String type, int page, int size) {
        return events(user, roles, building, history, type, page, size, null, null, null, null, null);
    }

    public PageResponse<Event> events(Long user, Set<String> roles, String building, boolean history, String type, int page, int size,
            String alarmType, String state, String keyword, Long fromAt, Long toAt) {
        access.require(user, roles, building, history ? HISTORY : LIVE, false);
        if (!Set.of("DEVICE", "EMISSION").contains(type) || page < 1 || page > 10000 || size < 1 || size > 100) {
            throw new BusinessException("报警查询条件无效");
        }
        String where = " WHERE building_id=? AND category=?" + (history ? " AND status IN ('RECOVERED','CLOSED')" : " AND status='ACTIVE'");
        List<Object> args = new ArrayList<>(List.of(building, type));
        if (!blank(alarmType)) {
            requireEventType(alarmType); where += " AND rule_type=?"; args.add(alarmType);
        }
        if (!blank(state)) {
            if (history) {
                if(!Set.of("RECOVERED","CLOSED").contains(state)) throw new BusinessException("历史处理状态无效");
                where+=" AND status=?"; args.add(state);
            } else
            switch (state) {
                case "UNACK" -> { where += " AND ack_at IS NULL AND (silenced_until IS NULL OR silenced_until<=?)"; args.add(clock.millis()); }
                case "ACK" -> { where += " AND ack_at IS NOT NULL AND (silenced_until IS NULL OR silenced_until<=?)"; args.add(clock.millis()); }
                case "SILENCED" -> { where += " AND silenced_until>?"; args.add(clock.millis()); }
                case "ACTIVE" -> { where += " AND (silenced_until IS NULL OR silenced_until<=?)"; args.add(clock.millis()); }
                default -> throw new BusinessException("处理状态无效");
            }
        }
        if (!blank(keyword)) {
            if (keyword.length() > 100) throw new BusinessException("搜索词过长");
            String escaped = "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where += " AND (object_name LIKE ? ESCAPE '!' OR space_name LIKE ? ESCAPE '!' OR description LIKE ? ESCAPE '!')";
            args.addAll(List.of(escaped, escaped, escaped));
        }
        if (fromAt != null) { where += " AND first_at>=?"; args.add(fromAt); }
        if (toAt != null) { where += " AND first_at<?"; args.add(toAt); }
        if (fromAt != null && toAt != null && fromAt >= toAt) throw new BusinessException("发生时间范围无效");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM biz_alarm_event" + where, Long.class, args.toArray());
        args.add(size); args.add((page - 1) * size);
        var rows = jdbc.query("SELECT * FROM biz_alarm_event" + where + " ORDER BY first_at DESC,id DESC LIMIT ? OFFSET ?",
                AlarmService::event, args.toArray());
        return new PageResponse<>(page, size, total == null ? 0 : total, rows);
    }

    public Event detail(Long user, Set<String> roles, String building, String id) {
        // 先检查入口与建筑再读对象，避免用未知事件编号探测其他建筑。
        try { access.require(user, roles, building, LIVE, false); }
        catch (BusinessException denied) {
            if (denied.getCode() != 403) throw denied;
            access.require(user, roles, building, HISTORY, false);
        }
        Event row = find(building, id);
        access.require(user, roles, building, row.status().equals("ACTIVE") ? LIVE : HISTORY, false);
        var logs = jdbc.query("SELECT * FROM biz_alarm_event_log WHERE event_id=? ORDER BY sequence_no",
                (rs, n) -> new Log(rs.getLong("at_ms"), number(rs, "actor"), rs.getString("action"), rs.getString("note")), id);
        return new Event(row.id(), row.buildingId(), row.category(), row.type(), row.ruleId(),row.objectId(), row.objectName(),
                row.spaceName(), row.period(), row.status(), row.severity(), row.description(), row.firstAt(), row.lastAt(),
                row.recoveredAt(), row.ackBy(), row.ackAt(), row.silencedUntil(), row.ruleRevision(), row.evidence(), row.triggerEvidence(), logs,row.closedAt(),row.closedBy());
    }

    public void action(Long user, Set<String> roles, String building, String id, String action, String note, int minutes) {
        access.require(user, roles, building, LIVE, false);
        lock(building); Event row = find(building, id);
        if (!row.status().equals("ACTIVE")) throw new BusinessException(409, "已恢复事件仅供查阅");
        long now = clock.millis();
        switch (action) {
            case "ACK" -> {
                if (row.ackAt() != null) return;
                jdbc.update("UPDATE biz_alarm_event SET ack_by=?,ack_at=? WHERE id=?", user, now, id);
            }
            case "NOTE" -> validateNote(note);
            case "SILENCE" -> {
                validateNote(note);
                if (!row.category().equals("DEVICE") || minutes < 1 || minutes > 10080) throw new BusinessException("维护屏蔽时长须为1分钟至7天");
                // 按设备屏蔽同一次检修涉及的全部活动异常，保留各事件确认记录。
                for (Event event : active(building).stream().filter(e -> e.category().equals("DEVICE") && e.objectId().equals(row.objectId())).toList()) {
                    jdbc.update("UPDATE biz_alarm_event SET silenced_until=? WHERE id=?", now + minutes * 60000L, event.id());
                    log(event.id(), now, user, action, note);
                }
                return;
            }
            default -> throw new BusinessException("报警操作无效");
        }
        log(id, now, user, action, action.equals("ACK") ? "确认接手" : note);
    }

    /** 调度器调用；整建筑串行，原始数据读取失败的类别保持原事件并标记不可用。 */
    public void evaluate(String building) {
        initialize(building); lock(building);
        long now = clock.millis();
        List<Rule> rules = loadRules(building);
        YearMonth month = YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).minusMonths(1);
        String deviceReason;
        DeviceResult deviceResult = null;
        try {
            deviceResult = devices.load(building, rules, now);
            deviceReason = deviceResult.unavailableReason();
        } catch (RuntimeException exception) {
            log.warn("报警设备来源不可用，建筑={}，异常类型={}", building, exception.getClass().getSimpleName());
            deviceReason = "设备采集事实暂不可用";
        }
        if (deviceResult != null && deviceResult.complete()) {
            for(String id:deviceResult.unavailableRuleIds()) jdbc.update("DELETE FROM biz_alarm_rule_state WHERE building_id=? AND rule_id=?",building,id);
            Set<String> unknownRules=deviceResult.unavailableRuleIds();
            reconcile(building,"DEVICE",deviceResult.candidates(),rules.stream().filter(rule->!unknownRules.contains(rule.id())).toList(),now);
        } else jdbc.update("DELETE FROM biz_alarm_rule_state WHERE building_id=?",building);
        for (Rule rule : rules) if (!rule.enabled()) recoverRule(building, rule.id(), now, "规则停用");
        // 跨月归档独立于新月数据是否已就绪，不能把上月报警冒充当前评价月。
        for (Event event : active(building)) if (event.category().equals("EMISSION") && rules.stream().anyMatch(rule->rule.id().equals(event.ruleId())&&rule.type().equals("QUOTA")) && !month.toString().equals(event.period())) recover(event, now, "评价月份切换");
        String emissionReason;
        List<Rule> quotaRules = rules.stream().filter(r -> r.type().equals("QUOTA") && r.enabled()).toList();
        if (quotaRules.isEmpty()) emissionReason = "月度配额规则已停用";
        else {
            Quota quota = null;
            try {
                if (quotas.size() != 1) emissionReason = "月度配额数据源尚未接入";
                else {
                    quota = quotas.getFirst().load(building, month);
                    emissionReason = validQuota(quota, building, month);
                }
            } catch (RuntimeException exception) {
                log.warn("报警月配额来源不可用，建筑={}，异常类型={}", building, exception.getClass().getSimpleName());
                emissionReason = "月度配额数据暂不可用";
            }
            if (emissionReason == null && quota != null) {
                List<Candidate> values = new ArrayList<>();
                for (Rule quotaRule : quotaRules) if (quota.actual().multiply(java.math.BigDecimal.valueOf(100))
                        .compareTo(quota.quota().multiply(java.math.BigDecimal.valueOf(quotaRule.parameter()))) >= 0) {
                    boolean over = quota.actual().compareTo(quota.quota()) > 0;
                    values.add(new Candidate("QUOTA",quotaRule.id(),building,"建筑月度排放",null,null,null,month.toString(),
                            over ? "IMPORTANT" : "WARNING",over ? "当月实际排放已超配额" : "当月实际排放接近配额",quotaEvidence(quota,quotaRule.parameter())));
                }
                reconcile(building, "EMISSION", values, quotaRules, now);
            }
        }
        List<Rule> extendedEmission=rules.stream().filter(rule->rule.type().equals("EMISSION")&&rule.enabled()).toList();
        for(Rule rule:extendedEmission) {
            var period=AlarmEmissionEvaluator.resolvePeriod(rule.advanced()==null?null:rule.advanced().emission(),now);
            for(Event event:active(building)) if(event.ruleId().equals(rule.id())&&(period==null||!period.period().equals(event.period()))) recover(event,now,"评价期间切换");
        }
        if(!extendedEmission.isEmpty()) {
            DeviceResult result=emissionEvaluator().evaluate(building,extendedEmission,now);
            Set<String> unknown=result.unavailableRuleIds();
            for(String id:unknown) jdbc.update("DELETE FROM biz_alarm_rule_state WHERE building_id=? AND rule_id=?",building,id);
            reconcile(building,"EMISSION",result.candidates(),extendedEmission.stream().filter(rule->!unknown.contains(rule.id())).toList(),now);
            String legacyReason=quotaRules.isEmpty()?null:emissionReason;
            emissionReason=java.util.stream.Stream.of(legacyReason,result.unavailableReason()).filter(Objects::nonNull).filter(value->!value.isBlank()).distinct().collect(Collectors.joining("；"));
            if(emissionReason.isBlank()) emissionReason=null;
        }
        if (rules.stream().noneMatch(r -> r.enabled() && !Set.of("QUOTA","EMISSION").contains(r.type()))) deviceReason = "设备报警规则已停用";
        jdbc.update("UPDATE biz_alarm_scope SET evaluated_at=?,device_reason=?,emission_reason=?,period=? WHERE building_id=?",
                now, deviceReason, emissionReason, month.toString(), building);
    }

    private String validQuota(Quota value, String building, YearMonth month) {
        if (value == null) return "月度配额数据尚未就绪";
        if (value.unavailableReason() != null) return value.unavailableReason();
        if (!building.equals(value.buildingId()) || !month.equals(value.month()) || value.actual() == null || value.actual().signum() < 0
                || value.quota() == null || value.quota().signum() <= 0
                || blank(value.planVersion()) || blank(value.batchId()) || blank(value.evidenceHash())) return "缺少有效月配额或正式核算证据";
        return null;
    }


    /** 按真实观测游标维护跨调度连续时间与恢复周期；同一快照重跑不会推进计数。 */
    private void reconcile(String building, String category, List<Candidate> candidates, List<Rule> rules, long now) {
        Map<String, Rule> byId = rules.stream().collect(Collectors.toMap(Rule::id, Function.identity()));
        Map<String, Candidate> desired = new LinkedHashMap<>();
        Map<String, Event> activeByKey = new HashMap<>();
        for (Event event : active(building)) {
            if (event.category().equals(category)) {
                activeByKey.put(key(building, candidateFor(event)), event);
            }
        }
        for (Candidate candidate : candidates) {
            Rule rule = candidate.ruleId() == null ? null : byId.get(candidate.ruleId());
            if (rule == null || !rule.enabled() || !supportsCandidateType(rule, candidate)
                    || !applicable(rule, candidate)) continue;

            String eventKey = key(building, candidate);
            Event previous = activeByKey.get(eventKey);
            boolean ready = updateObservationState(building, rule, candidate, previous, now);
            if (!candidate.matched() && (previous == null || ready)) continue;
            if (candidate.matched() && !ready && previous == null) continue;

            Candidate effective = candidate;
            if (previous != null && !ready) {
                // 未满足触发持续时间或恢复门槛时，保留已经存在的活动事件及其证据。
                effective = new Candidate(candidate.type(), candidate.ruleId(), candidate.objectId(),
                        previous.objectName(), candidate.spaceId(), candidate.deviceCategory(), previous.spaceName(),
                        candidate.period(), previous.severity(), previous.description(), previous.evidence(), true,
                        candidate.observationAt(), candidate.observationKey(), candidate.observationCycleKey());
            }
            if (!ready && previous == null) continue;
            String severity = Set.of("QUOTA","EMISSION").contains(rule.type()) ? effective.severity() : rule.severity();
            Candidate actual = new Candidate(effective.type(), rule.id(), effective.objectId(), effective.objectName(),
                    effective.spaceId(), effective.deviceCategory(), effective.spaceName(), effective.period(), severity,
                    effective.description(), effective.evidence());
            desired.put(key(building, actual), actual);
        }
        Map<String, Event> existing = new HashMap<>();
        for (Event event : active(building)) {
            if (!event.category().equals(category) || !byId.containsKey(event.ruleId())) continue;
            String key = key(building, candidateFor(event));
            Rule rule = byId.get(event.ruleId());
            if (!desired.containsKey(key) || rule.revision() != event.ruleRevision()) recover(event, now, "条件解除或规则修订");
            else existing.put(key, event);
        }
        for (var entry : desired.entrySet()) {
            Candidate value = entry.getValue(); Event previous = existing.get(entry.getKey());
            if (previous != null) {
                if (!previous.severity().equals(value.severity())) log(previous.id(), now, null, "LEVEL_CHANGED", value.description());
                jdbc.update("UPDATE biz_alarm_event SET last_at=?,severity=?,description=?,evidence=? WHERE id=?",
                        now, value.severity(), value.description(), value.evidence(), previous.id());
            } else {
                String id = UUID.randomUUID().toString();
                Long silence = null;
                if (category.equals("DEVICE")) silence = jdbc.queryForObject("SELECT MAX(silenced_until) FROM biz_alarm_event WHERE building_id=? AND object_id=? AND category='DEVICE' AND silenced_until>?", Long.class, building, value.objectId(), now);
                jdbc.update("""
                        INSERT INTO biz_alarm_event(id,active_key,building_id,category,rule_id,rule_type,object_id,object_name,space_name,
                        period,status,severity,description,first_at,last_at,rule_revision,evidence,trigger_evidence,silenced_until)
                        VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,?)
                        """, id, entry.getKey(), building, category, value.ruleId(), value.type(), value.objectId(), value.objectName(),
                        value.spaceName(), value.period(), value.severity(), value.description(), now, now,
                        byId.get(value.ruleId()).revision(), value.evidence(), value.evidence(), silence);
                log(id, now, null, "TRIGGERED", value.description());
            }
        }
    }

    private static Candidate candidateFor(Event event) {
        return new Candidate(event.type(), event.ruleId(), event.objectId(), "", null, null, null,
                event.period(), "", "", "");
    }

    private static boolean supportsCandidateType(Rule rule, Candidate candidate) {
        return rule.type().equals(candidate.type())
                || Set.of("CONDITION","JUMP","STUCK").contains(rule.type()) && Set.of("VALUE", "FAULT").contains(candidate.type())
                || rule.type().equals("EMISSION") && Set.of("QUOTA","INTENSITY","MOM","YOY","BASELINE","COMPLETENESS","FORECAST").contains(candidate.type());
    }

    private boolean updateObservationState(String building, Rule rule, Candidate candidate, Event previous, long now) {
        if (candidate.observationKey() == null) return candidate.matched() || !Boolean.FALSE.equals(candidate.recoveryMatched());
        RuleState state = ruleState(building, rule.id(), candidate.objectId());
        String key = shortHash(candidate.observationKey());
        boolean newer = state.lastAt == null || candidate.observationAt() >= state.lastAt;
        boolean distinct = newer && !key.equals(state.lastKey);
        if (distinct) {
            boolean continuous = state.lastAt == null || candidate.observationAt() > state.lastAt
                    && consecutiveCycles(state.lastCycleKey, candidate.observationCycleKey());
            if (candidate.matched()) {
                state.triggerSince = state.triggerSince == null || !continuous
                        ? candidate.observationAt() : state.triggerSince;
                state.recoveryCycles = 0;
                state.recoverySince = null;
            } else {
                if(Boolean.FALSE.equals(candidate.recoveryMatched())) {
                    state.triggerSince=null; state.recoverySince=null; state.recoveryCycles=0;
                    state.lastKey=key; state.lastAt=candidate.observationAt(); state.lastCycleKey=candidate.observationCycleKey();
                    saveRuleState(building,rule.id(),candidate.objectId(),state);
                    return false;
                }
                // 任一已知反向事实立即打断触发连续时间，即使它仍在同一分钟内。
                state.triggerSince = null;
                if (previous != null && !Objects.equals(state.lastCycleKey, candidate.observationCycleKey())) {
                    if (!continuous) {
                        state.recoveryCycles = 0;
                        state.recoverySince = candidate.observationAt();
                    }
                    state.recoveryCycles++;
                } else if (previous == null) {
                    state.recoveryCycles = 0;
                    state.recoverySince = null;
                }
                if (previous != null && state.recoverySince == null) state.recoverySince = candidate.observationAt();
            }
            state.lastKey = key;
            state.lastAt = candidate.observationAt();
            if (candidate.observationCycleKey() != null) state.lastCycleKey = candidate.observationCycleKey();
            saveRuleState(building, rule.id(), candidate.objectId(), state);
        }

        if (candidate.matched()) {
            long sustainedThrough = rule.type().equals("OFFLINE") ? now : candidate.observationAt();
            return state.triggerSince != null
                    && sustainedThrough - state.triggerSince >= rule.triggerMinutes() * 60_000L;
        }
        if (previous == null) return false;
        int cycles = rule.recoveryCycles() == null ? 1 : rule.recoveryCycles();
        return state.recoveryCycles >= cycles && (rule.recoveryMinutes() == 0
                || state.recoverySince != null && candidate.observationAt() - state.recoverySince >= rule.recoveryMinutes() * 60_000L);
    }

    private RuleState ruleState(String building,String rule,String object) {
        jdbc.update("INSERT IGNORE INTO biz_alarm_rule_state(building_id,rule_id,object_id) VALUES (?,?,?)",building,rule,object);
        return jdbc.queryForObject("SELECT last_observation_key,last_cycle_key,last_observation_at,trigger_since,recovery_cycles,recovery_since FROM biz_alarm_rule_state WHERE building_id=? AND rule_id=? AND object_id=?",
                (rs,n)->new RuleState(rs.getString("last_observation_key"),rs.getString("last_cycle_key"),number(rs,"last_observation_at"),number(rs,"trigger_since"),rs.getInt("recovery_cycles"),number(rs,"recovery_since")),building,rule,object);
    }
    private void saveRuleState(String building,String rule,String object,RuleState state) {
        jdbc.update("UPDATE biz_alarm_rule_state SET last_observation_key=?,last_cycle_key=?,last_observation_at=?,trigger_since=?,recovery_cycles=?,recovery_since=? WHERE building_id=? AND rule_id=? AND object_id=?",
                state.lastKey,state.lastCycleKey,state.lastAt,state.triggerSince,state.recoveryCycles,state.recoverySince,building,rule,object);
    }
    private static String shortHash(String value) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException exception){throw new IllegalStateException(exception);}
    }
    private static boolean consecutiveCycles(String previous,String current) {
        if(previous==null||current==null)return previous==null;
        if(previous.equals(current))return true;
        String[] before=previous.split(":"),after=current.split(":");
        if(before.length<2||after.length<2||!before[0].equals(after[0]))return false;
        try {
            long delta=Long.parseLong(after[1])-Long.parseLong(before[1]);
            return delta==1;
        }
        catch(NumberFormatException exception){return false;}
    }
    private static final class RuleState {
        String lastKey;
        String lastCycleKey;
        Long lastAt;
        Long triggerSince;
        int recoveryCycles;
        Long recoverySince;

        RuleState(String key,String cycleKey,Long at,Long trigger,int cycles,Long recovery) {
            lastKey=key; lastCycleKey=cycleKey; lastAt=at; triggerSince=trigger;
            recoveryCycles=cycles; recoverySince=recovery;
        }
    }

    private void initialize(String building) {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM biz_building WHERE building_id=? AND del_flag=0", Long.class, building);
        if (exists == null || exists == 0) throw new BusinessException(404, "建筑不存在");
        jdbc.update("INSERT IGNORE INTO biz_alarm_scope(building_id) VALUES (?)", building);
        for (String type : List.of("OFFLINE", "FAULT", "QUOTA")) {
            int parameter = type.equals("OFFLINE") ? 3 : type.equals("FAULT") ? 1 : 85;
            String id=defaultRuleId(type), name=defaultRuleName(type), severity=type.equals("QUOTA")?null:"IMPORTANT";
            String quality=type.equals("OFFLINE")?"Q2":type.equals("FAULT")?"Q1":"Q0";
            jdbc.update("INSERT IGNORE INTO biz_alarm_rule(building_id,rule_id,rule_type,rule_name,severity,quality_level,enabled,parameter_value,revision) VALUES (?,?,?,?,?,?,1,?,1)", building,id,type,name,severity,quality,parameter);
            jdbc.update("INSERT IGNORE INTO biz_alarm_rule_version(building_id,rule_id,rule_type,rule_name,severity,quality_level,revision,enabled,parameter_value,changed_at,changed_by) VALUES (?,?,?,?,?,?,1,1,?,?,NULL)", building,id,type,name,severity,quality,parameter,clock.millis());
        }
    }
    private void lock(String building) { jdbc.queryForList("SELECT building_id FROM biz_alarm_scope WHERE building_id=? FOR UPDATE", building); }
    private List<Rule> loadRules(String building) {
        return jdbc.query("SELECT * FROM biz_alarm_rule WHERE building_id=? ORDER BY rule_type,rule_name,rule_id", AlarmService::rule, building);
    }
    private List<Event> active(String building) { return jdbc.query("SELECT * FROM biz_alarm_event WHERE building_id=? AND status='ACTIVE'", AlarmService::event, building); }
    private Event find(String building, String id) {
        var rows = jdbc.query("SELECT * FROM biz_alarm_event WHERE building_id=? AND id=?", AlarmService::event, building, id);
        if (rows.isEmpty()) throw new BusinessException(404, "报警事件不存在");
        return rows.getFirst();
    }
    private void recoverRule(String building,String ruleId,long now,String reason) {
        active(building).stream().filter(e -> e.ruleId().equals(ruleId)).forEach(e -> recover(e, now, reason));
    }
    private Rule findRule(String building,String id) {
        List<Rule> rows=jdbc.query("SELECT * FROM biz_alarm_rule WHERE building_id=? AND rule_id=?",AlarmService::rule,building,id);
        if(rows.isEmpty()) throw new BusinessException(404,"报警规则不存在");
        return rows.getFirst();
    }
    private static String defaultRuleId(String type){ return "default-"+type; }
    private static int primaryRank(String type) { return switch(type){case "OFFLINE"->0;case "QUALITY"->1;case "VALUE"->2;case "FAULT"->3;default->4;}; }
    private static String defaultRuleName(String type){ return switch(type){case "OFFLINE"->"设备离线";case "FAULT"->"设备故障";case "QUOTA"->"月度配额";default->type;}; }
    private static boolean applicable(Rule rule,Candidate value){
        // 空范围表示整栋建筑；指定空间和设备类别都要求精确匹配。
        return (rule.spaceId()==null||rule.spaceId().isBlank()||Objects.equals(rule.spaceId(),value.spaceId()))
                &&(rule.deviceCategory()==null||rule.deviceCategory().isBlank()||Objects.equals(rule.deviceCategory(),value.deviceCategory()));
    }
    private void recover(Event event, long now, String reason) {
        jdbc.update("UPDATE biz_alarm_event SET active_key=NULL,status='RECOVERED',recovered_at=? WHERE id=?", now, event.id());
        log(event.id(), now, null, "RECOVERED", reason);
    }
    private void log(String id, long now, Long actor, String action, String note) {
        // 调用方持有建筑锁；同毫秒多次操作仍按发生顺序回溯，不能用随机 UUID 排序。
        Long sequence = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM biz_alarm_event_log WHERE event_id=?", Long.class, id);
        jdbc.update("INSERT INTO biz_alarm_event_log VALUES (?,?,?,?,?,?,?)", UUID.randomUUID().toString(), id, sequence, now, actor, action, note);
    }
    private static void validateNote(String note) { if (blank(note) || note.length() > 500) throw new BusinessException("请填写500字以内的处理说明"); }
    private static Rule rule(ResultSet rs,int row) throws SQLException {
        String encoded=rs.getString("conditions_json"); List<Condition> conditions=List.of();
        if(encoded!=null&&!encoded.isBlank()) try { conditions=new ObjectMapper().readValue(encoded,new com.fasterxml.jackson.core.type.TypeReference<List<Condition>>(){}); }
        catch(Exception exception){throw new SQLException("报警测点条件无法解析",exception);}
        return new Rule(rs.getString("rule_id"),rs.getString("rule_type"),rs.getString("rule_name"),rs.getString("severity"),
                rs.getString("quality_level"),rs.getString("space_id"),rs.getString("device_category"),rs.getBoolean("enabled"),
                rs.getInt("parameter_value"),rs.getLong("revision"),number(rs,"updated_at"),number(rs,"updated_by"),rs.getInt("recovery_cycles"),
                rs.getInt("trigger_minutes"),rs.getInt("recovery_minutes"),rs.getString("condition_logic"),rs.getString("classification"),conditions,readAdvanced(rs.getString("advanced_json")));
    }
    private static void requireRuleType(String type) { if (type==null||!Set.of("OFFLINE","FAULT","QUOTA","QUALITY","CONDITION","JUMP","STUCK","EMISSION").contains(type)) throw new BusinessException("报警规则无效"); }
    private static void requireLegacyRuleType(String type) { if (type==null||!Set.of("OFFLINE","FAULT","QUOTA").contains(type)) throw new BusinessException("旧版规则类型无效"); }
    private static void requireEventType(String type) { if (type==null||!Set.of("OFFLINE","QUALITY","VALUE","FAULT","QUOTA","INTENSITY","MOM","YOY","BASELINE","COMPLETENESS","FORECAST").contains(type)) throw new BusinessException("报警事件类型无效"); }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String key(String building, Candidate value) { return building + ":" + value.ruleId() + ":" + value.objectId() + ":" + Objects.toString(value.period(), ""); }
    private Summary unavailable(String reason) { return new Summary(null, null, List.of(), List.of(), clock.millis(), YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).minusMonths(1).toString(), reason, reason); }
    private String quotaEvidence(Quota value, int threshold) {
        com.fasterxml.jackson.databind.node.ObjectNode evidence = json.valueToTree(value);
        evidence.put("ratioPercent", value.actual().multiply(java.math.BigDecimal.valueOf(100))
                .divide(value.quota(), 2, java.math.RoundingMode.HALF_UP).toPlainString());
        evidence.put("thresholdPercent", threshold);
        evidence.put("unit", "tCO2e");
        try { return json.writeValueAsString(evidence); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法保存报警依据", exception); }
    }
    private static Long number(ResultSet rs, String column) throws SQLException { return rs.getObject(column) == null ? null : rs.getLong(column); }
    private static Event event(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getString("id"), rs.getString("building_id"), rs.getString("category"), rs.getString("rule_type"),rs.getString("rule_id"),
                rs.getString("object_id"), rs.getString("object_name"), rs.getString("space_name"), rs.getString("period"),
                rs.getString("status"), rs.getString("severity"), rs.getString("description"), rs.getLong("first_at"), rs.getLong("last_at"),
                number(rs,"recovered_at"), number(rs,"ack_by"), number(rs,"ack_at"), number(rs,"silenced_until"), rs.getLong("rule_revision"), rs.getString("evidence"), rs.getString("trigger_evidence"), List.of(),number(rs,"closed_at"),number(rs,"closed_by"));
    }
}
