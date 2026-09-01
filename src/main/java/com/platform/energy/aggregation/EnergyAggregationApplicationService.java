package com.platform.energy.aggregation;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationQuery;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationResult;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.AggregationSimulationRequest;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.AggregationSimulationView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;

@Service
@RequiredArgsConstructor
/** 编排受权限保护的研发聚合；不保存或发布正式能源结果。 */
public class EnergyAggregationApplicationService {
    private final EnergyAggregationAuthorization authorization;
    private final EnergyAggregationInputAssembler inputAssembler;
    private final EnergyAggregationExecutor executor;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    public AggregationSimulationView simulate(
            long userId, Collection<String> roles, AggregationSimulationRequest request) {
        authorization.requireRunner(userId, roles);
        authorization.checkBuilding(userId, roles, request.buildingId());
        AggregationQuery query = new AggregationQuery(request.buildingId(), request.pointId(),
                request.startInclusive(), request.endExclusive(), request.calculationAsOf());
        AggregationResult result = executor.execute(query,
                current -> inputAssembler.load(userId, roles, current));
        auditWriter.append(new AuditEvidence("ENERGY_AGGREGATION", request.buildingId(), "USER", userId,
                "RUN_DEVELOPMENT_AGGREGATION", "ENERGY_ACTIVITY_AGGREGATION", request.pointId(),
                null, null, null, "resultNature=" + result.resultNature()
                + ";quantityUnit=" + result.resultUnitCode(), "SUCCESS", null,
                TraceContext.current(), LocalDateTime.now(), auditProperties.getEnvironmentMode(), false));
        return view(result);
    }

    private static AggregationSimulationView view(AggregationResult result) {
        return new AggregationSimulationView(result.resultNature(), result.buildingId(), result.pointId(),
                result.energyItemCode(), result.valueSemantics().name(), result.quantity(),
                result.resultUnitCode(), result.coverageRatio(), result.maximumObservedGapSeconds(),
                result.completeness().name(), result.calculationAsOf(), result.activityWatermark(),
                result.relationVersionId(), result.pointBindingVersionId(), result.qualityPolicyVersions(),
                result.meterEventVersions(), result.correctionVersions(),
                result.integrationPolicyVersionId());
    }
}
