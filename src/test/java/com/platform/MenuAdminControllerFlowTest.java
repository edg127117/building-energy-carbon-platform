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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MenuAdminControllerFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void shouldEnforceAdminAuthorityAndValidatedTreeLifecycle() throws Exception {
        String adminToken = login("admin", "123456");
        String ownerName = "menu_contract_owner";
        register(ownerName);
        String ownerToken = login(ownerName, "123456");

        mockMvc.perform(get("/menu/admin/tree").header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/menu/admin/tree").header(auth(), bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/menu/add")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"menuName":"非法菜单","menuType":"X",
                                 "visible":1,"status":1,"sortOrder":1}
                                """))
                .andExpect(status().isBadRequest());

        MvcResult created = mockMvc.perform(post("/menu/add")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":200,"menuName":"隐藏测试页","menuType":"C",
                                 "path":"/system/hidden-contract-test","visible":0,"status":1,"sortOrder":99}
                                """))
                .andExpect(status().isOk()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertThat(id).isPositive();

        mockMvc.perform(put("/menu/update")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"parentId":%d,"menuName":"循环测试","menuType":"C",
                                 "path":"/system/hidden-contract-test","visible":0,"status":1,"sortOrder":99}
                                """.formatted(id, id)))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/menu/update")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"parentId":200,"menuName":"隐藏测试页更新","menuType":"C",
                                 "path":"/system/hidden-contract-test","visible":0,"status":1,"sortOrder":98}
                                """.formatted(id)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/menu/delete/{id}", id).header(auth(), bearer(adminToken)))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("token").asText();
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"菜单测试业主"}
                                """.formatted(username)))
                .andExpect(status().isOk());
    }

    private String auth() { return "Authorization"; }
    private String bearer(String token) { return "Bearer " + token; }
}
