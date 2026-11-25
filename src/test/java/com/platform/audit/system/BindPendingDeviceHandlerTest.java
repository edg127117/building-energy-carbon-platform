package com.platform.audit.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BindPendingDeviceHandlerTest {

    @Test
    void normalizesAutomaticPointCreationForNewEquipment() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DeviceOnboardingService service = mock(DeviceOnboardingService.class);
        BindPendingDeviceHandler handler = new BindPendingDeviceHandler(
                new SystemSensitiveCommandSupport(mapper), service);
        BindCommand command = new BindCommand(" pending-1 ", " product-1 ", " BLD001 ", " SPACE001 ",
                " GROUP001 ", null, new DeviceOnboardingContracts.NewEquipmentRequest(" 新设备 ", null),
                List.of(), true, false);
        when(service.resolveBindBuilding(eq("pending-1"), any(), any())).thenReturn("BLD001");

        com.fasterxml.jackson.databind.node.ObjectNode oldCommand = mapper.valueToTree(command);
        assertThat(oldCommand.has("activateOnApprove")).isFalse();
        assertThat(oldCommand.has("gatewayMac")).isFalse();
        NormalizedSensitiveCommand normalized = handler.normalize(oldCommand);
        assertThat(handler.normalize(oldCommand.deepCopy().put("activateOnApprove", false)).canonicalJson())
                .isEqualTo(normalized.canonicalJson());
        assertThat(handler.normalize(oldCommand.deepCopy().put("activateOnApprove", true)).canonicalJson())
                .contains("\"activateOnApprove\":true");
        assertThat(handler.normalize(oldCommand.deepCopy().putNull("gatewayMac")).canonicalJson())
                .isEqualTo(normalized.canonicalJson());
        var door = handler.normalize(oldCommand.deepCopy().put("gatewayMac", "a123b456c789"));
        assertThat(door.canonicalJson()).contains("\"gatewayMac\":\"A123B456C789\"");
        BindCommand canonical = mapper.readValue(normalized.canonicalJson(), BindCommand.class);

        assertThat(normalized.impactSummary()).contains("pointMode=AUTO", "pointCount=0");
        assertThat(canonical.autoCreatePoints()).isTrue();
        assertThat(canonical.request().pointBindings()).isEmpty();
    }
}
