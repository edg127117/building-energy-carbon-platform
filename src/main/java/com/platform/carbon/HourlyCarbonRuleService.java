package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.carbon.CarbonModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import static com.platform.carbon.CarbonErrors.*;

/** 每小时在短只读事务中固定因子、GWP、公式及舍入证据；不持有事务读取时序源。 */
@Service
@RequiredArgsConstructor
public class HourlyCarbonRuleService {
    private final CarbonRuleRepository rules;
    private final CarbonCalculationCore core;
    private final PlatformTransactionManager transactionManager;
    private final CarbonProperties properties;
    public record Calculated(BigDecimal exactKgCo2e,String factorVersionId,String formulaVersionId,String gwpVersionId,
                             String roundingPolicyVersionId,String gasCoverage,JsonNode evidence) { }

    public Calculated calculate(ActivitySegment activity,UsageNature usage) {
        var transaction=new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var remaining=CarbonCalculationDeadline.currentRemaining();
        var budget=remaining==null||remaining.compareTo(properties.getQueryTimeout())>0?properties.getQueryTimeout():remaining;
        transaction.setTimeout(CarbonCalculationDeadline.timeoutSeconds(budget));
        return transaction.execute(status->calculateSnapshot(activity,usage));
    }

    private Calculated calculateSnapshot(ActivitySegment activity,UsageNature usage) {
        var from=LocalDateTime.ofInstant(activity.startInclusive(),ZoneId.of(activity.timezoneId()));
        var to=LocalDateTime.ofInstant(activity.endExclusive(),ZoneId.of(activity.timezoneId()));
        var nature=usage==UsageNature.FORMAL?ResultNature.FORMAL:ResultNature.DEVELOPMENT_SIMULATION;
        var match=core.match(activity,rules.findBuildingRegion(activity.buildingId()),nature,rules.hourlyFactors(activity.energyItemCode(),from,to));
        var factor=match.factor();
        if(factor.scopeType()!=ScopeType.SCOPE_2 || factor.category()!=FactorCategory.PURCHASED_ELECTRICITY_LOCATION)
            throw error(409,FACTOR_CONFLICT,"小时曲线首期仅计算外购电力位置法范围二");
        if(!rules.activeFormulaMatches(factor.formulaVersionId(),factor.category(),usage,factor.resultBasis())
                ||!rules.activeRoundingPolicy(factor.roundingPolicyVersionId()))throw error(409,DEPENDENCY_UNAVAILABLE,"小时因子引用的公式或舍入版本未生效");
        var formula=rules.formulaEvidence(factor.formulaVersionId());
        if(!covers(formula,from,to))throw error(409,FACTOR_CONFLICT,"公式版本未覆盖整个小时");
        GwpVersion gwp=null;
        if("GAS_MASS".equals(factor.resultBasis())) {
            var values=rules.hourlyGwp(factor.gasCode(),usage,from,to);
            if(values.isEmpty())throw error(409,FACTOR_MISSING,"该小时缺少GWP版本");
            if(values.size()!=1 || values.getFirst().effectiveFrom().isAfter(from)
                    || values.getFirst().effectiveTo()!=null && values.getFirst().effectiveTo().isBefore(to))
                throw error(409,FACTOR_CONFLICT,"小时内GWP换版或存在冲突");
            gwp=values.getFirst();
        }
        var source=rules.findSourceVersion(factor.sourceVersionId());
        if(source==null)throw error(409,DEPENDENCY_UNAVAILABLE,"排放因子来源版本不存在");
        var calculated=core.calculate(activity,match,gwp);
        var evidence=(com.fasterxml.jackson.databind.node.ObjectNode)CarbonEvidence.parse(calculated.evidenceJson());
        evidence.set("formulaDefinition",CarbonEvidence.tree(formula));
        evidence.set("roundingDefinition",CarbonEvidence.tree(rules.roundingEvidence(factor.roundingPolicyVersionId())));
        evidence.set("factorSource",CarbonEvidence.tree(source));
        return new Calculated(calculated.exactEmissionKgCo2e(),factor.factorVersionId(),factor.formulaVersionId(),calculated.gwpVersionId(),
                factor.roundingPolicyVersionId(),factor.gasCoverage(),evidence);
    }
    private static boolean covers(Map<String,Object> definition,LocalDateTime from,LocalDateTime to) {
        LocalDateTime start=date(definition.get("effective_from")),end=date(definition.get("effective_to"));
        return start!=null&&!start.isAfter(from)&&(end==null||!end.isBefore(to));
    }
    private static LocalDateTime date(Object value) {
        if(value==null)return null;
        if(value instanceof java.sql.Timestamp stamp)return stamp.toLocalDateTime();
        if(value instanceof LocalDateTime date)return date;
        throw error(409,DEPENDENCY_UNAVAILABLE,"公式有效期证据类型无效");
    }
}
