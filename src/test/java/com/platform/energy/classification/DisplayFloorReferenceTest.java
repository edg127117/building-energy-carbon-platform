package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.relation.RelationGovernanceProperties;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayFloorReferenceTest {
    @Test void onlyAcceptsFloorObjectsFromTheExplicitHistoricalBuildingVersion() {
        var repository=mock(ClassificationRepository.class);var authorization=mock(EnergyPeriodAuthorization.class);var relations=mock(RelationGovernanceService.class);
        when(repository.buildingExists("B")).thenReturn(true);
        var service=new ClassificationService(repository,authorization,relations,null,new ClassificationCore(),new ObjectMapper(),null,null,null,new RelationGovernanceProperties());
        var metadata=new QueryMetadata("B","REL",1,LocalDateTime.now(),1,0,false,0,0,0,0);
        var floor=new SpaceNodeView("F","一层","FLOOR",null,1,List.of(new SpaceNodeView("R","房间","ROOM","F",1,List.of())));
        when(relations.historicalSpaceTree(7L,List.of("ENERGY_MANAGER"),"B","REL")).thenReturn(new SpaceTreeView(metadata,List.of(floor)));
        service.validateDisplayFloors(7,List.of("ENERGY_MANAGER"),"B","REL",Set.of("F"));
        assertThatThrownBy(()->service.validateDisplayFloors(7,List.of("ENERGY_MANAGER"),"B","REL",Set.of("R"))).hasMessageContaining("非楼层");
        assertThatThrownBy(()->service.validateDisplayFloors(7,List.of("ENERGY_MANAGER"),"B","REL",Set.of("UNKNOWN"))).hasMessageContaining("不存在");
        when(relations.historicalSpaceTree(7L,List.of("ENERGY_MANAGER"),"B","REL")).thenReturn(new SpaceTreeView(new QueryMetadata("OTHER","REL",1,LocalDateTime.now(),1,0,false,0,0,0,0),List.of(floor)));
        assertThatThrownBy(()->service.validateDisplayFloors(7,List.of("ENERGY_MANAGER"),"B","REL",Set.of("F"))).hasMessageContaining("建筑/版本");
        verify(relations,never()).effectiveSpaceTree(anyLong(),anyCollection(),anyString());
    }
}
