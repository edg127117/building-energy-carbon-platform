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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PersonnelLifecycleTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void should_logically_delete_restore_and_keep_deleted_username_reserved() throws Exception {
        String adminToken = login("admin", "123456");
        MvcResult created = mockMvc.perform(post("/system/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"lifecycle_user","password":"123456",
                                 "roleKeys":["ENERGY_MANAGER"],"buildingIds":["BLD002"]}
                                """))
                .andExpect(status().isOk()).andReturn();
        long userId = json(created).path("data").path("id").asLong();

        mockMvc.perform(delete("/system/users/{id}", userId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_user\",\"password\":\"123456\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult includeDeleted = mockMvc.perform(get("/system/users")
                        .header("Authorization", bearer(adminToken))
                        .param("includeDeleted", "true")
                        .param("keyword", "lifecycle_user"))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = json(includeDeleted).path("data").path("records");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).path("delFlag").asInt()).isEqualTo(1);
        assertThat(records.get(0).path("password").isMissingNode()).isTrue();

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_user\",\"password\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/system/users/{id}/restore", userId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle_user\",\"password\":\"123456\"}"))
                .andExpect(status().isForbidden());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        String token = json(result).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) { return "Bearer " + token; }
}
