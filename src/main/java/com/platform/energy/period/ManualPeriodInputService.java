package com.platform.energy.period;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.classification.ClassificationContracts.Reading;
import com.platform.energy.classification.ClassificationRepository;
import com.platform.relation.RelationGovernanceService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.platform.energy.period.EnergyPeriodModels.*;
import static com.platform.energy.period.EnergyPeriodErrors.*;

/** 人工审核来源只生成候选原生月量；独立封账批准后才成为正式输入，不补折标或碳值。 */
@Service
@RequiredArgsConstructor
public class ManualPeriodInputService {
    public record Request(@NotBlank String pointId, @NotNull LocalDate month,
                          @NotBlank String policyVersionId, @NotBlank String relationVersionId,
                          @NotNull @Size(min=1,max=500) List<@NotBlank String> factIds,
                          @NotNull @Min(0) Long expectedProjectionRevision,
                          @NotBlank @Size(max=500) String reason) { }
    public record Evidence(String inputKind, Request request, List<String> readingJson,
                           List<String> batchIds, String pointBindingVersionId, String relationVersionId,
                           String periodPolicyVersionId, String timezoneId, String energyItemVersionId) { }
    private final EnergyPeriodAuthorization authorization;
    private final ClassificationRepository manual;
    private final EnergyCatalogService catalog;
    private final EnergyPeriodGovernanceService policies;
    private final RelationGovernanceService relations;
    private final ObjectMapper mapper;

    @Transactional(rollbackFor=Exception.class)
    public ProjectionCalculation prepare(long user, Collection<String> roles, String building, Request request) {
        authorization.requireReader(roles);authorization.checkBuilding(user,roles,building);
        if(request==null||request.factIds()==null||request.factIds().isEmpty()||request.factIds().size()>500
                ||new HashSet<>(request.factIds()).size()!=request.factIds().size()||request.reason()==null||request.reason().isBlank()
                ||request.reason().length()>500)throw invalid("须选择1至500条不重复的已审核来源并填写原因");
        // 与人工导入审批使用同一建筑锁，冻结来源选择并排除正在替代的事实。
        manual.lock(building);
        var policy=policies.periodPolicyVersion(request.policyVersionId());
        if(!building.equals(policy.buildingId())||!"APPROVED".equals(policy.status())||!"FORMAL".equals(policy.sourceType()))
            throw invalid("需要本建筑已独立审核的FORMAL周期口径，不能沿用研发口径");
        var window=EnergyPeriodBoundary.resolve("MONTH",request.month(),policy.timezoneId());
        var from=LocalDateTime.ofInstant(window.startInclusive(),ZoneId.of(policy.timezoneId()));
        var end=LocalDateTime.ofInstant(window.endExclusive(),ZoneId.of(policy.timezoneId()));
        if(policy.effectiveFrom().isAfter(from)||policy.effectiveTo()!=null&&policy.effectiveTo().isBefore(end)
                ||Instant.now().isBefore(window.endExclusive().plusSeconds(policy.closingDelayHours()*3600L)))
            throw invalid("周期口径未覆盖完整月份或尚未结束迟到窗口");
        var relation=relations.versionDetail(user,roles,building,request.relationVersionId());
        if(!Set.of("EFFECTIVE","SUPERSEDED").contains(relation.version().status())||relation.metadata().effectiveAt()==null)
            throw invalid("计量关系须已审核生效");
        // 关系域旧 DATETIME 契约沿用平台 +08:00，不能按楼宇周期时区重解释历史。
        Instant relationStart=relation.metadata().effectiveAt().toInstant(ZoneOffset.ofHours(8));
        var current=relations.model(user,roles,building);
        if(relationStart.isAfter(window.startInclusive()))throw invalid("关系生效时间未覆盖完整月份");
        if(!request.relationVersionId().equals(current.activeVersionId())) {
            var next=relations.versions(user,roles,building).stream().filter(v->v.effectiveAt()!=null
                    &&v.effectiveAt().isAfter(relation.metadata().effectiveAt())).map(v->v.effectiveAt().toInstant(ZoneOffset.ofHours(8))).min(Comparator.naturalOrder());
            if(next.isEmpty()||next.get().isBefore(window.endExclusive()))throw invalid("月份跨计量关系版本，不能拆估用量");
        }
        var available=manual.readings(building,window.startInclusive(),window.endExclusive(),true);
        if(available.size()>5000)throw invalid("来源查询超过上限");
        var selected=available.stream().filter(r->request.factIds().contains(r.id())).toList();
        if(selected.size()!=request.factIds().size())throw invalid("来源未审核、已被替代、跨建筑或不在指定月份内");
        var readings=selected.stream().map(r->decode(r.json())).sorted(Comparator.comparing(Reading::startInclusive)).toList();
        Instant cursor=window.startInclusive();BigDecimal quantity=BigDecimal.ZERO;String energy=null,unit=null;
        for(var r:readings) {
            if(r.startInclusive()==null||r.endExclusive()==null||!r.startInclusive().isBefore(r.endExclusive())||!request.pointId().equals(r.pointId())||!"CONFIRMED".equals(r.status())||!"EXACT".equals(r.timePrecision())
                    ||!cursor.equals(r.startInclusive())||r.endExclusive().isAfter(window.endExclusive()))
                throw invalid("来源须为同一测点、已确认精确边界且连续覆盖整月，不能重叠或按比例补齐");
            if(energy==null){energy=r.energyItemCode();unit=r.unit();}
            if(!Objects.equals(energy,r.energyItemCode())||!Objects.equals(unit,r.unit()))throw invalid("来源能源和单位不一致");
            BigDecimal normalized=normalized(r);
            var stored=selected.stream().filter(s->decode(s.json()).equals(r)).findFirst().orElseThrow();
            if(stored.quantity()==null||normalized.compareTo(stored.quantity())!=0)throw invalid("人工来源归一用量与固定证据不一致");
            quantity=quantity.add(normalized);cursor=r.endExclusive();
        }
        if(!cursor.equals(window.endExclusive())||quantity.precision()>38||quantity.scale()>12)throw invalid("月来源不完整或用量超出精度");
        var binding=catalog.effectiveBinding(user,roles,building,request.pointId(),from);
        var last=catalog.effectiveBinding(user,roles,building,request.pointId(),end.minusNanos(1));
        if(!binding.bindingVersionId().equals(last.bindingVersionId())||!Objects.equals(energy,binding.energyItemCode())
                ||!Objects.equals(unit,binding.rawUnit())||!"CONFIRMED".equals(binding.confirmationStatus()))throw invalid("测点能源绑定、单位或有效期尚未确认");
        var evidence=new Evidence("REVIEWED_MANUAL_MONTH",request,selected.stream().map(ClassificationRepository.StoredReading::json).sorted().toList(),
                selected.stream().map(ClassificationRepository.StoredReading::batchId).distinct().sorted().toList(),binding.bindingVersionId(),request.relationVersionId(),
                policy.versionId(),policy.timezoneId(),binding.energyItemVersionId());
        String json=encode(evidence);
        // 完整性针对原生月量；tce 未计算保持 null，绝不把它补为0或推导碳排放。
        return new ProjectionCalculation("FORMAL",building,request.pointId(),window,policy.versionId(),energy,quantity,unit,
                null,null,BigDecimal.ONE,List.of(EnergyPeriodCalculationService.TCE_RULE_UNAVAILABLE),json,EnergyPeriodCalculationService.digest(json),null,
                window.endExclusive(),LocalDateTime.now());
    }
    public void verifyStored(long user,Collection<String> roles,CurrentProjection current) {
        try {
            var tree=mapper.readTree(current.evidenceJson());
            if(!"REVIEWED_MANUAL_MONTH".equals(tree.path("inputKind").asText()))return;
            var evidence=mapper.treeToValue(tree,Evidence.class);
            var refreshed=prepare(user,roles,current.buildingId(),evidence.request());
            if(!refreshed.evidenceHash().equals(current.evidenceHash())||refreshed.nativeQuantity().compareTo(current.nativeQuantity())!=0)
                throw invalid("人工来源或专业依赖已变化，请重新生成候选月量与封账申请");
        } catch(com.platform.framework.exception.BusinessException exception){throw exception;}
        catch(Exception exception){throw invalid("人工来源固定证据无效");}
    }
    /** 分类查询遇到已封账人工来源时只取快照，不再重复叠加同一批人工事实。 */
    public static Set<String> sealedFactIds(ObjectMapper mapper,String json) {
        try {
            var tree=mapper.readTree(json);
            while(tree.has("calculationEvidence"))tree=tree.path("calculationEvidence");
            if(!"REVIEWED_MANUAL_MONTH".equals(tree.path("inputKind").asText()))return Set.of();
            var ids=new HashSet<String>();tree.path("request").path("factIds").forEach(id->ids.add(id.asText()));return Set.copyOf(ids);
        }catch(Exception e){throw invalid("封账人工来源固定证据无法读取");}
    }
    private Reading decode(String json) {try{return mapper.readValue(json,Reading.class);}catch(Exception e){throw invalid("人工来源无法解析");}}
    private String encode(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw invalid("人工来源无法冻结");}}
    private static BigDecimal normalized(Reading r) {
        if(r.multiplier()==null||r.multiplier().signum()<=0||r.alreadyScaled()&&r.multiplier().compareTo(BigDecimal.ONE)!=0)throw invalid("倍率尚未确认或已换算来源重复应用倍率");
        BigDecimal quantity;
        if("CUMULATIVE".equals(r.semantics())&&r.startReading()!=null&&r.endReading()!=null&&r.quantity()==null)
            quantity=r.endReading().subtract(r.startReading());
        else if("PERIOD_TOTAL".equals(r.semantics())&&r.quantity()!=null&&r.startReading()==null&&r.endReading()==null)quantity=r.quantity();
        else throw invalid("来源读数语义不完整");
        quantity=quantity.multiply(r.multiplier());if(quantity.signum()<0)throw invalid("负用量必须先走修正流程");return quantity;
    }
    private static com.platform.framework.exception.BusinessException invalid(String message){return error(409,LOCK_BLOCKED,message);}
}
