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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 完整 JWT 安全链；test profile 强制关闭厂家客户端，即使宿主机存在启用环境变量。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DaikinControlSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired DaikinControlProperties properties;

    @Test void defaultsAndAnonymousRequestsCannotCreateExternalIo() throws Exception {
        assertThat(properties.isEnabled()).isFalse(); assertThat(properties.isWireConfirmed()).isFalse();
        assertThat(properties.isTestAccessEnabled()).isFalse();
        assertThat(context.getBeansOfType(DaikinControlGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(DaikinSession.class)).isEmpty();
        mvc.perform(post("/v1/hvac-control/devices/EQUIP_WCR_B1/operations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_UNAUTHORIZED"));
    }

    @Test void existingAdministratorReceivesNoImplicitControlDuty() throws Exception {
        var login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}")).andExpect(status().isOk()).andReturn();
        String token = json.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(get("/v1/hvac-control/devices/EQUIP_WCR_B1/options").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canControl").value(false));
        mvc.perform(post("/v1/hvac-control/devices/EQUIP_WCR_B1/operations").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_FORBIDDEN"));
    }
}
