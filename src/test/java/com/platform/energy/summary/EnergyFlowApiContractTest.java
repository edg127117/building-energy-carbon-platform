package com.platform.energy.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EnergyFlowApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Test void enforcesAuthenticationAndCalendarWithoutInvokingCalculations() throws Exception {
        mvc.perform(post("/v1/energy-boundary-summaries/flow/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("ENERGY_SUMMARY_UNAUTHORIZED"));
        String login=mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=mapper.readTree(login).path("data").path("token").asText();
        mvc.perform(post("/v1/energy-boundary-summaries/flow/query").header("Authorization","Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"buildingId\":\"BLD001\",\"month\":\"2026-13\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("ENERGY_SUMMARY_VALIDATION_FAILED"));
        mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/energy-boundary-summaries/flow/query'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/energy-boundary-summaries/flow/query'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.components.schemas.EnergyFlowQueryRequest.properties.month").exists())
                .andExpect(jsonPath("$.components.schemas.EnergyFlowQueryView.properties.resultVersion").exists())
                .andExpect(jsonPath("$.components.schemas.EnergyFlowGraph.properties.total").exists())
                .andExpect(jsonPath("$.components.schemas.EnergyFlowLink.properties.value").exists());
    }
}
