package com.platform.carbon;

import com.platform.audit.*;
import com.platform.carbon.api.CarbonContracts.LifecycleRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDate;
import java.util.*;
import static com.platform.carbon.CarbonModels.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AreaLifecycleAuditTest {
    @Test void activationAuditsEveryChangedIntervalWithoutTruncatingLongHistory() {
        var repository=mock(CarbonRuleRepository.class);
        var authorization=mock(CarbonAuthorization.class);
        var writer=mock(AuditEvidenceWriter.class);
        var value=mock(DenominatorVersion.class);
        when(value.denominatorVersionId()).thenReturn("V");when(value.denominatorId()).thenReturn("D");
        when(value.buildingId()).thenReturn("B");when(value.type()).thenReturn(DenominatorType.BUILDING_AREA);
        when(value.status()).thenReturn(LifecycleStatus.APPROVED);when(value.effectiveFrom()).thenReturn(LocalDate.of(2024,1,1));
        when(repository.findDenominatorVersion("V")).thenReturn(value);
        when(repository.activateDenominator(eq("V"),eq(0),eq(3L),any())).thenReturn(1);
        var rows=java.util.stream.IntStream.range(0,40).mapToObj(i->"old-version-"+i+":2024-01-01:2025-01-01").toList();
        when(repository.areaAudit(value)).thenReturn(String.join(";",rows),"V:2024-01-01:null");
        var service=new CarbonRuleService(authorization,repository,writer,new AuditGovernanceProperties());
        service.activateDenominator(3,List.of("PLATFORM_ADMIN"),"V",new LifecycleRequest(0));
        var audit=ArgumentCaptor.forClass(AuditEvidence.class);
        verify(writer,times(42)).append(audit.capture());
        assertThat(audit.getAllValues()).filteredOn(a->a.actionType().equals("REMOVE_CARBON_AREA_PERIOD"))
                .extracting(AuditEvidence::beforeSummary).containsExactlyElementsOf(rows);
        verify(repository).findDenominatorIdForUpdate("B",DenominatorType.BUILDING_AREA);
        verify(repository).applyArea(value,3);
    }
}
