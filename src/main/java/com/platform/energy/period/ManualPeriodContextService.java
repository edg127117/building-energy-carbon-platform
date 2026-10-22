package com.platform.energy.period;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationRepository;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import static com.platform.energy.period.EnergyPeriodErrors.*;
import static com.platform.energy.period.EnergyPeriodModels.*;

/** 以授权建筑和明确月份读取后端候选项，不要求用户手填来源、策略或投影内部ID。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class ManualPeriodContextService {
    public record ProjectionReference(String projectionId,long revision,String status,String quantity,String unit,String nature) { }
    public record PendingLock(String requestId,String projectionId,long projectionRevision,long submittedBy,String status) { }
    public record Context(List<PeriodPolicyVersion> policies,List<RelationContracts.VersionView> relations,
                          List<ClassificationContracts.ReadingView> readings,ProjectionReference projection,List<PendingLock> pendingLocks) { }
    private final EnergyPeriodAuthorization authorization;
    private final EnergyPeriodGovernanceRepository policies;
    private final ClassificationRepository manual;
    private final EnergyPeriodResultRepository periods;
    private final RelationGovernanceService relations;
    private final ObjectMapper mapper;
    @Qualifier("mysqlJdbcTemplate") private final JdbcTemplate jdbc;
    public Context context(long user,Collection<String> roles,String building,String point,LocalDate month,String policyId) {
        authorization.requireReader(roles);authorization.checkBuilding(user,roles,building);
        if(!manual.objectExists(building,"POINT",point))throw error(404,NOT_FOUND,"标准测点不存在或不属于指定建筑");
        var choices=policies.manualPolicies(building);
        var versions=relations.versions(user,roles,building).stream().filter(v->Set.of("EFFECTIVE","SUPERSEDED").contains(v.status())).toList();
        if(policyId==null||policyId.isBlank())return new Context(choices,versions,List.of(),null,List.of());
        var policy=choices.stream().filter(p->p.versionId().equals(policyId)&&p.status().equals("APPROVED")).findFirst()
                .orElseThrow(()->error(409,POLICY_REQUIRED,"请选择已审核的正式周期口径"));
        var window=EnergyPeriodBoundary.resolve("MONTH",month,policy.timezoneId());
        var rows=manual.readings(building,window.startInclusive(),window.endExclusive(),true);
        if(rows.size()>5000)throw error(409,LOCK_BLOCKED,"人工来源超过读取上限");
        var readings=rows.stream().map(r->{try{return new ClassificationContracts.ReadingView(r.id(),r.batchId(),mapper.readValue(r.json(),ClassificationContracts.Reading.class),r.quantity(),r.author(),r.batchStatus());}
            catch(Exception e){throw error(409,LOCK_BLOCKED,"人工来源证据无法读取");}}).filter(r->point.equals(r.reading().pointId())).toList();
        var current=periods.findCurrent(building,point,"MONTH",window.startInclusive(),window.endExclusive());
        var reference=current==null?null:new ProjectionReference(current.projectionId(),current.revision(),current.status(),current.nativeQuantity().toPlainString(),current.nativeUnitCode(),current.resultNature());
        var pending=current==null?List.<PendingLock>of():jdbc.query("SELECT request_id,projection_id,projection_revision,submitted_by,status FROM biz_energy_period_lock_request WHERE building_id=? AND projection_id=? AND status='PENDING_REVIEW' ORDER BY submitted_at DESC LIMIT 100",
                (r,n)->new PendingLock(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),r.getString(5)),building,current.projectionId());
        return new Context(choices,versions,readings,reference,pending);
    }
}
