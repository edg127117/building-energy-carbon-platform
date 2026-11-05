package com.platform.alarm;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/** 报警独立契约。时间为 UTC 毫秒，核算月采用北京时间，排放量为 tCO2e。 */
public final class AlarmModels {
    private AlarmModels() { }
    public record Rule(String id, String type, String name, String severity, String qualityLevel, String spaceId, String deviceCategory,
            boolean enabled, int parameter, long revision, Long updatedAt, Long updatedBy, Integer recoveryCycles,
            Integer triggerMinutes, Integer recoveryMinutes, String conditionLogic, String classification, List<Condition> conditions, Advanced advanced) {
        public Rule(String id,String type,String name,String severity,String qualityLevel,String spaceId,String deviceCategory,
                boolean enabled,int parameter,long revision,Long updatedAt,Long updatedBy,Integer recoveryCycles,Integer triggerMinutes,
                Integer recoveryMinutes,String conditionLogic,String classification,List<Condition> conditions) {
            this(id,type,name,severity,qualityLevel,spaceId,deviceCategory,enabled,parameter,revision,updatedAt,updatedBy,
                    recoveryCycles,triggerMinutes,recoveryMinutes,conditionLogic,classification,conditions,null);
        }
        public Rule(String id,String type,String name,String severity,String qualityLevel,String spaceId,String deviceCategory,
                boolean enabled,int parameter,long revision,Long updatedAt,Long updatedBy) {
            this(id,type,name,severity,qualityLevel,spaceId,deviceCategory,enabled,parameter,revision,updatedAt,updatedBy,1,0,0,"ALL",null,List.of());
        }
    }
    public record SaveRule(boolean enabled, int parameter, long revision) { }
    public record RuleRequest(String type, String name, String severity, String qualityLevel, String spaceId, String deviceCategory,
            boolean enabled, int parameter, Long revision, Integer recoveryCycles, Integer triggerMinutes, Integer recoveryMinutes,
            String conditionLogic, String classification, List<Condition> conditions, Advanced advanced) {
        public RuleRequest(String type,String name,String severity,String qualityLevel,String spaceId,String deviceCategory,
                boolean enabled,int parameter,Long revision,Integer recoveryCycles,Integer triggerMinutes,Integer recoveryMinutes,
                String conditionLogic,String classification,List<Condition> conditions) {
            this(type,name,severity,qualityLevel,spaceId,deviceCategory,enabled,parameter,revision,recoveryCycles,triggerMinutes,
                    recoveryMinutes,conditionLogic,classification,conditions,null);
        }
        public RuleRequest(String type,String name,String severity,String qualityLevel,String spaceId,String deviceCategory,
                boolean enabled,int parameter,Long revision) {
            this(type,name,severity,qualityLevel,spaceId,deviceCategory,enabled,parameter,revision,null,null,null,null,null,List.of());
        }
    }
    /** 单层数值比较；QUALITY规则只使用pointId绑定测点，质量值来自正式冻结分钟。 */
    public record Condition(String pointId,String operator,BigDecimal value,BigDecimal upperValue,List<StateOption> states,String dictionaryId,Long dictionaryRevision) {
        public Condition(String pointId,String operator,BigDecimal value,BigDecimal upperValue,List<StateOption> states) {
            this(pointId,operator,value,upperValue,states,null,null);
        }
        public Condition(String pointId,String operator,BigDecimal value,BigDecimal upperValue) {
            this(pointId,operator,value,upperValue,List.of());
        }
        public Condition {
            states=states==null?List.of():states;
        }
    }
    /** DIGITAL编码的中文展示字典；报警判断仍只比较Condition.value中的数值。 */
    public record StateOption(BigDecimal value,String label) { }
    public record Option(String value, String label) { }
    public record PointOption(String value,String label,String unit,String dataType,String equipmentId,String spaceId,String deviceCategory,String dictionaryId,Long dictionaryRevision,List<StateOption> states) {
        public PointOption(String value,String label,String unit,String dataType,String equipmentId,String spaceId,String deviceCategory) {
            this(value,label,unit,dataType,equipmentId,spaceId,deviceCategory,null,null,List.of());
        }
    }
    /** 可选扩展保持旧规则JSON可读；触发与恢复条件使用相同的有界条件树。 */
    public record Advanced(String primaryEquipmentId, ConditionNode trigger, ConditionNode recovery,
            Timing timing, Window window, EmissionSettings emission) { }
    public record ConditionNode(String logic, List<ConditionNode> children, Condition condition) { }
    public record Timing(Integer expectedIntervalSeconds, Integer allowedDelaySeconds, Integer alignmentSeconds,
            Integer staleAfterSeconds) { }
    public record Window(Integer minutes, BigDecimal tolerance, String changeMode, String runningPointId,
            BigDecimal runningValue) { }
    public record EmissionSettings(String template, String period, BigDecimal threshold, BigDecimal recoveryThreshold,
            String changeMode, String baselineModelId, String forecastMethod,java.util.Map<String,BigDecimal> parameters) {
        public EmissionSettings(String template,String period,BigDecimal threshold,BigDecimal recoveryThreshold,String changeMode,String baselineModelId,String forecastMethod) {
            this(template,period,threshold,recoveryThreshold,changeMode,baselineModelId,forecastMethod,java.util.Map.of());
        }
    }
    public record StateDictionary(String id,String name,long revision,List<StateOption> states,long updatedAt,Long updatedBy) { }
    public record SaveDictionary(String name,Long revision,List<StateOption> states) { }
    public record DictionaryBinding(String dictionaryId,Long dictionaryRevision) { }
    public record TrendSample(long at,double value,int quality) { }
    public record TrendSeries(String pointId,String pointName,String unit,List<TrendSample> samples,boolean truncated,String unavailableReason) {
        public TrendSeries(String pointId,String pointName,String unit,List<TrendSample> samples) { this(pointId,pointName,unit,samples,false,null); }
    }
    public record RuleOptions(List<Option> spaces, List<Option> deviceCategories, List<PointOption> points,List<Option> equipment) {
        public RuleOptions(List<Option> spaces,List<Option> deviceCategories,List<PointOption> points) { this(spaces,deviceCategories,points,List.of()); }
        public RuleOptions(List<Option> spaces,List<Option> deviceCategories) { this(spaces,deviceCategories,List.of()); }
    }
    public record Note(String note) { }
    public record Silence(int minutes, String note) { }
    public record Group(String type, long count) { }
    public record Summary(Long deviceAnomalies, Long emissionWarnings, List<Group> deviceGroups,
            List<Group> emissionGroups, long updatedAt, String period, String deviceReason, String emissionReason) { }
    public record Event(String id, String buildingId, String category, String type, String ruleId, String objectId,
            String objectName, String spaceName, String period, String status, String severity,
            String description, long firstAt, long lastAt, Long recoveredAt, Long ackBy, Long ackAt,
            Long silencedUntil, long ruleRevision, String evidence, String triggerEvidence, List<Log> logs,Long closedAt,Long closedBy) {
        public Event(String id,String buildingId,String category,String type,String ruleId,String objectId,String objectName,
                String spaceName,String period,String status,String severity,String description,long firstAt,long lastAt,
                Long recoveredAt,Long ackBy,Long ackAt,Long silencedUntil,long ruleRevision,String evidence,String triggerEvidence,List<Log> logs) {
            this(id,buildingId,category,type,ruleId,objectId,objectName,spaceName,period,status,severity,description,firstAt,lastAt,
                    recoveredAt,ackBy,ackAt,silencedUntil,ruleRevision,evidence,triggerEvidence,logs,null,null);
        }
    }
    public record Log(long at, Long actor, String action, String note) { }
    public record Candidate(String type, String ruleId, String objectId, String objectName, String spaceId,
            String deviceCategory, String spaceName, String period, String severity, String description, String evidence,
            boolean matched, long observationAt, String observationKey, String observationCycleKey,Boolean recoveryMatched) {
        public Candidate(String type,String ruleId,String objectId,String objectName,String spaceId,String deviceCategory,
                String spaceName,String period,String severity,String description,String evidence,boolean matched,long observationAt,
                String observationKey,String observationCycleKey) {
            this(type,ruleId,objectId,objectName,spaceId,deviceCategory,spaceName,period,severity,description,evidence,matched,
                    observationAt,observationKey,observationCycleKey,null);
        }
        public Candidate(String type,String ruleId,String objectId,String objectName,String spaceId,String deviceCategory,
                String spaceName,String period,String severity,String description,String evidence) {
            this(type,ruleId,objectId,objectName,spaceId,deviceCategory,spaceName,period,severity,description,evidence,true,0,null,null);
        }
    }
    /** 没有适用设备也可完整对账，但计数仍由 unavailableReason 标为缺失。 */
    public record DeviceResult(List<Candidate> candidates, boolean complete, String unavailableReason,java.util.Set<String> unavailableRuleIds) {
        public DeviceResult(List<Candidate> candidates,boolean complete,String reason) { this(candidates,complete,reason,java.util.Set.of()); }
    }
    public record Quota(String buildingId, YearMonth month,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actual,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quota, String planVersion,
            String batchId, String evidenceHash, String unavailableReason) { }
}
