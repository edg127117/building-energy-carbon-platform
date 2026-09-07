package com.platform.energy.efficiency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EerpApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void realSecurityBindingAndOpenApiRemainExplicit() throws Exception {
        mvc.perform(get("/v1/energy-efficiency/tasks/unknown"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("EERP_UNAUTHORIZED"));
        var login=mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}")).andExpect(status().isOk()).andReturn();
        String bearer="Bearer "+mapper.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(post("/v1/energy-efficiency/period-tasks").header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("BACKOFFICE_DUTY_REQUIRED"));
        jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by) VALUES ('EERP_API_DUTY',1,'ENERGY_CALCULATION_RUN','ACTIVE',CURRENT_TIMESTAMP,1)");
        mvc.perform(post("/v1/energy-efficiency/period-tasks").header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("EERP_INVALID_REQUEST"));
        mvc.perform(post("/v1/energy-efficiency/period-tasks").header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("EERP_INVALID_REQUEST"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.traceId").exists());
        mvc.perform(get("/v1/energy-efficiency/tasks/unknown").header("Authorization",bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("EERP_NOT_FOUND"));
        var api=mvc.perform(get("/v3/api-docs").header("Authorization",bearer)).andExpect(status().isOk()).andReturn();
        var spec=mapper.readTree(api.getResponse().getContentAsString());
        assertThat(spec.path("paths").has("/v1/energy-efficiency/annual-tasks")).isTrue();
        assertThat(spec.path("components").path("schemas").has("EerpApiError")).isTrue();
        var schema=spec.path("components").path("schemas").path("EerpTaskView").path("properties").path("result");
        assertThat(schema.path("oneOf")).hasSize(2);
    }
}
