package com.platform.energy.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
class ClassificationApiContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Test void authenticationValidationAndOpenApiUseActualHttpContracts() throws Exception {
        String base="/v1/energy-classification/buildings/BLD001";
        mvc.perform(post(base+"/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        var response=mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String token=json.readTree(response.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(post(base+"/query").header("Authorization","Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var docs=mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn();
        var paths=json.readTree(docs.getResponse().getContentAsString()).path("paths");
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/query")).isTrue();
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/readings/batches/{id}/approve")).isTrue();
        assertThat(paths.has("/v1/energy-classification/buildings/{buildingId}/versions/{id}/withdraw")).isTrue();
    }
}
