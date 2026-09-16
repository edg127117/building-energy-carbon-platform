package com.platform.audit.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BindTypedPendingDeviceHandlerTest {

    @Test
    void normalizePreservesTypedTemperatureBindingsAndExplicitSource() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DeviceOnboardingService service = mock(DeviceOnboardingService.class);
        SystemSensitiveCommandSupport support = new SystemSensitiveCommandSupport(mapper);
        BindTypedPendingDeviceHandler handler = new BindTypedPendingDeviceHandler(support, service);
        var point = new DeviceOnboardingContracts.PointBindingRequest(
                " roomTemp ", null, " AHU1_roomTemp ", " 室温 ", " RULE_AHU_MAIN ",
                " AHU ", " MAIN ", " AI ");
        var binding = new DeviceOnboardingContracts.TypedBindRequest(
                " product-1 ", " BLD001 ", " SPACE001 ", " GROUP001 ", null,
                new DeviceOnboardingContracts.NewEquipmentRequest(" 大金内机 ", " Daikin "),
                List.of(point), " HTTP-DAIKIN-1 ");
        var command = new BindTypedPendingDeviceHandler.Command(" pending-1 ", binding);
        when(service.resolveTypedBindBuilding(eq("pending-1"), any(), any())).thenReturn("BLD001");

        var normalized = handler.normalize(mapper.valueToTree(command));
        var canonical = mapper.readValue(
                normalized.canonicalJson(), BindTypedPendingDeviceHandler.Command.class);

        assertThat(normalized.buildingId()).isEqualTo("BLD001");
        assertThat(normalized.impactSummary()).contains("pointCount=1");
        assertThat(canonical.pendingId()).isEqualTo("pending-1");
        assertThat(canonical.binding().numericSourceId()).isEqualTo("HTTP-DAIKIN-1");
        assertThat(canonical.binding().pointBindings()).singleElement().satisfies(mapped -> {
            assertThat(mapped.metricCode()).isEqualTo("roomTemp");
            assertThat(mapped.pointCode()).isEqualTo("AHU1_roomTemp");
            assertThat(mapped.pointName()).isEqualTo("室温");
            assertThat(mapped.namingRuleId()).isEqualTo("RULE_AHU_MAIN");
            assertThat(mapped.familyCode()).isEqualTo("AHU");
            assertThat(mapped.componentCode()).isEqualTo("MAIN");
            assertThat(mapped.dataType()).isEqualTo("AI");
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DeviceOnboardingContracts.TypedBindRequest> bindingCaptor =
                ArgumentCaptor.forClass(DeviceOnboardingContracts.TypedBindRequest.class);
        verify(service).resolveTypedBindBuilding(eq("pending-1"), bindingCaptor.capture(), any());
        assertThat(bindingCaptor.getValue().numericSourceId()).isEqualTo("HTTP-DAIKIN-1");
        assertThat(bindingCaptor.getValue().pointBindings()).hasSize(1);
    }
}
