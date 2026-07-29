package com.platform.hvac.service;

import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.impl.BuildingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildingServiceImplTest {

    @Mock
    private BuildingMapper mapper;

    private BuildingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BuildingServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void locksExistingBuildingThroughMapper() {
        Building building = new Building();
        building.setBuildingId("BLD001");
        when(mapper.selectExistingForUpdate("BLD001")).thenReturn(building);

        service.lockExistingForUpdate("BLD001");

        verify(mapper).selectExistingForUpdate("BLD001");
    }

    @Test
    void reportsNotFoundWhenBuildingCannotBeLocked() {
        when(mapper.selectExistingForUpdate("BLD404")).thenReturn(null);

        assertThatThrownBy(() -> service.lockExistingForUpdate("BLD404"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(404);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo("建筑不存在");
                });
    }
}
