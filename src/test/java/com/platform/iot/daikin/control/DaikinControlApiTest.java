package com.platform.iot.daikin.control;

import com.platform.audit.SecurityAuditService;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 Controller/Service/H2 链路搭配模拟厂家，单独的安全链测试验证未登录请求。 */
class DaikinControlApiTest {
    ControlFixture f;
    MockMvc mvc;
    UsernamePasswordAuthenticationToken auth;
    @BeforeEach void setup() throws Exception {
        f = new ControlFixture();
        mvc = MockMvcBuilders.standaloneSetup(new DaikinControlController(f.service))
                .setControllerAdvice(new DaikinControlExceptionHandler(), new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
        auth = new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L, "offline-operator"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ENERGY_MANAGER")));
    }

    @Test void optionsSubmitPollHistoryAndReadbackHaveStableContract() throws Exception {
        var response = mvc.perform(get("/v1/hvac-control/devices/equipment/options").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canControl").value(true))
                .andExpect(jsonPath("$.data.actions.length()").value(5)).andExpect(jsonPath("$.data.currentTemperature").value(24)).andReturn();
        String revision = f.json.readTree(response.getResponse().getContentAsString()).path("data").path("revision").asText();
        var command = f.json.createObjectNode().put("action", "ON_OFF").put("value", "on").put("idempotencyKey", "request-123").put("revision", revision);
        var submitted = mvc.perform(post("/v1/hvac-control/devices/equipment/operations").principal(auth)
                .contentType(MediaType.APPLICATION_JSON).content(command.toString())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED")).andReturn();
        String id = f.json.readTree(submitted.getResponse().getContentAsString()).path("data").path("operationId").asText();
        mvc.perform(get("/v1/hvac-control/operations/" + id).principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendorCode").value("10000"));
        f.stabilize(f.service);
        mvc.perform(get("/v1/hvac-control/devices/equipment/operations").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].status").value("CLOUD_VERIFIED"))
                .andExpect(jsonPath("$.data.items[0].isolated").value(false))
                .andExpect(jsonPath("$.data.items[0].cloudStable").value(true));
        mvc.perform(post("/v1/hvac-control/operations/" + id + "/confirm").principal(auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"模拟现场确认已开机且无联动\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_ONSITE_CONFIRMATION_REQUIRED"));
        assertThat(f.gateway.writes).hasValue(1);
    }

    @Test void arbitraryVendorFieldsAndUnknownActionsAre400AndCrossBuildingIs403() throws Exception {
        for (String json : List.of("{\"action\":\"ARBITRARY\"}", "{\"action\":\"ON_OFF\",\"vendorId\":\"other\"}", "{}")) {
            mvc.perform(post("/v1/hvac-control/devices/equipment/operations").principal(auth).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_INVALID_REQUEST"));
        }
        mvc.perform(get("/v1/hvac-control/devices/other/options").principal(auth)).andExpect(status().isForbidden());
        assertThat(f.gateway.reads).hasValue(0); assertThat(f.gateway.writes).hasValue(0);
    }
}
