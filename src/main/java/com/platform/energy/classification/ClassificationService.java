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
import org.springframework.transaction.annotation.Isolation;
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
    // 内部只读分页复用既有5000条读取规模；导入批次仍为500条，累计与扫描上限独立保留。
    private static final int MONTHLY_MANUAL_PAGE_SIZE=5_000;
    private static final int MONTHLY_MANUAL_MAX=20_000;
    private static final int SEALED_MANUAL_SCAN_MAX=400_000;
    private static final String MONTH_TO_DAY="MONTH_TO_DAY";
    private static final ZoneId REFINEMENT_ZONE=ZoneId.of("Asia/Shanghai");
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
    /** 供下游在同一授权边界内取得覆盖完整期间的唯一正式分类版本，不暴露新的 HTTP 配置入口。 */
    public VersionView effectiveVersion(long user,Collection<String> roles,String building,Instant from,Instant to,String versionId) {
        read(user,roles,building);window(from,to);return view(resolveEffectiveVersion(building,from,to,versionId));
    }

    /** 展示治理只复用对象归属及已生效关系校验，不创建正式分类或改变其有效期。 */
    public void validateDisplayMapping(long user, Collection<String> roles, String building, Configuration configuration) {
        read(user,roles,building);core.validate(configuration);validateObjects(building,configuration);
        if(blank(configuration.relationVersionId())) fail("展示归属必须明确计量关系版本");
        loadRelations(user,roles,building,configuration.relationVersionId());
    }
    /** 引用指定历史关系快照的楼层对象，拒绝将房间或当前资产树冒充历史楼层。 */
    public void validateDisplayFloors(long user,Collection<String> roles,String building,String relation,Set<String> spaceIds) {
        read(user,roles,building);
        var tree=relations.historicalSpaceTree(user,roles,building,relation);
        if(!building.equals(tree.metadata().buildingId())||!relation.equals(tree.metadata().versionId())||tree.metadata().truncated())fail("历史空间树建筑/版本不符或被截断");
        Set<String> floors=new HashSet<>();Deque<SpaceNodeView> pending=new ArrayDeque<>(tree.roots());int count=0;
        while(!pending.isEmpty()) {
            if(++count>5000)fail("历史空间树超过有界上限");
            var node=pending.removeFirst();if("FLOOR".equals(node.spaceType()))floors.add(node.spaceId());pending.addAll(node.children());
        }
        if(!floors.containsAll(spaceIds))fail("楼层引用不存在、跨建筑或属于房间等非楼层空间");
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
        if(repository.approvedStartsAtOrAfter(building,request.effectiveFrom())) throw error(409,"新版本生效时间必须晚于已有版本");
        Configuration config=decode(version.json(),Configuration.class);core.validate(config);validateObjects(building,config);
        // 未确认条目可以随配置发布，但计算时保持待确认；正式计量关系仍须走原治理流程。
        if(!blank(config.relationVersionId())) loadRelations(user,roles,building,config.relationVersionId());
        if(repository.approve(version,request.expectedRevision(),request.effectiveFrom(),user,request.reason())!=1) throw error(409,"分类版本已变化或不可审核");
        log(user,building,"APPROVE_CLASSIFICATION_VERSION",id,request.reason());return view(requireVersion(building,id));
    }
    /** 审核闭合历史适用期，不倒签登记时间，也不结束当前版本。 */
    @Transactional(rollbackFor = Exception.class)
    public VersionView approveHistorical(long user, Collection<String> roles, String building,
                                         String id, HistoricalApprovalRequest request) {
        read(user, roles, building);
        authorization.requirePolicyReviewer(user, roles);
        text(request.reason(), 500);
        repository.lock(building);
        var version = requireVersion(building, id);
        authorization.requireSeparation(version.author(), user);
        if (request.effectiveFrom() == null || request.effectiveTo() == null
                || !request.effectiveTo().isAfter(request.effectiveFrom())
                || request.effectiveTo().isAfter(Instant.now())) fail("历史补录必须使用已经结束的闭合非空区间");
        if (!request.effectiveFrom().equals(Instant.ofEpochMilli(request.effectiveFrom().toEpochMilli()))
                || !request.effectiveTo().equals(Instant.ofEpochMilli(request.effectiveTo().toEpochMilli()))) {
            fail("历史分类适用期的时间精度最多毫秒");
        }
        if (repository.overlapsApproved(building, request.effectiveFrom(), request.effectiveTo())) {
            throw error(409, "历史分类区间与已有审核版本重叠，请走修订流程");
        }
        Configuration config = decode(version.json(), Configuration.class);
        core.validate(config);
        validateObjects(building, config);
        if (!blank(config.relationVersionId())) loadRelations(user, roles, building, config.relationVersionId());
        if (repository.approveHistorical(version, request.expectedRevision(), request.effectiveFrom(),
                request.effectiveTo(), user, request.reason()) != 1) throw error(409, "分类版本已变化或不可审核");
        log(user, building, "APPROVE_HISTORICAL_CLASSIFICATION_VERSION", id,
                request.reason() + ";effectiveFrom=" + request.effectiveFrom() + ";effectiveTo=" + request.effectiveTo());
        return view(requireVersion(building, id));
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

    /** 只读配置覆盖检查不读取、生成或封存用量，正式执行仍由原流程复核。 */
    public VersionView configurationForPeriod(long user, Collection<String> roles, String building,
            String versionId, Instant from, Instant to, String energyItem, String unit) {
        read(user, roles, building);
        window(from, to);
        ClassificationRepository.Version version;
        if (blank(versionId)) {
            var effective = repository.effective(building, from, to);
            if (effective.size() != 1) throw error(409, "统计期间跨配置版本或没有完整有效配置，请分段查询");
            version = effective.getFirst();
        } else version = requireVersion(building, versionId);
        if (!"APPROVED".equals(version.status()) || version.from() > from.toEpochMilli()
                || version.to() != null && version.to() < to.toEpochMilli()) {
            throw error(409, "指定配置未覆盖完整统计期间");
        }
        Configuration config = decode(version.json(), Configuration.class);
        core.validate(config);
        if (blank(config.relationVersionId())) throw error(409, "尚未关联正式计量关系版本");
        var rules = config.boundaryRules().stream().filter(r -> energyItem.equals(r.energyItemCode())
                && unit.equals(r.unit())).toList();
        if (rules.isEmpty() || rules.stream().anyMatch(r -> !"CONFIRMED".equals(r.status()))) {
            throw error(409, "该能源品种和单位的计量口径尚未确认");
        }
        RelationData relation = loadRelations(user, roles, building, config.relationVersionId());
        if (relation.nextEffectiveAt() != null && to.isAfter(relation.nextEffectiveAt())) {
            throw error(409, "统计期间超过所引用计量关系的有效范围");
        }
        String historical = historicalScope(user, roles, building, config.relationVersionId(), relation, from, to);
        if (relation.metadata().effectiveAt() == null || relation.metadata().effectiveAt()
                .toInstant(ZoneOffset.ofHours(8)).isAfter(from) && historical == null) {
            throw error(409, "关系生效前的历史计量范围尚未确认");
        }
        return view(version);
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
        if(nonNull.stream().anyMatch(r->r.refinementMode()!=null)
                &&nonNull.stream().anyMatch(r->!MONTH_TO_DAY.equals(r.refinementMode())))
            issues.add(new Issue("ERROR","MIXED_REFINEMENT",null,"细化批次不能混入普通读数或修订"));
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
    // 授权读取在建筑锁之前；READ_COMMITTED保证等待锁的竞审者看到已提交的新来源，而非旧一致性快照。
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public ImportView approveReadings(long user,Collection<String> roles,String building,String batchId,TransitionRequest request) {
        read(user,roles,building);authorization.requireExceptionReviewer(user,roles);text(request.reason(),500);repository.lock(building);
        var batch=repository.batch(building,batchId,false);if(batch==null) throw error(404,"导入批次不存在");
        authorization.requireSeparation(batch.author(),user);
        var rows=repository.batchReadings(building,batchId);
        if(!"PENDING_REVIEW".equals(batch.status())||request.expectedRevision()==null
                ||batch.revision()!=request.expectedRevision())throw error(409,"批次状态或版本冲突");
        boolean refinement=rows.stream().anyMatch(row->decode(row.json(),Reading.class).refinementMode()!=null);
        if(refinement)validateRefinementBatch(building,rows);
        for(var row:rows) {
            Reading r=decode(row.json(),Reading.class);
            if("CONFIRMED".equals(r.status())) {
                var binding=catalog.effectiveBinding(user,roles,building,r.pointId(),LocalDateTime.ofInstant(r.startInclusive(),ZoneOffset.ofHours(8)));
                var endBinding=catalog.effectiveBinding(user,roles,building,r.pointId(),LocalDateTime.ofInstant(r.endExclusive().minusMillis(1),ZoneOffset.ofHours(8)));
                if(!r.energyItemCode().equals(binding.energyItemCode())||!r.unit().equals(binding.rawUnit())
                        ||!binding.bindingVersionId().equals(endBinding.bindingVersionId())) fail("人工读数品种、单位或有效期与已确认测点绑定不符");
            }
            if(refinement)continue;
            var overlaps=repository.currentApprovedReadings(building,r.pointId(),r.startInclusive(),r.endExclusive(),2);
            if(blank(r.supersedesFactId())) {if(!overlaps.isEmpty())throw error(409,"已有审核通过的同测点重叠期间，请提交明确修订");}
            else {
                if(row.quantity()==null||!"CONFIRMED".equals(r.status()))fail("修订必须有可确认的有效用量，不能以待确认记录替代已审核来源");
                if(overlaps.size()!=1||!overlaps.getFirst().id().equals(r.supersedesFactId()))throw error(409,"修订目标不是唯一当前有效记录");
                Reading previous=decode(overlaps.getFirst().json(),Reading.class);
                if(!previous.startInclusive().equals(r.startInclusive())||!previous.endExclusive().equals(r.endExclusive())||!previous.energyItemCode().equals(r.energyItemCode())||!previous.unit().equals(r.unit()))fail("修订必须保持测点、期间、品种和单位一致");
            }
        }
        if(repository.approveBatch(building,batchId,request.expectedRevision(),user)!=1) throw error(409,"批次状态或版本冲突");
        log(user,building,refinement?"APPROVE_ENERGY_READING_REFINEMENT":"APPROVE_ENERGY_READINGS",batchId,request.reason());return new ImportView(batchId,"APPROVED",0,List.of());
    }
    public ReadingBatchView readingBatch(long user, Collection<String> roles,
                                         String building, String batchId) {
        read(user, roles, building);
        text(batchId, 32);
        var batch = repository.batch(building, batchId, false);
        if (batch == null) throw error(404, "导入批次不存在");
        return new ReadingBatchView(batch.id(), batch.buildingId(), batch.status(),
                batch.revision(), batch.author(), repository.batchReadingCount(building, batchId));
    }

    public List<ReadingView> readings(long user,Collection<String> roles,String building,Instant from,Instant to) {
        read(user,roles,building);window(from,to);
        List<ClassificationRepository.StoredReading> values=repository.readings(building,from,to,false);limit(values.size());
        var rows=values.stream().map(r->new ReadingView(r.id(),r.batchId(),decode(r.json(),Reading.class),r.quantity(),r.author(),r.batchStatus(),null)).toList();
        return UnchangedReadingDiagnostics.annotate(rows);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public QueryView query(long user,Collection<String> roles,String building,QueryRequest request) {
        return queryProjection(user,roles,building,request).view();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public QueryProjection queryProjection(long user,Collection<String> roles,String building,QueryRequest request) {
        return project(user,roles,building,request,null);
    }

    /** 月源只替换人工事实读取；日授权、有效配置、封存去重及分类算法仍在当前日事务内执行。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public QueryView queryUsingMonthSource(long user,Collection<String> roles,String building,QueryRequest request,MonthlyReadingWindow sources) {
        if(!"DAY".equals(request.periodType()))throw error(400,"Prepared monthly sources require a daily query");
        return project(user,roles,building,request,Objects.requireNonNull(sources)).view();
    }

    /** Uses the caller-owned verified day spool without dropping the normal source slices or project evidence. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public QueryProjection queryProjectionUsingMonthSource(long user,Collection<String> roles,String building,
            QueryRequest request,MonthlyReadingWindow sources) {
        if(!"DAY".equals(request.periodType()))throw error(400,"Prepared monthly sources require a daily query");
        return project(user,roles,building,request,Objects.requireNonNull(sources));
    }

    private QueryProjection project(long user,Collection<String> roles,String building,QueryRequest request,MonthlyReadingWindow sources) {
        read(user,roles,building);window(request.startInclusive(),request.endExclusive());
        if(!Set.of("DAY","MONTH","YEAR").contains(request.periodType())) fail("统计周期无效");
        if("MONTH".equals(request.periodType())&&Duration.between(request.startInclusive(),request.endExclusive()).compareTo(Duration.ofDays(31))>0)
            throw error(409,"月度人工来源查询最多31天，请分段查询");
        text(request.energyItemCode(),64);text(request.unit(),32);text(request.schemeCode(),64);
        ClassificationRepository.Version version=resolveEffectiveVersion(building,request.startInclusive(),request.endExclusive(),request.versionId());
        Configuration config=decode(version.json(),Configuration.class);
        if(blank(config.relationVersionId())) return pending(building,version,request,config,"尚未关联正式计量关系版本");
        List<BoundaryRule> rules=config.boundaryRules().stream().filter(r->request.energyItemCode().equals(r.energyItemCode())&&request.unit().equals(r.unit())).toList();
        if(rules.isEmpty())return pending(building,version,request,config,"该能源品种和单位尚未配置计量口径");
        config=new Configuration(config.relationVersionId(),config.schemes(),config.nodes(),config.bindings(),rules);
        final String relationId=config.relationVersionId();
        RelationData relation=loadRelations(user,roles,building,config.relationVersionId());
        if(relation.nextEffectiveAt()!=null&&request.endExclusive().isAfter(relation.nextEffectiveAt())) throw error(409,"统计期间超过所引用计量关系的有效范围，请按关系生效时间分段");
        String historicalScopeId=historicalScope(user,roles,building,config.relationVersionId(),relation,request.startInclusive(),request.endExclusive());
        if(relation.metadata().effectiveAt()!=null&&relation.metadata().effectiveAt().toInstant(ZoneOffset.ofHours(8)).isAfter(request.startInclusive())&&historicalScopeId==null) return pending(building,version,request,config,"关系生效前的历史计量范围尚未确认");
        List<PeriodSnapshot> periods=snapshots.listVisibleSnapshots(building,request.periodType(),request.startInclusive(),request.endExclusive(),5001);limit(periods.size());
        Map<String,List<Piece>> grouped=new HashMap<>();
        for(PeriodSnapshot snapshot:periods) if(request.energyItemCode().equals(snapshot.energyItemCode())&&request.unit().equals(snapshot.nativeUnitCode())) {
            String rel=relationVersion(snapshot.evidenceJson());
            if(!relationId.equals(rel)) throw error(409,"周期结果引用不同关系版本，请按历史口径分段查询");
            grouped.computeIfAbsent(snapshot.pointId(),k->new ArrayList<>()).add(new Piece(snapshot.startInclusive(),snapshot.endExclusive(),snapshot.nativeQuantity(),snapshot.snapshotId(),snapshot.resultNature(),!"LOCKED_PARTIAL".equals(snapshot.status())));
        }
        Set<String> sealedManualFacts=new HashSet<>();
        for(var snapshot:periods)sealedManualFacts.addAll(com.platform.energy.period.ManualPeriodInputService.sealedFactIds(json,snapshot.evidenceJson()));
        // 日视图可能读取整楼24小时来源；已封账的来源先去重，再计入未封账来源上限。
        boolean pagedManual=Set.of("MONTH","DAY").contains(request.periodType());
        var manual=sources!=null?sources.readDay(building,request.startInclusive(),request.endExclusive(),sealedManualFacts):pagedManual
                ? monthlyCurrentApprovedReadings(building,request.startInclusive(),request.endExclusive(),sealedManualFacts)
                : repository.readings(building,request.startInclusive(),request.endExclusive(),true);
        if(!pagedManual)limit(manual.size());
        Set<String> sealedRefinements="MONTH".equals(request.periodType())
                ? sealedRefinementFacts(building,periods,manual,request):Set.of();
        for(var value:manual) {
            if(sealedManualFacts.contains(value.id())||sealedRefinements.contains(value.id()))continue;
            Reading r=decode(value.json(),Reading.class);
            if(!request.energyItemCode().equals(r.energyItemCode())||!request.unit().equals(r.unit())||value.quantity()==null) continue;
            if(!"CONFIRMED".equals(r.status())||("CUMULATIVE".equals(r.semantics())&&!"EXACT".equals(r.timePrecision()))) continue;
            grouped.computeIfAbsent(r.pointId(),k->new ArrayList<>()).add(new Piece(r.startInclusive(),r.endExclusive(),value.quantity(),value.id(),"REVIEWED_MANUAL",true));
        }
        List<Fact> facts=new ArrayList<>();List<String> sourceEvidence=new ArrayList<>();
        if(historicalScopeId!=null)sourceEvidence.add("HISTORICAL_METERING_SCOPE:"+historicalScopeId);
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
                if(!confirmed)return pending(building,version,request,config,"同设备存在多个非上下级计量通道，独立性尚未确认，不能相加");
            }
        }
        String totalKind=rules.stream().anyMatch(r->"SUBMETER_SUM".equals(r.mode()))?"MEASURED_SUBTOTAL":"BOUNDARY_TOTAL";
        QueryView view=new QueryView(building,version.id(),config.relationVersionId(),request.unit(),request.startInclusive(),request.endExclusive(),result.total(),result.assigned(),result.unallocated(),result.residual(),result.status(),result.nature(),totalKind,result.additive(),result.nodes(),result.balances(),result.issues(),sourceEvidence.stream().distinct().toList());
        List<SourceSlice> slices=request.filters()==null||request.filters().isEmpty()?result.sourceSlices():List.of();
        return new QueryProjection(view,slices,config);
    }

    public record QueryProjection(QueryView view,List<ClassificationCore.SourceSlice> slices,
                                  Configuration configuration) {}

    public record IntervalContext(String classificationVersionId,String relationVersionId,long modelRevision,
                                  Configuration configuration,List<Meter> meters,Map<String,Set<String>> resolvedBindings,
                                  String historicalMeteringScopeId) { }

    /** 仅活在一次监测计算中的版本结构；调用方仍须按来源版本守卫发布，不能作为跨请求缓存。 */
    public static final class MonitoringCapture {
        private record Scope(long user,Set<String> roles,String building) { }
        private Scope scope;
        private final Map<String,RelationData> relations=new HashMap<>();
        private final Map<String,Map<String,Set<String>>> spaces=new HashMap<>();
    }

    /** 小时计算复用同一历史计量结构与归属解析；这里只提供上下文，不伪造正式封账快照。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public IntervalContext intervalContext(long user,Collection<String> roles,String building,String versionId,
            Instant from,Instant to,String energy) {
        if(!Duration.between(from,to).equals(Duration.ofHours(1)))throw error(400,"小时计量上下文必须为完整一小时");
        return monitoringIntervalContext(user,roles,building,versionId,from,to,energy);
    }

    /** 设备时段监测复用同一计量树和历史适用期，调用方必须提供至多一小时的完整输入区间。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public IntervalContext monitoringIntervalContext(long user,Collection<String> roles,String building,String versionId,
            Instant from,Instant to,String energy) {
        return monitoringIntervalContext(user,roles,building,versionId,from,to,energy,new MonitoringCapture());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public IntervalContext monitoringIntervalContext(long user,Collection<String> roles,String building,String versionId,
            Instant from,Instant to,String energy,MonitoringCapture capture) {
        read(user,roles,building);window(from,to);
        var scope=new MonitoringCapture.Scope(user,Set.copyOf(roles),building);
        if(capture.scope!=null&&!capture.scope.equals(scope))throw error(409,"监测结构捕获不能跨用户、角色或建筑复用");
        capture.scope=scope;
        if(Duration.between(from,to).compareTo(Duration.ofHours(1))>0)throw error(400,"设备监测时段超过一小时");
        var version=resolveEffectiveVersion(building,from,to,versionId);
        Configuration original=decode(version.json(),Configuration.class);
        var rules=original.boundaryRules().stream().filter(r->energy.equals(r.energyItemCode())).toList();
        if(blank(original.relationVersionId()) || original.schemes().isEmpty() || rules.isEmpty()
                || rules.stream().anyMatch(r->!"CONFIRMED".equals(r.status())||"SUBMETER_SUM".equals(r.mode())))
            throw error(409,"小时建筑核算需要已确认的完整边界计量口径，不能把分表小计当全楼量");
        var config=new Configuration(original.relationVersionId(),original.schemes(),original.nodes(),original.bindings(),rules);
        var relation=capture.relations.computeIfAbsent(config.relationVersionId(),id->loadRelations(user,roles,building,id));
        String historicalScopeId=historicalScope(user,roles,building,config.relationVersionId(),relation,from,to);
        // 历史小时复用已经审核的计量适用期；保留引用，不把版本实际激活时间回填到过去。
        if(relation.metadata().effectiveAt()==null || relation.metadata().effectiveAt().toInstant(ZoneOffset.ofHours(8)).isAfter(from)&&historicalScopeId==null
                || relation.nextEffectiveAt()!=null && relation.nextEffectiveAt().isBefore(to))
            throw error(409,"计量关系未完整覆盖该小时");
        var boundaries=rules.stream().map(BoundaryRule::boundaryId).collect(java.util.stream.Collectors.toSet());
        var meters=relation.meters().stream().filter(m->boundaries.contains(m.boundaryId())).toList();
        if(meters.isEmpty() || meters.size()>500)throw error(409,"小时计量来源缺失或超过500个测点");
        Map<String,Meter> index=new HashMap<>();meters.forEach(m->index.put(m.pointId(),m));
        var equipment=repository.pointEquipment(building,index.keySet());
        var channels=new HashMap<String,List<Meter>>();
        for(var meter:meters)if(equipment.containsKey(meter.pointId()))channels.computeIfAbsent(equipment.get(meter.pointId()),id->new ArrayList<>()).add(meter);
        // 只在同一设备内比较通道；设备归属仍逐小时重读，独立性与祖先规则保持不变。
        for(var group:channels.values())for(Meter a:group)for(Meter b:group)if(!a.pointId().equals(b.pointId())
                &&!meterAncestor(a.pointId(),b.pointId(),index)&&!meterAncestor(b.pointId(),a.pointId(),index)&&rules.stream()
                .filter(r->r.boundaryId().equals(a.boundaryId())||r.boundaryId().equals(b.boundaryId())).anyMatch(r->!r.independentChannelsConfirmed()))
            throw error(409,"同设备独立计量通道尚未确认，不能相加");
        Map<String,Set<String>> ancestors=new HashMap<>();
        if(config.bindings().stream().anyMatch(b->"SPACE".equals(b.objectType()))) {
            var saved=capture.spaces.get(config.relationVersionId());
            if(saved!=null)ancestors.putAll(saved);
            else {
                var tree=relations.historicalSpaceTree(user,roles,building,config.relationVersionId());
                for(var root:tree.roots())collectSpaces(root,Set.of(),ancestors);
                capture.spaces.put(config.relationVersionId(),Map.copyOf(ancestors));
            }
        }
        return new IntervalContext(version.id(),config.relationVersionId(),relation.metadata().modelRevision(),config,meters,
                resolve(config,relation.assignments(),ancestors),historicalScopeId);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<ReadingView> reviewedIntervalReadings(long user,Collection<String> roles,String building,
            Instant from,Instant to,Instant asOf) {
        read(user,roles,building);window(from,to);
        if(!Duration.between(from,to).equals(Duration.ofHours(1)) || asOf==null || asOf.isBefore(to))throw error(400,"小时来源或接收截止无效");
        var values=repository.approvedReadingsAsOf(building,from,to,asOf,5001);limit(values.size());
        return values.stream().map(value->{
            var reading=decode(value.json(),Reading.class);
            if(value.quantity()==null || normalize(reading).compareTo(value.quantity())!=0)throw error(409,"已审核小时来源的固定用量证据不一致");
            return new ReadingView(value.id(),value.batchId(),reading,value.quantity(),value.author(),value.batchStatus(),null);
        }).toList();
    }

    /** 请求私有的完整北京自然日来源；保留跨日事实，由小时调用者执行原始相交与精确区间校验。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,
            propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public List<ReadingView> reviewedDayIntervalReadings(long user,Collection<String> roles,String building,
            Instant from,Instant to,Instant asOf,java.util.function.Supplier<Duration> remaining) {
        ReviewedDayReadings.checkRemaining(remaining);
        read(user,roles,building);window(from,to);
        LocalDate day=from.atZone(REFINEMENT_ZONE).toLocalDate();
        if(!day.atStartOfDay(REFINEMENT_ZONE).toInstant().equals(from)
                ||!day.plusDays(1).atStartOfDay(REFINEMENT_ZONE).toInstant().equals(to)
                ||asOf==null||asOf.isBefore(to))throw error(400,"日来源或接收截止无效");
        return ReviewedDayReadings.read(repository,building,from,to,asOf,remaining,value->{
            var reading=decode(value.json(),Reading.class);
            BigDecimal quantity=normalize(reading);
            if(value.quantity()==null||quantity==null||quantity.compareTo(value.quantity())!=0)
                throw error(409,"已审核小时来源的固定用量证据不一致");
            return new ReadingView(value.id(),value.batchId(),reading,value.quantity(),value.author(),value.batchStatus(),null);
        });
    }

    public record SealedProjection(String classificationVersionId,String relationVersionId,
                                   BigDecimal total,List<ClassificationCore.SourceSlice> slices,
                                   Configuration configuration,
                                   @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                   String historicalMeteringScopeId) {
        public SealedProjection(String classificationVersionId,String relationVersionId,BigDecimal total,
                                List<ClassificationCore.SourceSlice> slices,Configuration configuration) {
            this(classificationVersionId,relationVersionId,total,slices,configuration,null);
        }
    }

    /** 仅消费调用方固定的封账快照，不读取人工分类来源；权威总量仍由能源汇总端校验。 */
    public SealedProjection projectSealed(long user,Collection<String> roles,String building,String versionId,
                                         Instant from,Instant to,String energy,String unit,List<PeriodSnapshot> periods) {
        read(user,roles,building);window(from,to);
        var version=requireVersion(building,versionId);
        if(!"APPROVED".equals(version.status())||version.from()==null||version.from()>from.toEpochMilli()
                ||(version.to()!=null&&version.to()<to.toEpochMilli())) throw error(409,"分类版本未覆盖封账期间");
        Configuration original=decode(version.json(),Configuration.class);
        if(blank(original.relationVersionId())||original.schemes().isEmpty()) throw error(409,"缺少正式计量关系或分类方案");
        var rules=original.boundaryRules().stream().filter(r->energy.equals(r.energyItemCode())&&unit.equals(r.unit())).toList();
        if(rules.isEmpty()||rules.stream().anyMatch(r->"SUBMETER_SUM".equals(r.mode()))) throw error(409,"碳核算要求已确认建筑边界总量，不能使用部分分表小计");
        Configuration config=new Configuration(original.relationVersionId(),original.schemes(),original.nodes(),original.bindings(),rules);
        RelationData relation=loadRelations(user,roles,building,config.relationVersionId());
        String historicalScopeId=historicalScope(user,roles,building,config.relationVersionId(),relation,from,to);
        if(relation.metadata().effectiveAt()==null||(relation.metadata().effectiveAt().toInstant(ZoneOffset.ofHours(8)).isAfter(from)
                &&historicalScopeId==null)
                ||(relation.nextEffectiveAt()!=null&&to.isAfter(relation.nextEffectiveAt()))) throw error(409,"计量关系未覆盖完整封账期间");
        limit(periods.size());List<Fact> facts=new ArrayList<>();
        for(PeriodSnapshot s:periods) {
            if(!building.equals(s.buildingId())||!energy.equals(s.energyItemCode())||!unit.equals(s.nativeUnitCode())
                    ||!from.equals(s.startInclusive())||!to.equals(s.endExclusive())
                    ||!"FORMAL".equals(s.resultNature())||!Set.of("LOCKED_COMPLETE","LOCKED_NATIVE_COMPLETE").contains(s.status())
                    ||!config.relationVersionId().equals(relationVersion(s.evidenceJson()))) throw error(409,"封账来源身份、期间或计量版本不一致");
            facts.add(new Fact(s.pointId(),s.nativeQuantity(),s.snapshotId(),"FORMAL",true));
        }
        Set<String> boundaryIds=rules.stream().map(BoundaryRule::boundaryId).collect(java.util.stream.Collectors.toSet());
        var meters=relation.meters().stream().filter(m->boundaryIds.contains(m.boundaryId())).toList();
        Map<String,String> equipment=repository.pointEquipment(building,meters.stream().map(Meter::pointId).toList());
        Map<String,Meter> index=new HashMap<>();meters.forEach(m->index.put(m.pointId(),m));
        for(Meter a:meters)for(Meter b:meters)if(!a.pointId().equals(b.pointId())&&equipment.containsKey(a.pointId())
                &&equipment.get(a.pointId()).equals(equipment.get(b.pointId()))&&!meterAncestor(a.pointId(),b.pointId(),index)
                &&!meterAncestor(b.pointId(),a.pointId(),index)
                &&rules.stream().filter(r->r.boundaryId().equals(a.boundaryId())||r.boundaryId().equals(b.boundaryId()))
                .anyMatch(r->!r.independentChannelsConfirmed())) throw error(409,"同设备计量通道独立性待确认");
        Map<String,Set<String>> ancestors=new HashMap<>();
        if(config.bindings().stream().anyMatch(b->"SPACE".equals(b.objectType()))) {
            var tree=relations.historicalSpaceTree(user,roles,building,config.relationVersionId());
            for(var root:tree.roots())collectSpaces(root,Set.of(),ancestors);
        }
        var result=core.compute(config,config.schemes().getFirst().code(),List.of(),
                new Input(meters,facts,resolve(config,relation.assignments(),ancestors)));
        if(!"CALCULABLE".equals(result.status())||result.total()==null
                ||result.sourceSlices().stream().anyMatch(s->s.quantity().signum()<0)) throw error(409,"计量分解不完整，不能用于正式碳核算");
        return new SealedProjection(version.id(),config.relationVersionId(),result.total(),result.sourceSlices(),config,historicalScopeId);
    }

    private String historicalScope(long user,Collection<String> roles,String building,String id,
                                   RelationData relation,Instant from,Instant to) {
        if(relation.metadata().effectiveAt()==null||!relation.metadata().effectiveAt().toInstant(ZoneOffset.ofHours(8)).isAfter(from))return null;
        var scope=relations.historicalMeteringScope(user,roles,building,id,from,to);
        return scope==null?null:scope.scopeId();
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
    /** 所有页都保留调用方原始窗口；超过上限明确失败，不能以截断来源伪造完整统计。 */
    private List<ClassificationRepository.StoredReading> monthlyCurrentApprovedReadings(String building,Instant from,Instant to,Set<String> sealedFacts) {
        List<ClassificationRepository.StoredReading> values=new ArrayList<>();Instant cursorStart=null;String cursorFactId=null;
        int scanned=0;
        while(true) {
            int pageSize=MONTHLY_MANUAL_PAGE_SIZE;
            List<ClassificationRepository.StoredReading> page=repository.currentApprovedReadingsPage(building,from,to,cursorStart,cursorFactId,pageSize);
            // 500点×31天×24小时最多372000条；扫描仍有界，不能按点位或期间掩盖新修订事实。
            scanned+=page.size();
            if(scanned>SEALED_MANUAL_SCAN_MAX)throw error(409,"封账人工来源扫描超过400000条有界查询上限，请分段查询");
            page.stream().filter(value->!sealedFacts.contains(value.id())).forEach(values::add);
            if(values.size()>MONTHLY_MANUAL_MAX)throw error(409,"人工来源超过20000条有界查询上限，请分段查询");
            if(page.size()<pageSize)return values;
            var last=page.getLast();
            if(last.startInclusive()==null)throw error(409,"人工来源分页游标无效");
            cursorStart=last.startInclusive();cursorFactId=last.id();
        }
    }
    /** 建筑锁内整组验证，唯一一次批次状态变更使整月同时退出当前来源，不能逐日审核。 */
    private void validateRefinementBatch(String building,List<ClassificationRepository.StoredReading> rows) {
        if(rows.isEmpty()||rows.size()>500)fail("细化批次应为1至500条且包含完整自然日组");
        Map<String,List<ClassificationRepository.StoredReading>> groups=new LinkedHashMap<>();
        for(var row:rows) {
            Reading r=decode(row.json(),Reading.class);
            if(!MONTH_TO_DAY.equals(r.refinementMode())||blank(r.supersedesFactId()))
                fail("细化批次不能混入普通读数或修订");
            groups.computeIfAbsent(r.supersedesFactId(),ignored->new ArrayList<>()).add(row);
        }
        for(var entry:groups.entrySet()) {
            var parent=repository.approvedReading(building,entry.getKey());
            if(parent==null)throw error(409,"细化目标不是同建筑已审核事实");
            Reading previous=validateRefinementGroup(parent,entry.getValue());
            var current=repository.currentApprovedReadings(building,previous.pointId(),previous.startInclusive(),previous.endExclusive(),2);
            if(current.size()!=1||!current.getFirst().id().equals(parent.id()))
                throw error(409,"细化目标不是唯一当前有效记录");
        }
    }

    /** 整月边界精确不代表日来源精确；允许整组降为DATE_ONLY，禁止升级或混用精度。 */
    private Reading validateRefinementGroup(ClassificationRepository.StoredReading parent,
                                            List<ClassificationRepository.StoredReading> children) {
        Reading previous=decode(parent.json(),Reading.class);
        BigDecimal original=normalize(previous);
        LocalDate first=previous.startInclusive().atZone(REFINEMENT_ZONE).toLocalDate();
        Instant monthStart=first.withDayOfMonth(1).atStartOfDay(REFINEMENT_ZONE).toInstant();
        Instant monthEnd=first.withDayOfMonth(1).plusMonths(1).atStartOfDay(REFINEMENT_ZONE).toInstant();
        if(!"APPROVED".equals(parent.batchStatus())||!"CONFIRMED".equals(previous.status())
                ||!"PERIOD_TOTAL".equals(previous.semantics())||original==null||parent.quantity()==null
                ||original.compareTo(parent.quantity())!=0||!monthStart.equals(previous.startInclusive())
                ||!monthEnd.equals(previous.endExclusive())||children.size()!=first.lengthOfMonth())
            fail("细化需要已确认整自然月及同批完整自然日组");
        var ordered=children.stream().sorted(Comparator.comparing(row->decode(row.json(),Reading.class).startInclusive())).toList();
        Instant cursor=monthStart;BigDecimal total=BigDecimal.ZERO;String batch=ordered.getFirst().batchId();
        String precision=decode(ordered.getFirst().json(),Reading.class).timePrecision();
        if("DATE_ONLY".equals(previous.timePrecision())&&!"DATE_ONLY".equals(precision))
            fail("细化不能把日期精度来源升级为精确读数");
        for(var child:ordered) {
            Reading r=decode(child.json(),Reading.class);
            BigDecimal quantity=normalize(r);
            Instant dayEnd=cursor.atZone(REFINEMENT_ZONE).toLocalDate().plusDays(1).atStartOfDay(REFINEMENT_ZONE).toInstant();
            if(!MONTH_TO_DAY.equals(r.refinementMode())||!parent.id().equals(r.supersedesFactId())
                    ||!batch.equals(child.batchId())||!previous.pointId().equals(r.pointId())
                    ||!previous.energyItemCode().equals(r.energyItemCode())||!previous.unit().equals(r.unit())
                    ||!Objects.equals(precision,r.timePrecision())||!"CONFIRMED".equals(r.status())
                    ||!"PERIOD_TOTAL".equals(r.semantics())||!cursor.equals(r.startInclusive())
                    ||!dayEnd.equals(r.endExclusive())||quantity==null||child.quantity()==null
                    ||quantity.compareTo(child.quantity())!=0)
                fail("细化必须同点、品种、单位、时间精度且连续覆盖完整自然日，归一量与证据一致");
            cursor=dayEnd;total=total.add(quantity);
        }
        if(!monthEnd.equals(cursor)||total.compareTo(original)!=0)fail("细化日用量之和必须与原月用量精确守恒");
        return previous;
    }

    /** 只去重完整、未再修订的直接细化组；后续更正须走正式月修订，不能由旧封账静默遮蔽。 */
    private Set<String> sealedRefinementFacts(String building,List<PeriodSnapshot> periods,
            List<ClassificationRepository.StoredReading> manual,QueryRequest request) {
        Map<String,PeriodSnapshot> sealedParents=new HashMap<>();
        for(var snapshot:periods) {
            if(!"MONTH".equals(snapshot.periodType())||!building.equals(snapshot.buildingId())
                    ||!request.energyItemCode().equals(snapshot.energyItemCode())||!request.unit().equals(snapshot.nativeUnitCode())
                    ||!"Asia/Shanghai".equals(snapshot.timezoneId())
                    ||!Set.of("LOCKED_COMPLETE","LOCKED_NATIVE_COMPLETE").contains(snapshot.status())
                    ||snapshot.startInclusive().isBefore(request.startInclusive())||snapshot.endExclusive().isAfter(request.endExclusive()))continue;
            var ids=com.platform.energy.period.ManualPeriodInputService.sealedFactIds(json,snapshot.evidenceJson());
            if(ids.size()==1)sealedParents.put(ids.iterator().next(),snapshot);
        }
        Map<String,List<ClassificationRepository.StoredReading>> groups=new HashMap<>();
        for(var row:manual) {
            Reading r=decode(row.json(),Reading.class);
            if(MONTH_TO_DAY.equals(r.refinementMode())&&sealedParents.containsKey(r.supersedesFactId()))
                groups.computeIfAbsent(r.supersedesFactId(),ignored->new ArrayList<>()).add(row);
        }
        Set<String> suppressed=new HashSet<>();
        for(var entry:groups.entrySet()) {
            var parent=repository.approvedReading(building,entry.getKey());
            if(parent==null)throw error(409,"封账细化的原始事实缺失，请核对固定证据");
            Reading previous;
            try {previous=validateRefinementGroup(parent,entry.getValue());}
            catch(com.platform.framework.exception.BusinessException exception) {
                throw error(409,"完整日细化来源已变化，请走正式月度修订，不能覆盖原封账");
            }
            var snapshot=sealedParents.get(parent.id());
            if(!snapshot.pointId().equals(previous.pointId())||!snapshot.energyItemCode().equals(previous.energyItemCode())
                    ||!snapshot.nativeUnitCode().equals(previous.unit())||!snapshot.startInclusive().equals(previous.startInclusive())
                    ||!snapshot.endExclusive().equals(previous.endExclusive())||snapshot.nativeQuantity()==null
                    ||snapshot.nativeQuantity().compareTo(parent.quantity())!=0)continue;
            entry.getValue().forEach(row->suppressed.add(row.id()));
        }
        return suppressed;
    }

    private BigDecimal normalize(Reading r) {
        text(r.pointId(),80);text(r.energyItemCode(),64);text(r.unit(),32);text(r.sourceReference(),500);window(r.startInclusive(),r.endExclusive());
        if(r.refinementMode()!=null&&(!MONTH_TO_DAY.equals(r.refinementMode())||blank(r.supersedesFactId())))
            fail("细化模式无效或缺少被替代的整月事实");
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
    private ClassificationRepository.Version resolveEffectiveVersion(String building,Instant from,Instant to,String versionId) {
        if(blank(versionId)) {
            var effective=repository.effective(building,from,to);
            if(effective.size()!=1)throw error(409,"统计期间跨配置版本或没有完整有效配置，请分段查询");
            return effective.getFirst();
        }
        var version=requireVersion(building,versionId);
        if(!"APPROVED".equals(version.status())||version.from()==null||version.from()>from.toEpochMilli()
                ||(version.to()!=null&&version.to()<to.toEpochMilli()))throw error(409,"指定配置未覆盖完整统计期间");
        return version;
    }
    private VersionView view(ClassificationRepository.Version v) {var config=decode(v.json(),Configuration.class);return new VersionView(v.id(),v.buildingId(),v.no(),v.revision(),v.status(),instant(v.from()),instant(v.to()),v.author(),v.reviewer(),config,core.validate(config));}
    private QueryProjection pending(String building,ClassificationRepository.Version version,QueryRequest r,Configuration config,String message) {
        QueryView view=new QueryView(building,version.id(),null,r.unit(),r.startInclusive(),r.endExclusive(),null,null,null,null,"PENDING_EXPERT","UNDETERMINED","UNDETERMINED",false,List.of(),List.of(),List.of(issue("PENDING_EXPERT",version.id(),message)),List.of());
        return new QueryProjection(view,List.of(),config);
    }
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
