package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.classification.MonthlyReadingWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonErrors.*;

/** Reuses approved classification and project deductions, not raw totals or dynamic carbon results. */
@Service
public class DailyStaticCarbonInput {
    private final ClassificationService classifications;
    private final CarbonProjectService projects;
    private final CarbonProjectCore projectCore;
    public DailyStaticCarbonInput(ClassificationService classifications,CarbonProjectService projects,CarbonProjectCore projectCore) {
        this.classifications=classifications;this.projects=projects;this.projectCore=projectCore;
    }
    public record Input(BigDecimal quantity,String classificationVersion,String relationVersion,String projectVersion,
                        String sourceNature,JsonNode evidence) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Input read(long user,Collection<String> roles,String building,LocalDate date) {
        return withinDay(user,roles,building,date,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Input readPrepared(long user,Collection<String> roles,String building,LocalDate date,MonthlyReadingWindow sources) {
        return withinDay(user,roles,building,date,Objects.requireNonNull(sources));
    }
    private Input withinDay(long user,Collection<String> roles,String building,LocalDate date,MonthlyReadingWindow sources) {
        var zone=ZoneId.of("Asia/Shanghai");Instant from=date.atStartOfDay(zone).toInstant(),to=date.plusDays(1).atStartOfDay(zone).toInstant();
        var project=projects.effective(building,from,to);
        if(project==null || project.configuration().energies().stream().noneMatch(e->"ELECTRICITY".equals(e.energyItemCode())
                && "PURCHASED_ELECTRICITY".equals(e.pathway()) && "ENABLED".equals(e.capability())))
            throw error(409,DEPENDENCY_UNAVAILABLE,"Daily static calculation requires an effective purchased-electricity project");
        var config=classifications.effectiveVersion(user,roles,building,from,to,project.configuration().classificationVersionId());
        var units=config.configuration().boundaryRules().stream().filter(v->"ELECTRICITY".equals(v.energyItemCode()))
                .map(ClassificationContracts.BoundaryRule::unit).distinct().toList();
        if(units.size()!=1 || !"KWH".equalsIgnoreCase(units.getFirst()))throw error(409,UNIT_INCOMPATIBLE,"Daily electricity requires one KWH boundary");
        var scheme=config.configuration().schemes().stream().min(Comparator.comparing(ClassificationContracts.Scheme::code))
                .orElseThrow(()->error(409,DEPENDENCY_UNAVAILABLE,"Daily classification scheme missing"));
        var request=new ClassificationContracts.QueryRequest(from,to,"DAY","ELECTRICITY",units.getFirst(),scheme.code(),config.versionId(),List.of());
        var projection=sources==null?classifications.queryProjection(user,roles,building,request)
                :classifications.queryProjectionUsingMonthSource(user,roles,building,request,sources);
        var view=projection.view();
        if(!building.equals(view.buildingId()) || !from.equals(view.startInclusive()) || !to.equals(view.endExclusive())
                || !"KWH".equalsIgnoreCase(view.unit()) || !"CALCULABLE".equals(view.status()) || view.total()==null || view.total().signum()<0)
            throw error(409,ACTIVITY_INCOMPLETE,"Daily classified electricity is not complete");
        var applied=projectCore.apply(project.configuration(),"ELECTRICITY",projection.slices());
        if(applied.included().add(applied.excluded()).compareTo(view.total())!=0)
            throw error(409,ACTIVITY_INCOMPLETE,"Daily source slices disagree with the classified total");
        var evidence=CarbonEvidence.object();
        evidence.put("contractVersion","DAILY_STATIC_INPUT_V1");evidence.put("periodType","DAY");
        evidence.put("sourceNatureAssurance","SOURCE_LABELLED_NOT_FIELD_ACCEPTANCE");
        evidence.set("project",CarbonEvidence.tree(project));evidence.set("classification",CarbonEvidence.tree(config));
        evidence.set("projection",CarbonEvidence.tree(view));evidence.set("sourceSlices",CarbonEvidence.tree(projection.slices()));
        evidence.set("projectApplied",CarbonEvidence.tree(applied));
        return new Input(applied.included(),view.versionId(),view.relationVersionId(),project.versionId(),view.resultNature(),evidence);
    }
}
