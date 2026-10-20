package com.platform.energy.classification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.RelationGovernanceProperties;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeterStructuresView;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import com.platform.relation.api.RelationContracts.SpaceNodeView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static com.platform.energy.classification.ClassificationContracts.*;
import static com.platform.energy.classification.ClassificationCore.*;

/** 分类治理复用建筑权限、专业职责和正式关系查询；人工来源经独立审核后才参与原生统计。 */
@Service
@Transactional(readOnly = true)
public class ClassificationService {
    private final ClassificationRepository repository;
    private final EnergyPeriodAuthorization authorization;
    private final RelationGovernanceService relations;
    private final EnergyPeriodSnapshotReader snapshots;
    private final ClassificationCore core;
    private final ObjectMapper json;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    private final EnergyCatalogService catalog;
    private final RelationGovernanceProperties relationProperties;
    public ClassificationService(ClassificationRepository repository, EnergyPeriodAuthorization authorization,
                                 RelationGovernanceService relations, EnergyPeriodSnapshotReader snapshots,
                                 ClassificationCore core, ObjectMapper json, AuditEvidenceWriter audit,
                                 AuditGovernanceProperties auditProperties, EnergyCatalogService catalog,
                                 RelationGovernanceProperties relationProperties) {
        this.repository=repository;this.authorization=authorization;this.relations=relations;
        this.snapshots=snapshots;this.core=core;this.json=json;this.audit=audit;this.auditProperties=auditProperties;
        this.catalog=catalog;
        this.relationProperties=relationProperties;
    }
    public List<VersionView> versions(long user, Collection<String> roles, String building) {
        read(user,roles,building); return repository.versions(building).stream().map(this::view).toList();
    }
    public VersionView version(long user, Collection<String> roles, String building, String id) {
        read(user,roles,building);return view(requireVersion(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public VersionView create(long user, Collection<String> roles, String building, CreateRequest request) {
        write(user,roles,building);text(request.reason(),500);repository.lock(building);
        if(repository.versions(building).stream().anyMatch(v->Set.of("DRAFT","PENDING_REVIEW").contains(v.status()))) throw error(409,"已有待处理分类版本");
        Configuration config=blank(request.copiedFromVersionId())?new Configuration(null,List.of(),List.of(),List.of(),List.of()):decode(requireVersion(building,request.copiedFromVersionId()).json(),Configuration.class);
        String id=id();repository.insertVersion(id,building,encode(config),request.reason(),user);
        log(user,building,"CREATE_CLASSIFICATION_VERSION",id,request.reason());return view(requireVersion(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public VersionView save(long user,Collection<String> roles,String building,String id,SaveRequest request) {
        write(user,roles,building);text(request.reason(),500);repository.lock(building);
        var version=requireVersion(building,id);core.validate(request.configuration());validateObjects(building,request.configuration());
        if(version.author()!=user)throw error(403,"只能编辑本人创建的草稿");
        if(repository.save(version,request.expectedRevision(),encode(request.configuration()),request.reason())!=1) throw error(409,"分类版本已变化或不可编辑");
        log(user,building,"SAVE_CLASSIFICATION_VERSION",id,request.reason());return view(requireVersion(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public VersionView submit(long user,Collection<String> roles,String building,String id,TransitionRequest request) {
        write(user,roles,building);text(request.reason(),500);repository.lock(building);
        var version=requireVersion(building,id);core.validate(decode(version.json(),Configuration.class));
        if(version.author()!=user)throw error(403,"只能提交本人创建的草稿");
        if(repository.submit(version,request.expectedRevision(),request.reason())!=1) throw error(409,"分类版本已变化或不可提交");
        log(user,building,"SUBMIT_CLASSIFICATION_VERSION",id,request.reason());return view(requireVersion(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public VersionView approve(long user,Collection<String> roles,String building,String id,TransitionRequest request) {
        read(user,roles,building);authorization.requirePolicyReviewer(user,roles);text(request.reason(),500);repository.lock(building);
        var version=requireVersion(building,id);authorization.requireSeparation(version.author(),user);
        if(request.effectiveFrom()==null || request.effectiveFrom().isAfter(Instant.now())) fail("生效时间必须明确且不能晚于当前时间");
        if(repository.versions(building).stream().anyMatch(v->"APPROVED".equals(v.status())&&v.from()>=request.effectiveFrom().toEpochMilli())) throw error(409,"新版本生效时间必须晚于已有版本");
        Configuration config=decode(version.json(),Configuration.class);core.validate(config);validateObjects(building,config);
        // 未确认条目可以随配置发布，但计算时保持待确认；正式计量关系仍须走原治理流程。
        if(!blank(config.relationVersionId())) loadRelations(user,roles,building,config.relationVersionId());
        if(repository.approve(version,request.expectedRevision(),request.effectiveFrom(),user,request.reason())!=1) throw error(409,"分类版本已变化或不可审核");
        log(user,building,"APPROVE_CLASSIFICATION_VERSION",id,request.reason());return view(requireVersion(building,id));
    }
    @Transactional(rollbackFor=Exception.class)
    public VersionView reopen(long user,Collection<String> roles,String building,String id,TransitionRequest request,boolean reject) {
        read(user,roles,building);text(request.reason(),500);repository.lock(building);
        var version=requireVersion(building,id);
        if(reject) {authorization.requirePolicyReviewer(user,roles);authorization.requireSeparation(version.author(),user);}
        else {authorization.requirePolicyMaintainer(user,roles);if(version.author()!=user) throw error(403,"只能撤回本人提交的版本");}
        if(repository.reopen(version,request.expectedRevision(),request.reason())!=1) throw error(409,"版本不可退回或已变化");
        log(user,building,reject?"REJECT_CLASSIFICATION_VERSION":"WITHDRAW_CLASSIFICATION_VERSION",id,request.reason());return view(requireVersion(building,id));
    }

    public ImportView preview(long user,Collection<String> roles,String building,ImportRequest request) {
        read(user,roles,building);text(request.idempotencyKey(),80);text(request.reason(),500);
        if(request.readings()==null||request.readings().isEmpty()||request.readings().size()>500) fail("一次导入应为1至500条");
        List<Issue> issues=new ArrayList<>();Set<String> keys=new HashSet<>();int row=0;
        for(Reading reading:request.readings()) {
            row++;
            try {
                if(reading==null) fail("读数记录为空");
                if(!repository.objectExists(building,"POINT",reading.pointId())) fail("测点不存在或不属于当前建筑");
                normalize(reading);
                String key=reading.pointId()+"/"+reading.startInclusive()+"/"+reading.endExclusive();
                if(!keys.add(key)) fail("文件内同测点期间重复");
                if(!"CONFIRMED".equals(reading.status())||("CUMULATIVE".equals(reading.semantics())&&!"EXACT".equals(reading.timePrecision()))) issues.add(issue("PENDING_EXPERT",String.valueOf(row),"倍率、读数或时间边界待确认，暂不用于统计"));
            }catch(com.platform.framework.exception.BusinessException exception) { issues.add(new Issue("ERROR","INVALID_READING",String.valueOf(row),exception.getMessage())); }
        }
        var nonNull=request.readings().stream().filter(Objects::nonNull).filter(r->r.startInclusive()!=null&&r.endExclusive()!=null&&r.pointId()!=null).toList();
        for(int i=0;i<nonNull.size();i++)for(int j=i+1;j<nonNull.size();j++) {
            var a=nonNull.get(i);var b=nonNull.get(j);
            if(a.pointId().equals(b.pointId())&&a.startInclusive().isBefore(b.endExclusive())&&b.startInclusive().isBefore(a.endExclusive())) issues.add(new Issue("ERROR","OVERLAPPING_PERIOD",String.valueOf(j+1),"同文件同测点期间重叠"));
        }
        return new ImportView(null,issues.stream().anyMatch(i->"ERROR".equals(i.severity()))?"INVALID":"PREVIEW",0,issues);
    }
    @Transactional(rollbackFor=Exception.class)
    public ImportView importReadings(long user,Collection<String> roles,String building,ImportRequest request) {
        write(user,roles,building);ImportView preview=preview(user,roles,building,request);
        if("INVALID".equals(preview.status())) return preview;
        repository.lock(building);String hash=hash(encode(request));
        var previous=repository.batch(building,request.idempotencyKey(),true);
        if(previous!=null) {
            if(!previous.hash().equals(hash)) throw error(409,"幂等键已被不同内容使用");
            return new ImportView(previous.id(),previous.status(),request.readings().size(),preview.issues());
        }
        String batch=id();repository.insertBatch(batch,building,request.idempotencyKey(),hash,request.reason(),user);
        for(Reading reading:request.readings()) repository.insertReading(id(),batch,building,reading,encode(reading),normalize(reading),user);
        log(user,building,"IMPORT_ENERGY_READINGS",batch,request.reason());
        return new ImportView(batch,"PENDING_REVIEW",request.readings().size(),preview.issues());
    }
    @Transactional(rollbackFor=Exception.class)
    public ImportView approveReadings(long user,Collection<String> roles,String building,String batchId,TransitionRequest request) {
        read(user,roles,building);authorization.requireExceptionReviewer(user,roles);text(request.reason(),500);repository.lock(building);
        var batch=repository.batch(building,batchId,false);if(batch==null) throw error(404,"导入批次不存在");
        authorization.requireSeparation(batch.author(),user);
        for(var row:repository.batchReadings(building,batchId)) {
            Reading r=decode(row.json(),Reading.class);
            if("CONFIRMED".equals(r.status())) {
                var binding=catalog.effectiveBinding(user,roles,building,r.pointId(),LocalDateTime.ofInstant(r.startInclusive(),ZoneOffset.ofHours(8)));
                var endBinding=catalog.effectiveBinding(user,roles,building,r.pointId(),LocalDateTime.ofInstant(r.endExclusive().minusMillis(1),ZoneOffset.ofHours(8)));
                if(!r.energyItemCode().equals(binding.energyItemCode())||!r.unit().equals(binding.rawUnit())
                        ||!binding.bindingVersionId().equals(endBinding.bindingVersionId())) fail("人工读数品种、单位或有效期与已确认测点绑定不符");
            }
            var active=repository.readings(building,r.startInclusive(),r.endExclusive(),true);limit(active.size());
            var overlaps=active.stream().filter(a->decode(a.json(),Reading.class).pointId().equals(r.pointId())).toList();
            if(blank(r.supersedesFactId())) {if(!overlaps.isEmpty())throw error(409,"已有审核通过的同测点重叠期间，请提交明确修订");}
            else {
                if(row.quantity()==null||!"CONFIRMED".equals(r.status()))fail("修订必须有可确认的有效用量，不能以待确认记录替代已审核来源");
                if(overlaps.size()!=1||!overlaps.getFirst().id().equals(r.supersedesFactId()))throw error(409,"修订目标不是唯一当前有效记录");
                Reading previous=decode(overlaps.getFirst().json(),Reading.class);
                if(!previous.startInclusive().equals(r.startInclusive())||!previous.endExclusive().equals(r.endExclusive())||!previous.energyItemCode().equals(r.energyItemCode())||!previous.unit().equals(r.unit()))fail("修订必须保持测点、期间、品种和单位一致");
            }
        }
        if(repository.approveBatch(building,batchId,request.expectedRevision(),user)!=1) throw error(409,"批次状态或版本冲突");
        log(user,building,"APPROVE_ENERGY_READINGS",batchId,request.reason());return new ImportView(batchId,"APPROVED",0,List.of());
    }
    public List<ReadingView> readings(long user,Collection<String> roles,String building,Instant from,Instant to) {
        read(user,roles,building);window(from,to);
        List<ClassificationRepository.StoredReading> values=repository.readings(building,from,to,false);limit(values.size());
        return values.stream().map(r->new ReadingView(r.id(),r.batchId(),decode(r.json(),Reading.class),r.quantity(),r.author(),r.batchStatus())).toList();
    }

    public QueryView query(long user,Collection<String> roles,String building,QueryRequest request) {
        read(user,roles,building);window(request.startInclusive(),request.endExclusive());
        if(!Set.of("DAY","MONTH","YEAR").contains(request.periodType())) fail("统计周期无效");
        text(request.energyItemCode(),64);text(request.unit(),32);text(request.schemeCode(),64);
        ClassificationRepository.Version version;
        if(blank(request.versionId())) {
            var effective=repository.effective(building,request.startInclusive(),request.endExclusive());
            if(effective.size()!=1) throw error(409,"统计期间跨配置版本或没有完整有效配置，请分段查询");
            version=effective.getFirst();
        } else {
            version=requireVersion(building,request.versionId());
            if(!"APPROVED".equals(version.status())||version.from()>request.startInclusive().toEpochMilli()
                    ||(version.to()!=null&&version.to()<request.endExclusive().toEpochMilli())) throw error(409,"指定配置未覆盖完整统计期间");
        }
        Configuration config=decode(version.json(),Configuration.class);
        if(blank(config.relationVersionId())) return pending(building,version,request,"尚未关联正式计量关系版本");
        List<BoundaryRule> rules=config.boundaryRules().stream().filter(r->request.energyItemCode().equals(r.energyItemCode())&&request.unit().equals(r.unit())).toList();
        if(rules.isEmpty())return pending(building,version,request,"该能源品种和单位尚未配置计量口径");
        config=new Configuration(config.relationVersionId(),config.schemes(),config.nodes(),config.bindings(),rules);
        final String relationId=config.relationVersionId();
        RelationData relation=loadRelations(user,roles,building,config.relationVersionId());
        if(relation.nextEffectiveAt()!=null&&request.endExclusive().isAfter(relation.nextEffectiveAt())) throw error(409,"统计期间超过所引用计量关系的有效范围，请按关系生效时间分段");
        if(relation.metadata().effectiveAt()!=null&&relation.metadata().effectiveAt().toInstant(ZoneOffset.ofHours(8)).isAfter(request.startInclusive())) return pending(building,version,request,"关系生效前的历史计量范围尚未确认");
        List<PeriodSnapshot> periods=snapshots.listVisibleSnapshots(building,request.periodType(),request.startInclusive(),request.endExclusive(),5001);limit(periods.size());
        Map<String,List<Piece>> grouped=new HashMap<>();
        for(PeriodSnapshot snapshot:periods) if(request.energyItemCode().equals(snapshot.energyItemCode())&&request.unit().equals(snapshot.nativeUnitCode())) {
            String rel=relationVersion(snapshot.evidenceJson());
            if(!relationId.equals(rel)) throw error(409,"周期结果引用不同关系版本，请按历史口径分段查询");
            grouped.computeIfAbsent(snapshot.pointId(),k->new ArrayList<>()).add(new Piece(snapshot.startInclusive(),snapshot.endExclusive(),snapshot.nativeQuantity(),snapshot.snapshotId(),snapshot.resultNature(),!"LOCKED_PARTIAL".equals(snapshot.status())));
        }
        var manual=repository.readings(building,request.startInclusive(),request.endExclusive(),true);limit(manual.size());
        for(var value:manual) {
            Reading r=decode(value.json(),Reading.class);
            if(!request.energyItemCode().equals(r.energyItemCode())||!request.unit().equals(r.unit())||value.quantity()==null) continue;
            if(!"CONFIRMED".equals(r.status())||("CUMULATIVE".equals(r.semantics())&&!"EXACT".equals(r.timePrecision()))) continue;
            grouped.computeIfAbsent(r.pointId(),k->new ArrayList<>()).add(new Piece(r.startInclusive(),r.endExclusive(),value.quantity(),value.id(),"REVIEWED_MANUAL",true));
        }
        List<Fact> facts=new ArrayList<>();List<String> sourceEvidence=new ArrayList<>();
        for(var entry:grouped.entrySet()) {
            List<Piece> pieces=entry.getValue().stream().sorted(Comparator.comparing(Piece::from)).toList();
            Instant cursor=request.startInclusive();BigDecimal quantity=BigDecimal.ZERO;boolean complete=true;
            for(Piece piece:pieces) {
                if(piece.from().isBefore(cursor)||piece.from().isBefore(request.startInclusive())||piece.to().isAfter(request.endExclusive())) throw error(409,"来源期间重叠或跨查询边界，不能重复入账或按比例拆分");
                if(!piece.from().equals(cursor)) complete=false;
                cursor=piece.to();quantity=quantity.add(piece.quantity());complete&=piece.complete();sourceEvidence.add(piece.id());
            }
            complete&=cursor.equals(request.endExclusive());
            String evidence=String.join(",",pieces.stream().map(Piece::id).toList());
            facts.add(new Fact(entry.getKey(),quantity,evidence,pieces.stream().allMatch(p->"FORMAL".equals(p.nature()))?"FORMAL":"SOURCE_LABELLED",complete));
        }
        Map<String,Set<String>> spaceAncestors=new HashMap<>();
        if(config.bindings().stream().anyMatch(b->"SPACE".equals(b.objectType()))) {
            var currentTree=relations.effectiveSpaceTree(user,roles,building);
            var tree=config.relationVersionId().equals(currentTree.metadata().versionId())?currentTree:relations.historicalSpaceTree(user,roles,building,config.relationVersionId());
            for(var root:tree.roots()) collectSpaces(root,Set.of(),spaceAncestors);
        }
        Map<String,Set<String>> resolved=resolve(config,relation.assignments(),spaceAncestors);
        Computation result=core.compute(config,request.schemeCode(),request.filters(),new Input(relation.meters(),facts,resolved));
        List<String> usedPoints=result.balances().stream().map(Balance::pointId).distinct().toList();
        Map<String,String> pointEquipment=repository.pointEquipment(building,usedPoints);
        Map<String,Meter> meterIndex=new HashMap<>();relation.meters().forEach(m->meterIndex.put(m.pointId(),m));
        for(int i=0;i<usedPoints.size();i++)for(int j=i+1;j<usedPoints.size();j++) {
            String a=usedPoints.get(i),b=usedPoints.get(j);
            if(pointEquipment.containsKey(a)&&pointEquipment.get(a).equals(pointEquipment.get(b))
                    &&!meterAncestor(a,b,meterIndex)&&!meterAncestor(b,a,meterIndex)) {
                boolean confirmed=rules.stream().filter(r->r.boundaryId().equals(meterIndex.get(a).boundaryId())||r.boundaryId().equals(meterIndex.get(b).boundaryId()))
                        .allMatch(r->r.independentChannelsConfirmed()&&"CONFIRMED".equals(r.status()));
                if(!confirmed)return pending(building,version,request,"同设备存在多个非上下级计量通道，独立性尚未确认，不能相加");
            }
        }
        String totalKind=rules.stream().anyMatch(r->"SUBMETER_SUM".equals(r.mode()))?"MEASURED_SUBTOTAL":"BOUNDARY_TOTAL";
        return new QueryView(building,version.id(),config.relationVersionId(),request.unit(),request.startInclusive(),request.endExclusive(),result.total(),result.assigned(),result.unallocated(),result.residual(),result.status(),result.nature(),totalKind,result.additive(),result.nodes(),result.balances(),result.issues(),sourceEvidence.stream().distinct().toList());
    }

    private RelationData loadRelations(long user,Collection<String> roles,String building,String id) {
        int pageSize=Math.min(100,relationProperties.getMaxPageSize());
        MeterStructuresView current=relations.effectiveMeterStructures(user,roles,building,1,pageSize);
        boolean useCurrent=id.equals(current.metadata().versionId());
        Instant nextEffectiveAt=useCurrent?null:current.metadata().effectiveAt().toInstant(ZoneOffset.ofHours(8));
        List<MeteringAssignmentView> assignments=new ArrayList<>();QueryMetadata metadata=null;
        for(int page=1;page<=5001;page++) {
            var result=relations.historicalMeteringAssignments(user,roles,building,id,page,pageSize);
            if(result.total()>5000||result.size()<1) throw error(409,"关系查询超过处理上限或分页无效");
            metadata=result.metadata();assignments.addAll(result.items());limit(assignments.size());
            if(page*(long)result.size()>=result.total()) break;
        }
        Map<String,String> nodeToPoint=new HashMap<>();
        if(metadata==null||metadata.effectiveAt()==null) throw error(409,"分类只能引用曾经正式生效的计量关系版本");
        if(!useCurrent) {
            Instant historicalFrom=metadata.effectiveAt().toInstant(ZoneOffset.ofHours(8));
            nextEffectiveAt=relations.versions(user,roles,building).stream().filter(v->v.effectiveAt()!=null)
                    .map(v->v.effectiveAt().toInstant(ZoneOffset.ofHours(8))).filter(at->at.isAfter(historicalFrom)).min(Comparator.naturalOrder()).orElse(nextEffectiveAt);
        }
        assignments.forEach(a->{if(a.meterPointNodeId()!=null&&a.pointId()!=null)nodeToPoint.put(a.meterPointNodeId(),a.pointId());});
        List<Meter> meters=new ArrayList<>();
        for(int page=1;page<=5001;page++) {
            var result=useCurrent?(page==1?current:relations.effectiveMeterStructures(user,roles,building,page,pageSize))
                    :relations.historicalMeterStructures(user,roles,building,id,page,pageSize);
            if(result.total()>5000||result.size()<1||!id.equals(result.metadata().versionId())) throw error(409,"关系版本变化或超过有界处理上限");
            for(var m:result.items()) {
                String point=nodeToPoint.get(m.meterPointNodeId());String parent=m.parentMeterPointNodeId()==null?null:nodeToPoint.get(m.parentMeterPointNodeId());
                if(point==null||(m.parentMeterPointNodeId()!=null&&parent==null)) throw error(409,"计量结构缺少对应测点分配记录");
                meters.add(new Meter(point,parent,m.meteringBoundaryId(),m.meterRole(),m.meterDirection(),m.confirmationStatus()));
            }
            limit(meters.size());if(page*(long)result.size()>=result.total()) break;
        }
        return new RelationData(metadata,assignments,meters,nextEffectiveAt);
    }
    private Map<String,Set<String>> resolve(Configuration config,List<MeteringAssignmentView> assignments,Map<String,Set<String>> spaceAncestors) {
        Map<String,Set<String>> result=new HashMap<>();
        for(Binding binding:config.bindings()) {
            Set<String> points=new HashSet<>();
            for(var a:assignments) {
                boolean matches="POINT".equals(binding.objectType())?binding.objectId().equals(a.pointId()):binding.objectType().equals(a.targetNodeType())&&binding.objectId().equals(a.targetObjectId())&&"ASSIGNED".equals(a.allocationStatus());
                if("SPACE".equals(binding.objectType())&&"SPACE".equals(a.targetNodeType())&&"ASSIGNED".equals(a.allocationStatus())) matches=spaceAncestors.getOrDefault(a.targetObjectId(),Set.of()).contains(binding.objectId());
                if(matches&&(blank(binding.meterPointId())||binding.meterPointId().equals(a.pointId()))&&"CONFIRMED".equals(a.meterConfirmationStatus())&&"CONFIRMED".equals(a.boundaryConfirmationStatus())) points.add(a.pointId());
            }
            result.put(bindingKey(binding),Set.copyOf(points));
        }
        return result;
    }
    private void collectSpaces(SpaceNodeView node,Set<String> parents,Map<String,Set<String>> result) {
        if(result.size()>=5000||parents.size()>=100)throw error(409,"空间树超过有界处理上限");
        Set<String> ancestry=new HashSet<>(parents);if(!ancestry.add(node.spaceId()))throw error(409,"空间树形成循环");
        result.put(node.spaceId(),Set.copyOf(ancestry));for(var child:node.children())collectSpaces(child,ancestry,result);
    }
    private static boolean meterAncestor(String ancestor,String point,Map<String,Meter> meters) {
        Meter current=meters.get(point);Set<String> seen=new HashSet<>();
        while(current!=null&&seen.add(current.pointId())) {if(ancestor.equals(current.pointId()))return true;current=meters.get(current.parentId());}
        return false;
    }
    private void validateObjects(String building,Configuration config) {
        for(Binding binding:config.bindings()) {
            if(!repository.objectExists(building,binding.objectType(),binding.objectId())) fail("绑定对象不存在或跨建筑");
            if(!blank(binding.meterPointId())&&!repository.objectExists(building,"POINT",binding.meterPointId())) fail("计量测点不存在或跨建筑");
        }
    }
    private BigDecimal normalize(Reading r) {
        text(r.pointId(),80);text(r.energyItemCode(),64);text(r.unit(),32);text(r.sourceReference(),500);window(r.startInclusive(),r.endExclusive());
        if(r.status()==null||r.timePrecision()==null||!Set.of("CONFIRMED","PENDING_EXPERT").contains(r.status())||!Set.of("EXACT","DATE_ONLY").contains(r.timePrecision())) fail("读数确认状态或时间精度无效");
        BigDecimal quantity;
        if("CUMULATIVE".equals(r.semantics())) {
            if(r.startReading()==null||r.endReading()==null||r.startReading().signum()<0||r.endReading().signum()<0||r.quantity()!=null) fail("累计量需要非负起止读数且不能同时填写期间量");
            quantity=r.endReading().subtract(r.startReading());
        } else if("PERIOD_TOTAL".equals(r.semantics())) {
            if(r.quantity()==null||r.startReading()!=null||r.endReading()!=null) fail("期间量不能同时填写累计起止读数");
            quantity=r.quantity();
        } else {fail("仅支持累计读数或明确期间量");return null;}
        if(r.multiplier()==null&&"PENDING_EXPERT".equals(r.status()))return null;
        if(r.multiplier()==null||r.multiplier().signum()<=0||r.multiplier().precision()>20) fail("倍率必须是明确的正数，未知倍率请保留待确认");
        if(r.alreadyScaled()&&r.multiplier().compareTo(BigDecimal.ONE)!=0) {
            if("PENDING_EXPERT".equals(r.status()))return null;
            fail("已换算读数不能再次应用倍率");
        }
        quantity=quantity.multiply(r.multiplier());
        if(quantity.signum()<0) { if("CONFIRMED".equals(r.status())) fail("负增量须先确认换表、回绕或修正，不能直接入账");return null; }
        if(quantity.precision()>38||quantity.scale()>12||quantity.precision()-quantity.scale()>26) fail("用量超过十进制存储精度");
        return quantity;
    }
    private void read(long user,Collection<String> roles,String building) {text(building,32);authorization.requireReader(roles);authorization.checkBuilding(user,roles,building);if(!repository.buildingExists(building))throw error(404,"建筑不存在");}
    private void write(long user,Collection<String> roles,String building) {read(user,roles,building);authorization.requirePolicyMaintainer(user,roles);}
    private ClassificationRepository.Version requireVersion(String building,String id) {var v=repository.version(building,id);if(v==null)throw error(404,"分类版本不存在");return v;}
    private VersionView view(ClassificationRepository.Version v) {var config=decode(v.json(),Configuration.class);return new VersionView(v.id(),v.buildingId(),v.no(),v.revision(),v.status(),instant(v.from()),instant(v.to()),v.author(),v.reviewer(),config,core.validate(config));}
    private QueryView pending(String building,ClassificationRepository.Version version,QueryRequest r,String message) {return new QueryView(building,version.id(),null,r.unit(),r.startInclusive(),r.endExclusive(),null,null,null,null,"PENDING_EXPERT","UNDETERMINED","UNDETERMINED",false,List.of(),List.of(),List.of(issue("PENDING_EXPERT",version.id(),message)),List.of());}
    private void log(long user,String building,String action,String id,String reason) {audit.append(new AuditEvidence("ENERGY_CLASSIFICATION",building,"USER",user,action,"ENERGY_CLASSIFICATION",id,id,null,null,reason,"SUCCESS",null,TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),auditProperties.isAllowSelfApproval()));}
    private String encode(Object object) {try{return json.writeValueAsString(object);}catch(JsonProcessingException e){throw error(400,"配置或读数无法序列化");}}
    private <T>T decode(String text,Class<T> type) {try{return json.readValue(text,type);}catch(JsonProcessingException e){throw error(409,"已存储证据无法解析");}}
    private String relationVersion(String evidence) {try {var n=json.readTree(evidence);return n.path("calculationEvidence").has("relationVersionId")?n.path("calculationEvidence").path("relationVersionId").asText():n.path("relationVersionId").asText();}catch(JsonProcessingException e){throw error(409,"周期结果关系证据无效");}}
    private static void window(Instant from,Instant to) {if(from==null||to==null||!to.isAfter(from)||Duration.between(from,to).compareTo(Duration.ofDays(366))>0
            ||!from.equals(Instant.ofEpochMilli(from.toEpochMilli()))||!to.equals(Instant.ofEpochMilli(to.toEpochMilli())))fail("期间须为正且不超过366天，时间精度最多毫秒");}
    private static void limit(int size) {if(size>5000)throw error(409,"超过5000条有界查询上限，请缩小范围");}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static Instant instant(Long value){return value==null?null:Instant.ofEpochMilli(value);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private record RelationData(QueryMetadata metadata,List<MeteringAssignmentView> assignments,List<Meter> meters,Instant nextEffectiveAt){}
    private record Piece(Instant from,Instant to,BigDecimal quantity,String id,String nature,boolean complete){}
}
