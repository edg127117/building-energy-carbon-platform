package com.platform.iot.daikin.control;

import com.platform.audit.SecurityAuditService;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 管理 API 真实 Controller/Service/事务路径，仅注入内存读接口。 */
class DaikinControlGrantApiTest {
    @Test void batchContractWorksWithControlOffAndRejectsUntrustedFieldsAndNonAdmins() throws Exception {
        var f = new ControlFixture(); f.props.setEnabled(false);
        var reader = new DaikinControlGrantReader() {
            public boolean supports(String source) { return f.gateway.supports(source); }
            public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) { return f.gateway.read(target); }
        };
        var service = new DaikinControlGrantService(f.access, f.policy, f.store, List.of(reader), f.clock);
        var mvc = MockMvcBuilders.standaloneSetup(new DaikinControlGrantController(service))
                .setControllerAdvice(new DaikinControlExceptionHandler(), new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
        var admin = new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(9L, "offline-admin"), null, List.of());
        var manager = new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L, "offline-manager"), null, List.of());
        mvc.perform(get("/v1/hvac-control/device-grants/access").principal(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canManage").value(true));
        mvc.perform(get("/v1/hvac-control/device-grants/access").principal(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canManage").value(false));
        String body = """
                {"equipmentIds":["equipment","other"],"actions":["ON_OFF","TEMPERATURE"],"evidence":"统一批量开放测试许可"}
                """;
        mvc.perform(post("/v1/hvac-control/device-grants/batch").principal(manager).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/hvac-control/device-grants/batch").principal(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].opened").value(true)).andExpect(jsonPath("$.data.items[0].version").isNumber())
                .andExpect(jsonPath("$.data.items[1].reasonCode").value("TARGET_UNAVAILABLE"));
        for (String invalid : List.of("{}", "{\"equipmentIds\":[\"equipment\"],\"vendorId\":\"arbitrary\"}",
                "{\"equipmentIds\":[\"equipment\"],\"actions\":[\"ARBITRARY\"]}"))
            mvc.perform(post("/v1/hvac-control/device-grants/batch").principal(admin).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_INVALID_REQUEST"));
        assertThat(f.gateway.writes).hasValue(0);
    }
}
