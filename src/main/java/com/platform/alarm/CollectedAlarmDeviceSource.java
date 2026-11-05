package com.platform.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.collection.runtime.CollectionRuntimeStateService;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.HvacMinuteRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.*;
import java.math.BigDecimal;
import java.util.stream.Collectors;
import static com.platform.alarm.AlarmModels.*;

@Component
/**
 * 只读取正式采集配置、正式冻结分钟、原始事件及设备故障，不写采集状态。通信超时使用设备的最慢应报周期，
 * 任一有效测点的新鲜上报均证明通信；迟到补传、合成值及未来时间不能刷新通信时间。
 */
public class CollectedAlarmDeviceSource implements AlarmDeviceSource {
    private final JdbcTemplate jdbc;
    private final CollectionRuntimeStateService runtime;
    private final HvacRawEventRepository raw;
    private final HvacMinuteRepository minutes;
    private final ObjectMapper json;
    private final List<AlarmFaultSource> faultSources;

    @org.springframework.beans.factory.annotation.Autowired
    public CollectedAlarmDeviceSource(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            CollectionRuntimeStateService runtime, HvacRawEventRepository raw, HvacMinuteRepository minutes, ObjectMapper json,
            List<AlarmFaultSource> faultSources) {
        this.jdbc = jdbc;
        this.runtime = runtime;
        this.raw = raw;
        this.minutes = minutes;
        this.json = json;
        this.faultSources = faultSources == null ? List.of() : List.copyOf(faultSources);
    }

    public CollectedAlarmDeviceSource(JdbcTemplate jdbc, CollectionRuntimeStateService runtime, HvacRawEventRepository raw,
            HvacMinuteRepository minutes, ObjectMapper json) {
        this(jdbc, runtime, raw, minutes, json, List.of(new DaikinAlarmFaultSource(jdbc)));
    }

    CollectedAlarmDeviceSource(JdbcTemplate jdbc, CollectionRuntimeStateService runtime, HvacRawEventRepository raw, ObjectMapper json) {
        this(jdbc,runtime,raw,null,json,List.of(new DaikinAlarmFaultSource(jdbc)));
    }

    @Override
    public DeviceResult load(String building, List<Rule> rules, long now) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> unavailableRuleIds = new LinkedHashSet<>();
        List<String> unavailableReasons = new ArrayList<>();
        boolean covered = false;
        List<Rule> offlineRules=rules.stream().filter(r->r.type().equals("OFFLINE")&&r.enabled()).toList();
        List<Rule> faultRules=rules.stream().filter(r->r.type().equals("FAULT")&&r.enabled()).toList();
        if (!offlineRules.isEmpty()) {
            try {
            var snapshot = runtime.snapshot();
            Map<String, List<Point>> devices = new LinkedHashMap<>();
            for (var policy : snapshot.policies().values()) {
                if (!building.equals(policy.getBuildingId())) continue;
                var version = snapshot.activeVersions().get(policy.getActiveVersionId());
                if (version == null || !Integer.valueOf(1).equals(version.getEnabledFlag())
                        || version.getExpectedIntervalSeconds() == null || version.getExpectedIntervalSeconds() <= 0
                        || version.getEffectiveFrom() == null
                        || version.getEffectiveFrom().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() > now) continue;
                var source = snapshot.sources().get(policy.getSourceId());
                if (source == null) throw new IllegalStateException("采集配置尚未应用");
                var applied = runtime.state(source.getSourceId(), source.getStatus(), source.getRuntimeRevision());
                if (!"APPLIED".equals(applied.applyStatus().name())
                        || !Objects.equals(applied.appliedRuntimeRevision(), source.getRuntimeRevision())) {
                    throw new IllegalStateException("采集配置尚未应用");
                }
                var alias = snapshot.aliases().get(policy.getAliasId());
                if (alias == null) continue;
                List<Point> points = jdbc.query("""
                        SELECT p.point_id,e.equip_id,e.equip_name,e.space_id,e.equip_category,s.space_name
                        FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id
                          AND e.building_id=p.building_id AND e.del_flag=0
                        LEFT JOIN biz_space s ON s.space_id=e.space_id AND s.building_id=e.building_id AND s.del_flag=0
                        WHERE p.point_id=? AND p.building_id=? AND p.status='ONLINE'
                        """, (rs, row) -> new Point(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),rs.getString(6),
                        version.getExpectedIntervalSeconds(), version.getAllowedDelaySeconds() == null ? 0 : version.getAllowedDelaySeconds(),
                        version.getEffectiveFrom().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()),
                        alias.getPointId(), building);
                points.forEach(point -> devices.computeIfAbsent(point.device(), key -> new ArrayList<>()).add(point));
            }
            if (devices.size() > 500) throw new IllegalStateException("监测设备超过单轮处理上限");
            for (var points : devices.values()) {
                Point first = points.getFirst();
                covered |= offlineRules.stream().anyMatch(rule->matchesScope(rule,first.spaceId(),first.deviceCategory()));
                List<String> ids = points.stream().map(Point::id).distinct().toList();
                if (ids.size() > 500) throw new IllegalStateException("设备测点超过查询上限");
                long latest = points.stream().mapToLong(Point::enabledAt).max().orElse(now);
                long monitoringSince = latest;
                Long lastCommunication = null;
                long interval = points.stream().mapToLong(Point::interval).max().orElseThrow() * 1000L;
                long delay = points.stream().mapToLong(Point::delay).max().orElse(0) * 1000L;
                for (var reading : raw.findLatestByEquipmentPoints(building, first.device(), ids)) {
                    long enabledAt = points.stream().filter(p -> p.id().equals(reading.pointId())).mapToLong(Point::enabledAt).max().orElse(Long.MAX_VALUE);
                    if (ids.contains(reading.pointId()) && reading.dataQuality() == 0 && reading.eventTime() <= now && reading.eventTime() >= enabledAt
                            && reading.receivedTime() <= now && reading.receivedTime() >= reading.eventTime()
                            && reading.receivedTime() - reading.eventTime() <= interval + delay) {
                        latest = Math.max(latest, reading.receivedTime());
                        lastCommunication = lastCommunication == null ? reading.receivedTime() : Math.max(lastCommunication, reading.receivedTime());
                    }
                }
                for(Rule offline:offlineRules) {
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("lastCommunicationAt", lastCommunication);
                    evidence.put("monitoringSince", monitoringSince);
                    evidence.put("intervalSeconds", interval / 1000);
                    evidence.put("allowedDelaySeconds", delay / 1000);
                    long triggerAt=latest+interval*offline.parameter()+delay;
                    long cycleIndex=lastCommunication==null?-1:Math.floorDiv(lastCommunication-monitoringSince,interval);
                    long cycleAt=cycleIndex<0?latest:monitoringSince+cycleIndex*interval;
                    boolean missing=now-latest>=interval*offline.parameter()+delay;
                    String cycle=cycleIndex<0?"NONE":Long.toString(cycleIndex);
                    candidates.add(new Candidate("OFFLINE",offline.id(),first.device(),first.name(),first.spaceId(),first.deviceCategory(),first.space(),null,
                            offline.severity(), "连续" + offline.parameter() + "个应报周期未收到新鲜有效数据",
                            encode(evidence),missing,missing?triggerAt:cycleAt,
                            "OFFLINE:"+cycle+":"+(missing?"MISSING":"HEALTHY"),"OFFLINE:"+cycle));
                }
            }
            } catch (RuntimeException failure) {
                unavailableRuleIds.addAll(offlineRules.stream().map(Rule::id).toList());
                unavailableReasons.add("通信监测来源不可用: " + Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName()));
            }
        }
        if (!faultRules.isEmpty()) {
            try {
                List<PointScope> targets = jdbc.query("""
                        SELECT e.equip_name,e.space_id,e.equip_category,s.space_name
                        FROM biz_daikin_monitoring_target t
                        JOIN biz_equipment e ON e.equip_id=t.equipment_id AND e.building_id=t.building_id AND e.del_flag=0
                        JOIN biz_device_identity i ON i.identity_id=t.identity_id AND i.equip_id=e.equip_id
                          AND i.building_id=e.building_id AND i.status=1
                        LEFT JOIN biz_space s ON s.space_id=e.space_id AND s.building_id=e.building_id AND s.del_flag=0
                        WHERE e.building_id=? AND t.active=1
                        """, (rs, row) -> new PointScope(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), building);
                covered |= targets.stream().anyMatch(target -> faultRules.stream()
                        .anyMatch(rule -> matchesScope(rule, target.spaceId(), target.deviceCategory())));
            } catch (RuntimeException failure) {
                unavailableRuleIds.addAll(faultRules.stream().map(Rule::id).toList());
                unavailableReasons.add("故障设备范围读取失败");
            }
            List<AlarmFaultSource.FaultReading> readings = new ArrayList<>();
            for (AlarmFaultSource source : faultSources) {
                try {
                    List<AlarmFaultSource.FaultReading> loaded = source.load(building);
                    if (loaded != null) readings.addAll(loaded);
                } catch (RuntimeException failure) {
                    unavailableRuleIds.addAll(faultRules.stream().map(Rule::id).toList());
                    unavailableReasons.add("故障来源读取失败: " + source.getClass().getSimpleName());
                }
            }
            Map<String, AlarmFaultSource.FaultReading> distinctReadings = new LinkedHashMap<>();
            Set<String> faultedDevices = new LinkedHashSet<>();
            for (var reading : readings) {
                if (reading == null || !Objects.equals(building, reading.buildingId())
                        || reading.identityId() == null || reading.deviceId() == null) continue;
                distinctReadings.putIfAbsent(reading.identityId() + "|" + reading.deviceId() + "|" + reading.code(), reading);
                if (Boolean.TRUE.equals(reading.active())) faultedDevices.add(reading.deviceId());
            }
            for (var reading : distinctReadings.values()) {
                List<PointScope> rows;
                try {
                    rows = jdbc.query("""
                        SELECT e.equip_name,e.space_id,e.equip_category,s.space_name
                        FROM biz_equipment e LEFT JOIN biz_space s ON s.space_id=e.space_id
                          AND s.building_id=e.building_id AND s.del_flag=0
                        JOIN biz_device_identity i ON i.identity_id=? AND i.equip_id=e.equip_id
                          AND i.building_id=e.building_id AND i.status=1
                        WHERE e.equip_id=? AND e.building_id=? AND e.del_flag=0
                        """, (rs, row) -> new PointScope(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                        reading.identityId(), reading.deviceId(), building);
                } catch (RuntimeException failure) {
                    unavailableRuleIds.addAll(faultRules.stream().map(Rule::id).toList());
                    unavailableReasons.add("故障设备归属核验失败");
                    continue;
                }
                if (rows.isEmpty()) continue;
                PointScope target = rows.getFirst();
                covered |= faultRules.stream().anyMatch(rule -> matchesScope(rule, target.spaceId(), target.deviceCategory()));
                boolean active = Boolean.TRUE.equals(reading.active());
                if (!active && (faultedDevices.contains(reading.deviceId())
                        || Boolean.FALSE.equals(reading.active()) && reading.observationCycle() == null)) continue;
                boolean healthy = Boolean.FALSE.equals(reading.active()) && reading.eventAt() <= now
                        && now - reading.eventAt() <= 300_000L;
                String cycle = reading.observationCycle() == null ? null
                        : "DAIKIN_" + reading.identityId() + ":" + reading.observationCycle();
                String observationKey = active || healthy
                        ? reading.identityId() + ":" + reading.code() + ":" + reading.eventAt() : null;
                for (Rule rule : faultRules) {
                    if (!matchesScope(rule, target.spaceId(), target.deviceCategory())) continue;
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("sourceIdentityId", reading.identityId());
                    evidence.put("sourceCode", reading.code());
                    evidence.put("sourceEventAt", reading.eventAt());
                    evidence.put("sourceMessage", reading.message());
                    candidates.add(new Candidate("FAULT", rule.id(), reading.deviceId(), target.name(), target.spaceId(),
                            target.deviceCategory(), target.space(), null, rule.severity(),
                            Objects.requireNonNullElse(reading.message(), "设备报告故障"), encode(evidence),
                            active, reading.eventAt(), observationKey, cycle, healthy));
                }
            }
        }
        evaluateExtendedRules(building, rules, now, candidates, unavailableRuleIds, unavailableReasons);
        List<Rule> valueRules=rules.stream().filter(r->r.enabled()&&Set.of("QUALITY","CONDITION").contains(r.type())
                &&(r.advanced()==null||r.type().equals("QUALITY")&&timingOnly(r.advanced()))).toList();
        if(!valueRules.isEmpty()) {
            List<Rule> evaluableRules = new ArrayList<>(valueRules);
            Map<String,PointMeta> pointMeta=new HashMap<>();
            for(Rule rule:valueRules) {
                try {
                    if(rule.type().equals("QUALITY")&&minutes==null) throw new IllegalStateException("正式分钟数据源暂不可用");
                    for(Condition condition:rule.conditions()) {
                        List<PointMeta> rows=jdbc.query("""
                                SELECT p.point_id,p.point_name,p.unit,p.data_type,e.equip_id,e.equip_name,e.space_id,e.equip_category,s.space_name
                                FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0
                                LEFT JOIN biz_space s ON s.space_id=e.space_id AND s.building_id=e.building_id AND s.del_flag=0
                                WHERE p.point_id=? AND p.building_id=? AND p.status='ONLINE' AND p.del_flag=0
                                """,(rs,n)->new PointMeta(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),condition.pointId(),building);
                        if(rows.isEmpty()) throw new IllegalStateException("规则测点已不存在或未上线");
                        pointMeta.put(condition.pointId(),rows.getFirst());
                    }
                } catch(RuntimeException failure) {
                    unavailableRuleIds.add(rule.id());
                    unavailableReasons.add("规则 "+rule.id()+" 的测点元数据不可用");
                    evaluableRules.remove(rule);
                }
            }
            Map<String,com.platform.iot.temporal.model.HvacMinuteQueryRow> latest;
            try {
                latest=minutes==null?Map.of():minutes.findLatestByPointIds(new ArrayList<>(pointMeta.keySet())).stream().collect(Collectors.toMap(
                        com.platform.iot.temporal.model.HvacMinuteQueryRow::pointId, row->row,(a,b)->a));
            } catch(RuntimeException failure) {
                for(Rule rule:evaluableRules) {unavailableRuleIds.add(rule.id());unavailableReasons.add("正式分钟数据源读取失败");}
                evaluableRules.clear();
                latest=Map.of();
            }
            for(Rule rule:evaluableRules) {
                try {
                List<Condition> conditions=rule.conditions();
                if(conditions.isEmpty()) continue;
                Timing qualityTiming=rule.advanced()==null?null:rule.advanced().timing();
                Map<String,Long> freshness=rule.type().equals("QUALITY")?qualityFreshnessLimits(building,
                        conditions.stream().map(Condition::pointId).collect(Collectors.toSet()),qualityTiming,now):Map.of();
                // 一个规则绑定的点位必须属于同一设备，不能跨设备拼接条件。
                PointMeta first=pointMeta.get(conditions.getFirst().pointId());
                if(conditions.stream().map(c->pointMeta.get(c.pointId()).equipmentId()).distinct().count()!=1)
                    throw new IllegalStateException("规则测点不属于同一设备");
                List<BigDecimal> values=new ArrayList<>(); List<Integer> qualities=new ArrayList<>(); List<Long> times=new ArrayList<>(); List<String> names=new ArrayList<>();
                if(rule.type().equals("QUALITY")) {
                    for(Condition c:conditions) {var row=latest.get(c.pointId());PointMeta meta=pointMeta.get(c.pointId());
                        if(row==null||row.time()>now||now-row.time()>freshness.getOrDefault(c.pointId(),120_000L)||meta==null||row.dataQuality()<0||row.dataQuality()>2||!Double.isFinite(row.average()))throw new IllegalStateException("规则测点正式分钟缺失、质量无效或过期");
                        values.add(BigDecimal.valueOf(row.average()));qualities.add(row.dataQuality());times.add(row.time());names.add(meta.name());}
                } else {
                    List<String> ids=conditions.stream().map(Condition::pointId).toList();
                    Map<String,com.platform.iot.temporal.model.LatestRawReading> rawRows=raw.findLatestByEquipmentPoints(building,first.equipmentId(),ids).stream().collect(Collectors.toMap(
                            com.platform.iot.temporal.model.LatestRawReading::pointId,row->row,(a,b)->a));
                    for(Condition c:conditions) {var row=rawRows.get(c.pointId());PointMeta meta=pointMeta.get(c.pointId());
                        long freshLimit=freshness.getOrDefault(c.pointId(),120_000L);
                        if(row==null||row.dataQuality()!=0||!Double.isFinite(row.value())||row.eventTime()>now||now-row.eventTime()>freshLimit
                                ||row.receivedTime()>now||row.receivedTime()<row.eventTime()||row.receivedTime()-row.eventTime()>freshLimit
                                ||now-row.receivedTime()>freshLimit||meta==null)throw new IllegalStateException("条件测点原始值缺失、质量不合格或过期");
                        values.add(BigDecimal.valueOf(row.value()));qualities.add(row.dataQuality());times.add(row.eventTime());names.add(meta.name());}
                }
                long minute=times.getFirst()/60_000;
                Integer explicitAlignment=qualityTiming==null?null:qualityTiming.alignmentSeconds();
                long timeDifference=times.stream().mapToLong(Long::longValue).max().orElse(0)
                        -times.stream().mapToLong(Long::longValue).min().orElse(0);
                if(explicitAlignment!=null?timeDifference>explicitAlignment*1000L:times.stream().anyMatch(time->time/60_000!=minute))
                    throw new IllegalStateException("规则测点观测时间未对齐");
                boolean matched=rule.type().equals("QUALITY")
                        ? qualities.stream().anyMatch(quality->quality>qualityNumber(rule.qualityLevel()))
                        : combine(conditions,values,rule.conditionLogic());
                long observationAt=times.stream().mapToLong(Long::longValue).max().orElse(0);
                String observationKey=conditions.stream().map(c->c.pointId()+":"+times.get(conditions.indexOf(c))+":"+values.get(conditions.indexOf(c))+":"+qualities.get(conditions.indexOf(c))).sorted().collect(Collectors.joining("|"));
                Sampling qualitySampling=rule.type().equals("QUALITY")&&qualityTiming!=null?sampling(building,
                        conditions.stream().map(Condition::pointId).collect(Collectors.toSet()),qualityTiming,now):null;
                // 对齐容差只限制关联时间差；连续周期始终按应报周期，避免慢采样被误判为断档。
                String cycleKey=qualitySampling==null?"MINUTE:"+minute:"SAMPLE"+qualitySampling.intervalMillis()+":"+Math.floorDiv(observationAt,qualitySampling.intervalMillis());
                Map<String,Object> evidence=new LinkedHashMap<>(); evidence.put("classification",Objects.toString(rule.classification(),"VALUE"));
                evidence.put("qualityLevel",rule.qualityLevel()); evidence.put("conditionLogic",rule.conditionLogic()); evidence.put("observationKey",observationKey);
                evidence.put("triggerMinutes",rule.triggerMinutes()); evidence.put("recoveryMinutes",rule.recoveryMinutes()); evidence.put("recoveryCycles",rule.recoveryCycles());
                evidence.put("observations",conditions.stream().map(c->{var meta=pointMeta.get(c.pointId());int index=conditions.indexOf(c);Map<String,Object> item=new LinkedHashMap<>();item.put("pointId",c.pointId());item.put("pointName",names.get(index));item.put("value",values.get(index));item.put("unit",meta.unit());item.put("dataQuality",qualities.get(index));item.put("eventTime",times.get(index));return item;}).toList());
                String eventType=rule.type().equals("QUALITY")?"QUALITY":Objects.toString(rule.classification(),"VALUE");
                candidates.add(new Candidate(eventType,rule.id(),first.equipmentId(),first.equipmentName(),first.spaceId(),first.deviceCategory(),first.spaceName(),null,
                        rule.severity(),matched?rule.name()+"条件已满足":rule.name()+"条件已解除",encode(evidence),matched,observationAt,observationKey,cycleKey));
                covered |= matchesScope(rule,first.spaceId(),first.deviceCategory());
                } catch(RuntimeException failure) {
                    unavailableRuleIds.add(rule.id());
                    unavailableReasons.add("规则 "+rule.id()+" 的观测值不可用");
                }
            }
        }
        // 配置查询成功但已无监测对象时仍清理旧活动事件，卡片通过原因字段表达覆盖缺失。
        covered |= candidates.stream().anyMatch(candidate->!unavailableRuleIds.contains(candidate.ruleId())&&rules.stream()
                .anyMatch(rule->rule.id().equals(candidate.ruleId())&&matchesScope(rule,candidate.spaceId(),candidate.deviceCategory())));
        String unavailable = unavailableReasons.isEmpty() ? null : String.join("；", unavailableReasons);
        if (unavailable == null && !covered) unavailable = "暂无适用的已启用监测设备";
        return new DeviceResult(candidates, true, unavailable, unavailableRuleIds);
    }

    private void evaluateExtendedRules(String building, List<Rule> rules, long now, List<Candidate> candidates,
            Set<String> unavailableRuleIds, List<String> unavailableReasons) {
        for (Rule rule : rules) {
            if (!rule.enabled() || rule.advanced() == null || !Set.of("CONDITION", "JUMP", "STUCK").contains(rule.type())) continue;
            try {
                evaluateExtendedRule(building, rule, now, candidates);
            } catch (RuntimeException failure) {
                unavailableRuleIds.add(rule.id());
                unavailableReasons.add("规则 " + rule.id() + " 的判定数据不可用");
            }
        }
    }

    private void evaluateExtendedRule(String building, Rule rule, long now, List<Candidate> candidates) {
        Advanced advanced = rule.advanced();
        if (advanced.primaryEquipmentId() == null || advanced.primaryEquipmentId().isBlank()) throw new IllegalArgumentException("主设备未配置");
        List<ConditionNode> trees = new ArrayList<>();
        if (advanced.trigger() != null) trees.add(advanced.trigger());
        else if (rule.type().equals("CONDITION")) trees.add(legacyTree(rule.conditions(), rule.conditionLogic()));
        if (advanced.recovery() != null) trees.add(advanced.recovery());
        Set<String> pointIds = new LinkedHashSet<>();
        trees.forEach(tree -> collectPoints(tree, pointIds));
        if (rule.conditions() != null) rule.conditions().stream().filter(Objects::nonNull).map(Condition::pointId)
                .filter(Objects::nonNull).forEach(pointIds::add);
        if (advanced.window() != null && advanced.window().runningPointId() != null) pointIds.add(advanced.window().runningPointId());
        if (pointIds.isEmpty()) throw new IllegalArgumentException("规则没有关联测点");

        Map<String, PointMeta> pointMeta = new LinkedHashMap<>();
        for (String pointId : pointIds) {
            List<PointMeta> rows = jdbc.query("""
                    SELECT p.point_id,p.point_name,p.unit,p.data_type,e.equip_id,e.equip_name,e.space_id,e.equip_category,s.space_name
                    FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0
                    LEFT JOIN biz_space s ON s.space_id=e.space_id AND s.building_id=e.building_id AND s.del_flag=0
                    WHERE p.point_id=? AND p.building_id=? AND p.status='ONLINE' AND p.del_flag=0
                    """, (rs, row) -> new PointMeta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), pointId, building);
            if (rows.size() != 1) throw new IllegalArgumentException("条件测点不存在或未上线");
            pointMeta.put(pointId, rows.getFirst());
        }
        EquipmentMeta primary = equipmentMeta(building, advanced.primaryEquipmentId());
        if (!matchesScope(rule, primary.spaceId(), primary.deviceCategory())) throw new IllegalArgumentException("主设备超出规则范围");
        Sampling sampling = sampling(building, pointIds, advanced.timing(), now);
        if (sampling == null) throw new IllegalArgumentException("缺少已应用或显式采样周期");
        Map<String, Measurement> measurements = latestMeasurements(building, rule, pointMeta, now, sampling);
        if (rule.type().equals("CONDITION")) {
            Boolean triggered = AlarmRuleEvaluator.condition(trees.getFirst(), values(measurements));
            Boolean recovered = advanced.recovery() == null ? null : AlarmRuleEvaluator.condition(advanced.recovery(), values(measurements));
            if (triggered == null || advanced.recovery() != null && recovered == null) throw new IllegalArgumentException("条件观测不完整");
            long observedAt = measurements.values().stream().mapToLong(Measurement::eventAt).max().orElse(now);
            String key = measurements.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue().eventAt() + ":" + entry.getValue().value())
                    .sorted().collect(Collectors.joining("|"));
            Map<String, Object> evidence = extendedEvidence(rule, pointMeta, measurements, sampling);
            candidates.add(new Candidate(Objects.toString(rule.classification(), "VALUE"), rule.id(), advanced.primaryEquipmentId(), primary.equipmentName(), primary.spaceId(),
                    primary.deviceCategory(), primary.spaceName(), null, rule.severity(),
                    Boolean.TRUE.equals(triggered) ? rule.name() + "触发条件已满足" : rule.name() + "触发条件未满足",
                    encode(evidence), Boolean.TRUE.equals(triggered), observedAt, key,
                    "SAMPLE"+sampling.intervalMillis()+":" + Math.floorDiv(observedAt,sampling.intervalMillis()), recovered));
            return;
        }
        Window window = advanced.window();
        if (window == null) throw new IllegalArgumentException("判定窗口未配置");
        long from = now - window.minutes() * 60_000L;
        if (rule.conditions() == null || rule.conditions().isEmpty()) throw new IllegalArgumentException("窗口规则没有目标测点");
        String targetPointId = rule.conditions().getFirst().pointId();
        String targetEquipment = pointMeta.get(targetPointId).equipmentId();
        List<com.platform.iot.temporal.model.RawTelemetryEvent> history = raw.findPointHistory(building, targetEquipment,
                targetPointId, from, now == Long.MAX_VALUE ? now : now + 1, null, 5001);
        if (history == null || history.size() > 5000) throw new IllegalArgumentException("原始历史超出判定上限");
        Sampling targetSampling=sampling(building,Set.of(targetPointId),advanced.timing(),now);
        long intervalMillis = targetSampling.intervalMillis();
        long delayMillis = targetSampling.delayMillis();
        long staleMillis = targetSampling.staleMillis();
        Boolean matched;
        if (rule.type().equals("JUMP")) {
            matched = AlarmRuleEvaluator.jump(history, now, window.minutes() * 60_000L, intervalMillis, delayMillis,
                    staleMillis, window.tolerance(), window.changeMode());
        } else {
            List<com.platform.iot.temporal.model.RawTelemetryEvent> running = raw.findPointHistory(building,
                    pointMeta.get(window.runningPointId()).equipmentId(), window.runningPointId(),
                    from, now == Long.MAX_VALUE ? now : now + 1, null, 5001);
            if (running == null || running.size() > 5000) throw new IllegalArgumentException("运行状态历史超出判定上限");
            Sampling runningSampling=sampling(building,Set.of(window.runningPointId()),advanced.timing(),now);
            matched = AlarmRuleEvaluator.stuck(history, running, now, window.minutes() * 60_000L, intervalMillis,
                    delayMillis, staleMillis, window.tolerance(), window.changeMode(), window.runningValue(),
                    runningSampling.intervalMillis(),runningSampling.delayMillis(),runningSampling.staleMillis());
        }
        if (matched == null) throw new IllegalArgumentException("窗口历史存在缺口、迟到或过期");
        long observationAt = history.stream().filter(sample -> sample.eventTime() <= now).mapToLong(com.platform.iot.temporal.model.RawTelemetryEvent::eventTime).max().orElse(now);
        Map<String, Object> evidence = extendedEvidence(rule, pointMeta, measurements, sampling);
        evidence.put("windowMinutes", window.minutes());
        evidence.put("tolerance", window.tolerance());
        evidence.put("changeMode", window.changeMode());
        evidence.put("historyCount", history.size());
        String key = rule.id()+":"+measurements.entrySet().stream()
                .map(entry->entry.getKey()+":"+entry.getValue().eventAt()+":"+entry.getValue().value()).sorted().collect(Collectors.joining("|"));
        candidates.add(new Candidate(Objects.toString(rule.classification(),"VALUE"), rule.id(), advanced.primaryEquipmentId(), primary.equipmentName(), primary.spaceId(),
                primary.deviceCategory(), primary.spaceName(), null, rule.severity(), Boolean.TRUE.equals(matched) ? rule.name() + "判定成立" : rule.name() + "判定解除",
                encode(evidence), matched, observationAt, key, "WINDOW"+sampling.intervalMillis()+":" + Math.floorDiv(observationAt,sampling.intervalMillis()), !matched));
    }

    private Map<String, Measurement> latestMeasurements(String building, Rule rule, Map<String, PointMeta> pointMeta,
            long now, Sampling sampling) {
        List<String> ids = new ArrayList<>(pointMeta.keySet());
        Map<String, com.platform.iot.temporal.model.LatestRawReading> rawRows = new HashMap<>();
        pointMeta.values().stream().collect(Collectors.groupingBy(PointMeta::equipmentId)).forEach((equipmentId, metas) ->
                raw.findLatestByEquipmentPoints(building, equipmentId, metas.stream().map(PointMeta::id).toList())
                        .forEach(reading -> rawRows.putIfAbsent(reading.pointId(), reading)));
        Map<String, com.platform.iot.temporal.model.HvacMinuteQueryRow> minuteRows = minutes == null || rule.type().equals("QUALITY")
                ? Map.of() : minutes.findLatestByPointIds(ids).stream().collect(Collectors.toMap(
                        com.platform.iot.temporal.model.HvacMinuteQueryRow::pointId, value -> value, (left, right) -> left));
        Map<String, Measurement> result = new LinkedHashMap<>();
        int allowedQuality = qualityNumber(rule.qualityLevel());
        for (String pointId : ids) {
            Sampling pointSampling=sampling(building,Set.of(pointId),rule.advanced().timing(),now);
            if(pointSampling==null) throw new IllegalArgumentException("测点采样策略不可用");
            var minute = minuteRows.get(pointId);
            if (rule.type().equals("CONDITION") && !"DIGITAL".equals(pointMeta.get(pointId).dataType()) && allowedQuality > 0 && minute != null && minute.dataQuality() >= 0
                    && minute.dataQuality() <= allowedQuality && minute.time() <= now && now - minute.time() <= pointSampling.staleMillis()
                    && Double.isFinite(minute.average())) {
                result.put(pointId, new Measurement(BigDecimal.valueOf(minute.average()), minute.dataQuality(), minute.time(), "MINUTE"));
                continue;
            }
            var rawReading = rawRows.get(pointId);
            if (rawReading == null || rawReading.dataQuality() != 0 || !Double.isFinite(rawReading.value())
                    || rawReading.eventTime() > now || rawReading.receivedTime() > now || rawReading.receivedTime() < rawReading.eventTime()
                    || now - rawReading.eventTime() > pointSampling.staleMillis()
                    || rawReading.receivedTime() - rawReading.eventTime() > pointSampling.delayMillis()) continue;
            result.put(pointId, new Measurement(BigDecimal.valueOf(rawReading.value()), rawReading.dataQuality(), rawReading.eventTime(), "RAW"));
        }
        if (result.size() != pointMeta.size()) throw new IllegalArgumentException("测点数据过期或质量不满足规则");
        long alignmentMillis = sampling.alignmentMillis();
        if (result.size() > 1 && (alignmentMillis <= 0 || result.values().stream().mapToLong(Measurement::eventAt).max().orElse(0)
                - result.values().stream().mapToLong(Measurement::eventAt).min().orElse(0) > sampling.alignmentMillis())) {
            throw new IllegalArgumentException("规则测点时间未在显式对齐容差内");
        }
        return result;
    }

    private Sampling sampling(String building, Set<String> pointIds, Timing explicit, long now) {
        // 逐测点优先采用已APPLIED版本；没有该版本的点才回退到规则Timing。
        Map<String, AppliedSampling> appliedByPoint = new HashMap<>();
        var snapshot = runtime == null ? null : runtime.snapshot();
        if (snapshot != null) for (var policy : snapshot.policies().values()) {
            if (!building.equals(policy.getBuildingId())) continue;
            var alias = snapshot.aliases().get(policy.getAliasId());
            if (alias == null || !pointIds.contains(alias.getPointId())) continue;
            var version = snapshot.activeVersions().get(policy.getActiveVersionId());
            var source = snapshot.sources().get(policy.getSourceId());
            if (version == null || source == null || !Integer.valueOf(1).equals(version.getEnabledFlag())
                    || version.getExpectedIntervalSeconds() == null || version.getExpectedIntervalSeconds() <= 0
                    || version.getEffectiveFrom() == null
                    || version.getEffectiveFrom().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() > now) continue;
            var applied = runtime.state(source.getSourceId(), source.getStatus(), source.getRuntimeRevision());
            if (!"APPLIED".equals(applied.applyStatus().name()) || !Objects.equals(applied.appliedRuntimeRevision(), source.getRuntimeRevision())) continue;
            int delay = version.getAllowedDelaySeconds() == null ? 0 : version.getAllowedDelaySeconds();
            String pointId = alias.getPointId();
            AppliedSampling previous = appliedByPoint.get(pointId);
            appliedByPoint.put(pointId, previous == null ? new AppliedSampling(version.getExpectedIntervalSeconds(), delay)
                    : new AppliedSampling(Math.max(version.getExpectedIntervalSeconds(), previous.intervalSeconds()),
                            Math.max(delay, previous.delaySeconds())));
        }
        List<Integer> intervals = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        List<Integer> stales = new ArrayList<>();
        for (String pointId : pointIds) {
            AppliedSampling applied = appliedByPoint.get(pointId);
            int interval = applied == null ? explicit == null || explicit.expectedIntervalSeconds() == null
                    ? 0 : explicit.expectedIntervalSeconds() : applied.intervalSeconds();
            int delay = applied == null ? explicit == null || explicit.allowedDelaySeconds() == null
                    ? 0 : explicit.allowedDelaySeconds() : applied.delaySeconds();
            long staleMillis = freshnessLimitMillis(applied == null ? null : applied.intervalSeconds(),
                    applied == null ? null : applied.delaySeconds(), explicit, 0);
            if (interval <= 0 || staleMillis <= 0) return null;
            intervals.add(interval);
            delays.add(delay);
            stales.add(Math.toIntExact(staleMillis / 1000));
        }
        if (intervals.isEmpty()) return null;
        int interval = intervals.stream().mapToInt(Integer::intValue).max().orElse(0);
        int delay = delays.stream().mapToInt(Integer::intValue).max().orElse(0);
        int alignment = explicit == null || explicit.alignmentSeconds() == null ? 0 : explicit.alignmentSeconds();
        int stale = stales.stream().mapToInt(Integer::intValue).max().orElse(0);
        return new Sampling(interval * 1000L, delay * 1000L, alignment * 1000L, stale * 1000L);
    }

    private Map<String,Long> qualityFreshnessLimits(String building, Set<String> pointIds, Timing explicit, long now) {
        Map<String,Long> limits = new HashMap<>();
        for (String pointId : pointIds) {
            Sampling sampling = sampling(building, Set.of(pointId), explicit, now);
            limits.put(pointId, sampling == null ? freshnessLimitMillis(null, null, explicit, 120_000L) : sampling.staleMillis());
        }
        return limits;
    }

    static long freshnessLimitMillis(Integer appliedIntervalSeconds, Integer appliedDelaySeconds,
            Timing explicit, long legacyDefaultMillis) {
        if (explicit != null && explicit.staleAfterSeconds() != null && explicit.staleAfterSeconds() > 0) {
            return explicit.staleAfterSeconds() * 1000L;
        }
        Integer interval = appliedIntervalSeconds != null && appliedIntervalSeconds > 0 ? appliedIntervalSeconds
                : explicit == null ? null : explicit.expectedIntervalSeconds();
        if (interval == null || interval <= 0) return legacyDefaultMillis;
        int delay = appliedIntervalSeconds != null && appliedIntervalSeconds > 0
                ? Objects.requireNonNullElse(appliedDelaySeconds, 0)
                : explicit == null ? 0 : Objects.requireNonNullElse(explicit.allowedDelaySeconds(), 0);
        return (interval + delay) * 1000L;
    }

    private static boolean timingOnly(Advanced advanced) {
        return advanced.primaryEquipmentId() == null && advanced.trigger() == null && advanced.recovery() == null
                && advanced.window() == null && advanced.emission() == null;
    }

    private EquipmentMeta equipmentMeta(String building, String equipmentId) {
        List<EquipmentMeta> rows = jdbc.query("""
                SELECT e.equip_name,e.space_id,e.equip_category,s.space_name FROM biz_equipment e
                LEFT JOIN biz_space s ON s.space_id=e.space_id AND s.building_id=e.building_id AND s.del_flag=0
                WHERE e.building_id=? AND e.equip_id=? AND e.del_flag=0
                """, (rs, row) -> new EquipmentMeta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                building, equipmentId);
        if (rows.size() != 1) throw new IllegalArgumentException("主设备不存在或不属于当前建筑");
        return rows.getFirst();
    }

    private static ConditionNode legacyTree(List<Condition> conditions, String logic) {
        if (conditions == null || conditions.isEmpty()) return null;
        List<ConditionNode> leaves = conditions.stream().map(condition -> new ConditionNode(null, List.of(), condition)).toList();
        return leaves.size() == 1 ? leaves.getFirst() : new ConditionNode(logic, leaves, null);
    }

    private static void collectPoints(ConditionNode node, Set<String> points) {
        if (node == null) return;
        if (node.condition() != null) points.add(node.condition().pointId());
        else if (node.children() != null) node.children().forEach(child -> collectPoints(child, points));
    }

    private static Map<String, BigDecimal> values(Map<String, Measurement> measurements) {
        return measurements.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().value()));
    }

    private static Map<String, Object> extendedEvidence(Rule rule, Map<String, PointMeta> metas,
            Map<String, Measurement> measurements, Sampling sampling) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("primaryEquipmentId", rule.advanced().primaryEquipmentId());
        evidence.put("qualityLevel", rule.qualityLevel());
        evidence.put("expectedIntervalMillis", sampling.intervalMillis());
        evidence.put("allowedDelayMillis", sampling.delayMillis());
        evidence.put("alignmentToleranceMillis", sampling.alignmentMillis());
        evidence.put("staleAfterMillis", sampling.staleMillis());
        evidence.put("observations", measurements.entrySet().stream().map(entry -> Map.of("pointId", entry.getKey(),
                "pointName", metas.get(entry.getKey()).name(), "value", entry.getValue().value(), "quality", entry.getValue().quality(),
                "eventAt", entry.getValue().eventAt(), "source", entry.getValue().source())).toList());
        return evidence;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法保存报警依据", exception); }
    }
    private static boolean matchesScope(Rule rule,String spaceId,String deviceCategory){
        return (rule.spaceId()==null||rule.spaceId().isBlank()||Objects.equals(rule.spaceId(),spaceId))
                &&(rule.deviceCategory()==null||rule.deviceCategory().isBlank()||Objects.equals(rule.deviceCategory(),deviceCategory));
    }
    private static int qualityNumber(String quality){return switch(quality){case "Q0"->0;case "Q1"->1;case "Q2"->2;default->3;};}
    private static boolean combine(List<Condition> conditions,List<BigDecimal> values,String logic){
        List<Boolean> matches=new ArrayList<>();
        for(int i=0;i<conditions.size();i++) {Condition c=conditions.get(i);BigDecimal v=values.get(i);
            int cmp=v.compareTo(c.value()==null?BigDecimal.ZERO:c.value()); boolean match=switch(c.operator()){
                case "GT"->cmp>0;case "GE"->cmp>=0;case "LT"->cmp<0;case "LE"->cmp<=0;case "EQ"->cmp==0;case "NE"->cmp!=0;
                case "BETWEEN"->c.upperValue()!=null&&cmp>=0&&v.compareTo(c.upperValue())<=0;
                case "OUTSIDE"->c.upperValue()!=null&&(cmp<0||v.compareTo(c.upperValue())>0);default->false;}; matches.add(match);}
        return "ANY".equals(logic)?matches.stream().anyMatch(Boolean::booleanValue):matches.stream().allMatch(Boolean::booleanValue);
    }
    private record Point(String id, String device, String name, String spaceId, String deviceCategory, String space, int interval, int delay, long enabledAt) { }
    private record PointScope(String name, String spaceId, String deviceCategory, String space) { }
    private record PointMeta(String id,String name,String unit,String dataType,String equipmentId,String equipmentName,String spaceId,String deviceCategory,String spaceName) { }
    private record Measurement(BigDecimal value, int quality, long eventAt, String source) { }
    private record Sampling(long intervalMillis, long delayMillis, long alignmentMillis, long staleMillis) { }
    private record AppliedSampling(int intervalSeconds, int delaySeconds) { }
    private record EquipmentMeta(String equipmentName, String spaceId, String deviceCategory, String spaceName) { }
}
