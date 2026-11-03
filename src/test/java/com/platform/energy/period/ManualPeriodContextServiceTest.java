package com.platform.energy.period;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.classification.ClassificationContracts.Reading;
import com.platform.energy.classification.ClassificationRepository;
import com.platform.energy.period.EnergyPeriodModels.PeriodPolicyVersion;
import com.platform.relation.RelationGovernanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 候选上下文只能读取请求测点的当前审核来源，不能受同楼其他测点数量影响。 */
@ExtendWith(MockitoExtension.class)
class ManualPeriodContextServiceTest {
    private static final long USER=101L;
    private static final String BUILDING="BLD001",POINT="POINT001";
    private static final LocalDate MONTH=LocalDate.of(2020,1,1);
    private static final Instant START=Instant.parse("2019-12-31T16:00:00Z"),END=Instant.parse("2020-01-31T16:00:00Z");

    @Mock private EnergyPeriodAuthorization authorization;
    @Mock private EnergyPeriodGovernanceRepository policies;
    @Mock private ClassificationRepository manual;
    @Mock private EnergyPeriodResultRepository periods;
    @Mock private RelationGovernanceService relations;
    @Mock private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private ManualPeriodContextService service;

    @BeforeEach
    void setUp() {
        mapper=new ObjectMapper().findAndRegisterModules();
        service=new ManualPeriodContextService(authorization,policies,manual,periods,relations,mapper,jdbc);
    }

    @Test
    void contextReadsOnlyTheRequestedPointCurrentApprovedFacts() throws Exception {
        Reading reading=new Reading(POINT,START,END,"GRID_ELECTRICITY","kWh","PERIOD_TOTAL",
                null,null,new BigDecimal("300"),BigDecimal.ONE,false,"CONFIRMED","manual","EXACT",null);
        when(manual.objectExists(BUILDING,"POINT",POINT)).thenReturn(true);
        when(policies.manualPolicies(BUILDING)).thenReturn(List.of(policy()));
        when(relations.versions(USER,Set.of("ENERGY_MANAGER"),BUILDING)).thenReturn(List.of());
        when(manual.currentApprovedReadings(BUILDING,POINT,START,END,1001)).thenReturn(List.of(
                new ClassificationRepository.StoredReading("F1","IMPORT-1",mapper.writeValueAsString(reading),
                        new BigDecimal("300"),USER,"APPROVED")));

        var context=service.context(USER,Set.of("ENERGY_MANAGER"),BUILDING,POINT,MONTH,"POLICY-1");

        assertThat(context.readings()).singleElement().satisfies(value -> assertThat(value.factId()).isEqualTo("F1"));
        verify(manual).currentApprovedReadings(BUILDING,POINT,START,END,1001);
        verify(manual,never()).readings(anyString(),any(),any(),anyBoolean());
    }

    private PeriodPolicyVersion policy() {
        return new PeriodPolicyVersion("POLICY","POLICY-1",1,BUILDING,"Asia/Shanghai",0,
                "REVIEW_REQUIRED","APPROVED","FORMAL","evidence",LocalDateTime.of(2019,1,1,0,0),
                null,1,USER,LocalDateTime.of(2019,1,1,0,0),USER + 1,
                LocalDateTime.of(2019,1,2,0,0));
    }
}
