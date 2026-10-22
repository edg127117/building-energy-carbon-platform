package com.platform.energy.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.carbon.CarbonEvidence;
import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest;
import com.platform.framework.exception.BusinessException;
import com.platform.system.service.BuildingScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.platform.energy.reporting.ReportingContracts.*;

/**
 * 账单来源、填报审核和封账分别治理；审核不会暗中发布楼宇配置。
 * 写事务使用READ_COMMITTED：建筑锁前的权限查询不能固定旧快照，否则等待锁的请求会看不到已提交回执。
 */
@Service
@Transactional(readOnly=true)
public class ReportingService {
    private final ReportingRepository repository;
    private final BuildingScopeService scopes;
    private final BackendDutyService duties;
    private final EnergyCatalogService catalog;
    private final EnergyCatalogLookup lookup;
    private final EnergyConversionService conversion;
    private final ObjectMapper mapper;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    private final com.platform.carbon.ReportingSnapshotChanges changes;
    public ReportingService(ReportingRepository repository,BuildingScopeService scopes,BackendDutyService duties,
            EnergyCatalogService catalog,EnergyCatalogLookup lookup,EnergyConversionService conversion,
            ObjectMapper mapper,AuditEvidenceWriter audit,AuditGovernanceProperties auditProperties,com.platform.carbon.ReportingSnapshotChanges changes) {
        this.repository=repository;this.scopes=scopes;this.duties=duties;this.catalog=catalog;this.lookup=lookup;
        this.conversion=conversion;this.mapper=mapper;this.audit=audit;this.auditProperties=auditProperties;this.changes=changes;
    }
    public Context context(long user,Collection<String> roles,String building,LocalDate month) {
        read(user,roles,building);month(month);
        var at=month.atStartOfDay();
        var energies=catalog.listItems(roles).stream().filter(i->"APPROVED".equals(i.status())
                &&!i.effectiveFrom().isAfter(at)&&(i.effectiveTo()==null||i.effectiveTo().isAfter(at)))
                .map(i->new EnergyOption(i.itemCode(),i.itemName(),i.usageScopes())).toList();
        var units=catalog.listUnits(roles).stream().filter(i->"APPROVED".equals(i.status())
                &&!i.effectiveFrom().isAfter(at)&&(i.effectiveTo()==null||i.effectiveTo().isAfter(at)))
                .filter(i->!Set.of("POWER","STANDARD_COAL_EQUIVALENT").contains(i.dimensionCode()))
                .map(i->new UnitOption(i.unitCode(),i.symbol(),i.dimensionCode())).toList();
        // 只供实施人员选择已审核的口径组合，页面不能自己输入系数或猜测当量/等价值。
        var conversionOptions=conversion.listParameterVersions(roles).stream()
                .filter(p->"APPROVED".equals(p.status())&&"PRODUCTION".equals(p.usageScope())
                        &&!p.effectiveFrom().isAfter(at)&&(p.effectiveTo()==null||!p.effectiveTo().isBefore(month.plusMonths(1).atStartOfDay())))
                .map(p->new ConversionOption(p.energyItemCode(),p.method(),p.perspective(),p.regionCode(),
                        switch(p.method()){case "DIRECT_TCE_FACTOR"->"折标系数";case "LOWER_HEATING_VALUE"->"低位热值";default->"能量折标";}
                        +" · "+("CALORIFIC_EQUIVALENT".equals(p.perspective())?"当量值":"等价值")+" · "+p.regionCode()))
                .distinct().toList();
        return new Context(building,month,sources(building),batches(building).stream().filter(b->month.equals(b.month()))
                .sorted(Comparator.comparingInt((Batch b)->switch(b.status()){case "DRAFT","SUBMITTED","REVIEWED"->0;case "SEALED"->1;default->2;}).thenComparing(Batch::batchId)).toList(),
                energies,units,conversionOptions,new Capabilities(manager(roles)&&duties.hasDuty(user,BackendDuty.ENERGY_RULE_MAINTAIN),
                manager(roles)&&duties.hasDuty(user,BackendDuty.ENERGY_RULE_REVIEW),
                manager(roles)&&duties.hasDuty(user,BackendDuty.ENERGY_LOCK_APPROVE),
                manager(roles)&&duties.hasDuty(user,BackendDuty.ENERGY_LOCK_APPROVE)));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Source createSource(long user,Collection<String> roles,String building,CreateSource request) {
        read(user,roles,building);require(user,roles,BackendDuty.ENERGY_RULE_MAINTAIN);repository.lock(building);
        return once(user,building,request.idempotencyKey(),"CREATE_SOURCE",request,Source.class,()->{
            validateSource(request.configuration(),false);
            var value=new Source(id(),0,"DRAFT",user,null,request.configuration());
            repository.insert(stored(building,value));log(user,building,"CREATE_REPORTING_SOURCE",value.sourceId(),request.configuration().reason());return value;
        });
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Source saveSource(long user,Collection<String> roles,String building,String id,SaveSource request) {
        read(user,roles,building);require(user,roles,BackendDuty.ENERGY_RULE_MAINTAIN);repository.lock(building);
        var old=source(building,id);revision(old.revision(),request.expectedRevision());author(old.createdBy(),user);
        state(old.status(),"DRAFT");validateSource(request.configuration(),false);
        var value=new Source(id,old.revision()+1,"DRAFT",user,null,request.configuration());update(stored(building,value),old.revision());
        log(user,building,"SAVE_REPORTING_SOURCE",id,request.configuration().reason());return value;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Source sourceCommand(long user,Collection<String> roles,String building,String id,String action,Command request) {
        read(user,roles,building);repository.lock(building);reason(request.reason());
        boolean review=Set.of("approve","reject").contains(action);
        require(user,roles,review?BackendDuty.ENERGY_RULE_REVIEW:BackendDuty.ENERGY_RULE_MAINTAIN);
        Supplier<Source> transition=()->{
            var old=source(building,id);revision(old.revision(),request.expectedRevision());
            String status;Long reviewer=null;
            switch(action) {
                case "submit" -> {author(old.createdBy(),user);state(old.status(),"DRAFT");validateSource(old.configuration(),true);status="SUBMITTED";}
                case "approve","reject" -> {
                    state(old.status(),"SUBMITTED");separate(old.createdBy(),user);
                    if(action.equals("approve")){validateSource(old.configuration(),true);checkAuthority(building,old);reviewer=user;status="APPROVED";}
                    else status="DRAFT";
                }
                default -> throw invalid("来源操作无效");
            }
            var value=new Source(id,old.revision()+1,status,old.createdBy(),reviewer,old.configuration());
            update(stored(building,value),old.revision());log(user,building,"REPORTING_SOURCE_"+action.toUpperCase(Locale.ROOT),id,request.reason());return value;
        };
        return blank(request.idempotencyKey())?transition.get():once(user,building,request.idempotencyKey(),
                "SOURCE_"+id+"_"+action,request,Source.class,transition);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Batch createBatch(long user,Collection<String> roles,String building,CreateBatch request) {
        read(user,roles,building);month(request.month());reason(request.reason());repository.lock(building);
        return once(user,building,request.idempotencyKey(),"CREATE_BATCH",request,Batch.class,()->{
            var existing=batches(building).stream().filter(b->b.month().equals(request.month())).toList();
            if(existing.stream().anyMatch(b->Set.of("DRAFT","SUBMITTED","REVIEWED").contains(b.status())))
                throw conflict("该月已有待办填报，请继续原草稿");
            var current=existing.stream().filter(b->"SEALED".equals(b.status())).findFirst().orElse(null);
            if(current!=null&&!current.batchId().equals(request.supersedesBatchId()))throw conflict("已有封账，修订须明确引用原批次");
            if(request.supersedesBatchId()!=null&&(current==null||!current.batchId().equals(request.supersedesBatchId())))throw conflict("修订目标不是本月当前封账");
            List<Row> rows=current==null?sources(building).stream().filter(s->"APPROVED".equals(s.status())&&covers(s.configuration(),request.month()))
                    .map(s->new Row(s.sourceId(),s.revision(),null,"MISSING",request.month(),request.month().plusMonths(1),"","",null,"","","NATIVE_QUANTITY")).toList():current.rows();
            var value=new Batch(id(),building,request.month(),"DRAFT",0,user,null,request.supersedesBatchId(),rows,null);
            repository.insert(stored(value));log(user,building,"CREATE_REPORTING_BATCH",value.batchId(),request.reason());return value;
        });
    }
    public Batch batch(long user,Collection<String> roles,String building,String id) {read(user,roles,building);return batch(building,id);}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Batch saveBatch(long user,Collection<String> roles,String building,String id,SaveBatch request) {
        read(user,roles,building);repository.lock(building);reason(request.reason());
        var old=batch(building,id);revision(old.revision(),request.expectedRevision());state(old.status(),"DRAFT");author(old.createdBy(),user);
        if(request.rows()==null||request.rows().size()>100||request.rows().stream().anyMatch(Objects::isNull))throw invalid("一次填报最多100行");
        if(request.rows().stream().map(Row::sourceId).distinct().count()!=request.rows().size())throw invalid("来源不能重复填报");
        for(var r:request.rows()) {source(building,r.sourceId());basicRow(r);}
        var value=new Batch(id,building,old.month(),"DRAFT",old.revision()+1,user,null,old.supersedesBatchId(),List.copyOf(request.rows()),null);
        update(stored(value),old.revision());log(user,building,"SAVE_REPORTING_BATCH",id,request.reason());return value;
    }
    public Preview preview(long user,Collection<String> roles,String building,String id) {
        read(user,roles,building);var value=batch(building,id);
        if(Set.of("SEALED","SUPERSEDED").contains(value.status())&&value.snapshot()!=null)
            return new Preview(true,List.of(),value.snapshot().nativeStatus(),value.snapshot().tceStatus());
        return prepare(user,roles,value).preview();
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Batch batchCommand(long user,Collection<String> roles,String building,String id,String action,Command request) {
        read(user,roles,building);reason(request.reason());repository.lock(building);
        if(Set.of("approve","reject","seal").contains(action))require(user,roles,BackendDuty.ENERGY_LOCK_APPROVE);
        return once(user,building,request.idempotencyKey(),"BATCH_"+id+"_"+action,request,Batch.class,()->{
            var old=batch(building,id);revision(old.revision(),request.expectedRevision());String status;Long reviewer=old.reviewedBy();Snapshot snapshot=null;
            switch(action) {
                case "submit" -> {author(old.createdBy(),user);state(old.status(),"DRAFT");ready(prepare(user,roles,old));status="SUBMITTED";}
                case "approve" -> {state(old.status(),"SUBMITTED");separate(old.createdBy(),user);
                    var reviewed=prepare(user,roles,old);ready(reviewed);snapshot=reviewed.snapshot();exclusionDuty(user,roles,old);reviewer=user;status="REVIEWED";}
                case "reject" -> {if(!Set.of("SUBMITTED","REVIEWED").contains(old.status()))throw conflict("当前状态不能退回");separate(old.createdBy(),user);reviewer=null;status="DRAFT";}
                case "seal" -> {
                    state(old.status(),"REVIEWED");separate(old.createdBy(),user);exclusionDuty(user,roles,old);
                    var prepared=prepare(user,roles,old);ready(prepared);snapshot=prepared.snapshot();
                    if(old.snapshot()==null||!old.snapshot().evidenceHash().equals(snapshot.evidenceHash()))throw conflict("审核后的参数或来源依赖已变化，请退回重审");
                    status="SEALED";
                    changes.sealed(building,old.supersedesBatchId(),id,old.month(),user);
                    // 仅当前月封账指针替换；旧载荷与碳报告留存，历史报告仍须原重算审批链替代。
                    if(old.supersedesBatchId()!=null) {
                        var previous=batch(building,old.supersedesBatchId());state(previous.status(),"SEALED");
                        var retired=new Batch(previous.batchId(),building,previous.month(),"SUPERSEDED",previous.revision()+1,
                                previous.createdBy(),previous.reviewedBy(),previous.supersedesBatchId(),previous.rows(),previous.snapshot());
                        update(stored(retired),previous.revision());
                    } else if(batches(building).stream().anyMatch(b->old.month().equals(b.month())&&"SEALED".equals(b.status())))
                        throw conflict("该月已被其他批次封账");
                }
                default -> throw invalid("填报操作无效");
            }
            var value=new Batch(id,building,old.month(),status,old.revision()+1,old.createdBy(),reviewer,old.supersedesBatchId(),old.rows(),snapshot);
            update(stored(value),old.revision());log(user,building,"REPORTING_BATCH_"+action.toUpperCase(Locale.ROOT),id,request.reason());return value;
        });
    }
    record Preparation(Preview preview,Snapshot snapshot) { }
    private Preparation prepare(long user,Collection<String> roles,Batch batch) {
        List<Issue> issues=new ArrayList<>();List<PreparedRow> prepared=new ArrayList<>();
        Set<String> selected=new HashSet<>();Set<String> primaryEnergies=new HashSet<>();boolean allTce=true;boolean hasPrimary=false;
        if(batch.rows().isEmpty())issues.add(new Issue("SOURCE_REQUIRED",batch.batchId(),"尚无可核算来源"));
        for(var row:batch.rows()) {
            if(!selected.add(row.sourceId())){issues.add(new Issue("DUPLICATE_SOURCE",row.sourceId(),"来源重复"));continue;}
            try {
                basicRow(row);var source=source(batch.buildingId(),row.sourceId());var c=source.configuration();
                if(!"APPROVED".equals(source.status())||source.revision()!=row.sourceRevision())throw conflict("来源未审核或版本已变化，请刷新来源");
                if(!covers(c,batch.month()))throw conflict("来源有效期未覆盖整月");
                validateSource(c,true);
                if("CHECK_ONLY".equals(c.purpose())&&!"CONFIRMED".equals(row.valueStatus()))continue;
                if(batch.month().plusMonths(1).atStartOfDay(ZoneId.of(c.timezoneId())).toInstant().isAfter(Instant.now()))throw conflict("月份尚未结束，不能封账");
                if(!row.startDate().equals(batch.month())||!row.endDate().equals(batch.month().plusMonths(1)))throw conflict("实际账期未精确覆盖整月，不自动拆分或平摊");
                if(!"CONFIRMED".equals(row.valueStatus())||row.quantity()==null)throw conflict("用量尚未确认；缺失或不适用不能冒充零值");
                if("PRIMARY".equals(c.purpose())&&!"NATIVE_QUANTITY".equals(row.dataNature()))throw conflict("金额或单价估算须另行确认方法，暂不进入正式计算");
                if(blank(row.evidence()))throw conflict("须引用账单、读数或统计凭证");
                var start=batch.month().atStartOfDay();var end=batch.month().plusMonths(1).atStartOfDay();
                var item=lookup.findApprovedItem(c.energyItemCode(),start).orElseThrow(()->conflict("能源品种未审核"));
                var unit=lookup.findApprovedUnit(c.unitCode(),start).orElseThrow(()->conflict("单位未审核"));
                if(lookup.findApprovedItem(c.energyItemCode(),end.minusNanos(1)).filter(i->i.versionId().equals(item.versionId())).isEmpty()
                    ||lookup.findApprovedUnit(c.unitCode(),end.minusNanos(1)).filter(i->i.versionId().equals(unit.versionId())).isEmpty())
                    throw conflict("期间跨能源或单位版本，不能按天猜分");
                if("PRIMARY".equals(c.purpose())){hasPrimary=true;if(!primaryEnergies.add(c.energyItemCode()))throw conflict("同一能源存在多个总量来源，须明确唯一主来源");}
                BigDecimal tce=null;Object conversionEvidence=null;String tceStatus="NOT_CALCULATED";
                if("PRIMARY".equals(c.purpose())) {
                    if(!blank(c.conversionMethod())&&!blank(c.conversionPerspective())&&!blank(c.regionCode())) {
                        try {
                            var value=conversion.evaluateFormalRules(user,roles,new SimulationRequest(batch.buildingId(),c.energyItemCode(),row.quantity(),
                                c.unitCode(),c.conversionMethod(),c.conversionPerspective(),c.pathway(),c.regionCode(),start),end);
                            tce=value.tce();conversionEvidence=CarbonEvidence.parse(CarbonEvidence.canonical(CarbonEvidence.tree(value)));tceStatus="COMPLETE";
                        } catch(BusinessException e) {if(e.getCode()!=409&&e.getCode()!=404)throw e;}
                    }
                    if(tce==null)allTce=false;
                } else tceStatus="CHECK_ONLY";
                prepared.add(new PreparedRow(row,source,row.quantity(),c.unitCode(),item.versionId(),unit.versionId(),unit,tce,tceStatus,conversionEvidence));
            } catch(BusinessException e) {
                if(e.getCode()==403||e.getCode()==401)throw e;
                issues.add(new Issue(e.getErrorCode()==null?"ROW_INVALID":e.getErrorCode(),row.sourceId(),e.getMessage()));
            }
        }
        // 已配置的本月主来源不能因删除表格行而静默消失。核对来源可不填，不会进入总量。
        for(var s:sources(batch.buildingId()))if("APPROVED".equals(s.status())&&"PRIMARY".equals(s.configuration().purpose())
                &&covers(s.configuration(),batch.month())&&!selected.contains(s.sourceId()))
            issues.add(new Issue("SOURCE_MISSING",s.sourceId(),"本月主来源未填报"));
        if(!hasPrimary)issues.add(new Issue("PRIMARY_REQUIRED",batch.batchId(),"至少需要一个已确认主来源"));
        String nativeStatus=issues.isEmpty()?"COMPLETE":"INCOMPLETE";
        String tceStatus=issues.isEmpty()&&allTce?"COMPLETE":"NOT_CALCULATED";
        var payload=new Snapshot("ENERGY_BILL_MONTH_V1",nativeStatus,tceStatus,List.copyOf(prepared),null);
        var snapshot=new Snapshot(payload.contractVersion(),nativeStatus,tceStatus,payload.rows(),digest(payload));
        return new Preparation(new Preview(issues.isEmpty(),List.copyOf(issues),nativeStatus,tceStatus),snapshot);
    }
    /** 仅供碳输入适配器读取已封账载荷；无用户查询借此绕过建筑授权。 */
    public List<Source> primarySources(String building) {return sources(building).stream().filter(s->"APPROVED".equals(s.status())&&"PRIMARY".equals(s.configuration().purpose())).toList();}
    public List<Batch> sealed(String building) {return batches(building).stream().filter(b->"SEALED".equals(b.status())).toList();}
    private void validateSource(SourceConfig c,boolean formal) {
        if(c==null||blank(c.name())||c.name().length()>100||blank(c.evidence())||c.evidence().length()>1000)throw invalid("来源名称与配置依据必填");
        reason(c.reason());if(formal&&(c.effectiveFrom()==null||c.effectiveFrom().getDayOfMonth()!=1||c.effectiveTo()!=null&&c.effectiveTo().getDayOfMonth()!=1))throw conflict("账单来源生效边界须在自然月边界，月内切换待独立处理");
        if(c.effectiveFrom()==null||c.effectiveTo()!=null&&!c.effectiveTo().isAfter(c.effectiveFrom()))throw invalid("来源有效期无效");
        try{ZoneId.of(c.timezoneId());}catch(Exception e){throw invalid("来源时区无效");}
        if(c.channel()==null||c.purpose()==null||c.meterOverlap()==null||!Set.of("BILL","MANUAL_STATISTICS").contains(c.channel())||!Set.of("PRIMARY","CHECK_ONLY").contains(c.purpose())
                ||!"WHOLE_BUILDING".equals(c.coverage())||!Set.of("NO_METER","REPLACES_METER_TOTAL","PENDING_CONFIRMATION").contains(c.meterOverlap()))
            throw invalid("来源渠道、覆盖范围或主来源选择无效");
        if(c.pathway()==null)throw invalid("核算路径必填");
        if(Set.of("WATER","GREEN_ENERGY").contains(c.pathway())) {if(formal&&"PRIMARY".equals(c.purpose()))throw conflict("水与绿色能源仅预留，未启用核算");return;}
        if(!Set.of("STATIONARY_COMBUSTION","PURCHASED_ELECTRICITY","PURCHASED_HEAT").contains(c.pathway()))throw invalid("不支持该能源路径，移动源不在本期范围");
        var item=lookup.findApprovedItem(c.energyItemCode(),c.effectiveFrom().atStartOfDay()).orElseThrow(()->conflict("来源须选择已审核能源"));
        if(!item.usageScopes().contains(c.pathway())||!switch(c.pathway()){
            case "PURCHASED_ELECTRICITY"->"ELECTRICITY".equals(item.compatibleCategory());
            case "PURCHASED_HEAT"->"HEAT".equals(item.compatibleCategory());
            default->Set.of("NATURAL_GAS","FUEL").contains(item.compatibleCategory());})throw invalid("能源品种与核算路径不一致");
        var unit=lookup.findApprovedUnit(c.unitCode(),c.effectiveFrom().atStartOfDay()).orElseThrow(()->conflict("来源单位须已审核"));
        if(!Set.of("MASS","NORMAL_VOLUME","ENERGY","ACTUAL_VOLUME").contains(unit.dimensionCode()))throw invalid("来源必须使用原生用量单位");
        if(Set.of("PURCHASED_ELECTRICITY","PURCHASED_HEAT").contains(c.pathway())&&!"ENERGY".equals(unit.dimensionCode()))
            throw invalid("外购电与热必须选择能量单位，不能录入质量、流量或功率");
        if("STATIONARY_COMBUSTION".equals(c.pathway())&&!Set.of("MASS","NORMAL_VOLUME","ACTUAL_VOLUME").contains(unit.dimensionCode()))
            throw invalid("固定源燃料须填写质量或明确体积，不能以折标煤或金额替代");
        if(formal&&"ACTUAL_VOLUME".equals(unit.dimensionCode()))throw conflict("普通体积与标准体积换算条件未明确");
        if(formal&&"NORMAL_VOLUME".equals(unit.dimensionCode())&&blank(unit.standardConditionCode()))throw conflict("标准体积的状态条件尚未确认");
        if(formal&&"PRIMARY".equals(c.purpose())&&"PENDING_CONFIRMATION".equals(c.meterOverlap()))throw conflict("与表计的覆盖关系尚待确认");
    }
    private void checkAuthority(String building,Source proposed) {
        var c=proposed.configuration();if(!"PRIMARY".equals(c.purpose()))return;
        Source replaced=null;
        for(var other:sources(building))if(!other.sourceId().equals(proposed.sourceId())&&"APPROVED".equals(other.status())
                &&"PRIMARY".equals(other.configuration().purpose())&&c.energyItemCode().equals(other.configuration().energyItemCode())
                &&overlaps(c,other.configuration())) {
            if(!other.sourceId().equals(c.supersedesSourceId())||!c.effectiveFrom().isAfter(other.configuration().effectiveFrom()))
                throw conflict("同能源覆盖期间已有主来源，不能重复计入总量");
            if(!c.timezoneId().equals(other.configuration().timezoneId()))throw conflict("替代来源不能隐式改变核算时区，须单独确认历史边界");
            replaced=other;
        }
        if(c.supersedesSourceId()!=null&&replaced==null)throw conflict("替代来源不存在、不在本建筑或没有有效期重叠");
        if(replaced!=null) {
            var old=replaced.configuration();
            var closed=new SourceConfig(old.name(),old.energyItemCode(),old.unitCode(),old.channel(),old.pathway(),old.purpose(),old.coverage(),
                old.meterOverlap(),old.timezoneId(),old.effectiveFrom(),c.effectiveFrom(),old.evidence(),old.reason(),
                old.conversionMethod(),old.conversionPerspective(),old.regionCode(),old.supersedesSourceId());
            update(stored(building,new Source(replaced.sourceId(),replaced.revision()+1,replaced.status(),replaced.createdBy(),replaced.reviewedBy(),closed)),replaced.revision());
        }
    }
    private static boolean overlaps(SourceConfig a,SourceConfig b) {
        var az=ZoneId.of(a.timezoneId());var bz=ZoneId.of(b.timezoneId());
        return (b.effectiveTo()==null||a.effectiveFrom().atStartOfDay(az).toInstant().isBefore(b.effectiveTo().atStartOfDay(bz).toInstant()))
            &&(a.effectiveTo()==null||b.effectiveFrom().atStartOfDay(bz).toInstant().isBefore(a.effectiveTo().atStartOfDay(az).toInstant()));
    }
    private static boolean covers(SourceConfig c,LocalDate month) {return !c.effectiveFrom().isAfter(month)&&(c.effectiveTo()==null||!c.effectiveTo().isBefore(month.plusMonths(1)));}
    private static void basicRow(Row r) {
        if(r==null||blank(r.sourceId())||r.sourceRevision()<0||r.startDate()==null||r.endDate()==null||!r.startDate().isBefore(r.endDate()))throw invalid("来源或实际账期无效");
        if(r.valueStatus()==null||!Set.of("CONFIRMED","PENDING_CONFIRMATION","NOT_APPLICABLE","MISSING").contains(r.valueStatus()))throw invalid("用量状态无效");
        if(r.quantity()!=null&&(r.quantity().signum()<0||r.quantity().precision()>28||r.quantity().scale()>12||(long)r.quantity().precision()-r.quantity().scale()>28))throw invalid("用量必须非负且精度最多12位");
        if(r.exclusionQuantity()!=null&&(r.exclusionQuantity().signum()<0||r.quantity()==null||r.exclusionQuantity().compareTo(r.quantity())>0
                ||r.exclusionQuantity().scale()>12||r.exclusionQuantity().precision()>28||(long)r.exclusionQuantity().precision()-r.exclusionQuantity().scale()>28))throw invalid("排除量须非负且不超过原生用量");
        if(r.exclusionQuantity()!=null&&r.exclusionQuantity().signum()>0&&(blank(r.exclusionReason())||blank(r.exclusionEvidence())))throw invalid("排除须有明确量、原因及计量依据");
        if(r.evidence()!=null&&r.evidence().length()>1000||r.note()!=null&&r.note().length()>1000)throw invalid("凭证或说明超长");
    }
    private void exclusionDuty(long user,Collection<String> roles,Batch b) {if(b.rows().stream().anyMatch(r->r.exclusionQuantity()!=null&&r.exclusionQuantity().signum()>0))require(user,roles,BackendDuty.CARBON_RULE_REVIEW);}
    private void read(long user,Collection<String> roles,String building) {
        if(roles==null||Collections.disjoint(roles,List.of("BUILDING_OWNER","ENERGY_MANAGER","PLATFORM_ADMIN")))throw new BusinessException(403,"REPORTING_FORBIDDEN","无权访问填报");
        if(blank(building)||building.length()>32)throw invalid("建筑标识无效");scopes.checkAccess(user,roles,building);if(!repository.buildingExists(building))throw new BusinessException(404,"REPORTING_NOT_FOUND","建筑不存在");
    }
    private void require(long user,Collection<String> roles,BackendDuty duty) {if(!manager(roles))throw new BusinessException(403,"REPORTING_FORBIDDEN","缺少治理角色");duties.requireDuty(user,duty);}
    private static boolean manager(Collection<String> roles){return roles!=null&&(roles.contains("ENERGY_MANAGER")||roles.contains("PLATFORM_ADMIN"));}
    private List<Source> sources(String building){return records(building,"SOURCE").stream().map(r->decode(r.payload(),Source.class)).toList();}
    private List<Batch> batches(String building){return records(building,"BATCH").stream().map(r->decode(r.payload(),Batch.class)).toList();}
    private List<ReportingRepository.Stored> records(String building,String kind){var rows=repository.list(building,kind);if(rows.size()>500)throw conflict("记录超过单次500条上限，请联系管理员归档");return rows;}
    private Source source(String building,String id){return decode(record(building,id,"SOURCE").payload(),Source.class);}
    private Batch batch(String building,String id){return decode(record(building,id,"BATCH").payload(),Batch.class);}
    private ReportingRepository.Stored record(String building,String id,String kind){var r=repository.find(building,id);if(r==null||!kind.equals(r.kind()))throw new BusinessException(404,"REPORTING_NOT_FOUND","来源或批次不存在");return r;}
    private ReportingRepository.Stored stored(String building,Source s){return new ReportingRepository.Stored(s.sourceId(),building,"SOURCE",s.status(),s.revision(),null,json(s));}
    private ReportingRepository.Stored stored(Batch b){return new ReportingRepository.Stored(b.batchId(),b.buildingId(),"BATCH",b.status(),b.revision(),b.month().toString(),json(b));}
    private void update(ReportingRepository.Stored r,long expected){if(repository.update(r,expected)!=1)throw conflict("修订已变化，请刷新后重试");}
    private <T>T once(long user,String building,String key,String action,Object request,Class<T> type,Supplier<T> operation){
        if(blank(key)||key.length()>100)throw invalid("幂等键必填且最多100字");
        String hash=digest(List.of(user,action,request));var receipt=repository.receipt(building,key);
        if(receipt!=null){if(!receipt.hash().equals(hash))throw conflict("幂等键已对应其他操作或内容");return decode(receipt.json(),type);}
        T value=operation.get();repository.receipt(building,key,hash,json(value));return value;
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw invalid("填报数据无法保存");}}
    private <T>T decode(String json,Class<T> type){try{return mapper.readValue(json,type);}catch(Exception e){throw conflict("填报固定证据无法读取");}}
    private static String digest(Object value){return com.platform.carbon.CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(value)));}
    private static void ready(Preparation p){if(!p.preview().ready())throw conflict(p.preview().issues().getFirst().message());}
    private static void month(LocalDate month){if(month==null||month.getDayOfMonth()!=1||month.getYear()<2000||month.getYear()>2200)throw invalid("月份须为2000至2200年内每月首日");}
    private static void reason(String reason){if(blank(reason)||reason.length()>500)throw invalid("操作原因必填且最多500字");}
    private void separate(long creator,long reviewer){
        if(creator==reviewer)throw new BusinessException(403,"REPORTING_REVIEW_SEPARATION","填报与来源必须由另一人独立审核");
        duties.requireSeparation(creator,reviewer);
    }
    private static void author(long author,long user){if(author!=user)throw new BusinessException(403,"REPORTING_FORBIDDEN","只能修改本人草稿");}
    private static void state(String actual,String expected){if(!expected.equals(actual))throw conflict("当前状态不允许此操作");}
    private static void revision(long actual,Long expected){if(expected==null||actual!=expected)throw conflict("修订号已变化");}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static BusinessException invalid(String message){return new BusinessException(400,"REPORTING_INVALID",message);}
    private static BusinessException conflict(String message){return new BusinessException(409,"REPORTING_BLOCKED",message);}
    private void log(long user,String building,String action,String id,String reason){audit.append(new AuditEvidence("ENERGY_REPORTING",building,"USER",user,action,"ENERGY_REPORTING",id,id,null,null,reason,"SUCCESS",null,TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),false));}
}
