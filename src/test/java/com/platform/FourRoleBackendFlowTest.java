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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FourRoleBackendFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void should_enforce_role_menu_building_request_and_open_api_boundaries() throws Exception {
        String adminToken = login("admin", "123456");

        mockMvc.perform(delete("/system/users/1").header(auth(), bearer(adminToken)))
                .andExpect(status().isConflict());

        String ownerName = "owner_scope_flow";
        register(ownerName);
        String ownerToken = login(ownerName, "123456");

        JsonNode ownerMenu = json(getWithToken("/menu/current", ownerToken)).path("data");
        assertThat(flattenMenuIds(ownerMenu)).contains(100L, 101L)
                .doesNotContain(200L, 210L, 211L, 212L, 220L, 223L, 240L, 241L);

        JsonNode emptyBuildings = json(getWithToken("/building/list", ownerToken))
                .path("data").path("records");
        assertThat(emptyBuildings).isEmpty();

        mockMvc.perform(get("/system/users").header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());

        MvcResult submitted = mockMvc.perform(post("/building-access/requests")
                        .header(auth(), bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buildingId":"BLD001","reason":"负责一号楼的能效管理"}
                                """))
                .andExpect(status().isOk()).andReturn();
        long requestId = json(submitted).path("data").path("id").asLong();
        assertThat(requestId).isPositive();

        mockMvc.perform(put("/system/building-access/requests/{id}/approve", requestId)
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"资料核验通过\"}"))
                .andExpect(status().isOk());

        JsonNode approvedBuildings = json(getWithToken("/building/list", ownerToken))
                .path("data").path("records");
        assertThat(approvedBuildings).hasSize(1);
        assertThat(approvedBuildings.get(0).path("buildingId").asText()).isEqualTo("BLD001");

        MvcResult thirdCreated = mockMvc.perform(post("/system/users")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"third_open_flow","password":"123456",
                                 "nickname":"第三方接口账号","roleKeys":["THIRD_PARTY"],
                                 "buildingIds":["BLD001"]}
                                """))
                .andExpect(status().isOk()).andReturn();
        JsonNode thirdView = json(thirdCreated).path("data");
        assertThat(thirdView.path("password").isMissingNode()).isTrue();
        assertThat(thirdView.path("roles").toString()).contains("THIRD_PARTY");

        String thirdToken = login("third_open_flow", "123456");
        assertThat(json(getWithToken("/menu/current", thirdToken)).path("data")).isEmpty();

        mockMvc.perform(get("/building/list").header(auth(), bearer(thirdToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/open-api/buildings/BLD001").header(auth(), bearer(thirdToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/open-api/buildings/BLD002").header(auth(), bearer(thirdToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/" + "device" + "/list")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/" + "telemetry" + "/history")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/" + "control" + "/issue")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"建筑业主"}
                                """.formatted(username)))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        String token = json(result).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private MvcResult getWithToken(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header(auth(), bearer(token)))
                .andExpect(status().isOk()).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> flattenMenuIds(JsonNode nodes) {
        List<Long> ids = new ArrayList<>();
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                ids.add(node.path("id").asLong());
                ids.addAll(flattenMenuIds(node.path("children")));
            }
        }
        return ids;
    }

    private String auth() { return "Authorization"; }
    private String bearer(String token) { return "Bearer " + token; }
}
