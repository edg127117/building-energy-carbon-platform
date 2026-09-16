package com.platform.iot.daikin.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 默认关闭厂家IO的完整安全链与只读路由测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DaikinMonitoringApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void anonymousCannotReadMonitoringOrTemperature() throws Exception {
        for (String path : new String[] {"/v1/hvac-monitoring/buildings/BLD001/devices",
                "/v1/hvac-monitoring/devices/missing/temperatures/current?field=roomTemp"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("DAIKIN_MONITORING_UNAUTHORIZED"));
        }
    }

    @Test
    void authenticatedApiExposesReadOnlyContractAndSafeNotFound() throws Exception {
        var login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}")).andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(get("/v1/hvac-monitoring/devices/not-found/temperatures/current?field=roomTemp")
                .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        var spec = mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token)).andReturn();
        var paths = mapper.readTree(spec.getResponse().getContentAsString()).path("paths");
        assertThat(paths.has("/v1/hvac-monitoring/devices/{equipmentId}/temperatures")).isTrue();
        assertThat(paths.path("/v1/hvac-monitoring/devices/{equipmentId}/temperatures").has("post")).isFalse();
    }
}
