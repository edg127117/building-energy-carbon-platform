package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthRbacFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void should_register_login_and_enforce_rbac() throws Exception {
        String username = "user_demo";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"演示用户"}
                                """.formatted(username)))
                .andExpect(status().isOk());

        String userToken = loginAndGetToken(username, "123456");

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/device/list"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/device/list")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/device/add")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"meter-999","deviceName":"Demo电表","deviceType":1,"location":"Demo位置"}
                                """))
                .andExpect(status().isForbidden());

        String adminToken = loginAndGetToken("admin", "123456");

        mockMvc.perform(post("/device/add")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"meter-999","deviceName":"Demo电表","deviceType":1,"location":"Demo位置"}
                                """))
                .andExpect(status().isOk());

        MvcResult listResult = mockMvc.perform(get("/device/list")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "1")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode listJson = objectMapper.readTree(listResult.getResponse().getContentAsString());
        JsonNode devices = listJson.get("data").get("records");
        assertThat(devices).isNotNull();

        Long addedId = null;
        for (JsonNode d : devices) {
            if ("meter-999".equals(d.get("deviceId").asText())) {
                addedId = d.get("id").asLong();
                break;
            }
        }
        assertThat(addedId).isNotNull();

        mockMvc.perform(delete("/device/delete/" + addedId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode tokenNode = json.path("data").path("token");
        assertThat(tokenNode.isMissingNode()).isFalse();
        String token = tokenNode.asText();
        assertThat(token).isNotBlank();
        return token;
    }
}
