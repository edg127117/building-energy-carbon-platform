package com.platform.energy.reporting;

import com.platform.carbon.CarbonEvidence;
import com.platform.carbon.CarbonCalculationCore;
import com.platform.carbon.CarbonModels.*;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.energy.reporting.ReportingContracts.*;

/** 仅读取独立审核后的冻结账单；主来源明确替代表计总量，核对来源不参与碳计算。 */
@Component
public class ReportingCarbonInput {
    private final ReportingService service;
    public ReportingCarbonInput(ReportingService service) {this.service=service;}
    public record Input(List<ActivitySegment> activities,Map<String,String> authority) { }
    public Input read(String building,Instant start,Instant end,int limit) {
        if(limit<1)throw blocked("碳活动查询上限无效");
        var batches=service.sealed(building);List<ActivitySegment> result=new ArrayList<>();Map<String,String> authority=new HashMap<>();
        for(var source:service.primarySources(building)) {
            var config=source.configuration();var zone=ZoneId.of(config.timezoneId());
            LocalDate month=LocalDateTime.ofInstant(start,zone).toLocalDate().withDayOfMonth(1);
            int checked=0;
            while(month.atStartOfDay(zone).toInstant().isBefore(end)) {
                if(++checked>13)throw blocked("单次账单核算最多13个月段");
                LocalDate current=month;
                if(!config.effectiveFrom().isAfter(current)&&(config.effectiveTo()==null||!config.effectiveTo().isBefore(current.plusMonths(1)))
                    &&batches.stream().noneMatch(b->current.equals(b.month())&&b.snapshot()!=null&&b.snapshot().rows().stream()
                        .anyMatch(r->r.source().sourceId().equals(source.sourceId())&&"PRIMARY".equals(r.source().configuration().purpose()))))
                    throw blocked("已配置主来源尚无对应月份正式封账，不能回退到表计或零用量");
                month=month.plusMonths(1);
            }
        }
        for(var batch:batches) {
            var snapshot=batch.snapshot();
            if(snapshot==null||!"COMPLETE".equals(snapshot.nativeStatus()))throw blocked("封账账单证据不完整");
            var unsigned=new Snapshot(snapshot.contractVersion(),snapshot.nativeStatus(),snapshot.tceStatus(),snapshot.rows(),null);
            String hash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(unsigned)));
            if(!hash.equals(snapshot.evidenceHash()))throw blocked("封账账单摘要校验失败");
            for(var row:snapshot.rows()) {
                var config=row.source().configuration();if(!"PRIMARY".equals(config.purpose()))continue;
                ZoneId zone=ZoneId.of(config.timezoneId());
                Instant from=batch.month().atStartOfDay(zone).toInstant(),to=batch.month().plusMonths(1).atStartOfDay(zone).toInstant();
                if(!from.isBefore(end)||!to.isAfter(start))continue;
                if(from.isBefore(start)||to.isAfter(end))throw blocked("核算区间切入账单，不允许按天拆分");
                String key=key(config.energyItemCode(),from);
                if(authority.putIfAbsent(key,config.meterOverlap())!=null)throw blocked("封账账单存在重复总量来源");
                if(!Set.of("NO_METER","REPLACES_METER_TOTAL").contains(config.meterOverlap()))throw blocked("封账账单的表计覆盖关系未确认");
                var trace=CarbonEvidence.object();trace.put("contractVersion","ENERGY_BILL_CARBON_V1");
                trace.put("batchId",batch.batchId());trace.put("batchSnapshotHash",snapshot.evidenceHash());
                trace.put("sourceId",row.source().sourceId());trace.put("projectEnergyPathway",config.pathway());
                trace.set("billSource",CarbonEvidence.tree(row));trace.put("classificationStatus","UNALLOCATED");
                BigDecimal excluded=row.input().exclusionQuantity()==null?BigDecimal.ZERO:row.input().exclusionQuantity();
                BigDecimal included=row.nativeQuantity().subtract(excluded);
                if(included.signum()<0)throw blocked("封账排除量超过原生量");
                trace.put("grossQuantity",row.nativeQuantity().toPlainString());trace.put("excludedQuantity",excluded.toPlainString());
                if(excluded.signum()>0&&included.signum()==0)trace.put("activityStatus","EXCLUDED");
                // 只使用封账时固定的单位比例，碳算法收到标准基准量，不再读取当前单位目录。
                var unit=row.unitEvidence();
                if(unit==null||!row.unitVersionId().equals(unit.versionId())||!row.nativeUnit().equals(unit.unitCode())
                        ||unit.scaleFactor()==null||unit.scaleFactor().signum()<=0)throw blocked("封账单位证据不完整");
                if("NORMAL_VOLUME".equals(unit.dimensionCode())) {
                    if(unit.standardConditionCode()==null||unit.standardConditionCode().isBlank())throw blocked("封账标准体积条件缺失");
                    trace.put("standardConditionCode",unit.standardConditionCode());
                }
                BigDecimal canonical=included.multiply(unit.scaleFactor(),java.math.MathContext.DECIMAL128);
                trace.put("canonicalQuantity",canonical.toPlainString());trace.put("canonicalUnit",unit.canonicalUnitCode());
                String evidenceHash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(trace));
                result.add(new ActivitySegment(evidenceHash.substring(0,32),building,PeriodType.MONTH,from,to,config.timezoneId(),
                    config.energyItemCode(),canonical,unit.canonicalUnitCode(),"LOCKED_NATIVE_COMPLETE","COMPLETE",ResultNature.FORMAL,evidenceHash,trace));
                if(result.size()>limit)throw blocked("账单活动量超过单次上限");
            }
        }
        return new Input(List.copyOf(result),Map.copyOf(authority));
    }
    public static String key(String energy,Instant from) {return energy+"|"+from;}
    private static BusinessException blocked(String message){return new BusinessException(409,"REPORTING_INPUT_BLOCKED",message);}
}
