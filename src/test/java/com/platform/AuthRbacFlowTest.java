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

import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证公开注册、正式角色登录和旧电表 HTTP 路由下线后的认证边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthRbacFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRegisterLoginAndExposeOnlyFormalRbacRoutes() throws Exception {
        String username = "user_demo";
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"演示用户"}
                                """.formatted(username)))
                .andExpect(status().isOk());

        String ownerToken = loginAndGetToken(username, "123456");
        JsonNode owner = json(mockMvc.perform(get("/auth/me")
                        .header(auth(), bearer(ownerToken)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(roles(owner.path("roles")))
                .containsExactly("BUILDING_OWNER");

        mockMvc.perform(get("/hvac/buildings/BLD001/snapshot"))
                .andExpect(status().isUnauthorized());

        String adminToken = loginAndGetToken("admin", "123456");
        JsonNode admin = json(mockMvc.perform(get("/auth/me")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(roles(admin.path("roles")))
                .containsExactly("PLATFORM_ADMIN");

        for (String path : List.of(
                "/" + "device" + "/list",
                "/" + "telemetry" + "/history")) {
            mockMvc.perform(get(path).header(auth(), bearer(adminToken)))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/" + "control" + "/issue")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private String loginAndGetToken(
            String username,
            String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();

        String token = json(result).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString());
    }

    private List<String> roles(JsonNode roleNodes) {
        return StreamSupport.stream(roleNodes.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private String auth() {
        return "Authorization";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
