package com.platform.iot.temperature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 HTTP 契约与鉴权测试；测试账号和 H2 均来自隔离测试配置。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemperatureApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private static final String ROOT = "/v1/operations/hvac-temperature-bindings";

    @Test void anonymousCannotPreviewCreateOrReadJob() throws Exception {
        mvc.perform(post(ROOT + "/preview").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT + "/batch-jobs").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/batch-jobs/unknown")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/batch-jobs/latest").param("pendingId", "unknown")).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT + "/initialization/preview").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT + "/initialization/jobs").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void validatesInputAndPublishesDistinctSchemas() throws Exception {
        String login = mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("data").path("token").asText();
        mvc.perform(post(ROOT + "/preview").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/initialization/preview").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"pendingIds\":[],\"templateProductId\":\"template\"}"))
                .andExpect(status().isBadRequest());
        String spec = mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document = mapper.readTree(spec);
        assertThat(document.path("paths").has(ROOT + "/preview")).isTrue();
        assertThat(document.path("paths").has(ROOT + "/batch-jobs/{jobId}/retry")).isTrue();
        assertThat(document.path("paths").has(ROOT + "/batch-jobs/latest")).isTrue();
        assertThat(document.path("paths").has(ROOT + "/initialization/preview")).isTrue();
        assertThat(document.path("paths").has(ROOT + "/initialization/jobs")).isTrue();
        assertThat(document.path("components").path("schemas").has("HvacTemperaturePlanView")).isTrue();
        assertThat(document.path("components").path("schemas").path("TypedBindRequest").path("properties")
                .has("temperatureMode")).isTrue();
    }
}
