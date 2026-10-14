package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.daikin.client.DaikinSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 完整 TEST 安全链仍需登录；权限开放不隐式创建真实厂家客户端。 */
@SpringBootTest(properties="daikin.control.test-open-controls=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DaikinControlOpenSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired DaikinControlProperties properties;

    @Test void loggedInTestUserGetsOpenAccessWhilePhysicalControlDefaultsOff() throws Exception {
        assertThat(properties.isTestOpenControls()).isTrue();
        assertThat(properties.isEnabled()).isFalse(); assertThat(properties.isWireConfirmed()).isFalse();
        assertThat(context.getBeansOfType(DaikinControlGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(DaikinSession.class)).isEmpty();
        var login=mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String token=json.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(get("/v1/hvac-control/device-grants/access").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canManage").value(true))
                .andExpect(jsonPath("$.data.testOpenControls").value(true));
    }

    @Test void anonymousRequestsStillCannotReadOrSend() throws Exception {
        mvc.perform(get("/v1/hvac-control/device-grants/access")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/hvac-control/buildings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/hvac-control/devices/equipment/operations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/hvac-control/operations/unknown/recovery/continue").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
