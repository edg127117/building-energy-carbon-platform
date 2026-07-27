package com.platform.iot.formula;

import com.platform.hvac.mapper.BizIndicatorMapper;
import com.platform.hvac.model.entity.BizIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MySqlIndicatorConfigProviderTest {
    @Mock private BizIndicatorMapper mapper;
    private MySqlIndicatorConfigProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MySqlIndicatorConfigProvider(mapper);
    }

    @Test
    void refreshPublishesOnlyActiveIndicatorsAsAnImmutableSnapshot() {
        BizIndicator active = indicator("IND001", 1);
        BizIndicator disabled = indicator("IND002", 0);
        when(mapper.selectList(any())).thenReturn(List.of(active, disabled));

        provider.refreshAll();

        assertThat(provider.findAllActive()).containsExactly(active);
        assertThat(provider.findActive("IND001")).contains(active);
        assertThat(provider.findActive("IND002")).isEmpty();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> provider.findAllActive().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failedRefreshRetainsPriorCompleteSnapshot() {
        BizIndicator active = indicator("IND001", 1);
        when(mapper.selectList(any()))
                .thenReturn(List.of(active))
                .thenThrow(new IllegalStateException("mysql unavailable"));

        provider.refreshAll();
        provider.refreshAll();

        assertThat(provider.findAllActive()).containsExactly(active);
    }

    private static BizIndicator indicator(String id, int status) {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId(id);
        indicator.setBuildingId("BLD001");
        indicator.setIndicatorCode("WCR_COP");
        indicator.setEquipId("EQUIP_WCR_B1");
        indicator.setStatus(status);
        return indicator;
    }
}
