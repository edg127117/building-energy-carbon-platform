package com.platform.carbon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.UUID;

/** 账单封账变更进入既有碳影响候选链，不直接替代任何已发布报告。与账单封账同事务。 */
@Component
@RequiredArgsConstructor
public class ReportingSnapshotChanges {
    private final CarbonRuleRepository repository;
    public void sealed(String building,String previousId,String currentId,LocalDate month,long user) {
        repository.insertDependencyChange(UUID.randomUUID().toString().replace("-",""),"ACTIVITY_SNAPSHOT","ENERGY_REPORTING_BATCH",currentId,
            "账单正式活动输入已封账或修订",previousId,currentId,CarbonCalculationCore.sha256("BILL|"+building+"|"+currentId),
            building,null,month.atStartOfDay(),month.plusMonths(1).atStartOfDay(),user,LocalDateTime.now());
    }
}
